package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** The four outcomes of a diagnosis (contract 0.6 §4). All four are values; failures arrive as activity exceptions. */
enum class DiagnosisOutcome { RECOMMENDED, NO_GROUNDS, UNCITED, OUT_OF_CANDIDATES }

data class CardLine(val label: String, val text: String)

/** An unchecked claim in the rationale: a CITATION not in the evidence bundle, or a NUMBER not found there. */
data class UnverifiedClaim(val kind: String, val text: String?, val foundIn: List<String>?)

/** The five version fields of a diagnosis (contract §4, design §13) — investigation material, not a guarantee. */
data class DiagnosisVersions(
    val modelId: String?,
    val promptVersion: String?,
    val corpusVersion: String?,
    val searchFingerprint: String?,
    val narratorCommit: String?,
)

/** A narrator `diagnose` response (contract 0.6 §4), read by [parseDiagnosisResponse]. [raw] keeps the wire form for the record (R11). */
class DiagnosisResponse internal constructor(
    val contractVersion: String,
    val episodeId: String,
    val attempt: Int,
    val outcome: DiagnosisOutcome,
    val candidateId: String?,
    val picked: String?,
    val sawCandidatesVersion: String,
    val rationale: String?,
    val card: List<CardLine>,
    val cause: String?,
    val citations: List<JsonNode>,
    val unverifiedClaims: List<UnverifiedClaim>,
    val uncitedSentences: List<String>,
    val versions: DiagnosisVersions,
    val elapsedSeconds: Double,
    raw: ObjectNode,
) {
    val raw: ObjectNode = raw.deepCopy()

    /**
     * Clean enough for auto-approval under `requireClean` (design §9.5): there is a reason, and nothing in it is unchecked.
     * A null rationale is never clean — with no reason there are no sentences to count, so the lists are trivially empty.
     */
    val clean: Boolean get() = rationale != null && uncitedSentences.isEmpty() && unverifiedClaims.isEmpty()
}

sealed interface ResponseParse {
    data class Valid(val response: DiagnosisResponse) : ResponseParse
    /** [raw] is a deep copy of what arrived, so an unreadable answer can still be recorded verbatim (design §13). */
    class Invalid(val errors: List<String>, raw: JsonNode) : ResponseParse {
        val raw: JsonNode = raw.deepCopy()
        override fun toString() = "Invalid($errors)"
    }
}

private val SHA256_VERSION = Regex("sha256:[0-9a-f]{64}")
private val VERSION_KEYS = listOf("modelId", "promptVersion", "corpusVersion", "searchFingerprint", "narratorCommit")

/**
 * Reads a response strictly (contract 0.6 §2, §4): every required key must be present (null, never omitted), and all
 * violations are reported together. Keys this module does not know are ignored so that the provider can add fields;
 * they stay in [DiagnosisResponse.raw].
 */
fun parseDiagnosisResponse(node: JsonNode): ResponseParse {
    if (!node.isObject) return ResponseParse.Invalid(listOf("response: expected an object"), node)
    val e = mutableListOf<String>()
    fun present(key: String): JsonNode? = node.get(key).also { if (it == null) e += "$key: missing (keys are never omitted)" }
    fun text(key: String): String? = present(key)?.let {
        if (it.isTextual && it.textValue().isNotBlank()) it.textValue() else { e += "$key: expected a non-blank string"; null }
    }
    fun nullableText(key: String): String? = present(key)?.let {
        when {
            it.isNull -> null
            it.isTextual && it.textValue().isNotBlank() -> it.textValue()
            else -> { e += "$key: expected null or a non-blank string"; null }
        }
    }
    fun list(key: String): JsonNode? = present(key)?.let { if (it.isArray) it else { e += "$key: expected a list"; null } }

    val contractVersion = text("contractVersion")
    val episodeId = text("episodeId")
    val attempt = present("attempt")?.let {
        if (it.isIntegralNumber && it.canConvertToInt() && it.intValue() >= 1) it.intValue() else { e += "attempt: expected an integer >= 1"; null }
    }
    val outcome = present("outcome")?.let { o ->
        DiagnosisOutcome.entries.firstOrNull { o.isTextual && it.name == o.textValue() }
            ?: run { e += "outcome: expected one of ${DiagnosisOutcome.entries}"; null }
    }

    val candidateIdNode = present("candidateId")
    val candidateId = candidateIdNode?.takeIf { it.isTextual }?.textValue()
    if (candidateIdNode != null && outcome != null) {
        if (outcome == DiagnosisOutcome.RECOMMENDED && candidateId.isNullOrBlank()) e += "candidateId: RECOMMENDED needs a candidate id"
        if (outcome != DiagnosisOutcome.RECOMMENDED && !candidateIdNode.isNull) e += "candidateId: must be null unless RECOMMENDED"
    }
    val pickedNode = present("picked")
    val picked = pickedNode?.takeIf { it.isTextual }?.textValue()
    if (pickedNode != null && outcome != null) {
        if (outcome == DiagnosisOutcome.OUT_OF_CANDIDATES && !pickedNode.isNull && !pickedNode.isTextual) e += "picked: expected null or a string"
        if (outcome != DiagnosisOutcome.OUT_OF_CANDIDATES && !pickedNode.isNull) e += "picked: must be null unless OUT_OF_CANDIDATES"
    }

    val saw = present("sawCandidatesVersion")?.let {
        if (it.isTextual && SHA256_VERSION.matches(it.textValue())) it.textValue() else { e += "sawCandidatesVersion: expected sha256: + 64 lowercase hex"; null }
    }
    val rationale = nullableText("rationale")
    val cause = nullableText("cause")

    val card = list("card")?.mapIndexedNotNull { i, c ->
        val label = c.get("label")?.takeIf { it.isTextual }?.textValue()
        val t = c.get("text")?.takeIf { it.isTextual }?.textValue()
        if (label == null) e += "card[$i].label: expected a string"
        if (t == null) e += "card[$i].text: expected a string"
        if (label != null && t != null) CardLine(label, t) else null
    }
    val citations = list("citations")?.let { l ->
        l.forEachIndexed { i, c -> if (!c.isObject) e += "citations[$i]: expected an object" }
        l.map { it.deepCopy<JsonNode>() }
    }
    val unverified = list("unverifiedClaims")?.mapIndexedNotNull { i, c ->
        val kind = c.get("kind")?.takeIf { it.isTextual && it.textValue() in setOf("CITATION", "NUMBER") }?.textValue()
        if (kind == null) e += "unverifiedClaims[$i].kind: expected CITATION or NUMBER"
        val t = c.get("text")
        if (t == null || !(t.isNull || t.isTextual)) e += "unverifiedClaims[$i].text: expected null or a string"
        val f = c.get("foundIn")
        if (f == null || !(f.isNull || (f.isArray && f.all { it.isTextual }))) e += "unverifiedClaims[$i].foundIn: expected null or a list of strings"
        if (kind == "CITATION" && f != null && !f.isNull) e += "unverifiedClaims[$i].foundIn: a CITATION claim has foundIn null"
        if (kind != null) UnverifiedClaim(kind, t?.takeIf { it.isTextual }?.textValue(), f?.takeIf { it.isArray }?.map { it.textValue() }) else null
    }
    val uncited = list("uncitedSentences")?.let { l ->
        l.forEachIndexed { i, s -> if (!s.isTextual) e += "uncitedSentences[$i]: expected a string" }
        l.filter { it.isTextual }.map { it.textValue() }
    }
    val versions = present("versions")?.let { v ->
        if (!v.isObject) { e += "versions: expected an object"; null } else {
            val values = VERSION_KEYS.map { k ->
                val x = v.get(k)
                when {
                    x == null -> { e += "versions.$k: missing (keys are never omitted)"; null }
                    x.isNull -> null
                    x.isTextual -> x.textValue()
                    else -> { e += "versions.$k: expected null or a string"; null }
                }
            }
            DiagnosisVersions(values[0], values[1], values[2], values[3], values[4])
        }
    }
    val elapsed = present("elapsedSeconds")?.let {
        if (it.isNumber && it.doubleValue() >= 0) it.doubleValue() else { e += "elapsedSeconds: expected a number >= 0"; null }
    }

    if (e.isNotEmpty()) return ResponseParse.Invalid(e.toList(), node)
    return ResponseParse.Valid(
        DiagnosisResponse(
            contractVersion!!, episodeId!!, attempt!!, outcome!!, candidateId, picked, saw!!, rationale, card!!, cause,
            citations!!, unverified!!, uncited!!, versions!!, elapsed!!, node as ObjectNode,
        ),
    )
}
