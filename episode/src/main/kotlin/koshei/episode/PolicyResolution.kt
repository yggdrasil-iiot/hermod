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

    /** Set only when THIS read produced a valid table: the caller remembers it as the new last valid one. */
    val adopt: PolicyTable?

    /**
     * [autoApproveSuspended] is true whenever the fresh read was rejected or unreadable: we cannot know which
     * auto-approval the operator meant, so a person approves.
     */
    data class Active(
        val policy: PolicyTable,
        val autoApproveSuspended: Boolean,
        override val rejectedErrors: List<String>,
        override val adopt: PolicyTable?,
    ) : PolicyResolution

    /** [policy] is the table whose retention (`escalatedRetentionMs`) applies — the one in force, else the last valid one. */
    data class Off(
        val reason: PolicyOffReason,
        val policy: PolicyTable?,
        override val rejectedErrors: List<String>,
        override val adopt: PolicyTable?,
    ) : PolicyResolution
}

/**
 * Decides the policy for one decision (design §8.2, §5.3). Every doubt is resolved toward the RESTRICTIVE side:
 * - an explicit absence is POLICY_MISSING (the last valid table is kept only for ESCALATED retention, it is not in force);
 * - a rejected or unreadable read keeps [lastValid] in force — so one bad edit cannot silently escalate every episode —
 *   but suspends auto-approval (a person approves) and reports why;
 * - restrictive intent literally present in a rejected tree is still honoured: `agentLayerEnabled` present and anything
 *   but boolean `true` switches the layer off, a past `expiresAt` expires the policy (R5);
 * - an `expiresAt` in a rejected tree that is not a readable instant is ignored: the read falls back like any other
 *   rejection (last valid table, auto-approval suspended);
 * - the table in force is finally checked for the agent-layer switch, then for expiry (`expiresAt <= now`) — the same
 *   order as for a rejected tree.
 */
fun resolvePolicy(read: PolicyRead, lastValid: PolicyTable?, now: Instant): PolicyResolution {
    var adopt: PolicyTable? = null
    val inForce: PolicyTable?
    val rejected: List<String>
    when (read) {
        PolicyRead.Missing -> return PolicyResolution.Off(PolicyOffReason.POLICY_MISSING, lastValid, emptyList(), null)
        is PolicyRead.Unreadable -> {
            inForce = lastValid
            rejected = listOf("policy: unreadable (${read.reason})")
        }
        is PolicyRead.Found -> when (val parsed = parsePolicy(read.node)) {
            is PolicyParse.Valid -> {
                inForce = parsed.policy
                adopt = parsed.policy
                rejected = emptyList()
            }
            is PolicyParse.Invalid -> {
                rejected = parsed.errors
                val enabled = read.node.get("agentLayerEnabled")
                if (enabled != null && !(enabled.isBoolean && enabled.booleanValue()))
                    return PolicyResolution.Off(PolicyOffReason.AGENT_LAYER_OFF, lastValid, rejected, null)
                val expires = read.node.get("expiresAt")
                val expiresAt = if (expires != null && expires.isTextual) {
                    try { Instant.parse(expires.textValue()) } catch (e: java.time.format.DateTimeParseException) { null }
                } else null
                if (expiresAt != null && !now.isBefore(expiresAt))
                    return PolicyResolution.Off(PolicyOffReason.POLICY_EXPIRED, lastValid, rejected, null)
                inForce = lastValid
            }
        }
    }
    if (inForce == null) return PolicyResolution.Off(PolicyOffReason.POLICY_MISSING, null, rejected, adopt)
    // The off switch before expiry, in the same order as for a rejected tree above: an explicit switch is the stronger intent.
    if (!inForce.agentLayerEnabled) return PolicyResolution.Off(PolicyOffReason.AGENT_LAYER_OFF, inForce, rejected, adopt)
    val expiresAt = inForce.expiresAt
    if (expiresAt != null && !now.isBefore(expiresAt)) return PolicyResolution.Off(PolicyOffReason.POLICY_EXPIRED, inForce, rejected, adopt)
    return PolicyResolution.Active(inForce, autoApproveSuspended = rejected.isNotEmpty(), rejectedErrors = rejected, adopt = adopt)
}
