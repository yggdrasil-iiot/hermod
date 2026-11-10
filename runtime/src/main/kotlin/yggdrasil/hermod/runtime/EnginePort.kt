package yggdrasil.hermod.runtime

/** Client boundary to the durable engine (§7.2). v0.1: Temporal-backed via TemporalEnginePort. */
interface EnginePort {
    fun start(workflowId: String, input: WorkflowInput): String
    fun signalApproval(workflowId: String)
    fun signalReject(workflowId: String, reason: String)
    fun signalRetry(workflowId: String, nodeId: String)   // v0.4b: re-attempt a PARKED node (interactive run)
    fun signalAbort(workflowId: String)                   // v0.4b: graceful reverse-topo compensation of the run
    fun queryStatus(workflowId: String): String   // v0.1: execution status name via DescribeWorkflowExecution gRPC
    fun queryNodeStates(workflowId: String): Map<String, String>  // v0.3f: per-node states for the operator canvas
    fun queryCompensationTimeline(workflowId: String): List<CompensationEvent>  // v0.4c: ordered compensation results
    fun awaitResult(workflowId: String): WorkflowOutput
}
