package yggdrasil.hermod.runtime

import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface SagaWorkflow {
    @WorkflowMethod fun run(input: WorkflowInput): WorkflowOutput
    @SignalMethod fun approve()
    /** F5 seam (§7.2): operator declines at a human gate -> permanent failure -> reverse-unwind compensation. */
    @SignalMethod fun reject(reason: String)
    /** v0.3f: per-node execution state (nodeId -> PENDING|RUNNING|DONE|FAILED|COMPENSATED|PARKED) for the operator canvas. */
    @QueryMethod fun queryNodeStates(): Map<String, String>
    /** v0.4b: operator retries a node that PARKED on failure (interactive runs). nodeId-targeted. */
    @SignalMethod fun retryNode(nodeId: String)
    /** v0.4b: operator aborts the run -> parked/gated awaits throw -> reverse-topo compensation. */
    @SignalMethod fun abort()
    /** v0.4c: ordered per-step compensation results (reverse-topo) for the operator timeline. */
    @QueryMethod fun queryCompensationTimeline(): List<CompensationEvent>
}
