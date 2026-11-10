package yggdrasil.hermod.runtime

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowExtension
import yggdrasil.hermod.core.WorkflowDef
import yggdrasil.hermod.core.WorkflowStep
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.RegisterExtension
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v0.4c compensation result timeline: a failing run's reverse-topo unwind is exposed as an ordered, per-step
 * result list (queryCompensationTimeline). Compensation is best-effort — a failed step is recorded FAILED and
 * the unwind continues. Mirrors SagaWorkflowTest's harness.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompensationTimelineTest {
    private val pg = PostgreSQLContainer("postgres:16")
    private val probe = InterventionProbeActivities(BlockActivitiesImpl())

    @JvmField @RegisterExtension
    val testEnv: TestWorkflowExtension = TestWorkflowExtension.newBuilder()
        .setWorkflowTypes(SagaWorkflowImpl::class.java)
        .setActivityImplementations(probe)
        .setWorkflowClientOptions(DataConverterSupport.clientOptions())
        .build()

    @BeforeAll fun up() {
        pg.start()
        DbTestSupport.override(pg.jdbcUrl, pg.username, pg.password)
        DbTestSupport.exec("CREATE TABLE source_rows (id TEXT PRIMARY KEY, val TEXT NOT NULL)")
        DbTestSupport.exec("CREATE TABLE target_rows (id TEXT PRIMARY KEY, val TEXT NOT NULL)")
        // two DISTINCT compensable blocks (db.upsert + notify.email) then a non-compensable failing sink.
        BoundWorkflow.bind("comp-timeline", RuntimeAssembly.planFor(WorkflowDef("comp-timeline", listOf(
            WorkflowStep("db.read", "1.0.0", id = "src"),
            WorkflowStep("db.upsert", "1.2.0", id = "m", params = mapOf("table" to "target_rows"), wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("notify.email", "1.0.0", id = "n", wiring = mapOf("rows" to "m.written")),
            WorkflowStep("transform.map", "1.0.0", id = "sink", wiring = mapOf("rows" to "n.rows")),
        ))))
    }
    @AfterAll fun down() { pg.stop() }

    @BeforeEach fun seed() {
        DbTestSupport.exec("TRUNCATE source_rows"); DbTestSupport.exec("TRUNCATE target_rows")
        DbTestSupport.exec("INSERT INTO source_rows VALUES ('r1','x'),('r2','y')")
        probe.failBlockId = null; probe.failRemaining.set(0); probe.failCompensateBlockId = null
    }

    @Test fun `timeline records the reverse-topo unwind in order, all COMPENSATED`(client: WorkflowClient, options: WorkflowOptions) {
        val wf = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val out = wf.run(WorkflowInput(autoApprove = true, workflowName = "comp-timeline", failAtBlockId = "transform.map"))
        assertTrue(!out.completed)
        assertEquals(listOf("notify.email", "db.upsert"), out.compensatedInReverseOrder)
        val tl = wf.queryCompensationTimeline()
        assertEquals(listOf("notify.email", "db.upsert"), tl.map { it.blockId }, "timeline blockIds in reverse-topo order")
        assertTrue(tl.all { it.outcome == "COMPENSATED" })
        assertEquals(listOf(0, 1), tl.map { it.index })
    }

    @Test fun `a failed compensation is recorded FAILED and the unwind continues (best-effort)`(client: WorkflowClient, options: WorkflowOptions) {
        probe.failCompensateBlockId = "notify.email"   // first in reverse-topo → its compensation fails
        val wf = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val out = wf.run(WorkflowInput(autoApprove = true, workflowName = "comp-timeline", failAtBlockId = "transform.map"))
        assertTrue(!out.completed)
        assertEquals(listOf("db.upsert"), out.compensatedInReverseOrder)  // success-only list
        val tl = wf.queryCompensationTimeline()
        assertEquals("notify.email", tl[0].blockId); assertEquals("FAILED", tl[0].outcome)
        assertEquals("db.upsert", tl[1].blockId); assertEquals("COMPENSATED", tl[1].outcome)  // continued past the failure
        assertEquals("COMP_FAILED", wf.queryNodeStates()["n"])
    }
}
