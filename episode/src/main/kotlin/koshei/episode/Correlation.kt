package koshei.episode

import com.fasterxml.jackson.databind.JsonNode

/**
 * One merge rule (design §10.1, §12): symptoms of [kinds] whose lines carry every field of [by] with the same values
 * belong to one episode. A rule never guesses — a missing, non-text or unsafe value means it does not apply.
 */
data class CorrelationRule(val kinds: Set<SymptomKind>, val by: List<String>)

/** What [correlate] decided. The workflow id is `ep:<key>` (design §7.1). */
sealed interface Correlation {
    val key: String

    /** Rules gave exactly one key. */
    data class Merged(override val key: String) : Correlation

    /** No rule applied, or the rules disagreed: then [related] holds every key they gave — the cross-reference of §12. */
    data class Own(override val key: String, val related: List<String>) : Correlation
}

/** An episode key: one path segment of the control plane (design §7.2 구현(B3c)), short enough for a workflow id. */
val CORRELATION_KEY = Regex("[A-Za-z0-9._:-]{1,200}")

/** A field value a key may carry: no `:`, so values and field names can never run into each other. */
private val KEY_VALUE = Regex("[A-Za-z0-9._-]{1,64}")

/** A field name a rule may name. */
internal val RULE_FIELD = Regex("[A-Za-z][A-Za-z0-9]{0,63}")

/** At most this many fields per rule. */
internal const val RULE_MAX_FIELDS = 4

/**
 * The merge key of one picasso export line (design §12) — pure, deterministic, never an LLM. [id] is the line's own id
 * (`searchId` / `incidentId`). The own key is `<runId>:<id>`; one applicable rule gives `<runId>:by:<f1>:<v1>…`;
 * rules that disagree are ambiguous and merge nothing. Null when even the own key is not a valid key (or [id] carries
 * a `:`, so it could pose as a merged key): the caller stops.
 */
fun correlate(kind: SymptomKind, line: JsonNode, runId: String, id: String, rules: List<CorrelationRule>): Correlation? {
    if (!KEY_VALUE.matches(id)) return null
    val own = "$runId:$id".takeIf { CORRELATION_KEY.matches(it) } ?: return null
    val keys = rules.filter { kind in it.kinds }.mapNotNull { rule -> keyOf(rule, line, runId) }.distinct()
    return when (keys.size) {
        0 -> Correlation.Own(own, emptyList())
        1 -> Correlation.Merged(keys.single())
        else -> Correlation.Own(own, keys)
    }
}

private fun keyOf(rule: CorrelationRule, line: JsonNode, runId: String): String? {
    val parts = mutableListOf(runId, "by")
    for (field in rule.by) {
        val value = line.get(field)?.takeIf { it.isTextual }?.textValue()?.takeIf { KEY_VALUE.matches(it) } ?: return null
        parts += field
        parts += value
    }
    return parts.joinToString(":").takeIf { CORRELATION_KEY.matches(it) }
}
