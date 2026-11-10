package yggdrasil.hermod.runtime

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ApplicationFailure
import io.temporal.workflow.Async
import io.temporal.workflow.Promise
import io.temporal.workflow.Workflow
import yggdrasil.hermod.sdk.BlockInput
import yggdrasil.hermod.sdk.BlockOutput
import yggdrasil.hermod.compiler.IrInputWire
import yggdrasil.hermod.compiler.IrSource
import yggdrasil.hermod.core.CompensationKind
import yggdrasil.hermod.core.IdempotencyKey
import yggdrasil.hermod.core.IdempotencyStrategy
import yggdrasil.hermod.core.Reversibility
import java.time.Duration

/**
 * Contract-driven saga. Builds a PROMISE GRAPH over the precomputed BoundWorkflow.plan() WorkflowIR
 * (immutable, replay-safe, already topologically ordered by the compiler): each node is an
 * Async.function Promise<BlockOutput> that awaits its upstream nodes' promises, so INDEPENDENT BRANCHES
 * RUN CONCURRENTLY (v0.3b). Per node it derives — all from the YAML-loaded BlockContract, never from
 * block code — the human gate, retry policy, idempotency dedup, and compensation eligibility.
 * On failure it SETTLES ALL branches first (Promise.handle + Promise.allOf — never fail-fast, the
 * sdk-java #902 non-determinism mitigation), then compensates the successfully-completed compensable
 * nodes in REVERSE-TOPOLOGICAL order (CompensationOrder, reverse of the compiler's topo node list).
 * Determinism: only the plan.nodes List drives control flow; the promises/completed maps are
 * keyed-access only. (Conductor concurrency / FORK_JOIN is the v0.3c boundary.)
 */
class SagaWorkflowImpl : SagaWorkflow {
    private var approved = false
    private var rejected = false
    private var rejectReason = ""
    // LinkedHashMap (not the spec's HashMap) is deliberate: preserves topo insertion order for a stable canvas node ordering.
    private val nodeStates = LinkedHashMap<String, String>()   // nodeId -> state; mutated only on the workflow thread
    private val retryCount = HashMap<String, Int>()            // v0.4b: nodeId -> # of operator retry signals (keyed-access only)
    private var aborted = false                                // v0.4b: operator aborted the run
    private val compTimeline = mutableListOf<CompensationEvent>()  // v0.4c: ordered compensation results (workflow thread only)
    override fun approve() { approved = true }
    override fun reject(reason: String) { rejected = true; rejectReason = reason }
    override fun retryNode(nodeId: String) { retryCount[nodeId] = (retryCount[nodeId] ?: 0) + 1 }
    override fun abort() { aborted = true }
    override fun queryNodeStates(): Map<String, String> = LinkedHashMap(nodeStates)  // snapshot, never blocks
    override fun queryCompensationTimeline(): List<CompensationEvent> = ArrayList(compTimeline)  // snapshot, never blocks

    override fun run(input: WorkflowInput): WorkflowOutput {
        val plan = BoundWorkflow.plan(input.workflowName)
        val promises = HashMap<String, Promise<BlockOutput>>()      // nodeId -> output promise
        val completed = HashMap<String, BoundComp>()                // successfully-completed compensable nodes
        var failure: Exception? = null                              // sentinel: any settled failure flips us to compensate; the specific exception is never surfaced, so first-wins is fine
        var inputCount = 0
        var dedupCount = 0

        plan.nodes.forEach { nodeStates[it.nodeId] = "PENDING" }

        // build the promise graph in topological order (every upstream promise exists before its consumer)
        for (node in plan.nodes) {
            val c = node.contract
            val upstreamPromises = node.inputs
                .mapNotNull { (it.source as? IrSource.NodeOutput)?.nodeId }
                .map { promises.getValue(it) }
            promises[node.nodeId] = Async.function<BlockOutput> {
                // 1. await all upstream outputs. If an upstream failed, this throws here, BEFORE the RUNNING flip -> node stays PENDING.
                upstreamPromises.forEach { it.get() }

                // v0.4b park loop: on an interactive run a failed node PARKS for an operator decision (retry/abort)
                // instead of rethrowing into settle-all+compensate. interactive=false reproduces the prior body
                // (first iteration: try -> success return, or catch -> FAILED + throw) byte-for-byte (replay-safe).
                // 2. human gate BEFORE the step — evaluated ONCE, OUTSIDE the park loop: an approve covers all
                //    forward retries below, and a reject/abort here is a TERMINAL operator decision that must
                //    propagate (NOT be caught by the park catch and re-parked).
                if (c.human.requireApprovalBefore || c.compensation.reversibility == Reversibility.IRREVERSIBLE) {
                    if (!input.autoApprove) {
                        nodeStates[node.nodeId] = "AWAITING_APPROVAL"   // parked at the human gate (was "RUNNING")
                        Workflow.await { approved || rejected || aborted }
                        if (aborted)  { nodeStates[node.nodeId] = "FAILED"; throw ApplicationFailure.newNonRetryableFailure("aborted at human gate", "Aborted") }
                        if (rejected) { nodeStates[node.nodeId] = "FAILED"; throw ApplicationFailure.newNonRetryableFailure("rejected at human gate: $rejectReason", "Rejected") }
                    }
                }

                // v0.4b park loop: wraps only the BLOCK execution (steps 3-5), never the gate above.
                while (true) {
                    nodeStates[node.nodeId] = "RUNNING"   // now active
                    try {
                        val result: BlockOutput = run {
                            // 3. assemble inputs from now-resolved upstream outputs
                            val wireRows: (IrInputWire) -> List<Map<String, String?>> = { w ->
                                when (val s = w.source) {
                                    is IrSource.WorkflowInput -> input.rows
                                    is IrSource.NodeOutput -> promises.getValue(s.nodeId).get().rows
                                }
                            }
                            val isMulti = node.inputs.size > 1
                            val primaryRows = node.inputs.firstOrNull()?.let(wireRows) ?: emptyList()
                            val named: Map<String, List<Map<String, String?>>> =
                                if (isMulti) node.inputs.associate { it.inputName to wireRows(it) } else emptyMap()

                            // 4. useDbRead=false: skip the db.read forward; publish its output from input.rows
                            if (c.id == "db.read" && !input.useDbRead) {
                                BlockOutput(rows = input.rows)
                            } else {
                                // 5. idempotency dedup on the primary-input rows
                                val effectiveRows =
                                    if (c.idempotency.strategy == IdempotencyStrategy.KEY_DEDUP && c.idempotency.keyExpression != null) {
                                        val seen = HashSet<String>()
                                        val deduped = primaryRows.filter { row ->
                                            val k = IdempotencyKey.derive(c.idempotency.keyExpression!!, row)
                                            k == null || seen.add(k)
                                        }
                                        inputCount = primaryRows.size
                                        dedupCount = deduped.size
                                        deduped
                                    } else primaryRows

                                val slow = if (input.slowAtBlockId == null || input.slowAtBlockId == c.id) input.slowMs else 0

                                val retry = RetryOptions.newBuilder()
                                    .setMaximumAttempts(c.retry.maxAttempts)
                                    .setInitialInterval(Duration.ofMillis(c.retry.initialMs))
                                    .setMaximumInterval(Duration.ofMillis(c.retry.maxMs))
                                    .build()
                                val stub = Workflow.newActivityStub(
                                    BlockActivities::class.java,
                                    ActivityOptions.newBuilder()
                                        .setStartToCloseTimeout(Duration.ofMillis(c.timeoutMs))
                                        .setRetryOptions(retry)
                                        .build()
                                )
                                val out = stub.forward(
                                    c.id, c.version,
                                    BlockInput(rows = effectiveRows, params = node.params, namedInputs = named, failAtBlockId = input.failAtBlockId, slowMs = slow, runId = Workflow.getInfo().workflowId)
                                )
                                if (c.compensation.reversibility != Reversibility.IRREVERSIBLE &&
                                    c.compensation.kind != CompensationKind.NONE) {
                                    completed[node.nodeId] = BoundComp(node.nodeId, c.id, c.version, out.boundState)
                                }
                                out
                            }
                        }
                        nodeStates[node.nodeId] = "DONE"
                        return@function result
                    } catch (e: Exception) {
                        if (!input.interactive) { nodeStates[node.nodeId] = "FAILED"; throw e }  // prior behavior
                        nodeStates[node.nodeId] = "PARKED"
                        val seen = retryCount[node.nodeId] ?: 0
                        Workflow.await { aborted || (retryCount[node.nodeId] ?: 0) > seen }
                        if (aborted) { nodeStates[node.nodeId] = "FAILED"; throw e }  // -> settle-all -> compensate
                        // else: a retry was requested for this node -> loop and re-attempt forward
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                throw IllegalStateException("unreachable")
            }
        }

        // settle ALL branches (do NOT fail-fast). Promise.handle completes on settle (success OR failure)
        // without rethrow, so allOf waits for every branch (sdk-java #902 mitigation).
        val settled = plan.nodes.map { node ->
            promises.getValue(node.nodeId).handle { _, ex ->
                if (ex != null && failure == null) failure = ex
                Unit
            }
        }
        Promise.allOf(settled).get()

        return if (failure == null) {
            WorkflowOutput(completed = true, dedupCount = dedupCount, inputCount = inputCount)
        } else {
            compensate(plan, completed, dedupCount, inputCount)
        }
    }

    private fun compensate(
        plan: yggdrasil.hermod.compiler.WorkflowIR,
        completed: Map<String, BoundComp>,
        dedupCount: Int,
        inputCount: Int,
    ): WorkflowOutput {
        val compStub = Workflow.newActivityStub(
            BlockActivities::class.java,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())  // v0.4c: bounded (was default-unlimited)
                .build()
        )
        val compensated = mutableListOf<String>()
        // v0.4c: best-effort unwind — record each step's result (COMPENSATED/FAILED) and CONTINUE past a failure.
        for ((i, bc) in CompensationOrder.reverseTopological(plan.nodes, completed).withIndex()) {
            try {
                compStub.compensate(bc.blockId, bc.version, bc.boundState, Workflow.getInfo().workflowId)
                // intentionally different keys: node-state keys by nodeId (canvas); the result list keys by blockId
                // to preserve the existing compensatedInReverseOrder contract. Do not "align" these.
                nodeStates[bc.nodeId] = "COMPENSATED"
                compensated += bc.blockId   // success-only → preserves the compensatedInReverseOrder contract
                compTimeline += CompensationEvent(i, bc.nodeId, bc.blockId, bc.version, "COMPENSATED", Workflow.currentTimeMillis())
            } catch (e: Exception) {
                nodeStates[bc.nodeId] = "COMP_FAILED"
                compTimeline += CompensationEvent(i, bc.nodeId, bc.blockId, bc.version, "FAILED", Workflow.currentTimeMillis())
                // best-effort: do NOT rethrow — continue unwinding the rest
            }
        }
        return WorkflowOutput(
            completed = false,
            compensatedInReverseOrder = compensated,
            dedupCount = dedupCount,
            inputCount = inputCount,
        )
    }

}
