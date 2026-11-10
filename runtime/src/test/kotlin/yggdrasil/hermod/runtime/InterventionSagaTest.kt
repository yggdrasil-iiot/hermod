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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v0.4b operator interventions: on an interactive run a failed node PARKS, and the operator can retry it
 * (recover a transient failure) or abort the run (reverse-topo compensation). The default (non-interactive)
 * path must keep auto-compensating with no operator (no hang). Mirrors SagaWorkflowTest's harness.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InterventionSagaTest {
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
        // intervention: src -> db.upsert(mid, compensable) -> transform.map(sink, pure, the faulted node).
        // No IRREVERSIBLE block -> no human gate fires, so the sink parks at forward (not at an approval gate).
        BoundWorkflow.bind("intervention", RuntimeAssembly.planFor(WorkflowDef("intervention", listOf(
            WorkflowStep("db.read", "1.0.0", id = "src"),
            WorkflowStep("db.upsert", "1.2.0", id = "mid", params = mapOf("table" to "target_rows"), wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("transform.map", "1.0.0", id = "sink", wiring = mapOf("rows" to "mid.written")),
        ))))
        // demo (has the actuate IRREVERSIBLE human gate) — for the reject-on-interactive regression test.
        BoundWorkflow.bind("demo", RuntimeAssembly.planFor(DemoWorkflow.DEF))
    }
    @AfterAll fun down() { pg.stop() }

    @BeforeEach fun seed() {
        DbTestSupport.exec("TRUNCATE source_rows"); DbTestSupport.exec("TRUNCATE target_rows")
        DbTestSupport.exec("INSERT INTO source_rows VALUES ('r1','x'),('r2','y')")
        probe.failBlockId = null; probe.failRemaining.set(0)
    }

    /** Poll the live node-state query (real wall-clock, like SagaWorkflowTest's gate-poll) until [state] or timeout. */
    private fun awaitNodeState(stub: SagaWorkflow, nodeId: String, state: String) {
        repeat(50) { if (stub.queryNodeStates()[nodeId] == state) return; Thread.sleep(100) }
        throw AssertionError("node $nodeId never reached $state; states=${stub.queryNodeStates()}")
    }

    @Test fun `failed node parks then retry recovers the run`(client: WorkflowClient, options: WorkflowOptions) {
        probe.failBlockId = "transform.map"; probe.failRemaining.set(1)   // fail once, then succeed
        val stub = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val exec = WorkflowClient.start(stub::run, WorkflowInput(interactive = true, workflowName = "intervention"))
        awaitNodeState(stub, "sink", "PARKED")
        assertEquals("DONE", stub.queryNodeStates()["mid"], "upstream upsert should have completed before the sink parked")
        stub.retryNode("sink")
        val out = client.newUntypedWorkflowStub(exec.workflowId).getResult(WorkflowOutput::class.java)
        assertTrue(out.completed, "run should complete after the operator retried the parked node")
        assertEquals("DONE", stub.queryNodeStates()["sink"])
    }

    @Test fun `abort at a parked node compensates the completed upstream in reverse-topo`(client: WorkflowClient, options: WorkflowOptions) {
        probe.failBlockId = "transform.map"; probe.failRemaining.set(99)  // never recovers
        val stub = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val exec = WorkflowClient.start(stub::run, WorkflowInput(interactive = true, workflowName = "intervention"))
        awaitNodeState(stub, "sink", "PARKED")
        stub.abort()
        val out = client.newUntypedWorkflowStub(exec.workflowId).getResult(WorkflowOutput::class.java)
        assertFalse(out.completed)
        assertEquals(listOf("db.upsert"), out.compensatedInReverseOrder)
        assertEquals("COMPENSATED", stub.queryNodeStates()["mid"])
        assertEquals(0, DbTestSupport.count("target_rows"))
    }

    @Test fun `reject at a human gate terminates and compensates even on an interactive run`(client: WorkflowClient, options: WorkflowOptions) {
        // The "demo" workflow's actuate is IRREVERSIBLE -> it parks at the human gate. On an interactive run a
        // reject must still throw past the park loop (terminal decision), NOT re-park the node. Without that, this
        // test would hang at getResult. (Guards review finding: gate reject neutralized on interactive runs.)
        DbTestSupport.exec("TRUNCATE target_rows")
        DbTestSupport.exec("INSERT INTO target_rows(id,val) VALUES('A','old')")
        val stub = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val exec = WorkflowClient.start(stub::run, WorkflowInput(
            useDbRead = false, interactive = true, workflowName = "demo",
            rows = listOf(mapOf("id" to "A", "val" to "new"), mapOf("id" to "B", "val" to "fresh")),
        ))
        repeat(10) { Thread.sleep(100) }   // let it reach the actuate gate
        stub.reject("operator declined")
        val out = client.newUntypedWorkflowStub(exec.workflowId).getResult(WorkflowOutput::class.java)
        assertFalse(out.completed)
        assertEquals(listOf("notify.email", "db.upsert"), out.compensatedInReverseOrder)
    }

    @Test fun `non-interactive run still auto-compensates with no operator (no hang)`(client: WorkflowClient, options: WorkflowOptions) {
        probe.failBlockId = "transform.map"; probe.failRemaining.set(99)
        val stub = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val out = stub.run(WorkflowInput(interactive = false, workflowName = "intervention"))  // synchronous; must not hang
        assertFalse(out.completed)
        assertEquals(listOf("db.upsert"), out.compensatedInReverseOrder)
    }
}
