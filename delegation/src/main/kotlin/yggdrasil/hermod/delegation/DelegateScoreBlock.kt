package yggdrasil.hermod.delegation

import yggdrasil.hermod.sdk.*

/**
 * `delegate.score` — governed external ML-scoring gate.
 *
 * Calls an external scorer through a [DelegatePort]; fail-closes (throws [PermanentBlockFailure]) on
 * policy denial, service error, or a score below the policy threshold — so the saga compensates the
 * upstream staged setpoints. Read-only, so [compensate] is a NOOP.
 *
 * **runId for audit:** comes from the block execution context — [BlockInput.runId] in forward
 * (the workflow runId; `"-"` when there is no run context).
 */
class DelegateScoreBlock(
    delegate: DelegatePort? = null,
    private val policy: DelegationPolicy = DelegationPolicy.default(),
) : Block {
    override val id = "delegate.score"

    /** Lazy: defers HttpDelegatePort.default() so BuiltinBlocks can construct without a live scorer. */
    private val effective: DelegatePort by lazy { delegate ?: HttpDelegatePort.default() }

    /** Fail-closed policy gate; re-fires on an invalid policy (Kotlin lazy doesn't cache a thrown exception). */
    @Suppress("unused")
    private val policyChecked: Unit by lazy {
        val r = DelegationPolicyValidator.validate(policy)
        if (!r.ok) throw PermanentBlockFailure("invalid delegation policy: ${r.errors}")
    }
    private fun ensurePolicyValid() { @Suppress("UNUSED_EXPRESSION") policyChecked }

    override fun forward(input: BlockInput): BlockOutput {
        if (input.failAtBlockId == id) throw PermanentBlockFailure("injected failure at $id")
        ensurePolicyValid()

        val endpointId = input.params["endpoint"]
            ?: throw PermanentBlockFailure("delegate.score requires an 'endpoint' param")

        val decision = policy.evaluate(endpointId)
        if (!decision.allowed) {
            tryAudit(input.runId, endpointId, null, null, DelegationDecisionOutcome.DENIED, "endpoint not allowed by policy")
            throw PermanentBlockFailure("delegation denied by policy for endpoint '$endpointId'")
        }

        // Scores the FIRST feature record only; all input.rows pass through unchanged to the output.
        val payload = input.rows.firstOrNull() ?: emptyMap()
        val result = effective.call(DelegationRequest(endpointId, payload))
        if (!result.ok) {
            tryAudit(input.runId, endpointId, null, null, DelegationDecisionOutcome.FAILED, result.detail)
            throw PermanentBlockFailure("delegation call failed for '$endpointId': ${result.detail}")
        }

        val score = result.score ?: run {
            tryAudit(input.runId, endpointId, null, null, DelegationDecisionOutcome.FAILED, "null score")
            throw PermanentBlockFailure("delegation returned null score for '$endpointId'")
        }
        val threshold = policy.thresholdFor(endpointId)
        if (score < threshold) {
            tryAudit(input.runId, endpointId, score, threshold, DelegationDecisionOutcome.REJECTED, "score $score < threshold $threshold")
            throw PermanentBlockFailure("delegation gate REJECTED: score $score < threshold $threshold for '$endpointId'")
        }

        tryAudit(input.runId, endpointId, score, threshold, DelegationDecisionOutcome.PASSED, "score $score >= threshold $threshold")
        return BlockOutput(rows = input.rows, boundState = mapOf("score" to score.toString()))
    }

    /** Read-only call → nothing to undo; saga-level compensation is proven by the upstream opcua.write RESTORE. */
    override fun compensate(boundState: Map<String, String>, ctx: CompensationContext): CompensationAction =
        CompensationAction("NOOP", "scoring is read-only")

    private fun tryAudit(
        runId: String, endpointId: String, score: Double?, threshold: Double?, decision: DelegationDecisionOutcome, detail: String?,
    ) {
        try {
            DelegationAudit.record(runId, id, endpointId, score, threshold, decision, detail)
        } catch (_: java.sql.SQLException) {
            // Non-fatal: audit DB unavailable (unit-test context). Outcome already enforced by the throw.
        }
    }
}
