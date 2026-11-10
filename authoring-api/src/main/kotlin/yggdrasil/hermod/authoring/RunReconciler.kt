package yggdrasil.hermod.authoring

import yggdrasil.hermod.registry.RunStore
import yggdrasil.hermod.runtime.CompensationEvent
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Durable run archive (spec 2026-06-26). One idempotent primitive, two entry points:
 *  - reconcile(runId): on observing a terminal run, write-once final_status + grace-aware node/comp snapshots.
 *  - sweep(): @Scheduled background pass over not-yet-frozen runs (catches runs nobody opened).
 * Also called lazily from the read path (RunController) for immediacy.
 */
@Component
class RunReconciler(
    private val runStore: RunStore,
    private val router: EngineRouter,
    // Outbound governance-event surface (spec 2026-07-01). null unless HERMOD_EMIT_MODE is on
    // (EmitConfig returns null beans) → this path is byte-identical to today when emit is off.
    private val emitter: yggdrasil.hermod.authoring.emit.GovernanceEventEmitter? = null,
) {
    private val log = LoggerFactory.getLogger(RunReconciler::class.java)
    private val graceMs = 30_000L   // backend analogue of RunDetail TERMINAL_GRACE=30 (×1s); §3.3

    /** True once final_status is set AND the grace window has elapsed (snapshots frozen → serve from DB). */
    fun isFrozen(row: RunStore.Row): Boolean {
        val done = row.completedAtEpochMs ?: return false
        return row.finalStatus != null && (System.currentTimeMillis() - done) > graceMs
    }

    /** Idempotent. Safe to call concurrently from the sweep and the read path. */
    fun reconcile(runId: String) {
        try {
            val row = runStore.get(runId) ?: return
            if (isFrozen(row)) return                                  // already archived & frozen
            val port = router.port(row.engine)
            emitter?.observe(runId, row, port)   // NEW: emit concern; deduped + fail-open internally
            val status = port.queryStatus(runId)
            if (!RunStatus.isTerminal(status)) return                  // still in flight
            // null == the comp query FAILED (engine hiccup) — distinct from a real empty timeline. Never let a
            // transient failure overwrite/wipe a previously-captured snapshot: only write comp state on a real
            // read (symmetric with the node-state `?.let` guard below). markTerminal must still set final_status;
            // a NONE written there self-corrects on a later in-grace refresh.
            val timelineResult = runCatchingQuiet { port.queryCompensationTimeline(runId) }
            val timeline = timelineResult ?: emptyList()
            val compOutcome = RunStatus.summarizeCompOutcome(timeline)
            if (row.finalStatus == null) runStore.markTerminal(runId, status, compOutcome)  // write-once (RAW)
            else if (timelineResult != null) runStore.refreshCompOutcome(runId, compOutcome) // refresh on a real read only
            runCatchingQuiet { port.queryNodeStates(runId) }?.let { runStore.snapshotNodeStates(runId, it) }
            if (timelineResult != null) runStore.snapshotCompEvents(runId, timeline.map { it.toRow() })
        } catch (e: Exception) {
            log.warn("reconcile {} skipped: {}", runId, e.toString())  // engine down/purged != error
        }
    }

    @Scheduled(fixedDelayString = "\${hermod.reconciler.fixedDelayMs:10000}",
               initialDelayString = "\${hermod.reconciler.initialDelayMs:10000}")
    fun sweep() {
        if (System.getenv("HERMOD_RECONCILER_DISABLED") == "1") return
        for (row in runStore.archivedOrInFlight(graceMs)) reconcile(row.runId)
    }

    private fun <T> runCatchingQuiet(block: () -> T): T? = try { block() } catch (e: Exception) { null }
}

// edge mapping (boundary): :runtime CompensationEvent -> :registry CompEventRow
private fun CompensationEvent.toRow() =
    RunStore.CompEventRow(index, nodeId, blockId, version, outcome, atMillis)
