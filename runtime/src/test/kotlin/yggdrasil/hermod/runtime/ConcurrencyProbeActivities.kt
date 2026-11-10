package yggdrasil.hermod.runtime

import yggdrasil.hermod.sdk.BlockInput
import yggdrasil.hermod.sdk.BlockOutput
import yggdrasil.hermod.sdk.CompensationAction
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-only BlockActivities decorator: counts concurrent forward() executions. With the saga running
 * independent branches in parallel (and slowMs making them overlap), maxConcurrent rises above 1 —
 * a deterministic proof of concurrency that does not depend on wall-clock timing.
 */
class ConcurrencyProbeActivities(private val delegate: BlockActivities) : BlockActivities {
    val current = AtomicInteger(0)
    val maxConcurrent = AtomicInteger(0)
    val seenParams = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    val seenRunId = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun forward(blockId: String, version: String, input: BlockInput): BlockOutput {
        seenParams[blockId] = input.params
        seenRunId[blockId] = input.runId
        val now = current.incrementAndGet()
        maxConcurrent.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try { return delegate.forward(blockId, version, input) } finally { current.decrementAndGet() }
    }

    val seenCompRunId = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun compensate(blockId: String, version: String, boundState: Map<String, String>, runId: String): CompensationAction {
        seenCompRunId[blockId] = runId
        return delegate.compensate(blockId, version, boundState, runId)
    }
}
