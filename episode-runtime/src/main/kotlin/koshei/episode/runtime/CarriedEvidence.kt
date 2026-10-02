package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode

/**
 * A JobResponse as the host carries it to koshei (design §12). The shape is the dev one, `koshei-carry-0`: picasso's
 * JobResponse fields by name, plus the `executionId` the host knows — picasso's JobResponse has none (picasso
 * `Model.kt:310-337`, `8ab5400`). The real shape is decided with the host (§19 C).
 */
object CarriedEvidence {
    val FILE = BundleFile("job-responses.jsonl", "jobResponses", "jobResponseId")
    val SCHEMA_VERSIONS = setOf("koshei-carry-0")

    sealed interface Line {
        /** A report and the order whose episodes it goes to. */
        data class Routed(val jobOrderId: String, val signal: EvidenceSignal) : Line
        /** Read, but it names no order: there is nowhere to carry it. */
        data class Unrouted(val jobResponseId: String) : Line
        /** A field has a shape this reader does not know: stop, never guess. */
        data class Broken(val why: String) : Line
    }

    fun of(node: JsonNode, receivedAtMillis: Long): Line {
        val id = node.get("jobResponseId")?.takeIf { it.isTextual && it.textValue().isNotBlank() }?.textValue()
            ?: return Line.Broken("no jobResponseId")
        // Each field reader returns null for absent or JSON null ("not reported") and throws [WrongShape] otherwise.
        fun text(f: String): String? = node.get(f).let { v ->
            when {
                v == null || v.isNull -> null
                v.isTextual -> v.textValue()
                else -> throw WrongShape("$f is not text")
            }
        }
        fun units(f: String): List<String>? = node.get(f).let { v ->
            when {
                v == null || v.isNull -> null
                v.isArray && v.all { it.isTextual } -> v.map { it.textValue() }
                else -> throw WrongShape("$f is not a list of text")
            }
        }
        fun flag(f: String): Boolean? = node.get(f).let { v ->
            when {
                v == null || v.isNull -> null
                v.isBoolean -> v.booleanValue()
                else -> throw WrongShape("$f is not true or false")
            }
        }
        val signal = try {
            EvidenceSignal(
                jobResponseId = id,
                executionId = text("executionId"),
                physicalState = text("physicalState"),
                reachedEvidence = text("reachedEvidence"),
                completedUnits = units("completedUnits"),
                inDoubtUnits = units("inDoubtUnits"),
                unverifiedUnits = units("unverifiedUnits"),
                operatorRequired = flag("operatorRequired"),
                receivedAtMillis = receivedAtMillis,
            )
        } catch (e: WrongShape) {
            return Line.Broken("$id: ${e.message}")
        }
        val order = node.get("jobOrderId")?.takeIf { it.isTextual && it.textValue().isNotBlank() }?.textValue()
            ?: return Line.Unrouted(id)
        return Line.Routed(order, signal)
    }

    /** A field of a shape this reader does not know. */
    private class WrongShape(why: String) : Exception(why, null, false, false)
}
