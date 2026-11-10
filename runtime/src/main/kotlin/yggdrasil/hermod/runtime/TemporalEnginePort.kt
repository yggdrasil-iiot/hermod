package yggdrasil.hermod.runtime

import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions

class TemporalEnginePort(
    private val client: WorkflowClient,
    private val taskQueue: String,
    private val namespace: String = "default",
) : EnginePort {
    override fun start(workflowId: String, input: WorkflowInput): String {
        val stub = client.newWorkflowStub(SagaWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(taskQueue).setWorkflowId(workflowId).build())
        WorkflowClient.start(stub::run, input)
        return workflowId
    }
    override fun signalApproval(workflowId: String) =
        client.newWorkflowStub(SagaWorkflow::class.java, workflowId).approve()
    override fun signalReject(workflowId: String, reason: String) =
        client.newWorkflowStub(SagaWorkflow::class.java, workflowId).reject(reason)
    override fun signalRetry(workflowId: String, nodeId: String) =
        client.newWorkflowStub(SagaWorkflow::class.java, workflowId).retryNode(nodeId)
    override fun signalAbort(workflowId: String) =
        client.newWorkflowStub(SagaWorkflow::class.java, workflowId).abort()
    // SDK 1.25.1: WorkflowStub.describe() does NOT exist (added in 1.26.0). Use the service-stub
    // DescribeWorkflowExecution gRPC, which IS in 1.25.1, to keep the proven SDK version pinned.
    override fun queryStatus(workflowId: String): String {
        val req = DescribeWorkflowExecutionRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build())
            .build()
        val resp = client.workflowServiceStubs.blockingStub().describeWorkflowExecution(req)
        return resp.workflowExecutionInfo.status.name
    }
    override fun queryNodeStates(workflowId: String): Map<String, String> =
        client.newWorkflowStub(SagaWorkflow::class.java, workflowId).queryNodeStates()
    override fun queryCompensationTimeline(workflowId: String): List<CompensationEvent> =
        client.newWorkflowStub(SagaWorkflow::class.java, workflowId).queryCompensationTimeline()
    override fun awaitResult(workflowId: String): WorkflowOutput =
        client.newUntypedWorkflowStub(workflowId).getResult(WorkflowOutput::class.java)
}
