package yggdrasil.hermod.conductor

import com.netflix.conductor.common.metadata.tasks.Task
import com.netflix.conductor.common.run.Workflow

/**
 * Pure mapper: Conductor workflow execution -> {nodeId -> node state}, the same vocabulary the operator
 * console renders for Temporal (PENDING/RUNNING/DONE/FAILED/COMPENSATED/COMP_FAILED). Observe-only: derived
 * entirely from the Conductor server's Workflow.tasks (no DB). Structural tasks (FORK_JOIN/JOIN, anything
 * without `_nodeId`) are skipped. The compensation run, when present, overlays COMPENSATED/COMP_FAILED.
 */
object ConductorNodeStates {

    private fun nodeIdOf(t: Task): String? =
        (t.inputData?.get("_nodeId") as? String)?.takeIf { it.isNotEmpty() }

    /** Forward task status -> node state (exhaustive over conductor-client 5.0.1 Task.Status). */
    private fun forwardState(t: Task): String = when (t.status) {
        Task.Status.COMPLETED, Task.Status.COMPLETED_WITH_ERRORS -> "DONE"
        Task.Status.IN_PROGRESS -> if (t.taskType == "WAIT") "AWAITING_APPROVAL" else "RUNNING"
        Task.Status.FAILED, Task.Status.FAILED_WITH_TERMINAL_ERROR -> "FAILED"
        Task.Status.SCHEDULED, Task.Status.CANCELED, Task.Status.SKIPPED, Task.Status.TIMED_OUT, null -> "PENDING"
    }

    fun nodeStates(main: Workflow, comp: Workflow?): Map<String, String> {
        val states = LinkedHashMap<String, String>()
        for (t in main.tasks.orEmpty()) {
            val nodeId = nodeIdOf(t) ?: continue
            states[nodeId] = forwardState(t)
        }
        for (t in comp?.tasks.orEmpty()) {
            val nodeId = nodeIdOf(t) ?: continue
            states[nodeId] = when (t.status) {
                Task.Status.COMPLETED, Task.Status.COMPLETED_WITH_ERRORS -> "COMPENSATED"
                Task.Status.FAILED, Task.Status.FAILED_WITH_TERMINAL_ERROR -> "COMP_FAILED"
                else -> states[nodeId] ?: "PENDING"
            }
        }
        return states
    }
}
