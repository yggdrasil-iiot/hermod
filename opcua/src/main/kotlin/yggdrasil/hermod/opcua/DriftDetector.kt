package yggdrasil.hermod.opcua

/** The drift decision for an observed prior->observed state change. */
sealed interface DriftDecision {
    object Ok : DriftDecision
    data class Drift(val reason: String) : DriftDecision
}

/**
 * Pure field-transition drift detection (R4 §9.2). Given the equipment's PRIOR observed state code and the
 * NEWLY observed state code, decide — purely from the Git FSM spec — whether that observed transition is
 * DECLARED (any driver, any command; we observe the RESULT state, not a command). Fail-closed: an undeclared
 * prior/observed state code, or a from->to with no declared transition, is DRIFT. Detect-only — hermod does
 * NOT drive the equipment (see design 2026-07-03).
 */
object DriftDetector {
    fun detect(fsm: FsmSpec, priorCode: Int, observedCode: Int): DriftDecision {
        if (priorCode == observedCode) return DriftDecision.Ok
        val prior = fsm.states.firstOrNull { it.code == priorCode }
            ?: return DriftDecision.Drift("prior state code $priorCode not declared in FSM '${fsm.name}'")
        val obs = fsm.states.firstOrNull { it.code == observedCode }
            ?: return DriftDecision.Drift("observed state code $observedCode not declared in FSM '${fsm.name}'")
        val declared = fsm.transitions.any { it.from == prior.id && it.to == obs.id }
        return if (declared) DriftDecision.Ok
               else DriftDecision.Drift("undeclared transition ${prior.id} -> ${obs.id} (no such transition in FSM '${fsm.name}')")
    }
}
