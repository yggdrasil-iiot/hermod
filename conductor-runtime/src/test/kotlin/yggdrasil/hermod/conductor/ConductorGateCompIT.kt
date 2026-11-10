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
 * The HARD parts of the Conductor e2e (Task 4.3): the human-gate (approve/reject) and saga compensation
 * driven by the Postgres `comp_ledger`, against a REAL conductor-standalone. Reuses [ConductorItSupport]
 * (the JVM-singleton container fixture) so we don't restart conductor/Postgres.
 *
 * Three behaviours proven:
 *  1. APPROVE  — a workflow with an IRREVERSIBLE `actuate` step emits a WAIT task; the run blocks IN_PROGRESS
 *                until `approve(workflowId)` completes it, then reaches COMPLETED.
 *  2. REJECT   -> COMPENSATION — the same workflow's `db.upsert`+`notify.email` already applied side effects
 *                (target_rows row inserted, comp_ledger rows written) when the WAIT blocks. `reject` fails the
 *                WAIT -> main FAILED -> Conductor dispatches `<name>-compensation`, which (R-3) reads the
 *                ledger by the FAILED workflow id Conductor injects as `input.workflowId`, undoes the insert
 *                in REVERSE order, and marks the ledger compensated.
 *  3. MID-FAILURE -> COMPENSATION — `notify.email` is fault-injected to throw after `db.upsert` succeeded; the
 *                task FAILS_WITH_TERMINAL_ERROR (PermanentBlockFailure, non-retriable) -> main FAILED immediately
 *                -> Conductor dispatches compensation, which undoes db.upsert.
 */
class ConductorGateCompIT {

    companion object {
        private lateinit var client: ConductorClient
        private lateinit var runner: TaskRunnerConfigurer

        /** db.read -> transform.map -> db.upsert(target_rows) -> notify.email -> actuate(IRREVERSIBLE => WAIT). */
        private val gateDef = WorkflowDef(
            name = "it-gate",
            steps = listOf(
                WorkflowStep("db.read", "1.0.0"),
                WorkflowStep("transform.map", "1.0.0"),
                WorkflowStep("db.upsert", "1.2.0", params = mapOf("table" to "target_rows")),
                WorkflowStep("notify.email", "1.0.0"),
                WorkflowStep("actuate", "1.0.0"),
            ),
        )

        /** db.read -> transform.map -> db.upsert(target_rows) -> notify.email (no WAIT; mid-flight failure). */
        private val midfailDef = WorkflowDef(
            name = "it-midfail",
            steps = listOf(
                WorkflowStep("db.read", "1.0.0"),
                WorkflowStep("transform.map", "1.0.0"),
                WorkflowStep("db.upsert", "1.2.0", params = mapOf("table" to "target_rows")),
                WorkflowStep("notify.email", "1.0.0"),
            ),
        )

        @BeforeAll
        @JvmStatic
        fun setup() {
            ConductorItSupport.ensureUp()
            client = ConductorItSupport.newClient()

            val registry = ConductorItSupport.registry()
            ConductorDeployer(client).deploy(ConductorBackend.emitBundle(WorkflowCompiler.compile(gateDef, registry)))
            ConductorDeployer(client).deploy(ConductorBackend.emitBundle(WorkflowCompiler.compile(midfailDef, registry)))

            // No retry-delay override needed: PermanentBlockFailure now maps to FAILED_WITH_TERMINAL_ERROR,
            // so a failing task is rejected immediately without any retry. The workflow fails fast.

            val ledger = ConductorItSupport.ledger()
            val builtinIds = listOf("db.read", "transform.map", "db.upsert", "notify.email", "actuate")
            val workers: List<Worker> = builtinIds.map { ForwardWorker(it, registry, ledger) } +
                CompensateWorker(registry, ledger)
            runner = TaskRunnerConfigurer.Builder(TaskClient(client), workers)
                .withThreadCount(builtinIds.size + 1)
                .build()
            runner.init()
            // Note: the test-only retryDelaySeconds=1 override for notify.email was previously needed
            // because PermanentBlockFailure -> FAILED (retriable) would delay ~60s per retry.
            // Now that PermanentBlockFailure -> FAILED_WITH_TERMINAL_ERROR (non-retriable), the task
            // fails immediately without retrying, so the override is no longer necessary.
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            if (::runner.isInitialized) runner.shutdown()
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private fun seed() {
        ConductorItSupport.exec("DELETE FROM source_rows")
        ConductorItSupport.exec("DELETE FROM target_rows")
        ConductorItSupport.exec("DELETE FROM comp_ledger")
        ConductorItSupport.exec("INSERT INTO source_rows(id,val) VALUES ('r1','alpha'),('r2','beta')")
    }

    private fun targetCount(): Int = ConductorItSupport.count("target_rows")
    private fun ledgerCount(where: String): Int = ConductorItSupport.count("comp_ledger", where)

    /** Poll until the single WAIT task is IN_PROGRESS (the gate is blocking), or fail after timeout. */
    private fun awaitWaitBlocked(starter: ConductorStarter, workflowId: String, timeoutMs: Long = 60_000): Workflow {
        val deadline = System.currentTimeMillis() + timeoutMs
        var wf = starter.getWorkflow(workflowId)
        while (System.currentTimeMillis() < deadline) {
            val waitInProgress = wf.tasks.any {
                it.taskType == "WAIT" && it.status == Task.Status.IN_PROGRESS
            }
            if (waitInProgress) return wf
            // bail early if the run already went terminal without ever blocking
            if (wf.status.isTerminal) return wf
            Thread.sleep(1_000)
            wf = starter.getWorkflow(workflowId)
        }
        return wf
    }

    // ---- 1. APPROVE -------------------------------------------------------------------------------------

    @Test
    fun `human gate APPROVE blocks at WAIT then completes`() {
        seed()
        val starter = ConductorStarter(client)
        val workflowId = starter.start("it-gate", mapOf("rows" to emptyList<Any?>(), "workflowId" to ""))
        assertTrue(workflowId.isNotBlank(), "start must return a workflowId")

        // Run reaches the WAIT and BLOCKS there (RUNNING, WAIT task IN_PROGRESS).
        val blocked = awaitWaitBlocked(starter, workflowId)
        assertEquals(
            Workflow.WorkflowStatus.RUNNING, blocked.status,
            "must be RUNNING at the gate (was ${blocked.status}; reason=${blocked.reasonForIncompletion})"
        )
        assertTrue(
            blocked.tasks.any { it.taskType == "WAIT" && it.status == Task.Status.IN_PROGRESS },
            "the actuate WAIT task must be IN_PROGRESS (blocked)"
        )

        // Approve the gate -> the WAIT completes -> the run finishes.
        assertTrue(starter.approve(workflowId), "approve must find and complete the IN_PROGRESS WAIT task")
        val done = starter.awaitTerminal(workflowId, timeoutMs = 60_000)
        assertEquals(
            Workflow.WorkflowStatus.COMPLETED, done.status,
            "approved gate must COMPLETE (was ${done.status}; reason=${done.reasonForIncompletion})"
        )
    }

    // ---- 2. REJECT -> COMPENSATION ----------------------------------------------------------------------

    @Test
    fun `human gate REJECT triggers reverse-order compensation that undoes the upsert and marks the ledger`() {
        seed()
        val starter = ConductorStarter(client)
        val workflowId = starter.start("it-gate", mapOf("rows" to emptyList<Any?>(), "workflowId" to ""))

        val blocked = awaitWaitBlocked(starter, workflowId)
        assertEquals(Workflow.WorkflowStatus.RUNNING, blocked.status, "must block at the gate before reject")

        // PRECONDITION: the compensable side effects already happened by the time the WAIT blocks.
        assertEquals(2, targetCount(), "db.upsert inserted both rows into target_rows before the gate")
        // db.upsert (insertedIds) + notify.email (sent) both ledgered, keyed by THIS run's id, uncompensated.
        assertEquals(2, ledgerCount("workflow_id='$workflowId'"), "db.upsert + notify.email both ledgered")
        assertEquals(2, ledgerCount("workflow_id='$workflowId' AND NOT compensated"), "both ledger rows uncompensated")
        assertEquals(1, ledgerCount("workflow_id='$workflowId' AND block_id='db.upsert'"), "db.upsert ledger row exists")

        // REJECT the gate -> main FAILED -> Conductor dispatches the failureWorkflow.
        assertTrue(starter.reject(workflowId, "operator declined"), "reject must fail the IN_PROGRESS WAIT")
        val mainFailed = starter.awaitTerminal(workflowId, timeoutMs = 120_000)
        assertEquals(
            Workflow.WorkflowStatus.FAILED, mainFailed.status,
            "rejected main workflow must be FAILED (was ${mainFailed.status}; reason=${mainFailed.reasonForIncompletion})"
        )

        // R-3: the compensation run is located by the FAILED workflow id that Conductor injects as input.workflowId.
        val comp = starter.awaitCompensationTerminal(workflowId, "it-gate-compensation", timeoutMs = 120_000)
        assertNotNull(comp, "Conductor must have dispatched the it-gate-compensation failureWorkflow")
        assertEquals(
            Workflow.WorkflowStatus.COMPLETED, comp.status,
            "compensation workflow must COMPLETE (was ${comp.status}; reason=${comp.reasonForIncompletion})"
        )

        // (a) the db.upsert insert is UNDONE: both inserted rows are gone from target_rows.
        assertEquals(0, targetCount(), "target_rows insert must be undone by db.upsert.compensate after rejection")
        // (b) the ledger rows are now marked compensated.
        assertEquals(2, ledgerCount("workflow_id='$workflowId' AND compensated"), "both ledger rows marked compensated")
        assertEquals(0, ledgerCount("workflow_id='$workflowId' AND NOT compensated"), "no uncompensated ledger rows remain")

        // REVERSE-ORDER proof from Conductor's own execution record: the compensation workflow's compensate
        // tasks, ordered by scheduled time, must be notify.email THEN db.upsert (reverse of forward order).
        val compensatedOrder = comp.tasks
            .filter { it.taskType == "compensate" }
            .sortedBy { it.scheduledTime }
            .map { it.inputData["_blockId"] as? String }
        assertEquals(
            listOf("notify.email", "db.upsert"), compensatedOrder,
            "compensation must run in REVERSE order (notify.email before db.upsert); got $compensatedOrder"
        )
    }

    // ---- 3. MID-FAILURE -> COMPENSATION -----------------------------------------------------------------

    @Test
    fun `mid-flight failure after db_upsert triggers compensation that undoes the upsert`() {
        seed()
        val starter = ConductorStarter(client)
        // Inject the fault via start input: ForwardWorker reads `_failAtBlockId` (emit forwards it into every task).
        val workflowId = starter.start(
            "it-midfail",
            mapOf("rows" to emptyList<Any?>(), "workflowId" to "", "_failAtBlockId" to "notify.email"),
        )

        // db.upsert succeeds + ledgers; notify.email throws PermanentBlockFailure -> task FAILED_WITH_TERMINAL_ERROR
        // (non-retriable, fails immediately) -> main FAILED. Generous timeout for dispatch lag.
        val mainFailed = starter.awaitTerminal(workflowId, timeoutMs = 120_000)
        assertEquals(
            Workflow.WorkflowStatus.FAILED, mainFailed.status,
            "the mid-flight failure must FAIL the main workflow (was ${mainFailed.status}; reason=${mainFailed.reasonForIncompletion})"
        )

        // notify.email threw BEFORE its ledger append, so only db.upsert is in the ledger for this run.
        assertEquals(1, ledgerCount("workflow_id='$workflowId'"), "only db.upsert ledgered (notify.email failed pre-append)")
        assertEquals(1, ledgerCount("workflow_id='$workflowId' AND block_id='db.upsert'"), "db.upsert ledger row present")

        // Conductor dispatches it-midfail-compensation; located by R-3 input.workflowId == this run's id.
        val comp = starter.awaitCompensationTerminal(workflowId, "it-midfail-compensation", timeoutMs = 90_000)
        assertNotNull(comp, "Conductor must have dispatched the it-midfail-compensation failureWorkflow")
        assertEquals(
            Workflow.WorkflowStatus.COMPLETED, comp.status,
            "compensation must COMPLETE (was ${comp.status}; reason=${comp.reasonForIncompletion})"
        )

        // db.upsert insert undone; ledger marked compensated.
        assertEquals(0, targetCount(), "target_rows insert undone by db.upsert.compensate after mid-flight failure")
        assertEquals(1, ledgerCount("workflow_id='$workflowId' AND compensated"), "db.upsert ledger row marked compensated")
    }
}
