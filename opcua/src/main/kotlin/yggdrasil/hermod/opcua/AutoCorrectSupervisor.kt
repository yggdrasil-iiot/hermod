package yggdrasil.hermod.opcua

/** The per-unit outcome of one auto-correct sweep. Detect + evaluate only — NEVER a dispatch. */
sealed interface AutoCorrectAction {
    val unit: String
    data class Baseline(override val unit: String, val code: Int) : AutoCorrectAction
    data class Ok(override val unit: String, val from: Int, val to: Int) : AutoCorrectAction
    /** DRIFT with a governed corrective SafeHold available (awaiting operator dispatch+approval). */
    data class DriftCorrectable(override val unit: String, val from: Int, val to: Int, val workflow: String, val driftReason: String) : AutoCorrectAction
    /** DRIFT with NO declared safe path (govern DENY) — manual intervention. */
    data class DriftBlocked(override val unit: String, val from: Int, val to: Int, val driftReason: String, val governReason: String) : AutoCorrectAction
    /** No active deployment / unreadable stateNode — skipped (operational, not a drift verdict). */
    data class Skipped(override val unit: String, val why: String) : AutoCorrectAction
}

/**
 * Periodic drift surveillance + corrective-SafeHold evaluation across deployed units. Composes the existing
 * pure DriftDetector + TransitionGovernor; records the observation exactly as `fsm drift-check` does. NEVER
 * dispatches (human-in-the-loop). All effects (live read, FSM resolution, store reads/writes) are INJECTED
 * lambdas so this stays pure :opcua (0-registry) + deterministically unit-testable. See design 2026-07-03.
 */
object AutoCorrectSupervisor {
    fun sweep(
        units: List<String>,
        readState: (FsmSpec) -> Int?,
        resolveFsm: (unit: String) -> FsmSpec?,
        lastState: (unit: String) -> Int?,
        recordObservation: (unit: String, from: Int?, to: Int, verdict: String, detail: String) -> Unit,
    ): List<AutoCorrectAction> = units.map { unit ->
        try { sweepOne(unit, readState, resolveFsm, lastState, recordObservation) }
        catch (t: Throwable) { AutoCorrectAction.Skipped(unit, "sweep error: ${t.message}") }
    }

    private fun sweepOne(
        unit: String,
        readState: (FsmSpec) -> Int?,
        resolveFsm: (String) -> FsmSpec?,
        lastState: (String) -> Int?,
        recordObservation: (String, Int?, Int, String, String) -> Unit,
    ): AutoCorrectAction {
        val fsm = resolveFsm(unit) ?: return AutoCorrectAction.Skipped(unit, "no resolvable active FSM spec (no deployment or missing spec file)")
        val observed = readState(fsm) ?: return AutoCorrectAction.Skipped(unit, "could not read stateNode '${fsm.stateNode}'")
        val prior = lastState(unit)
            ?: run { recordObservation(unit, null, observed, "BASELINE", "-"); return AutoCorrectAction.Baseline(unit, observed) }
        return when (val d = DriftDetector.detect(fsm, prior, observed)) {
            is DriftDecision.Ok -> { recordObservation(unit, prior, observed, "OK", "-"); AutoCorrectAction.Ok(unit, prior, observed) }
            is DriftDecision.Drift -> {
                recordObservation(unit, prior, observed, "DRIFT", d.reason)
                when (val g = TransitionGovernor.govern(fsm, observed, "SafeHold")) {
                    is GovernDecision.Allow -> AutoCorrectAction.DriftCorrectable(unit, prior, observed, g.workflow, d.reason)
                    is GovernDecision.Deny  -> AutoCorrectAction.DriftBlocked(unit, prior, observed, d.reason, g.reason)
                }
            }
        }
    }
}
