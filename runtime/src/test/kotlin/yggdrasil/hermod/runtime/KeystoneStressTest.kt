package yggdrasil.hermod.runtime

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowExtension
import yggdrasil.hermod.blocks.ActuateBlock
import yggdrasil.hermod.blocks.NotifyEmailBlock
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.RegisterExtension
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §9.1 keystone stress: prove the contract-driven dedup and compensation behave correctly under
 * adversarial input, with explicit count/state deltas (not just "no error").
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeystoneStressTest {
    private val pg = PostgreSQLContainer("postgres:16")

    @JvmField @RegisterExtension
    val testEnv: TestWorkflowExtension = TestWorkflowExtension.newBuilder()
        .setWorkflowTypes(SagaWorkflowImpl::class.java)
        .setActivityImplementations(BlockActivitiesImpl())
        .setWorkflowClientOptions(DataConverterSupport.clientOptions())
        .build()

    @BeforeAll fun up() {
        pg.start(); DbTestSupport.override(pg.jdbcUrl, pg.username, pg.password)
        DbTestSupport.exec("CREATE TABLE source_rows (id TEXT PRIMARY KEY, val TEXT NOT NULL)")
        DbTestSupport.exec("CREATE TABLE target_rows (id TEXT PRIMARY KEY, val TEXT NOT NULL)")
        BoundWorkflow.bind("demo", RuntimeAssembly.planFor(DemoWorkflow.DEF))
    }
    @AfterAll fun down() { pg.stop() }

    @BeforeEach fun seed() {
        DbTestSupport.exec("TRUNCATE source_rows"); DbTestSupport.exec("TRUNCATE target_rows")
        NotifyEmailBlock.SENT.clear(); ActuateBlock.FIRED.clear()
    }

    private fun runSaga(client: WorkflowClient, options: WorkflowOptions, input: WorkflowInput): WorkflowOutput =
        client.newWorkflowStub(SagaWorkflow::class.java, options).run(input)

    // (a) duplicate-row KEY_DEDUP fires — explicit count delta, not just "no error"
    @Test fun `notify KEY_DEDUP drops duplicate rows - output strictly smaller`(
        client: WorkflowClient, options: WorkflowOptions,
    ) {
        val input = WorkflowInput(useDbRead = false, autoApprove = true, rows = listOf(
            mapOf("id" to "A", "val" to "x"), mapOf("id" to "A", "val" to "x"), // dup id
            mapOf("id" to "B", "val" to "y"),
        ))
        val out = runSaga(client, options, input)
        assertEquals(3, out.inputCount)
        assertTrue(out.dedupCount < out.inputCount, "dedup must drop rows: ${out.dedupCount} !< ${out.inputCount}")
        assertEquals(2, out.dedupCount, "A deduped to one")
    }

    // (b) batch partial-failure -> complete compensation correct under partial state
    @Test fun `batch upsert then failure compensates inserts and updates correctly`(
        client: WorkflowClient, options: WorkflowOptions,
    ) {
        DbTestSupport.exec("INSERT INTO target_rows(id,val) VALUES('A','old')")
        val input = WorkflowInput(useDbRead = false, autoApprove = true, failAtBlockId = "actuate", rows = listOf(
            mapOf("id" to "A", "val" to "new"),   // update
            mapOf("id" to "B", "val" to "fresh"), // insert
        ))
        val out = runSaga(client, options, input)
        assertFalse(out.completed)
        assertEquals(listOf("notify.email", "db.upsert"), out.compensatedInReverseOrder) // reverse order incl. mitigatable
        assertEquals("old", DbTestSupport.queryVal("A")) // restored
        assertNull(DbTestSupport.queryVal("B"))          // inserted row deleted
    }
}
