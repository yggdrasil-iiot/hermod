package yggdrasil.hermod.runtime

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.testing.TestWorkflowExtension
import yggdrasil.hermod.blocks.ActuateBlock
import yggdrasil.hermod.blocks.NotifyEmailBlock
import yggdrasil.hermod.core.WorkflowDef
import yggdrasil.hermod.core.WorkflowStep
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.RegisterExtension
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SagaWorkflowTest {
    private val pg = PostgreSQLContainer("postgres:16")

    private val probe = ConcurrencyProbeActivities(BlockActivitiesImpl())

    @JvmField
    @RegisterExtension
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
        BoundWorkflow.bind("demo", RuntimeAssembly.planFor(DemoWorkflow.DEF))   // bind once, off the workflow thread
        // diamond fan-in: src -> {b, c} -> join(merge left=b,right=c) -> sink(db.upsert)
        BoundWorkflow.bind("diamond", RuntimeAssembly.planFor(WorkflowDef("diamond", listOf(
            WorkflowStep("db.read", "1.0.0", id = "src"),
            WorkflowStep("transform.map", "1.0.0", id = "b", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("transform.map", "1.0.0", id = "c", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("merge", "1.0.0", id = "join", wiring = mapOf("left" to "b.rows", "right" to "c.rows")),
            WorkflowStep("db.upsert", "1.2.0", id = "sink", params = mapOf("table" to "target_rows"), wiring = mapOf("rows" to "join.out")),
        ))))
        // diamond compensation: an upstream reversible upsert (b) must be undone when a later step (sink) fails
        BoundWorkflow.bind("diamond-comp", RuntimeAssembly.planFor(WorkflowDef("diamond-comp", listOf(
            WorkflowStep("db.read", "1.0.0", id = "src"),
            WorkflowStep("db.upsert", "1.2.0", id = "b", params = mapOf("table" to "target_rows"), wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("transform.map", "1.0.0", id = "c", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("merge", "1.0.0", id = "join", wiring = mapOf("left" to "b.written", "right" to "c.rows")),
            WorkflowStep("notify.email", "1.0.0", id = "sink", wiring = mapOf("rows" to "join.out")),
        ))))
        // wide fan-out for the concurrency proof: 3 parallel transform.map branches off src
        BoundWorkflow.bind("conc-fanout", RuntimeAssembly.planFor(WorkflowDef("conc-fanout", listOf(
            WorkflowStep("db.read", "1.0.0", id = "src"),
            WorkflowStep("transform.map", "1.0.0", id = "p1", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("transform.map", "1.0.0", id = "p2", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("transform.map", "1.0.0", id = "p3", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("merge", "1.0.0", id = "join", wiring = mapOf("left" to "p1.rows", "right" to "p2.rows")),
            WorkflowStep("db.upsert", "1.2.0", id = "sink", params = mapOf("table" to "target_rows"), wiring = mapOf("rows" to "join.out")),
        ))))
        // partial-failure: two concurrent compensable upserts (b,c) to the SAME table -> join -> notify.email sink
        BoundWorkflow.bind("conc-comp", RuntimeAssembly.planFor(WorkflowDef("conc-comp", listOf(
            WorkflowStep("db.read", "1.0.0", id = "src"),
            WorkflowStep("db.upsert", "1.2.0", id = "b", params = mapOf("table" to "target_rows"), wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("db.upsert", "1.2.0", id = "c", params = mapOf("table" to "target_rows"), wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("merge", "1.0.0", id = "join", wiring = mapOf("left" to "b.written", "right" to "c.written")),
            WorkflowStep("notify.email", "1.0.0", id = "sink", wiring = mapOf("rows" to "join.out")),
        ))))
    }
    @AfterAll fun down() { pg.stop() }

    @BeforeEach fun seed() {
        DbTestSupport.exec("TRUNCATE source_rows"); DbTestSupport.exec("TRUNCATE target_rows")
        DbTestSupport.exec("INSERT INTO source_rows VALUES ('A1','x')")
        DbTestSupport.exec("INSERT INTO target_rows VALUES ('A1','old')") // prior value for compensation
        NotifyEmailBlock.SENT.clear(); ActuateBlock.FIRED.clear()
        probe.maxConcurrent.set(0); probe.current.set(0)
    }

    @Test fun `P3 - permanent failure compensates db_upsert in reverse order`(
        client: WorkflowClient, options: WorkflowOptions,
    ) {
        val wf = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val out = wf.run(WorkflowInput(failAtBlockId = "notify.email", autoApprove = true))
        assertFalse(out.completed)
        // only db.upsert is on the comp stack before notify (db.read/transform have kind=NONE)
        assertEquals(listOf("db.upsert"), out.compensatedInReverseOrder)
        // prior value restored
        assertEquals("old", DbTestSupport.value("target_rows", "A1"))
        // actuate never fired
        assertTrue(ActuateBlock.FIRED.isEmpty())
    }

    @Test fun `P4 - human gate parks before actuate until approve signal`(
        client: WorkflowClient, options: WorkflowOptions,
    ) {
        val stub = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val exec = WorkflowClient.start(stub::run, WorkflowInput(autoApprove = false))
        // The saga runs the first 4 steps then PARKS before actuate. Poll ~1s asserting the gate holds.
        repeat(10) { Thread.sleep(100); assertTrue(ActuateBlock.FIRED.isEmpty(), "actuate fired before approval") }
        stub.approve()
        val out = client.newUntypedWorkflowStub(exec.workflowId).getResult(WorkflowOutput::class.java)
        assertTrue(out.completed)
        assertEquals(1, ActuateBlock.FIRED.size)
    }

    @Test fun `reject at human gate triggers compensation (F5 seam)`(
        client: WorkflowClient, options: WorkflowOptions,
    ) {
        DbTestSupport.exec("TRUNCATE target_rows")
        DbTestSupport.exec("INSERT INTO target_rows(id,val) VALUES('A','old')")
        val stub = client.newWorkflowStub(SagaWorkflow::class.java, options)
        // autoApprove=false so it parks at the actuate gate; then signal reject.
        val exec = WorkflowClient.start(stub::run, WorkflowInput(
            useDbRead = false,
            rows = listOf(mapOf("id" to "A", "val" to "new"), mapOf("id" to "B", "val" to "fresh")),
        ))
        // wait until parked at the gate (forward steps done, actuate not fired)
        repeat(10) { Thread.sleep(100); assertTrue(ActuateBlock.FIRED.isEmpty(), "actuate fired before gate decision") }
        stub.reject("operator declined")
        val out = client.newUntypedWorkflowStub(exec.workflowId).getResult(WorkflowOutput::class.java)
        assertFalse(out.completed)
        assertEquals(listOf("notify.email", "db.upsert"), out.compensatedInReverseOrder)
        assertEquals("old", DbTestSupport.queryVal("A"))
        assertNull(DbTestSupport.queryVal("B"))
        assertTrue(ActuateBlock.FIRED.isEmpty())
    }

    @Test fun `diamond fan-in - join consumes both branches`(
        client: WorkflowClient, options: WorkflowOptions,
    ) {
        DbTestSupport.exec("TRUNCATE source_rows"); DbTestSupport.exec("TRUNCATE target_rows")
        DbTestSupport.exec("INSERT INTO source_rows(id,val) VALUES ('r1','a'),('r2','b')")
        val wf = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val out = wf.run(WorkflowInput(autoApprove = true, workflowName = "diamond"))
        assertTrue(out.completed)
        // join merged b(2) + c(2) = 4 rows; db.upsert collapses by PK to 2 distinct ids in target_rows.
        assertEquals(2, DbTestSupport.count("target_rows"))
    }

    @Test fun `diamond compensation - upstream upsert undone when later step fails`(
        client: WorkflowClient, options: WorkflowOptions,
    ) {
        DbTestSupport.exec("TRUNCATE source_rows"); DbTestSupport.exec("TRUNCATE target_rows")
        DbTestSupport.exec("INSERT INTO source_rows(id,val) VALUES ('r1','a'),('r2','b')")
        val wf = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val out = wf.run(WorkflowInput(autoApprove = true, workflowName = "diamond-comp", failAtBlockId = "notify.email"))
        assertFalse(out.completed)
        assertEquals(listOf("db.upsert"), out.compensatedInReverseOrder)
        assertEquals(0, DbTestSupport.count("target_rows")) // b's upsert compensated
    }

    @Test fun `fan-out branches run concurrently`(client: WorkflowClient, options: WorkflowOptions) {
        DbTestSupport.exec("TRUNCATE source_rows"); DbTestSupport.exec("TRUNCATE target_rows")
        DbTestSupport.exec("INSERT INTO source_rows VALUES ('r1','x'),('r2','y')")
        val wf = client.newWorkflowStub(SagaWorkflow::class.java, options)
        // slow ONLY the parallel transform.map branches so they overlap on the activity executor
        val out = wf.run(WorkflowInput(autoApprove = true, workflowName = "conc-fanout",
            slowMs = 400, slowAtBlockId = "transform.map"))
        assertTrue(out.completed)
        assertTrue(probe.maxConcurrent.get() >= 2, "expected concurrent forwards, got max=${probe.maxConcurrent.get()}")
    }

    @Test fun `partial failure compensates both concurrent branches in reverse-topo order`(
        client: WorkflowClient, options: WorkflowOptions,
    ) {
        DbTestSupport.exec("TRUNCATE source_rows"); DbTestSupport.exec("TRUNCATE target_rows")
        DbTestSupport.exec("INSERT INTO source_rows VALUES ('r1','x'),('r2','y')")
        val wf = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val out = wf.run(WorkflowInput(autoApprove = true, workflowName = "conc-comp", failAtBlockId = "notify.email"))
        assertFalse(out.completed)
        assertEquals(listOf("db.upsert", "db.upsert"), out.compensatedInReverseOrder)
        assertEquals(0, DbTestSupport.count("target_rows"))
    }

    @Test fun `per-step params reach the block via BlockInput`(
        client: WorkflowClient, options: WorkflowOptions,
    ) {
        DbTestSupport.exec("TRUNCATE source_rows"); DbTestSupport.exec("TRUNCATE target_rows")
        DbTestSupport.exec("INSERT INTO source_rows(id,val) VALUES ('r1','a'),('r2','b')")
        val wf = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val out = wf.run(WorkflowInput(autoApprove = true, workflowName = "diamond"))
        assertTrue(out.completed)
        assertEquals("target_rows", probe.seenParams["db.upsert"]?.get("table"),
            "step params must be threaded into BlockInput; got ${probe.seenParams["db.upsert"]}")
    }

    @Test fun `workflow runId reaches the block via BlockInput`(
        client: WorkflowClient, options: WorkflowOptions,
    ) {
        DbTestSupport.exec("TRUNCATE source_rows"); DbTestSupport.exec("TRUNCATE target_rows")
        DbTestSupport.exec("INSERT INTO source_rows(id,val) VALUES ('r1','a'),('r2','b')")
        val wf = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val out = wf.run(WorkflowInput(autoApprove = true, workflowName = "diamond"))
        assertTrue(out.completed)
        val execId = WorkflowStub.fromTyped(wf).execution.workflowId
        assertEquals(execId, probe.seenRunId["db.upsert"],
            "workflow runId must be threaded into BlockInput; got ${probe.seenRunId["db.upsert"]}")
    }

    @Test fun `workflow runId reaches compensate via CompensationContext`(
        client: WorkflowClient, options: WorkflowOptions,
    ) {
        DbTestSupport.exec("TRUNCATE source_rows"); DbTestSupport.exec("TRUNCATE target_rows")
        DbTestSupport.exec("INSERT INTO source_rows VALUES ('r1','x'),('r2','y')")
        val wf = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val out = wf.run(WorkflowInput(autoApprove = true, workflowName = "conc-comp", failAtBlockId = "notify.email"))
        assertFalse(out.completed)
        val execId = WorkflowStub.fromTyped(wf).execution.workflowId
        assertEquals(execId, probe.seenCompRunId["db.upsert"],
            "workflow runId must be threaded into compensate; got ${probe.seenCompRunId["db.upsert"]}")
    }

    @Test fun `node states reach DONE for every node on success`(
        client: WorkflowClient, options: WorkflowOptions,
    ) {
        DbTestSupport.exec("TRUNCATE source_rows"); DbTestSupport.exec("TRUNCATE target_rows")
        DbTestSupport.exec("INSERT INTO source_rows(id,val) VALUES ('r1','a'),('r2','b')")
        val wf = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val out = wf.run(WorkflowInput(autoApprove = true, workflowName = "diamond"))
        assertTrue(out.completed)
        val states = wf.queryNodeStates()
        assertEquals(setOf("src", "b", "c", "join", "sink"), states.keys)
        assertTrue(states.values.all { it == "DONE" }, "expected all DONE, got $states")
    }

    @Test fun `node states mark the failing node FAILED and the compensated node COMPENSATED`(
        client: WorkflowClient, options: WorkflowOptions,
    ) {
        DbTestSupport.exec("TRUNCATE source_rows"); DbTestSupport.exec("TRUNCATE target_rows")
        DbTestSupport.exec("INSERT INTO source_rows(id,val) VALUES ('r1','a'),('r2','b')")
        val wf = client.newWorkflowStub(SagaWorkflow::class.java, options)
        val out = wf.run(WorkflowInput(autoApprove = true, workflowName = "diamond-comp", failAtBlockId = "notify.email"))
        assertFalse(out.completed)
        val states = wf.queryNodeStates()
        assertEquals("FAILED", states["sink"], "notify.email node should be FAILED; got $states")
        assertEquals("COMPENSATED", states["b"], "db.upsert node should be COMPENSATED; got $states")
        assertEquals("DONE", states["c"])
    }
}
