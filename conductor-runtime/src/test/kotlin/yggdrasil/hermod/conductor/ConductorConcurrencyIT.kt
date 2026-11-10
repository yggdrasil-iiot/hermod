package yggdrasil.hermod.conductor

import com.netflix.conductor.client.automator.TaskRunnerConfigurer
import com.netflix.conductor.client.http.ConductorClient
import com.netflix.conductor.client.http.TaskClient
import com.netflix.conductor.client.worker.Worker
import com.netflix.conductor.common.run.Workflow
import yggdrasil.hermod.compiler.WorkflowCompiler
import yggdrasil.hermod.compiler.conductor.ConductorBackend
import yggdrasil.hermod.core.WorkflowDef
import yggdrasil.hermod.core.WorkflowStep
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Conductor branch-parallel proof on a REAL server (v0.3c). conc-fanout proves wall-clock OVERLAP of the three
 * transform.map branches (FORK_JOIN actually parallelizes); conc-comp proves partial-failure reverse-topo
 * compensation of BOTH same-blockId db.upsert nodes (nodeId-keyed ledger). Same emit as the CLI/gate.
 */
class ConductorConcurrencyIT {
    companion object {
        private lateinit var client: ConductorClient
        private lateinit var runner: TaskRunnerConfigurer

        private val fanoutDef = WorkflowDef("conc-fanout", listOf(
            WorkflowStep("db.read", "1.0.0", id = "src"),
            WorkflowStep("transform.map", "1.0.0", id = "p1", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("transform.map", "1.0.0", id = "p2", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("transform.map", "1.0.0", id = "p3", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("merge", "1.0.0", id = "join", wiring = mapOf("left" to "p1.rows", "right" to "p2.rows")),
            WorkflowStep("db.upsert", "1.2.0", id = "sink", params = mapOf("table" to "target_rows"), wiring = mapOf("rows" to "join.out")),
        ))
        private val compDef = WorkflowDef("conc-comp", listOf(
            WorkflowStep("db.read", "1.0.0", id = "src"),
            WorkflowStep("db.upsert", "1.2.0", id = "b", params = mapOf("table" to "target_rows"), wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("db.upsert", "1.2.0", id = "c", params = mapOf("table" to "target_rows"), wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("merge", "1.0.0", id = "join", wiring = mapOf("left" to "b.written", "right" to "c.written")),
            WorkflowStep("notify.email", "1.0.0", id = "sink", wiring = mapOf("rows" to "join.out")),
        ))

        @BeforeAll @JvmStatic fun setup() {
            ConductorItSupport.ensureUp()
            client = ConductorItSupport.newClient()
            val reg = ConductorItSupport.registry(); val led = ConductorItSupport.ledger()
            ConductorDeployer(client).deploy(ConductorBackend.emitBundle(WorkflowCompiler.compile(fanoutDef, reg)))
            ConductorDeployer(client).deploy(ConductorBackend.emitBundle(WorkflowCompiler.compile(compDef, reg)))
            val ids = listOf("db.read", "transform.map", "db.upsert", "notify.email", "actuate", "merge")
            val workers: List<Worker> = ids.map { ForwardWorker(it, reg, led) } + CompensateWorker(reg, led)
            runner = TaskRunnerConfigurer.Builder(TaskClient(client), workers).withThreadCount(ids.size + 1).build()
            runner.init()
        }
        @AfterAll @JvmStatic fun teardown() { if (::runner.isInitialized) runner.shutdown() }
    }

    @Test
    fun `fan-out transform_map branches overlap in wall-clock (FORK_JOIN concurrency)`() {
        ConductorItSupport.exec("DELETE FROM source_rows"); ConductorItSupport.exec("DELETE FROM target_rows")
        ConductorItSupport.exec("INSERT INTO source_rows(id,val) VALUES ('r1','a'),('r2','b')")
        val starter = ConductorStarter(client)
        val id = starter.start("conc-fanout", mapOf(
            "rows" to emptyList<Any?>(), "workflowId" to "",
            "_slowMs" to "4000", "_slowAtBlockId" to "transform.map",
        ))
        val wf: Workflow = starter.awaitTerminal(id, timeoutMs = 120_000)
        assertEquals(Workflow.WorkflowStatus.COMPLETED, wf.status, "reason=${wf.reasonForIncompletion}")
        val tms = wf.tasks.filter { it.referenceTaskName in setOf("p1","p2","p3") }
        assertEquals(3, tms.size)
        val maxStart = tms.maxOf { it.startTime }
        val minEnd = tms.minOf { it.endTime }
        assertTrue(maxStart < minEnd,
            "transform.map branches did not overlap (maxStart=$maxStart >= minEnd=$minEnd) -> serialized, not concurrent")
    }

    @Test
    fun `conc-comp partial failure compensates BOTH same-blockId nodes (nodeId-keyed)`() {
        ConductorItSupport.exec("DELETE FROM source_rows"); ConductorItSupport.exec("DELETE FROM target_rows")
        ConductorItSupport.exec("INSERT INTO source_rows(id,val) VALUES ('r1','a'),('r2','b')")
        val starter = ConductorStarter(client)
        val id = starter.start("conc-comp", mapOf(
            "rows" to emptyList<Any?>(), "workflowId" to "", "_failAtBlockId" to "notify.email",
        ))
        val wf = starter.awaitTerminal(id, timeoutMs = 120_000)
        assertTrue(wf.status == Workflow.WorkflowStatus.FAILED || wf.status == Workflow.WorkflowStatus.TERMINATED,
            "conc-comp must fail at notify.email (was ${wf.status})")
        val comp = starter.awaitCompensationTerminal(id, "conc-comp-compensation", timeoutMs = 60_000)
        assertEquals(Workflow.WorkflowStatus.COMPLETED, comp?.status, "compensation must complete")
        assertEquals(2, ConductorItSupport.count("comp_ledger", "workflow_id='$id' AND compensated"),
            "both same-blockId db.upsert nodes must be compensated independently")
        assertEquals(0, ConductorItSupport.count("target_rows"))
    }
}
