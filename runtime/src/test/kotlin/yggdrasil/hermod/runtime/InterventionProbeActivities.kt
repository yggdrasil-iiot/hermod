package yggdrasil.hermod.runtime

import io.temporal.failure.ApplicationFailure
import yggdrasil.hermod.sdk.BlockInput
import yggdrasil.hermod.sdk.BlockOutput
import yggdrasil.hermod.sdk.CompensationAction
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-only BlockActivities decorator: throws a NON-RETRYABLE failure for [failBlockId] while [failRemaining]
 * is positive (so the node fails immediately with no activity-level retries -> parks fast on an interactive
 * run), then delegates. Lets a test prove park -> retry-recovers (failRemaining=1) and park -> abort
 * (failRemaining large) without the production DB fault hook.
 */
class InterventionProbeActivities(private val delegate: BlockActivities) : BlockActivities {
    @Volatile var failBlockId: String? = null
    val failRemaining = AtomicInteger(0)
    @Volatile var failCompensateBlockId: String? = null   // v0.4c: fail compensate() for this blockId (best-effort proof)

    override fun forward(blockId: String, version: String, input: BlockInput): BlockOutput {
        if (blockId == failBlockId && failRemaining.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
            throw ApplicationFailure.newNonRetryableFailure("injected fault for $blockId", "InjectedFault")
        }
        return delegate.forward(blockId, version, input)
    }
    override fun compensate(blockId: String, version: String, boundState: Map<String, String>, runId: String): CompensationAction {
        if (blockId == failCompensateBlockId) {
            throw ApplicationFailure.newNonRetryableFailure("injected compensate fault for $blockId", "InjectedCompFault")
        }
        return delegate.compensate(blockId, version, boundState, runId)
    }
}
