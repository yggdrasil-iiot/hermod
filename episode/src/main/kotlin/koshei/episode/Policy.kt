package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * One auto-approval rule (design §10.1). Only APPROVE_REMEDY carries one; person tasks have no auto-approval.
 * [skills] is an allowlist: `[]` auto-approves nothing.
 */
data class AutoApproveRule(val allowed: Boolean, val requireClean: Boolean, val skills: List<String>)

/** Wait budgets in milliseconds (design §5.1, §10.1). */
data class DeadlinesMs(
    val correlatingMs: Long,
    val approvalMs: Long,
    val approvalValidityMs: Long,
    val revalidateMs: Long,
    val recordMs: Long,
    val dispatchMs: Long,
    val childMaxMs: Long,
    val evidenceMs: Long,
    val unknownRecheckMs: Long,
    val unknownMs: Long,
    val escalatedRetentionMs: Long,
    val episodeMs: Long,
)

/** The `diagnose` activity budget (design §8.1, narrator contract §2). */
data class DiagnosisBudget(val scheduleToCloseMs: Long, val startToCloseMs: Long, val heartbeatMs: Long, val maxAttempts: Int)

/**
 * A validated episode policy table (design §10). It can only be obtained from [parsePolicy], so holding one means every
 * rule of §10.2 held. There is deliberately no field for UNKNOWN's execution block: no policy can lift it (R8).
 */
class PolicyTable internal constructor(
    val version: String,
    val expiresAt: Instant?,
    val agentLayerEnabled: Boolean,
    val autoApprove: Map<CandidateKind, AutoApproveRule>,
    val deadlines: DeadlinesMs,
    val diagnosis: DiagnosisBudget,
    val maxAttemptsPerEpisode: Int,
    val repeatedRemedyThreshold: Int,
    val snapshotMaxBytes: Long,
    /** Merge rules; their meaning is plan C's. v1 only checks that this is a list. */
    val correlation: List<JsonNode>,
    source: JsonNode,
) {
    private val sourceCopy: JsonNode = source.deepCopy()

    /**
     * The tree this table was parsed from, as a fresh copy on every read. Persist it and re-parse with [parsePolicy] to
     * restore the table, e.g. across a Temporal continue-as-new.
     */
    val source: JsonNode get() = sourceCopy.deepCopy()

    override fun toString(): String = "PolicyTable($version)"
}

sealed interface PolicyParse {
    data class Valid(val policy: PolicyTable) : PolicyParse
    data class Invalid(val errors: List<String>) : PolicyParse
}

/** Temporal's payload warning line: a larger snapshot would bloat workflow history (design §10.2). */
const val SNAPSHOT_MAX_BYTES_CEILING: Long = 512L * 1024

/** Upper bound for every duration in the table (365 days), so deadline arithmetic on `Instant` can never overflow. */
const val MAX_DURATION_MS: Long = 365L * 24 * 60 * 60 * 1000

private val TOP_KEYS = setOf(
    "version", "expiresAt", "agentLayerEnabled", "autoApprove", "actionCatalog", "deadlines", "diagnosis",
    "maxAttemptsPerEpisode", "repeatedRemedyThreshold", "snapshotMaxBytes", "correlation",
)
private val DEADLINE_KEYS = listOf(
    "correlatingMs", "approvalMs", "approvalValidityMs", "revalidateMs", "recordMs", "dispatchMs", "childMaxMs",
    "evidenceMs", "unknownRecheckMs", "unknownMs", "escalatedRetentionMs", "episodeMs",
)
private val DIAGNOSIS_KEYS = setOf("scheduleToCloseMs", "startToCloseMs", "heartbeatMs", "maxAttempts")
private val RULE_KEYS = setOf("allowed", "requireClean", "skills")

/**
 * Reads AND validates a policy table from a JSON tree (the runtime reads the YAML file into the tree). All violations
 * of design §10.2 are reported together. An unknown key is an error: a typo must never fall back to a default (R14).
 */
fun parsePolicy(root: JsonNode): PolicyParse {
    val r = PolicyReader()
    val top = r.obj(root, "policy", TOP_KEYS) ?: return PolicyParse.Invalid(r.errors)

    val version = r.text(top, "version", "")
    val expiresAt = r.instantOrNull(top, "expiresAt", "")
    val agentLayerEnabled = r.bool(top, "agentLayerEnabled", "")

    val autoApprove = LinkedHashMap<CandidateKind, AutoApproveRule>()
    r.obj(top.get("autoApprove"), "autoApprove", CandidateKind.entries.map { it.name }.toSet())?.let { aa ->
        aa.fieldNames().forEach { k ->
            val kind = CandidateKind.entries.firstOrNull { it.name == k } ?: return@forEach   // reported as unknown key
            val path = "autoApprove.$k"
            if (kind != CandidateKind.APPROVE_REMEDY) {
                r.errors += "$path: $k has no auto-approval (person task or ESCALATE)"
                return@forEach
            }
            r.obj(aa.get(k), path, RULE_KEYS)?.let { rule ->
                val allowed = r.bool(rule, "allowed", path)
                val requireClean = if (rule.has("requireClean")) r.bool(rule, "requireClean", path) else true
                val skills = r.textList(rule, "skills", path)
                if (allowed != null && requireClean != null && skills != null) autoApprove[kind] = AutoApproveRule(allowed, requireClean, skills)
            }
        }
    }

    r.listOrEmpty(top, "actionCatalog", "")?.let {
        if (it.isNotEmpty()) r.errors += "actionCatalog: SAGA_ACTION entries are not supported before plan D"
    }
    val correlation = r.listOrEmpty(top, "correlation", "")

    val deadlines = r.obj(top.get("deadlines"), "deadlines", DEADLINE_KEYS.toSet())?.let { d ->
        val v = DEADLINE_KEYS.map { r.duration(d, it, "deadlines") }
        if (v.any { it == null }) null else v.map { it!! }.let {
            DeadlinesMs(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7], it[8], it[9], it[10], it[11])
        }
    }
    val diagnosis = r.obj(top.get("diagnosis"), "diagnosis", DIAGNOSIS_KEYS)?.let { d ->
        val s = r.duration(d, "scheduleToCloseMs", "diagnosis")
        val st = r.duration(d, "startToCloseMs", "diagnosis")
        val hb = r.duration(d, "heartbeatMs", "diagnosis")
        val ma = r.int(d, "maxAttempts", "diagnosis")
        if (s == null || st == null || hb == null || ma == null) null else DiagnosisBudget(s, st, hb, ma)
    }
    val maxAttemptsPerEpisode = r.int(top, "maxAttemptsPerEpisode", "")
    val repeatedRemedyThreshold = r.int(top, "repeatedRemedyThreshold", "")
    val snapshotMaxBytes = r.long(top, "snapshotMaxBytes", "")?.also {
        if (it >= SNAPSHOT_MAX_BYTES_CEILING) r.errors += "snapshotMaxBytes: must be < $SNAPSHOT_MAX_BYTES_CEILING, was $it"
    }

    if (maxAttemptsPerEpisode != null && repeatedRemedyThreshold != null && repeatedRemedyThreshold > maxAttemptsPerEpisode)
        r.errors += "repeatedRemedyThreshold ($repeatedRemedyThreshold) must be <= maxAttemptsPerEpisode ($maxAttemptsPerEpisode)"
    if (diagnosis != null) {
        val needed = try { Math.multiplyExact(diagnosis.maxAttempts.toLong(), diagnosis.startToCloseMs) } catch (e: ArithmeticException) { Long.MAX_VALUE }
        if (diagnosis.scheduleToCloseMs < needed)
            r.errors += "diagnosis.scheduleToCloseMs (${diagnosis.scheduleToCloseMs}) must cover maxAttempts × startToCloseMs " +
                "(${diagnosis.maxAttempts} × ${diagnosis.startToCloseMs})"
        if (diagnosis.heartbeatMs >= diagnosis.startToCloseMs)
            r.errors += "diagnosis.heartbeatMs (${diagnosis.heartbeatMs}) must be < startToCloseMs (${diagnosis.startToCloseMs})"
    }
    if (deadlines != null) {
        if (diagnosis != null && diagnosis.scheduleToCloseMs > deadlines.episodeMs)
            r.errors += "diagnosis.scheduleToCloseMs (${diagnosis.scheduleToCloseMs}) must be <= deadlines.episodeMs (${deadlines.episodeMs})"
        if (deadlines.unknownRecheckMs >= deadlines.unknownMs)
            r.errors += "deadlines.unknownRecheckMs (${deadlines.unknownRecheckMs}) must be < deadlines.unknownMs (${deadlines.unknownMs})"
        val bounded = DEADLINE_KEYS.zip(
            listOf(
                deadlines.correlatingMs, deadlines.approvalMs, deadlines.approvalValidityMs, deadlines.revalidateMs,
                deadlines.recordMs, deadlines.dispatchMs, deadlines.childMaxMs, deadlines.evidenceMs,
                deadlines.unknownRecheckMs, deadlines.unknownMs, deadlines.escalatedRetentionMs, deadlines.episodeMs,
            ),
        ).filter { (k, _) -> k != "escalatedRetentionMs" && k != "episodeMs" }
        bounded.filter { (_, v) -> v > deadlines.episodeMs }.forEach { (k, v) ->
            r.errors += "deadlines.$k must be <= deadlines.episodeMs ($v > ${deadlines.episodeMs})"
        }
    }

    if (r.errors.isNotEmpty()) return PolicyParse.Invalid(r.errors.toList())
    return PolicyParse.Valid(
        PolicyTable(
            version!!, expiresAt, agentLayerEnabled!!,
            java.util.Collections.unmodifiableMap(LinkedHashMap(autoApprove)),
            deadlines!!, diagnosis!!, maxAttemptsPerEpisode!!, repeatedRemedyThreshold!!, snapshotMaxBytes!!,
            correlation!!.map { it.deepCopy<JsonNode>() },
            root.deepCopy(),
        ),
    )
}

/** Collects every violation; each read returns null when its field is invalid (and records why). */
private class PolicyReader {
    val errors = mutableListOf<String>()

    private fun at(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    fun obj(node: JsonNode?, path: String, allowed: Set<String>): JsonNode? {
        if (node == null || !node.isObject) { errors += "$path: expected an object"; return null }
        node.fieldNames().forEach { if (it !in allowed) errors += "${if (path == "policy") it else "$path.$it"}: unknown key" }
        return node
    }

    fun text(parent: JsonNode, key: String, path: String): String? {
        val v = parent.get(key)
        if (v == null || !v.isTextual || v.textValue().isBlank()) { errors += "${at(path, key)}: expected a non-blank string"; return null }
        return v.textValue()
    }

    fun bool(parent: JsonNode, key: String, path: String): Boolean? {
        val v = parent.get(key)
        if (v == null || !v.isBoolean) { errors += "${at(path, key)}: expected true or false"; return null }
        return v.booleanValue()
    }

    fun long(parent: JsonNode, key: String, path: String): Long? {
        val v = parent.get(key)
        if (v == null || !v.isIntegralNumber || !v.canConvertToLong()) { errors += "${at(path, key)}: expected an integer"; return null }
        val n = v.longValue()
        if (n < 1) { errors += "${at(path, key)}: must be >= 1, was $n"; return null }
        return n
    }

    /** A duration in milliseconds: an integer from 1 to [MAX_DURATION_MS]. */
    fun duration(parent: JsonNode, key: String, path: String): Long? {
        val n = long(parent, key, path) ?: return null
        if (n > MAX_DURATION_MS) { errors += "${at(path, key)}: must be <= $MAX_DURATION_MS (365 days), was $n"; return null }
        return n
    }

    fun int(parent: JsonNode, key: String, path: String): Int? {
        val n = long(parent, key, path) ?: return null
        if (n > Int.MAX_VALUE) { errors += "${at(path, key)}: too large"; return null }
        return n.toInt()
    }

    fun instantOrNull(parent: JsonNode, key: String, path: String): Instant? {
        val v = parent.get(key)
        if (v == null || v.isNull) return null
        if (!v.isTextual) { errors += "${at(path, key)}: expected an ISO-8601 instant or null"; return null }
        return try { Instant.parse(v.textValue()) } catch (e: DateTimeParseException) {
            errors += "${at(path, key)}: expected an ISO-8601 instant or null, was '${v.textValue()}'"; null
        }
    }

    fun textList(parent: JsonNode, key: String, path: String): List<String>? {
        val v = parent.get(key)
        if (v == null || !v.isArray || v.any { !it.isTextual || it.textValue().isBlank() }) {
            errors += "${at(path, key)}: expected a list of non-blank strings"; return null
        }
        return v.map { it.textValue() }
    }

    fun listOrEmpty(parent: JsonNode, key: String, path: String): List<JsonNode>? {
        val v = parent.get(key) ?: return emptyList()
        if (!v.isArray) { errors += "${at(path, key)}: expected a list"; return null }
        return v.toList()
    }
}
