package koshei.episode.runtime

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.grpc.StatusRuntimeException
import koshei.episode.Correlation
import koshei.episode.CorrelationRule
import koshei.episode.PolicyParse
import koshei.episode.SymptomKind
import koshei.episode.correlate
import koshei.episode.parsePolicy
import java.io.IOException
import java.nio.file.Path
import java.time.Instant

/**
 * The watcher (design §12): carries picasso export lines in as `symptom` signals and the host's JobResponses in as
 * `evidence` signals. It carries, it does not judge — the only choices it makes are the merge key ([correlate], pure)
 * and which workflow a JobResponse goes to (the order its dispatch intent named). At least once: the cursor moves only
 * after Temporal took the signal; the core drops repeats by `eventId` / `jobResponseId` (§6).
 *
 * A source is named by its directory's real path (resolved at every poll), so [exports] must be stable: another
 * spelling of the same directory that resolves elsewhere would be a new source and send its lines again.
 * Known limit: two export directories carrying the same runId collide on episode keys and eventIds (design §7.1 keys
 * carry no path).
 */
class EpisodeWatcher(
    private val exports: List<Path>,
    private val carry: Path?,
    private val policy: PolicyFileReader,
    private val store: WatchStore,
    private val signals: EpisodeSignals,
    private val clock: () -> Instant = Instant::now,
) {
    data class PollReport(val opened: Int, val evidence: Int)

    private val said = HashSet<String>()
    private var lastRules: List<CorrelationRule>? = null

    /** One pass over every source. Stops the pass at the first signal Temporal did not take (it is sent again next time). */
    fun pollOnce(): PollReport {
        val rules = rules()
        var opened = 0
        for (dir in exports) {
            val intake = intake(stable(dir), rules)
            opened += intake.opened
            if (intake.stopped) return PollReport(opened, 0)
        }
        val evidence = carry?.let { carryIn(stable(it)) ?: return PollReport(opened, 0) } ?: 0
        return PollReport(opened, evidence)
    }

    /** [opened] lines Temporal took from one directory; [stopped] when a signal failed and the pass must stop. */
    private class Intake(val opened: Int, val stopped: Boolean)

    /** The directory's real path; while it cannot be resolved (not there yet), its absolute normal form. */
    private fun stable(dir: Path): Path = try {
        dir.toRealPath()
    } catch (e: IOException) {
        dir.toAbsolutePath().normalize()
    }

    /** Carries [dir]'s new lines in; the lines sent before a failed signal count, and stay carried. */
    private fun intake(dir: Path, rules: List<CorrelationRule>): Intake {
        val export = when (val r = ExportSymptoms.read(dir)) {
            is BundleRead.Ready -> r
            is BundleRead.NotReady -> { once("$dir", "NOT_READY", "why" to r.why); return Intake(0, stopped = false) }
            is BundleRead.Broken -> { once("$dir", "BROKEN", "why" to r.why); return Intake(0, stopped = false) }
        }
        var opened = 0
        for (kind in ExportKind.entries) {
            val source = "$dir#${kind.file.name}"
            val lines = export.lines.getValue(kind.file.name)
            val from = resume(source, export.runId, lines) ?: continue
            for (line in lines.drop(from)) {
                val symptom = ExportSymptoms.symptom(kind, export, line, clock().toEpochMilli())
                if (symptom == null) {
                    once(source, "BROKEN", "runId" to export.runId, "id" to line.id, "why" to "no digest: no eventId")
                    break
                }
                val correlation = correlate(SymptomKind.valueOf(kind.name), line.node, export.runId, line.id, rules)
                if (correlation == null) {
                    once(source, "STUCK", "runId" to export.runId, "id" to line.id, "why" to "no valid key")
                    break
                }
                if (correlation is Correlation.Own && correlation.related.isNotEmpty())
                    log(source, "AMBIGUOUS", "eventId" to symptom.eventId, "key" to correlation.key, "related" to correlation.related)
                try {
                    signals.open("ep:${correlation.key}", EpisodeStart(export.manifestJson), symptom)
                } catch (e: RuntimeException) {
                    signalFailed(source, e)
                    return Intake(opened, stopped = true)
                }
                store.advance(source, export.runId, line.position, line.id)
                opened++
            }
        }
        return Intake(opened, stopped = false)
    }

    /**
     * How many of [lines] were already carried: the cursor's position, if the line there still carries its id. Null
     * (and STUCK, once) when the source changed under the cursor — never re-guessed (design §12).
     */
    private fun resume(source: String, runId: String, lines: List<BundleLine>): Int? {
        val cursor = store.cursor(source, runId) ?: return 0
        if (cursor.position <= lines.size && lines[cursor.position - 1].id == cursor.lastId) return cursor.position
        once(source, "STUCK", "runId" to runId, "position" to cursor.position, "lastId" to cursor.lastId,
            "why" to "the line at the cursor is no longer the one carried")
        return null
    }

    /** The policy's merge rules; the last valid ones when the table cannot be used, none before any was valid. */
    private fun rules(): List<CorrelationRule> {
        val read = policy.read()
        val why = when (read.kind) {
            // Only text that is not JSON is "not JSON"; anything parsePolicy itself throws is a fault and goes up.
            "FOUND" -> when (val p = read.json?.let(::jsonOrNull)?.let(::parsePolicy)) {
                is PolicyParse.Valid -> { lastRules = p.policy.correlation; return p.policy.correlation }
                is PolicyParse.Invalid -> "rejected: ${p.errors.joinToString("; ")}"
                null -> "not JSON"
            }
            "MISSING" -> "missing"
            else -> "unreadable: ${read.reason}"
        }
        once("policy", "POLICY_UNUSABLE", "why" to why, "using" to if (lastRules == null) "no rules" else "the last valid rules")
        return lastRules ?: emptyList()
    }

    private fun jsonOrNull(text: String): JsonNode? = try {
        strictJson.readTree(text)
    } catch (e: JsonProcessingException) {
        null
    }

    /** The carry half — Task 7. */
    private fun carryIn(dir: Path): Int? = 0

    /**
     * SIGNAL_FAILED once per kind of failure — the exception's class and, when Temporal's gRPC call failed, its status
     * code — not per message: messages carry changing text (ids, times) and would log every poll while Temporal is down.
     * The message is kept in the detail.
     */
    private fun signalFailed(source: String, e: RuntimeException) {
        val status = generateSequence<Throwable>(e) { it.cause }.take(16)
            .filterIsInstance<StatusRuntimeException>().firstOrNull()?.status?.code
        val detail = buildList<Pair<String, Any>> {
            add("why" to (e.message ?: e.javaClass.simpleName))
            add("exception" to e.javaClass.name)
            if (status != null) add("status" to status.name)
        }
        once(source, "SIGNAL_FAILED", *detail.toTypedArray(), key = "${e.javaClass.name}|$status")
    }

    /**
     * Logs once per [source], [kind] and [key] (by default the whole detail) in this process. The key is remembered only
     * after the log row was written, so a failed write is tried again at a later poll. [said] is bounded: when full it
     * is cleared, and at worst a reason is logged once more.
     */
    private fun once(source: String, kind: String, vararg detail: Pair<String, Any>, key: String = detail.joinToString()) {
        val k = "$source|$kind|$key"
        if (k in said) return
        log(source, kind, *detail)
        if (said.size >= SAID_MAX) said.clear()
        said += k
    }

    private fun log(source: String, kind: String, vararg detail: Pair<String, Any>) {
        val node = JsonNodeFactory.instance.objectNode()
        detail.forEach { (k, v) ->
            when (v) {
                is Int -> node.put(k, v)
                is List<*> -> node.putArray(k).also { a -> v.forEach { a.add(it.toString()) } }
                else -> node.put(k, v.toString())
            }
        }
        store.log(source, kind, strictJson.writeValueAsString(node))
    }

    private companion object {
        /** At most this many remembered reasons (see [once]). */
        const val SAID_MAX = 1000
    }
}
