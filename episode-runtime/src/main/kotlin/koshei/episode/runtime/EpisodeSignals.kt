package koshei.episode.runtime

import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.api.errordetails.v1.NamespaceNotFoundFailure
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import io.temporal.serviceclient.StatusUtils
import io.temporal.workflow.Functions
import java.util.Optional
import java.util.UUID

/** What the watcher (and the dev CLI) send into the episode loop (design §7.2). Tests replace it. */
interface EpisodeSignals {
    /**
     * signalWithStart on [workflowId] (`ep:<key>`, design §7.1): starts the episode with [start] if no run is open, and
     * sends [symptom] to it. Returns the run id. Throws when Temporal does not take it.
     */
    fun open(workflowId: String, start: EpisodeStart, symptom: SymptomSignal): String

    /**
     * The `evidence` signal to episode instance [instanceId] (`<workflowId>/<run>`, design §7.1) — only while the
     * workflow's current run is running and holds that instance. False otherwise (ended, moved on, never started): the
     * next episode on the same key never receives a report meant for the one before.
     */
    fun evidence(instanceId: String, signal: EvidenceSignal): Boolean
}

class TemporalEpisodeSignals(private val client: WorkflowClient) : EpisodeSignals {
    override fun open(workflowId: String, start: EpisodeStart, symptom: SymptomSignal): String {
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(EPISODE_TASK_QUEUE)
            .setWorkflowId(workflowId)
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .build()
        val stub = client.newWorkflowStub(EpisodeWorkflow::class.java, options)
        val batch = client.newSignalWithStartRequest()
        batch.add(Functions.Func1<EpisodeStart, EpisodeResult> { stub.run(it) }, start)
        batch.add(Functions.Proc1<SymptomSignal> { stub.symptom(it) }, symptom)
        return client.signalWithStart(batch).runId
    }

    /**
     * As the control plane sends an Update (authoring-api `EpisodeGateway`): describe, a stub pinned to that run, check,
     * send on that run. A closed episode whose run is still draining (status RUNNING, design §7.1) still holds its
     * instance: it accepts the evidence and this returns true, but the workflow only records it (IGNORED).
     */
    override fun evidence(instanceId: String, signal: EvidenceSignal): Boolean {
        val run = current(instanceId.substringBeforeLast('/')) ?: return false
        return send(run, instanceId, signal)
    }

    /**
     * The workflow's current run while it is running, its view queried through a stub pinned to it; null when there is
     * no such workflow or its current run is not running. A missing namespace is thrown (see [workflowNotFound]).
     */
    internal fun current(workflowId: String): PinnedRun? {
        val info = try {
            client.workflowServiceStubs.blockingStub().describeWorkflowExecution(
                DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(client.options.namespace)
                    .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
                    .build(),
            ).workflowExecutionInfo
        } catch (e: StatusRuntimeException) {
            if (e.workflowNotFound()) return null else throw e
        }
        if (info.status != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING) return null
        val runId = info.execution.runId
        val stub = client.newWorkflowStub(EpisodeWorkflow::class.java, workflowId, Optional.of(runId))
        // The run can close between the describe and the query: then it is not running, as the describe would have said.
        val view = try {
            stub.view()
        } catch (e: WorkflowNotFoundException) {
            return null
        }
        return PinnedRun(workflowId, runId, view)
    }

    /**
     * Sends [signal] on [run] — the run as checked — if it holds [instanceId]; false when that run has ended or continued
     * as new since the check, so the next run never gets it. Not through the typed stub: the SDK's `WorkflowStub.signal`
     * drops the run id (1.25.1, `currentExecutionWithoutRunId`) and would reach whatever run is current. The request
     * names the run and is encoded with the client's converter, as the stub would encode it.
     */
    internal fun send(run: PinnedRun, instanceId: String, signal: EvidenceSignal): Boolean {
        if (run.view.instanceId != instanceId) return false
        val request = SignalWorkflowExecutionRequest.newBuilder()
            .setNamespace(client.options.namespace)
            .setWorkflowExecution(WorkflowExecution.newBuilder().setWorkflowId(run.workflowId).setRunId(run.runId))
            .setSignalName(EVIDENCE_SIGNAL)
            .setIdentity(client.options.identity)
            .setRequestId(UUID.randomUUID().toString())
        client.options.dataConverter.toPayloads(signal).ifPresent { request.setInput(it) }
        return try {
            client.workflowServiceStubs.blockingStub().signalWorkflowExecution(request.build())
            true
        } catch (e: StatusRuntimeException) {
            if (e.workflowNotFound()) false else throw e   // NOT_FOUND: the run ended between the check and the signal
        }
    }

    private companion object {
        /** [EpisodeWorkflow.evidence]'s signal name (`@SignalMethod` without a name: the method's). */
        const val EVIDENCE_SIGNAL = "evidence"

        /**
         * NOT_FOUND about the workflow or run — not about the namespace: Temporal reports a missing namespace as NOT_FOUND
         * with a `NamespaceNotFoundFailure`, and that is thrown, so the caller fails and retries rather than taking the
         * episode for ended. (The in-memory test server sends no failure details, so the absence of a `NotFoundFailure`
         * cannot mean "not the workflow".)
         */
        fun StatusRuntimeException.workflowNotFound(): Boolean =
            status.code == Status.Code.NOT_FOUND && !StatusUtils.hasFailure(this, NamespaceNotFoundFailure::class.java)
    }
}

/** A workflow's current run as [TemporalEpisodeSignals.current] checked it: [view] came through a stub pinned to [runId]. */
internal class PinnedRun(val workflowId: String, val runId: String, val view: EpisodeView)
