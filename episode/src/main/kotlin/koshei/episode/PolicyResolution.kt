package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/** What the runtime's `readPolicy` activity found (design §8.2). */
sealed interface PolicyRead {
    data class Found(val node: JsonNode) : PolicyRead
    /** The file exists but could not become a tree (syntax or I/O error). Resolved like an invalid table. */
    data class Unreadable(val reason: String) : PolicyRead
    data object Missing : PolicyRead
}

/** Why the agent layer is off for this decision (design §5.3). MISSING and EXPIRED behave alike but are never folded. */
enum class PolicyOffReason { POLICY_MISSING, POLICY_EXPIRED, AGENT_LAYER_OFF }

sealed interface PolicyResolution {
    /** Why the freshly read table was rejected, if it was (notify `POLICY_REJECTED`); empty otherwise. */
    val rejectedErrors: List<String>

    data class Active(val policy: PolicyTable, override val rejectedErrors: List<String>) : PolicyResolution

    /** [policy] is the table in force when there is one — ESCALATED still needs its retention. */
    data class Off(val reason: PolicyOffReason, val policy: PolicyTable?, override val rejectedErrors: List<String>) : PolicyResolution
}

/**
 * Decides the policy for one decision (design §8.2, §5.3). An explicit absence is POLICY_MISSING; an invalid read keeps
 * [lastValid] (and reports why) so one bad edit cannot silently escalate every episode; the table in force is then
 * checked for expiry (`expiresAt <= now`) and for the agent-layer switch.
 */
fun resolvePolicy(read: PolicyRead, lastValid: PolicyTable?, now: Instant): PolicyResolution {
    val (inForce, rejected) = when (read) {
        PolicyRead.Missing -> return PolicyResolution.Off(PolicyOffReason.POLICY_MISSING, null, emptyList())
        is PolicyRead.Unreadable -> lastValid to listOf("policy: unreadable (${read.reason})")
        is PolicyRead.Found -> when (val parsed = parsePolicy(read.node)) {
            is PolicyParse.Valid -> parsed.policy to emptyList()
            is PolicyParse.Invalid -> {
                // The off switch must work even in a table that fails validation for another reason (R5).
                val off = read.node.get("agentLayerEnabled")
                if (off != null && off.isBoolean && !off.booleanValue())
                    return PolicyResolution.Off(PolicyOffReason.AGENT_LAYER_OFF, lastValid, parsed.errors)
                lastValid to parsed.errors
            }
        }
    }
    if (inForce == null) return PolicyResolution.Off(PolicyOffReason.POLICY_MISSING, null, rejected)
    val expiresAt = inForce.expiresAt
    if (expiresAt != null && !now.isBefore(expiresAt)) return PolicyResolution.Off(PolicyOffReason.POLICY_EXPIRED, inForce, rejected)
    if (!inForce.agentLayerEnabled) return PolicyResolution.Off(PolicyOffReason.AGENT_LAYER_OFF, inForce, rejected)
    return PolicyResolution.Active(inForce, rejected)
}
