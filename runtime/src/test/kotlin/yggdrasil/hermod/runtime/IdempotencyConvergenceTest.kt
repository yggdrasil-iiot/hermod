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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdempotencyConvergenceTest {
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
        DbTestSupport.exec("INSERT INTO source_rows VALUES ('A1','x')")
        NotifyEmailBlock.SENT.clear(); ActuateBlock.FIRED.clear()
    }

    @Test fun `P1 - same input triggered twice converges to one target row`(
        client: WorkflowClient, options: WorkflowOptions,
    ) {
        repeat(2) {
            val wf = client.newWorkflowStub(SagaWorkflow::class.java, options)
            wf.run(WorkflowInput(autoApprove = true))
        }
        assertEquals(1, DbTestSupport.count("target_rows"))
        assertEquals("X", DbTestSupport.value("target_rows", "A1"))
    }
}
