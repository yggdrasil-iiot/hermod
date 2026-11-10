package yggdrasil.hermod.authoring

import yggdrasil.hermod.opcua.AutoCorrectAction

/**
 * Pure decision core for auto-correct auto-dispatch (design 2026-07-04). Every effect is an injected lambda, so
 * this is deterministically unit-testable with NO DB/Temporal — the AutoCorrectSupervisor idiom. Two phases,
 * order matters: (1) reconcile finished corrections FIRST so a just-completed one can't block a new drift's
 * dispatch; (2) dedup-guarded dispatch — never start a correction while one is already PENDING for the unit.
 */
object AutoCorrectDispatchCore {
    fun run(
        actions: List<AutoCorrectAction>,
        pendingRuns: List<Triple<Long, String, Long>>,   // id, runId, dispatchedAtEpochMs
        now: Long,
        staleAfterMillis: Long,
        runStatus: (runId: String) -> String?,
        resolve: (id: Long, status: String) -> Unit,
        activePending: (unit: String) -> Boolean,
        dispatch: (unit: String, from: Int, to: Int, workflow: String) -> Unit,
    ) {
        for ((id, runId, dispatchedAt) in pendingRuns) {
            val s = runStatus(runId)
            if (s == null) { if (now - dispatchedAt > staleAfterMillis) resolve(id, "FAILED"); continue }
            if (!RunStatus.isTerminal(s)) continue
            resolve(id, if (RunStatus.normalize(s) == "COMPLETED") "RESOLVED" else "FAILED")
        }
        for (a in actions.filterIsInstance<AutoCorrectAction.DriftCorrectable>()) {
            if (activePending(a.unit)) continue
            dispatch(a.unit, a.from, a.to, a.workflow)
        }
    }
}
