package yggdrasil.hermod.opcua

/** The governance decision for a requested (state, command): dispatch a governed run, or refuse. */
sealed interface GovernDecision {
    data class Allow(val workflow: String) : GovernDecision
    data class Deny(val reason: String) : GovernDecision
}

/**
 * Pure, state-aware transition governance (the R4 novel primitive). Given the equipment's current
 * state code and a requested command, decide — purely from the Git FSM spec — whether hermod drives
 * the transition's governed workflow. Deny-by-default; fail-closed on an unknown state code. A
 * field-driven transition is legal but not hermod's to drive → Deny (no dispatch). See spec §8.
 */
object TransitionGovernor {
    fun govern(fsm: FsmSpec, currentStateCode: Int, command: String): GovernDecision {
        val state = fsm.states.firstOrNull { it.code == currentStateCode }
            ?: return GovernDecision.Deny("unknown state code $currentStateCode (not declared in FSM '${fsm.name}')")
        val t = fsm.transitions.firstOrNull { it.from == state.id && it.command == command }
            ?: return GovernDecision.Deny("illegal transition: command '$command' not allowed from state '${state.id}'")
        if (t.driver != "hermod")
            return GovernDecision.Deny("transition '${t.id}' is field-driven (operator/PLC), not hermod-governed")
        val wf = t.action?.workflow
            ?: return GovernDecision.Deny("hermod transition '${t.id}' has no action.workflow")
        return GovernDecision.Allow(wf)
    }
}
