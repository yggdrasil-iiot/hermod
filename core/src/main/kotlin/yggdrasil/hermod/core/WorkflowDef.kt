package yggdrasil.hermod.core

/** One step in a workflow: which block (pinned), its operator params, and input wiring. */
data class WorkflowStep(
    val blockId: String,
    val pinnedVersion: String,
    val id: String? = null,
    val params: Map<String, String> = emptyMap(),
    /** map of this step's input name -> upstream "stepId.outputName". Empty => positional fallback (v0.3a §3.4). */
    val wiring: Map<String, String> = emptyMap(),
)

/** A workflow definition: an ordered list of steps. v0.1 is linear (DAG support is later). */
data class WorkflowDef(val name: String, val steps: List<WorkflowStep>)
