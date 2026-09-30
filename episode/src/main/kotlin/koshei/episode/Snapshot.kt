package koshei.episode

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The picasso export lines an episode diagnoses, kept VERBATIM (design §9.0): the manifest with every field picasso
 * wrote, and the incident / remedy-search lines with the episode-opening line FIRST in its list (narrator puts the
 * first of each list into its query). The checks mirror the diagnosis contract's request-reading rules (contract 0.6
 * §3), so koshei never builds a request narrator would reject as a non-retryable contract violation.
 */
class Snapshot(manifest: ObjectNode, incidents: List<ObjectNode>, searches: List<ObjectNode>) {
    // Deep copies: the caller keeps no handle that could change a snapshot after its checks ran.
    val manifest: ObjectNode = manifest.deepCopy()
    val incidents: List<ObjectNode> = incidents.map { it.deepCopy() }
    val searches: List<ObjectNode> = searches.map { it.deepCopy() }

    // "Verbatim" means tree-equal with field order kept, not byte-equal: a float in a future picasso line would be
    // re-rendered by Jackson. run-1 has none, and the canonical hash (Jcs) refuses numbers outright.
    init {
        val sv = manifest.get("schemaVersion")
        require(sv != null && sv.isTextual && sv.textValue() == SCHEMA_VERSION) {
            "manifest.schemaVersion must be the string \"$SCHEMA_VERSION\", was $sv"
        }
        require(incidents.isNotEmpty() || searches.isNotEmpty()) { "a snapshot needs at least one incident or search line" }
        incidents.forEach { require(!it.text("digest").isNullOrBlank()) { "incident line without digest: ${it.get("incidentId")}" } }
        searches.forEach { require(!it.text("searchId").isNullOrBlank()) { "search line without searchId" } }
    }

    fun toJson(): ObjectNode = mapper.createObjectNode().apply {
        set<JsonNode>("manifest", manifest.deepCopy())
        putArray("incidents").apply { incidents.forEach { add(it.deepCopy()) } }
        putArray("searches").apply { searches.forEach { add(it.deepCopy()) } }
    }

    companion object {
        /** The only picasso export schema narrator reads (contract 0.6 §3). */
        const val SCHEMA_VERSION = "5"

        fun parse(manifestJson: String, incidentLines: List<String>, searchLines: List<String>): Snapshot =
            Snapshot(obj(manifestJson), incidentLines.map(::obj), searchLines.map(::obj))

        private fun obj(json: String): ObjectNode {
            val node = try {
                mapper.readTree(json)
            } catch (e: JsonProcessingException) {
                throw IllegalArgumentException("not valid JSON (${e.originalMessage}): ${json.take(60)}", e)
            }
            return node as? ObjectNode ?: throw IllegalArgumentException("not a JSON object: ${json.take(60)}")
        }
    }
}
