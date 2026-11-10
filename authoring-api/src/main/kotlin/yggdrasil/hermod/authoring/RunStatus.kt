package yggdrasil.hermod.authoring

import yggdrasil.hermod.runtime.CompensationEvent

/** Pure run-status helpers. Mirrors the frontend console.ts normalization so the backend agrees on "terminal". */
object RunStatus {
    private val TERMINAL = setOf("COMPLETED", "FAILED", "TERMINATED", "CANCELED", "CANCELLED", "TIMED_OUT")

    /** Strip the Temporal "WORKFLOW_EXECUTION_STATUS_" prefix (Conductor already short), uppercase. */
    fun normalize(status: String): String =
        status.uppercase().removePrefix("WORKFLOW_EXECUTION_STATUS_")

    fun isTerminal(status: String): Boolean = normalize(status) in TERMINAL

    /** True if any node is parked at the human approval gate (B1). Pure. */
    fun isAwaitingApproval(states: Map<String, String>): Boolean = states.values.any { it == "AWAITING_APPROVAL" }

    /** NONE if no compensation ran; COMP_FAILED if any step failed; else COMPENSATED. */
    fun summarizeCompOutcome(timeline: List<CompensationEvent>): String = when {
        timeline.isEmpty() -> "NONE"
        timeline.any { it.outcome == "FAILED" } -> "COMP_FAILED"
        else -> "COMPENSATED"
    }
}
