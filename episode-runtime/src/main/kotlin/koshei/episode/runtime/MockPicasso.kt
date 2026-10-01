package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode

/**
 * picasso's approval window as plan B needs it (design §8.3, §11), in this process's memory. Like the real one it
 * drops the idempotency key and offers no lookup of past approvals: a proposal — one (robot, order) — is consumed by
 * its first approval. Approving it again answers REMEDY_NOT_APPLIED for the same search line and NO_PROPOSAL for
 * another one, both "my earlier attempt may have landed" (UNKNOWN(OUTCOME) in the core). Revalidation is the only
 * way to TRUE without a person (§8.3): TRUE while the proposal stands, FALSE once consumed.
 *
 * A test double: the consumption record lives in this process only, so a restarted worker approves again.
 */
class MockPicasso {
    private val consumedBy = HashMap<Pair<String, String>, String>()   // (robotId, jobOrderId) -> searchId that consumed it
    private var executions = 0

    @Synchronized fun revalidate(candidateJson: String): String {
        val candidate = try {
            strictJson.readTree(candidateJson)
        } catch (e: Exception) {
            return "UNKNOWN"
        }
        if (candidate?.path("kind")?.asText() != "APPROVE_REMEDY") return "TRUE"
        return if (proposal(candidate) in consumedBy) "FALSE" else "TRUE"
    }

    /** The answer text, picasso `ApprovalWire.encode` schema 2. [approverKind] is accepted and not checked here. */
    @Synchronized fun approve(intent: JsonNode, approverKind: String?): String {
        val candidate = intent.path("candidate")
        val key = proposal(candidate)
        val search = candidate.path("ref").path("searchId").asText()
        consumedBy[key]?.let { by ->
            val refusal = if (by == search) "REMEDY_NOT_APPLIED" else "NO_PROPOSAL"
            return refused(refusal, "mock: the proposal for ${key.first}/${key.second} was consumed by $by")
        }
        consumedBy[key] = search
        executions += 1
        return strictJson.createObjectNode().apply {
            put("schemaVersion", "2")
            put("contractSemver", "1.0.0")
            put("outcome", "APPROVED")
            put("executionId", "mock-exec-$executions")
            putArray("steps").apply {
                candidate.path("sawSkillTypes").forEach { addObject().put("skillType", it.asText()).putObject("parameters") }
            }
        }.toString()
    }

    private fun proposal(candidate: JsonNode): Pair<String, String> =
        candidate.path("ref").path("robotId").asText() to candidate.path("ref").path("jobOrderId").asText()

    private fun refused(refusal: String, reason: String): String = strictJson.createObjectNode().apply {
        put("schemaVersion", "2")
        put("contractSemver", "1.0.0")
        put("outcome", "REFUSED")
        put("refusal", refusal)
        put("reason", reason)
    }.toString()
}
