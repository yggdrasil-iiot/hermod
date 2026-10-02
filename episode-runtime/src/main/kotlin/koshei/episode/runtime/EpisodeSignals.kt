package koshei.episode.runtime

import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import io.temporal.workflow.Functions
import java.util.Optional

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

    /** As the control plane sends an Update (authoring-api `EpisodeGateway`): describe, a stub pinned to that run, check, send. */
    override fun evidence(instanceId: String, signal: EvidenceSignal): Boolean {
        val workflowId = instanceId.substringBeforeLast('/')
        val info = try {
            client.workflowServiceStubs.blockingStub().describeWorkflowExecution(
                DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(client.options.namespace)
                    .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
                    .build(),
            ).workflowExecutionInfo
        } catch (e: StatusRuntimeException) {
            if (e.status.code == Status.Code.NOT_FOUND) return false else throw e
        }
        if (info.status != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING) return false
        val stub = client.newWorkflowStub(EpisodeWorkflow::class.java, workflowId, Optional.of(info.execution.runId))
        return try {
            if (stub.view().instanceId != instanceId) return false
            stub.evidence(signal)
            true
        } catch (e: WorkflowNotFoundException) {
            false   // the run ended between the describe and the signal
        }
    }
}
