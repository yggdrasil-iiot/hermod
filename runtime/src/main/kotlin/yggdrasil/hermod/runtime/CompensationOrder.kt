package yggdrasil.hermod.runtime

import yggdrasil.hermod.compiler.IrNode

/** A completed compensable node's compensation handle (engine-neutral, pure data). */
data class BoundComp(val nodeId: String, val blockId: String, val version: String, val boundState: Map<String, String>)

/** One step of the reverse-topo unwind, as an observable result (v0.4c). Pure data; query-only. */
data class CompensationEvent(
    val index: Int,
    val nodeId: String,
    val blockId: String,
    val version: String,
    val outcome: String,   // "COMPENSATED" | "FAILED"
    val atMillis: Long,    // Workflow.currentTimeMillis() — deterministic, replay-safe
)

/**
 * Reverse-topological compensation order. The compiler emits `nodes` in a valid TOPOLOGICAL order, so
 * walking it in REVERSE yields a valid reverse-topological order — correct under partial parallel failure
 * without any new graph algorithm. Keeps only nodes that actually completed (and were compensable, i.e.
 * present in `completed`). Pure: unit-tested without Temporal.
 */
object CompensationOrder {
    fun reverseTopological(nodes: List<IrNode>, completed: Map<String, BoundComp>): List<BoundComp> =
        nodes.asReversed().mapNotNull { completed[it.nodeId] }
}
