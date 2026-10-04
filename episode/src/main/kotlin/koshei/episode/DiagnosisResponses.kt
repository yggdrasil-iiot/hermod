package koshei.episode

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.Collections

/** The four outcomes of a diagnosis (contract 0.6 §4). All four are values; failures arrive as activity exceptions. */
enum class DiagnosisOutcome { RECOMMENDED, NO_GROUNDS, UNCITED, OUT_OF_CANDIDATES }

data class CardLine(val label: String, val text: String)

/** The two kinds of unchecked claim (contract §4 `unverifiedClaims`). The enum NAME is the wire value. */
enum class ClaimKind { CITATION, NUMBER }

/**
 * An unchecked claim in the rationale: a CITATION not in the evidence bundle, or a NUMBER not found there.
 * [foundIn] as read by [parseDiagnosisResponse] is read-only.
 */
data class UnverifiedClaim(val kind: ClaimKind, val text: String?, val foundIn: List<String>?)

/** The five version fields of a diagnosis (contract §4, design §13) — investigation material, not a guarantee. */
data class DiagnosisVersions(
    val modelId: String?,
    val promptVersion: String?,
    val corpusVersion: String?,
    val searchFingerprint: String?,
    val narratorCommit: String?,
)

/**
 * A narrator `diagnose` response (contract 0.6 §4), read by [parseDiagnosisResponse]. [raw] keeps the wire form for the
 * record (R11). Nothing handed out can change the response: the JSON trees ([raw], [citations]) are fresh deep copies on
 * every access, and the lists are read-only.
 */
class DiagnosisResponse internal constructor(
    val contractVersion: String,
    val episodeId: String,
    val attempt: Int,
    val outcome: DiagnosisOutcome,
    val candidateId: String?,
    val picked: String?,
    val sawCandidatesVersion: String,
    val rationale: String?,
    card: List<CardLine>,
    val cause: String?,
    citations: List<JsonNode>,
    unverifiedClaims: List<UnverifiedClaim>,
    uncitedSentences: List<String>,
    val versions: DiagnosisVersions,
    val elapsedSeconds: Double,
    raw: ObjectNode,
) {
    val card: List<CardLine> = Collections.unmodifiableList(card.toList())
    val unverifiedClaims: List<UnverifiedClaim> = Collections.unmodifiableList(unverifiedClaims.toList())
    val uncitedSentences: List<String> = Collections.unmodifiableList(uncitedSentences.toList())

    private val citationsCopy: List<JsonNode> = citations.map { it.deepCopy<JsonNode>() }
    private val rawCopy: ObjectNode = raw.deepCopy()

    /** The citation objects as they arrived (`title`, `section`, `verified`, ...): a fresh deep copy on each access. */
    val citations: List<JsonNode> get() = Collections.unmodifiableList(citationsCopy.map { it.deepCopy<JsonNode>() })

    /** The wire form, extra keys included: a fresh deep copy on each access. */
    val raw: ObjectNode get() = rawCopy.deepCopy()

    /**
     * Clean enough for auto-approval under `requireClean` (design §9.5): there is a reason, and nothing in it is unchecked.
     * A null rationale is never clean — with no reason there are no sentences to count, so the lists are trivially empty.
     */
    val clean: Boolean get() = rationale != null && uncitedSentences.isEmpty() && unverifiedClaims.isEmpty()

    override fun toString(): String = "DiagnosisResponse($outcome, $candidateId, $episodeId attempt $attempt)"
}

sealed interface ResponseParse {
    data class Valid(val response: DiagnosisResponse) : ResponseParse
    /**
     * [raw] is what arrived, so an unreadable answer can still be recorded verbatim (design §13): a fresh deep copy on each
     * access. [rawText] is the wire text when the response was read from a string ([parseDiagnosisResponse] (String)),
     * whether it failed to parse or parsed and broke the contract; null when it was read from a tree. It is the only
     * record of text that is not JSON (where [raw] is `null`) or that a tree would misstate (a duplicate key).
     */
    class Invalid(errors: List<String>, raw: JsonNode, val rawText: String? = null) : ResponseParse {
        val errors: List<String> = Collections.unmodifiableList(errors.toList())
        private val rawCopy: JsonNode = raw.deepCopy()
        val raw: JsonNode get() = rawCopy.deepCopy()
        override fun toString() = "Invalid($errors)"
    }
}

private val SHA256_VERSION = Regex("sha256:[0-9a-f]{64}")
private val VERSION_KEYS = listOf("modelId", "promptVersion", "corpusVersion", "searchFingerprint", "narratorCommit")

/**
 * Reads a response from its wire text through the module's strict mapper, then as [parseDiagnosisResponse] (JsonNode).
 * Text that is not one JSON value — including an object with a duplicate key — is Invalid with raw `null`. Every Invalid
 * from this overload keeps the input in [ResponseParse.Invalid.rawText].
 *
 * The workflow must hand the activity result over as this raw string: a tree built by another mapper has already
 * silently kept only the last of duplicate keys, so a second `candidateId` could no longer be seen.
 */
fun parseDiagnosisResponse(json: String): ResponseParse {
    val node = try {
        mapper.readTree(json)
    } catch (e: JsonProcessingException) {
        return ResponseParse.Invalid(listOf("response: unreadable JSON: ${e.originalMessage}"), NullNode.instance, json)
    }
    return when (val parse = parseDiagnosisResponse(node)) {
        is ResponseParse.Valid -> parse
        is ResponseParse.Invalid -> ResponseParse.Invalid(parse.errors, parse.raw, json)
    }
}

/**
 * Reads a response strictly (contract 0.6 §2, §4): every required key must be present (null, never omitted), and all
 * violations are reported together. Keys this module does not know are ignored so that the provider can add fields;
 * they stay in [DiagnosisResponse.raw].
 *
 * A tree cannot show duplicate keys any more — whoever built it already dropped all but one. The workflow must pass the
 * activity result as the raw string to [parseDiagnosisResponse] (String); use this overload only for a tree the
 * module's own strict mapper built.
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
    val candidateId = node.text("candidateId")
    if (candidateIdNode != null && outcome != null) {
        if (outcome == DiagnosisOutcome.RECOMMENDED && candidateId.isNullOrBlank()) e += "candidateId: RECOMMENDED needs a candidate id"
        if (outcome != DiagnosisOutcome.RECOMMENDED && !candidateIdNode.isNull) e += "candidateId: must be null unless RECOMMENDED"
    }
    val pickedNode = present("picked")
    val picked = node.text("picked")
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
        val label = c.text("label")
        val t = c.text("text")
        if (label == null) e += "card[$i].label: expected a string"
        if (t == null) e += "card[$i].text: expected a string"
        if (label != null && t != null) CardLine(label, t) else null
    }
    val citations = list("citations")?.let { l ->
        l.forEachIndexed { i, c -> if (!c.isObject) e += "citations[$i]: expected an object" }
        l.toList()
    }
    val unverified = list("unverifiedClaims")?.mapIndexedNotNull { i, c -> readUnverifiedClaim("unverifiedClaims[$i]", c, e) }
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
                    x.isTextual && x.textValue().isNotBlank() -> x.textValue()
                    else -> { e += "versions.$k: expected null or a non-blank string"; null }
                }
            }
            DiagnosisVersions(values[0], values[1], values[2], values[3], values[4])
        }
    }
    val elapsed = present("elapsedSeconds")?.let {
        val s = it.doubleValue()
        if (it.isNumber && s.isFinite() && s >= 0) s else { e += "elapsedSeconds: expected a finite number >= 0"; null }
    }

    if (e.isNotEmpty()) return ResponseParse.Invalid(e.toList(), node)
    // Every null below has recorded an error, so this guard should never fire; it keeps a broken invariant from
    // throwing inside workflow code.
    val obj = node as? ObjectNode
    if (contractVersion == null || episodeId == null || attempt == null || outcome == null || saw == null || card == null ||
        citations == null || unverified == null || uncited == null || versions == null || elapsed == null || obj == null
    ) return ResponseParse.Invalid(listOf("internal: a required field was null without a recorded error"), node)
    return ResponseParse.Valid(
        DiagnosisResponse(
            contractVersion, episodeId, attempt, outcome, candidateId, picked, saw, rationale, card, cause,
            citations, unverified, uncited, versions, elapsed, obj,
        ),
    )
}

/** One `unverifiedClaims` element (contract §4): a known kind, a string-or-null text, and foundIn null for a CITATION. */
private fun readUnverifiedClaim(path: String, c: JsonNode, e: MutableList<String>): UnverifiedClaim? {
    val kind = c.text("kind")?.let { k -> ClaimKind.entries.firstOrNull { it.name == k } }
    if (kind == null) e += "$path.kind: expected CITATION or NUMBER"
    val t = c.get("text")
    if (t == null || !(t.isNull || t.isTextual)) e += "$path.text: expected null or a string"
    val f = c.get("foundIn")
    if (f == null || !(f.isNull || (f.isArray && f.all { it.isTextual }))) e += "$path.foundIn: expected null or a list of strings"
    if (kind == ClaimKind.CITATION && f != null && !f.isNull) e += "$path.foundIn: a CITATION claim has foundIn null"
    if (kind == null) return null
    val foundIn = f?.takeIf { it.isArray }?.map { it.textValue() }?.let { Collections.unmodifiableList(it) }
    return UnverifiedClaim(kind, c.text("text"), foundIn)
}
