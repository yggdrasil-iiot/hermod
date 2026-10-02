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
        fun text(f: String): Result<String?> = node.get(f).let { v ->
            when {
                v == null || v.isNull -> Result.success(null)
                v.isTextual -> Result.success(v.textValue())
                else -> Result.failure(IllegalArgumentException("$f is not text"))
            }
        }
        fun units(f: String): Result<List<String>?> = node.get(f).let { v ->
            when {
                v == null || v.isNull -> Result.success(null)
                v.isArray && v.all { it.isTextual } -> Result.success(v.map { it.textValue() })
                else -> Result.failure(IllegalArgumentException("$f is not a list of text"))
            }
        }
        val operatorRequired = node.get("operatorRequired").let { v ->
            when {
                v == null || v.isNull -> null
                v.isBoolean -> v.booleanValue()
                else -> return Line.Broken("$id: operatorRequired is not true or false")
            }
        }
        val signal = try {
            EvidenceSignal(
                jobResponseId = id,
                executionId = text("executionId").getOrThrow(),
                physicalState = text("physicalState").getOrThrow(),
                reachedEvidence = text("reachedEvidence").getOrThrow(),
                completedUnits = units("completedUnits").getOrThrow(),
                inDoubtUnits = units("inDoubtUnits").getOrThrow(),
                unverifiedUnits = units("unverifiedUnits").getOrThrow(),
                operatorRequired = operatorRequired,
                receivedAtMillis = receivedAtMillis,
            )
        } catch (e: IllegalArgumentException) {
            return Line.Broken("$id: ${e.message}")
        }
        val order = node.get("jobOrderId")?.takeIf { it.isTextual && it.textValue().isNotBlank() }?.textValue()
            ?: return Line.Unrouted(id)
        return Line.Routed(order, signal)
    }
}
