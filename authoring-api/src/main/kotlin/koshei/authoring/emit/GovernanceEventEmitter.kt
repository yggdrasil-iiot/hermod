package koshei.authoring.emit

import koshei.authoring.RunStatus
import koshei.opcua.CommandAuditReader
import koshei.opcua.emit.GovernanceEvent
import koshei.opcua.emit.GovernedNode
import koshei.registry.EmittedEventStore
import koshei.registry.RunStore
import koshei.runtime.EnginePort
import org.slf4j.LoggerFactory

/**
 * Observes each run at the reconcile choke point and emits at most one governance event of each type
 * per run (spec 2026-07-01 §3.3): RECONCILING on first "reconciliation in-flight" observation,
 * CONFIRMED/RECON_FAILED on terminal. Deduped by the write-once [EmittedEventStore]; terminal is checked
 * before RECONCILING so a late in-flight observation never emits out of order. Fail-open: an emit failure
 * never escapes observe.
 */
open class GovernanceEventEmitter(
    private val session: SparkplugEdgeSession,
    private val emittedLog: EmittedEventStore,
    private val stagedSetpoints: () -> List<GovernedNode>,          // desired canonical → STAGED nodes
    private val summarize: (String) -> List<GovernedNode> = CommandAuditReader::summarize,
    private val now: () -> Long = System::currentTimeMillis,
    private val defRefFor: (String) -> String? = koshei.opcua.ReconciliationProvenance::defRefFor,
) {
    private val log = LoggerFactory.getLogger(GovernanceEventEmitter::class.java)

    /** Called from RunReconciler.reconcile after the engine port is resolved. Fail-open. */
    open fun observe(runId: String, row: RunStore.Row, port: EnginePort) {
        try {
            val status = port.queryStatus(runId)
            if (RunStatus.isTerminal(status)) {
                emitTerminal(runId, row, status, port)
            } else if (inFlight(port.queryNodeStates(runId)) && !terminalClaimed(runId)) {
                emitReconciling(runId, row)
            }
        } catch (e: Exception) { log.warn("emit observe {} skipped: {}", runId, e.toString()) }
    }

    // "reconciliation in-flight": at least one governed step has finished (a node is DONE) while the run
    // is still non-terminal (some node still RUNNING/PENDING/AWAITING_APPROVAL). A human gate reports the
    // distinct "AWAITING_APPROVAL" node-state (SagaWorkflowImpl.kt:75, B1) — it MUST count as in-flight, else
    // a run parked at the gate (all upstream DONE, the gate node the last non-terminal one) would suppress
    // RECONCILING for the whole human-paced parked window. ("PARKED" is a *failed*-node/operator-decision
    // state, not the approval gate.) RECONCILING signals "staged, reconciliation underway".
    private fun inFlight(nodeStates: Map<String, String>): Boolean {
        val states = nodeStates.values
        return states.any { it == "DONE" } && states.any { it == "RUNNING" || it == "PENDING" || it == "AWAITING_APPROVAL" }
    }

    // Ordering guard: never emit RECONCILING after a terminal was already emitted for this run.
    private fun terminalClaimed(runId: String): Boolean =
        emittedLog.claimed(runId, "CONFIRMED") || emittedLog.claimed(runId, "RECON_FAILED")

    private fun emitReconciling(runId: String, row: RunStore.Row) {
        if (!emittedLog.tryClaim(runId, "RECONCILING", now())) return
        // TOCTOU: a terminal may have been claimed between observe()'s check and this claim (reconcile
        // runs on the sweep thread AND HTTP threads). Re-check after claiming so we never publish a
        // RECONCILING after CONFIRMED/RECON_FAILED went out on the wire.
        if (terminalClaimed(runId)) return
        session.publishNdata(GovernanceEvent("RECONCILING", runId, wf(row), row.engine,
            "IN_FLIGHT", "NONE", now(), stagedSetpoints(), defRef = defRefOrNull(runId)))
    }

    private fun emitTerminal(runId: String, row: RunStore.Row, status: String, port: EnginePort) {
        val comp = RunStatus.summarizeCompOutcome(port.queryCompensationTimeline(runId))
        val type = if (status.contains("COMPLETED") && comp == "NONE") "CONFIRMED" else "RECON_FAILED"
        if (!emittedLog.tryClaim(runId, type, now())) return
        // Per-node enrichment: summarize(runId) is now the PRIMARY path — command_audit is keyed by the
        // real workflow runId, so a run-keyed lookup returns this run's actual governed nodes/outcomes.
        // Fall back to the Git-canonical governed setpoints (desired value) tagged with the event outcome
        // only for a run that emitted no audit rows, so the event still carries the governed nodes the
        // consumer needs to correlate.
        val nodeOutcome = if (type == "CONFIRMED") "CONFIRMED" else "RESTORED"
        val nodes = summarize(runId).ifEmpty { stagedSetpoints().map { it.copy(outcome = nodeOutcome) } }
        session.publishNdata(GovernanceEvent(type, runId, wf(row), row.engine, status, comp, now(), nodes, defRef = defRefOrNull(runId)))
    }

    // defRef is optional enrichment looked up AFTER the write-once claim: a failed lookup (DB unreachable, or
    // reconciliation_provenance missing because app/schema.sql was not applied) must drop the defRef, not the event.
    private fun defRefOrNull(runId: String): String? =
        try { defRefFor(runId) } catch (e: Exception) { log.warn("emit defRef {} unavailable, publishing without it: {}", runId, e.toString()); null }

    private fun wf(row: RunStore.Row) = "${row.workflowName}:${row.workflowVersion}"
}
