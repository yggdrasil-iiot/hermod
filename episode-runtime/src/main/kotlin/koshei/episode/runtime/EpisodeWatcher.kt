package koshei.episode.runtime

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import koshei.episode.Correlation
import koshei.episode.CorrelationRule
import koshei.episode.PolicyParse
import koshei.episode.SymptomKind
import koshei.episode.correlate
import koshei.episode.parsePolicy
import java.nio.file.Path
import java.time.Instant

/**
 * The watcher (design §12): carries picasso export lines in as `symptom` signals and the host's JobResponses in as
 * `evidence` signals. It carries, it does not judge — the only choices it makes are the merge key ([correlate], pure)
 * and which workflow a JobResponse goes to (the order its dispatch intent named). At least once: the cursor moves only
 * after Temporal took the signal; the core drops repeats by `eventId` / `jobResponseId` (§6).
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
            val n = intake(dir.toAbsolutePath().normalize(), rules) ?: return PollReport(opened, 0)
            opened += n
        }
        val evidence = carry?.let { carryIn(it.toAbsolutePath().normalize()) ?: return PollReport(opened, 0) } ?: 0
        return PollReport(opened, evidence)
    }

    /** Lines opened from [dir]; null when a signal failed and the pass must stop. */
    private fun intake(dir: Path, rules: List<CorrelationRule>): Int? {
        val export = when (val r = ExportSymptoms.read(dir)) {
            is BundleRead.Ready -> r
            is BundleRead.NotReady -> { once("$dir", "NOT_READY", "why" to r.why); return 0 }
            is BundleRead.Broken -> { once("$dir", "BROKEN", "why" to r.why); return 0 }
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
                    once(source, "SIGNAL_FAILED", "why" to (e.message ?: e.javaClass.simpleName))
                    return null
                }
                store.advance(source, export.runId, line.position, line.id)
                opened++
            }
        }
        return opened
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
            "FOUND" -> when (val p = runCatching { parsePolicy(strictJson.readTree(read.json)) }.getOrNull()) {
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

    /** The carry half — Task 7. */
    private fun carryIn(dir: Path): Int? = 0

    private fun once(source: String, kind: String, vararg detail: Pair<String, Any>) {
        if (said.add("$source|$kind|${detail.joinToString()}")) log(source, kind, *detail)
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
}
