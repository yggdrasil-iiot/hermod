package yggdrasil.hermod.conductor

import com.netflix.conductor.client.automator.TaskRunnerConfigurer
import com.netflix.conductor.client.http.ConductorClient
import com.netflix.conductor.client.http.TaskClient
import com.netflix.conductor.client.worker.Worker
import com.netflix.conductor.common.metadata.tasks.Task
import com.netflix.conductor.common.run.Workflow
import yggdrasil.hermod.compiler.WorkflowCompiler
import yggdrasil.hermod.compiler.conductor.ConductorBackend
import yggdrasil.hermod.core.WorkflowDef
import yggdrasil.hermod.core.WorkflowStep
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-server diamond fan-in: the SAME engine-neutral IR used everywhere is deployed to a REAL Conductor
 * server and EXECUTED by in-process workers reusing the real block handlers. This is the Conductor side of
 * the diamond proof (the Temporal side is SagaWorkflowTest's `diamond fan-in` case).
 *
 * Topology (mirrors `app/.../workflows/dag-diamond.yaml`):
 *   src(db.read) -> { b(transform.map), c(transform.map) } -> join(merge: left=b, right=c) -> sink(db.upsert).
 *
 * Fan-in assertions:
 *  - the `join` (merge) task's own Conductor output `out` carries 4 rows (left=2 ++ right=2) — proving BOTH
 *    branches were consumed and fanned-in before the sink ran (read straight off the workflow's task outputs,
 *    which the harness already exposes via getWorkflow(..., includeTasks=true)).
 *  - db.upsert collapses those 4 rows by PK to exactly 2 distinct ids in target_rows (r1, r2 carried down both
 *    branches -> 4 merged -> upsert-by-PK collapses to 2). The collapse can only land on 2 if the merge truly
 *    produced 4 from 2 distinct PKs across both branches.
 *
 * Container fixture lives in [ConductorItSupport] (JVM singleton) — reused across the conductor ITs.
 */
class DagDiamondIT {

    companion object {
        private lateinit var client: ConductorClient
        private lateinit var runner: TaskRunnerConfigurer

        /** Diamond fan-in: src -> {b, c} -> join(merge left=b,right=c) -> sink(db.upsert). Mirrors dag-diamond.yaml. */
        private val diamondDef = WorkflowDef(
            name = "dag-diamond",
            steps = listOf(
                WorkflowStep("db.read", "1.0.0", id = "src"),
                WorkflowStep("transform.map", "1.0.0", id = "b", wiring = mapOf("rows" to "src.rows")),
                WorkflowStep("transform.map", "1.0.0", id = "c", wiring = mapOf("rows" to "src.rows")),
                WorkflowStep("merge", "1.0.0", id = "join", wiring = mapOf("left" to "b.rows", "right" to "c.rows")),
                WorkflowStep(
                    "db.upsert", "1.2.0", id = "sink",
                    params = mapOf("table" to "target_rows"), wiring = mapOf("rows" to "join.out"),
                ),
            ),
        )

        @BeforeAll
        @JvmStatic
        fun setup() {
            ConductorItSupport.ensureUp()
            client = ConductorItSupport.newClient()

            // Deploy the bundle (taskdefs + main + compensation) from the SAME emit the CLI/gate use.
            val ir = WorkflowCompiler.compile(diamondDef, ConductorItSupport.registry())
            ConductorDeployer(client).deploy(ConductorBackend.emitBundle(ir))

            // Boot in-process workers for every builtin block (+ compensate), against the container. `merge`
            // is the multi-input fan-in worker (ForwardWorker handles its left/right namedInputs).
            val registry = ConductorItSupport.registry()
            val ledger = ConductorItSupport.ledger()
            val builtinIds = listOf("db.read", "transform.map", "db.upsert", "notify.email", "actuate", "merge")
            val workers: List<Worker> = builtinIds.map { ForwardWorker(it, registry, ledger) } +
                CompensateWorker(registry, ledger)
            runner = TaskRunnerConfigurer.Builder(TaskClient(client), workers)
                .withThreadCount(builtinIds.size + 1)
                .build()
            runner.init()
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            if (::runner.isInitialized) runner.shutdown()
        }
    }

    @Test
    fun `diamond fan-in runs to COMPLETED with merge consuming both branches`() {
        // Seed 2 source rows; clear target so the collapse assertion is unambiguous.
        ConductorItSupport.exec("DELETE FROM source_rows")
        ConductorItSupport.exec("DELETE FROM target_rows")
        ConductorItSupport.exec("INSERT INTO source_rows(id,val) VALUES ('r1','alpha'),('r2','beta')")

        val starter = ConductorStarter(client)
        val workflowId = starter.start("dag-diamond", mapOf("rows" to emptyList<Any?>(), "workflowId" to ""))
        assertTrue(workflowId.isNotBlank(), "start must return a workflowId")

        val wf: Workflow = starter.awaitTerminal(workflowId, timeoutMs = 120_000)
        assertEquals(
            Workflow.WorkflowStatus.COMPLETED, wf.status,
            "diamond must COMPLETE (status was ${wf.status}; reason=${wf.reasonForIncompletion})"
        )

        // FAN-IN PROOF: the merge (`join` ref) task COMPLETED and its `out` output carries 4 rows (b's 2 ++ c's 2).
        val join: Task = wf.tasks.firstOrNull { it.referenceTaskName == "join" }
            ?: error("the diamond must contain a `join` (merge) task; tasks=${wf.tasks.map { it.referenceTaskName }}")
        assertEquals(
            Task.Status.COMPLETED, join.status,
            "the merge `join` task must have COMPLETED (was ${join.status})"
        )
        val mergedOut = join.outputData["out"] as? List<*>
        assertNotNull(mergedOut, "the merge task must publish its fan-in result under output key `out`")
        assertEquals(
            4, mergedOut.size,
            "merge must fan-in both branches: left=2 ++ right=2 == 4 rows (got ${mergedOut.size})"
        )

        // SINK: db.upsert collapses the 4 merged rows by PK to exactly 2 distinct ids in target_rows.
        assertEquals(
            2, ConductorItSupport.count("target_rows"),
            "db.upsert must collapse the 4 merged rows by PK to 2 distinct ids in target_rows"
        )
    }
}
