package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * picasso's approval window as the episode needs it (design §8.3, §11), in this process's memory, answering in schema 4
 * (picasso ADR 48, main 3d6530a). Like the real one it drops the idempotency key and offers no lookup of past approvals:
 * a proposal — one (robot, order) — is consumed by its first approval, and every later approval for it, whatever its
 * search line, is refused CONSUMED with the consumption record (ADR 46) — "my earlier attempt may have landed"
 * (UNKNOWN(OUTCOME) in the core). Revalidation is the only way to TRUE without a person (§8.3): TRUE while the proposal
 * stands, FALSE once consumed. The real window would raise a new proposal from a newer FOUND line; the mock never does.
 *
 * Every answer carries [instanceId] — by default a fresh one per mock, as picasso never names an instance
 * deterministically (ADR 48: a restarted instance must not repeat an old (instance, execution) pair); tests that carry
 * reports pass [INSTANCE]. Every approved step carries its unit, named as picasso names it: `remedy-{n}-{skillType}`,
 * n from 1 in step order. Executions are `mock-exec-N`, counted in this mock.
 *
 * What it cannot read it does not act on: a candidate that is not an object, names no kind it knows, or is a remedy
 * without its robot and order revalidates UNKNOWN, and such a remedy is refused NO_PROPOSAL without consuming anything.
 *
 * A test double: the consumption records live in this process only, so a restarted worker approves again. It does not
 * model the approver's id (always "koshei"), the consumption times (always the epoch), the REMEDY_NOT_APPLIED path (an
 * approval for an order already executing), or a missing approverKind (real picasso rejects it; the mock assumes PERSON).
 */
class MockPicasso(val instanceId: String = "mock-${UUID.randomUUID()}") : ApprovalWindow {
    private val consumed = HashMap<Pair<String, String>, ObjectNode>()   // (robotId, jobOrderId) -> consumption record
    private var executions = 0

    @Synchronized override fun revalidate(candidateJson: String): String {
        val candidate = try {
            strictJson.readTree(candidateJson)
        } catch (e: Exception) {
            return "UNKNOWN"
        }
        if (candidate == null || !candidate.isObject) return "UNKNOWN"
        return when (candidate.path("kind").textValue()) {
            "CHOOSE_SOURCE", "OPERATOR_DECISION" -> "TRUE"   // a person task: the mock has no source to read
            "APPROVE_REMEDY" -> {
                val key = proposal(candidate) ?: return "UNKNOWN"
                if (key in consumed) "FALSE" else "TRUE"
            }
            else -> "UNKNOWN"
        }
    }

    /** The answer text, picasso `ApprovalWire.encode` schema 4. [approverKind] goes into the consumption record. */
    @Synchronized override fun approve(intent: JsonNode, approverKind: String?): String {
        val candidate = intent.path("candidate")
        val key = proposal(candidate) ?: return refused("NO_PROPOSAL", "mock: no proposal for an unnamed robot/order", null)
        consumed[key]?.let { return refused("CONSUMED", "mock: the proposal for ${key.first}/${key.second} was consumed", it) }
        executions += 1
        val executionId = "mock-exec-$executions"
        val steps = strictJson.createArrayNode().apply {
            candidate.path("sawSkillTypes").forEachIndexed { i, skill ->
                addObject().put("unitId", "remedy-${i + 1}-${skill.asText()}").put("skillType", skill.asText()).putObject("parameters")
            }
        }
        consumed[key] = strictJson.createObjectNode().apply {
            put("approverId", "koshei")
            put("approverKind", approverKind ?: "PERSON")
            put("at", "1970-01-01T00:00:00Z")
            put("wallClockAt", "1970-01-01T00:00:00Z")
            put("executionId", executionId)
            set<ArrayNode>("steps", steps.deepCopy())
        }
        return answer().apply {
            put("outcome", "APPROVED")
            put("executionId", executionId)
            set<ArrayNode>("steps", steps)
        }.toString()
    }

    /** The (robotId, jobOrderId) a remedy names, or null when either is missing, not text, or blank. */
    private fun proposal(candidate: JsonNode): Pair<String, String>? {
        val robot = candidate.path("ref").path("robotId").textValue()
        val order = candidate.path("ref").path("jobOrderId").textValue()
        return if (robot.isNullOrBlank() || order.isNullOrBlank()) null else robot to order
    }

    private fun answer(): ObjectNode = strictJson.createObjectNode().apply {
        put("schemaVersion", "4")
        put("contractSemver", "1.0.0")
        put("instanceId", instanceId)
    }

    /** A refusal; `consumed` is never left out — the record for CONSUMED, JSON null otherwise (picasso §7.3). */
    private fun refused(refusal: String, reason: String, record: ObjectNode?): String = answer().apply {
        put("outcome", "REFUSED")
        put("refusal", refusal)
        put("reason", reason)
        if (record == null) putNull("consumed") else set<ObjectNode>("consumed", record.deepCopy())
    }.toString()

    companion object {
        /** A fixed instance for tests that carry reports to this mock's executions (the test-only `MockCarrier` writes it by default). */
        const val INSTANCE = "mock-instance-1"
    }
}
