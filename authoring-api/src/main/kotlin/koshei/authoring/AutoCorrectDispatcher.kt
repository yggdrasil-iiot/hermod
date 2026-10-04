package koshei.authoring

import koshei.opcua.AutoCorrectAction
import koshei.opcua.AutoCorrectSupervisor
import koshei.opcua.FsmSpec
import koshei.opcua.FsmStateReader
import koshei.registry.DriftCorrectionStore
import koshei.registry.DriftStore
import koshei.registry.FsmDeploymentStore
import koshei.registry.RunStore
import koshei.registry.WorkflowStore
import koshei.runtime.WorkflowInput
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.util.UUID

/** The one-shot sweep the endpoint runs (sweep, reconcile, dispatch). */
fun interface OneShotSweep {
    fun runOnce(modelDir: File): List<AutoCorrectAction>
}

/**
 * Turns swept AutoCorrectActions into auto-dispatched corrective runs, guarded by the drift_correction dedup
 * ledger (design 2026-07-04). Shared by the @Scheduled AutoCorrectBean and the one-shot POST /api/autocorrect/
 * sweep endpoint. The pure decision lives in AutoCorrectDispatchCore; this only wires real effects. Dispatch
 * mirrors ReconciliationController (router.port("temporal").start + runStore.record). The dispatched ot-safe-
 * hold parks at the intrinsic IRREVERSIBLE human gate (interactive=false, autoApprove default false), so koshei
 * never actuates without an operator approve.
 */
@Component
class AutoCorrectDispatcher(
    private val deploymentStore: FsmDeploymentStore,
    private val driftStore: DriftStore,
    private val correctionStore: DriftCorrectionStore,
    private val runStore: RunStore,
    private val workflowStore: WorkflowStore,
    private val router: EngineRouter,
) : OneShotSweep {
    private val log = LoggerFactory.getLogger(javaClass)
    private val endpoint = System.getenv("KOSHEI_OPCUA_URL") ?: "opc.tcp://localhost:48400"
    private val saga = "ot-safe-hold"; private val sagaVersion = "1.0.0"
    private val staleAfterMs = System.getenv("KOSHEI_AUTOCORRECT_STALE_MS")?.toLongOrNull() ?: 3_600_000L

    /** Sweep only (detect + evaluate + record observation) — today's alarm-only body; no reconcile, no dispatch. */
    fun sweepAlarmOnly(modelDir: File): List<AutoCorrectAction> = AutoCorrectSupervisor.sweep(
        units = deploymentStore.activeUnits(),
        readState = { fsm -> FsmStateReader.readStateCode(fsm, endpoint) },
        resolveFsm = { unit -> deploymentStore.activeVersion(unit)?.let { FsmSpec.resolve(modelDir, unit, it) }?.let(FsmSpec::fromFile) },
        lastState = { unit -> driftStore.lastState(unit) },
        recordObservation = { u, f, t, v, d -> driftStore.observe(u, f, t, v, d) },
    )

    /** Full cycle: sweep, then reconcile PENDING corrections + dedup-guarded auto-dispatch. */
    override fun runOnce(modelDir: File): List<AutoCorrectAction> {
        val actions = sweepAlarmOnly(modelDir)
        AutoCorrectDispatchCore.run(
            actions = actions,
            pendingRuns = correctionStore.allPending().map { Triple(it.id, it.runId, it.dispatchedAtEpochMs) },
            now = System.currentTimeMillis(), staleAfterMillis = staleAfterMs,
            runStatus = { runId ->
                try { router.port(runStore.engineOf(runId)).queryStatus(runId) }
                catch (e: Exception) { log.warn("runStatus {} failed: {}", runId, e.message); null }
            },
            resolve = { id, status -> correctionStore.resolve(id, status) },
            activePending = { unit -> correctionStore.activePending(unit) != null },
            dispatch = { unit, from, to, workflow -> doDispatch(unit, from, to, workflow) },
        )
        return actions
    }

    private fun doDispatch(unit: String, from: Int, to: Int, workflow: String) {
        if (workflowStore.get(saga, sagaVersion) == null) {
            log.warn("{}@{} not deployed — cannot auto-dispatch {}", saga, sagaVersion, unit); return
        }
        val runId = "autocorrect-$unit-${UUID.randomUUID()}"
        router.port("temporal").start(runId, WorkflowInput(workflowName = "$saga@$sagaVersion", interactive = false))
        try { runStore.record(runId, saga, sagaVersion, "{\"unit\":\"$unit\",\"autocorrect\":true}", "temporal") }
        catch (e: Exception) { log.warn("dispatched {} but not recorded: {}", runId, e.toString()) }
        val started = try { correctionStore.insertPending(unit, runId, from, to, workflow) }
                      catch (e: Exception) { log.warn("insertPending {} failed: {}", runId, e.toString()); false }
        if (!started) {
            log.warn("correction {} not recorded (concurrent dedup / insert error) — aborting the redundant run", runId)
            try { router.port("temporal").signalAbort(runId) } catch (e: Exception) { log.warn("abort {} failed: {}", runId, e.message) }
            return
        }
        log.warn("AUTO-DISPATCH: {} drift {}->{} — started {} ({}), parked for approval", unit, from, to, runId, workflow)
    }
}
