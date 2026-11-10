package yggdrasil.hermod.conductor

import com.netflix.conductor.client.http.ConductorClient
import com.netflix.conductor.client.http.TaskClient
import com.netflix.conductor.client.http.WorkflowClient
import com.netflix.conductor.common.metadata.tasks.Task
import com.netflix.conductor.common.metadata.tasks.TaskResult
import com.netflix.conductor.common.metadata.workflow.RerunWorkflowRequest
import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest
import com.netflix.conductor.common.run.Workflow

/**
 * Test-only injection keys carried in a run's start input. They are stripped on a whole-run retry so the
 * re-run is fault-free (and so recovers). The API `POST /runs` path injects only `_failAtBlockId`/`_slowMs`
 * (ConductorEnginePort.start); `_slowAtBlockId` is CLI-only — stripping it is a harmless no-op for API runs.
 */
internal val TEST_HOOK_KEYS = setOf("_failAtBlockId", "_slowMs", "_slowAtBlockId")
internal fun stripTestHooks(input: Map<String, Any?>): Map<String, Any?> = input - TEST_HOOK_KEYS

/**
 * Start / poll / human-gate a Conductor workflow over the REST clients.
 *
 * - [start] returns the workflowId.
 * - [awaitTerminal] polls `getWorkflow` until the workflow status is terminal (COMPLETED/FAILED/TERMINATED/...).
 * - [approve]/[reject] complete/fail the IN_PROGRESS WAIT task (R-2: via the
 *   `TaskClient.updateTask(workflowId, taskRefName, Status, output)` overload — no manual TaskResult plumbing).
 */
class ConductorStarter(client: ConductorClient) {
    private val workflows = WorkflowClient(client)
    private val tasks = TaskClient(client)

    fun start(name: String, input: Map<String, Any?>, version: Int = 1): String {
        val req = StartWorkflowRequest()
            .withName(name)
            .withVersion(version)
            .withInput(input)
        return workflows.startWorkflow(req)
    }

    fun getWorkflow(workflowId: String): Workflow = workflows.getWorkflow(workflowId, true)

    /** Poll until the workflow reaches a terminal status, or the timeout elapses (returns the last snapshot). */
    fun awaitTerminal(workflowId: String, timeoutMs: Long = 60_000, pollMs: Long = 1_000): Workflow {
        val deadline = System.currentTimeMillis() + timeoutMs
        var wf = workflows.getWorkflow(workflowId, true)
        while (!wf.status.isTerminal && System.currentTimeMillis() < deadline) {
            Thread.sleep(pollMs)
            wf = workflows.getWorkflow(workflowId, true)
        }
        return wf
    }

    /** The single IN_PROGRESS WAIT/HUMAN task currently blocking the run, or null if none. */
    private fun inProgressWaitTask(workflowId: String): Task? =
        workflows.getWorkflow(workflowId, true).tasks
            .firstOrNull { it.status == Task.Status.IN_PROGRESS && (it.taskType == "WAIT" || it.taskType == "HUMAN") }

    /**
     * Resolve the blocking WAIT task to [status] with [output].
     *
     * R-2: conductor-standalone 3.15 does NOT serve the client's `updateTaskByRefName`
     * (`POST /tasks/{wfId}/{ref}/{status}`) endpoint — it 404s. The portable path is the classic
     * `POST /tasks` with a full [TaskResult] carrying the task's own `taskId` + `workflowInstanceId`.
     */
    private fun resolveWait(workflowId: String, status: TaskResult.Status, output: Map<String, Any?>): Boolean {
        val task = inProgressWaitTask(workflowId) ?: return false
        val result = TaskResult(task).apply {
            this.status = status
            output.forEach { (k, v) -> addOutputData(k, v) }
        }
        tasks.updateTask(result)
        return true
    }

    /** Complete the blocking WAIT task (gate approve). Returns false if no WAIT task is currently IN_PROGRESS. */
    fun approve(workflowId: String): Boolean =
        resolveWait(workflowId, TaskResult.Status.COMPLETED, mapOf("approved" to true))

    /**
     * Reject the blocking WAIT task (gate reject) -> the main workflow FAILS -> Conductor runs the failureWorkflow.
     *
     * Must be FAILED_WITH_TERMINAL_ERROR, not plain FAILED: a WAIT marked FAILED is *retried* (the run stays
     * RUNNING and the failureWorkflow never fires); the terminal variant rejects permanently and fails the
     * workflow fast — which is what dispatches the compensation. (conductor-standalone 3.15.0.)
     */
    fun reject(workflowId: String, reason: String): Boolean =
        resolveWait(
            workflowId,
            TaskResult.Status.FAILED_WITH_TERMINAL_ERROR,
            mapOf("approved" to false, "reason" to reason),
        )

    /**
     * Find the `<compensationName>` execution Conductor dispatched as the failureWorkflow for the FAILED main
     * workflow [failedWorkflowId]. Conductor injects the failed workflow's execution id under the top-level
     * input key `workflowId` (R-3), so we locate the compensation run by matching `input["workflowId"]`.
     *
     * conductor-standalone indexes asynchronously, so the dispatch + index can lag a few seconds — poll the
     * search until the matching run appears. Returns its workflowId, or null if none surfaces before timeout.
     */
    fun findCompensationWorkflowId(
        failedWorkflowId: String,
        compensationName: String,
        timeoutMs: Long = 30_000,
        pollMs: Long = 1_000,
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        // searchV2(start, size, sort, freeText, query): structured `query` by workflowType, newest first. The
        // index is eventually consistent, so we retry; match the run whose injected input.workflowId is ours.
        // Search results may carry a trimmed input, so confirm the match against the full getWorkflow() input.
        val query = "workflowType=\"$compensationName\""
        do {
            val hit = runCatching {
                workflows.searchV2(0, 50, "startTime:DESC", "*", query).results
                    .map { it.workflowId }
                    .firstOrNull { id ->
                        workflows.getWorkflow(id, false).input?.get("workflowId") == failedWorkflowId
                    }
            }.getOrNull()
            if (hit != null) return hit
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(pollMs)
        } while (true)
        return null
    }

    /**
     * Locate the compensation run for [failedWorkflowId] and poll it to a terminal status.
     * Returns the terminal [Workflow], or null if the compensation run never surfaced.
     */
    fun awaitCompensationTerminal(
        failedWorkflowId: String,
        compensationName: String,
        timeoutMs: Long = 60_000,
        pollMs: Long = 1_000,
    ): Workflow? {
        val compId = findCompensationWorkflowId(failedWorkflowId, compensationName, timeoutMs, pollMs)
            ?: return null
        return awaitTerminal(compId, timeoutMs, pollMs)
    }

    /**
     * Whole-run retry (v0.6d): re-run [workflowId] from the start with the test-only fault hooks stripped,
     * reusing the SAME workflowId so the console's existing run row re-lights. Returns the (unchanged) id.
     *
     * Conductor's `rerunWorkflow` with a null `reRunFromTaskId` re-runs from the beginning; supplying a fresh
     * `workflowInput` replaces the original input, so dropping `_failAtBlockId` removes the injected fault and
     * the re-run recovers. (No park primitive exists in Conductor; see the design doc.)
     */
    fun rerunFromStart(workflowId: String): String {
        val original: Map<String, Any?> = workflows.getWorkflow(workflowId, false).input ?: emptyMap()
        val req = RerunWorkflowRequest().apply {
            reRunFromWorkflowId = workflowId
            workflowInput = stripTestHooks(original)
            // reRunFromTaskId left null => re-run from the start of the workflow.
        }
        return workflows.rerunWorkflow(workflowId, req)
    }

    /**
     * Abort (v0.6d): terminate [workflowId] AND compensate the completed upstream nodes reverse-topo — the same
     * unwind a forward fault triggers.
     *
     * R-1 (verified empirically by the gate, 2026-06-25): on conductor-standalone 3.15.0,
     * `terminateWorkflowWithFailure(id, reason, triggerFailureWorkflow=true)` terminates the run but does NOT
     * dispatch the registered failureWorkflow (the comp_ledger stays un-compensated). So we DISPATCH THE
     * COMPENSATION OURSELVES: plain `terminateWorkflow` to stop the run, then start the `<name>-compensation`
     * workflow with the `{workflowId: <id>}` input Conductor injects on a natural failure — its compensate
     * tasks resolve `${workflow.input.workflowId}` to read the forward run's comp_ledger entries and unwind
     * them (this is the exact dispatch the failureWorkflow would have done). `findCompensationWorkflowId` also
     * keys on `input.workflowId`, so the dispatched run is observable like any natural compensation run.
     */
    fun abortWithCompensation(workflowId: String, reason: String) {
        val compName = "${workflows.getWorkflow(workflowId, false).workflowName}-compensation"
        workflows.terminateWorkflow(workflowId, reason)                 // stop the run (no auto failureWorkflow)
        start(compName, mapOf("workflowId" to workflowId))             // dispatch the compensation ourselves
    }
}
