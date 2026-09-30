package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** The narrator diagnosis contract version this module builds (narrator repo, docs/superpowers/specs/2026-09-27-진단-계약-초안.md). */
const val DIAGNOSIS_CONTRACT_VERSION = "0.6"

/**
 * Builds the `diagnose` activity request (contract 0.6 §3): the verbatim snapshot, the candidates projected from it,
 * their version, the unknowns (derived from the snapshot plus [episodeUnknowns], e.g. OUTCOME after a picasso
 * NO_PROPOSAL), and the attempt history. [history] entries are passed through as given; their eight-field shape
 * (contract §3.4) is the transition function's job (plan B2).
 */
fun diagnosisRequest(
    episodeId: String,
    attempt: Int,
    snapshot: Snapshot,
    episodeUnknowns: List<Unknown> = emptyList(),
    history: List<ObjectNode> = emptyList(),
): ObjectNode {
    require(episodeId.isNotBlank()) { "episodeId must not be blank" }
    require(attempt >= 1) { "attempt starts at 1, was $attempt" }
    val unknowns = deriveUnknowns(snapshot) + episodeUnknowns
    val candidates = projectCandidates(snapshot, unknowns)
    return mapper.createObjectNode().apply {
        put("contractVersion", DIAGNOSIS_CONTRACT_VERSION)
        put("episodeId", episodeId)
        put("attempt", attempt)
        set<JsonNode>("snapshot", snapshot.toJson())
        putArray("candidates").apply { candidates.forEach { add(it.toJson()) } }
        put("candidatesVersion", candidatesVersion(candidates))
        putArray("unknowns").apply { unknowns.forEach { add(it.toJson()) } }
        putArray("history").apply { history.forEach { add(it.deepCopy()) } }
    }
}
