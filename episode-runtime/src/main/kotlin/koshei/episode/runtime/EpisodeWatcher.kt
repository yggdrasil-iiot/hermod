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
 *
 * Known limit: a line whose signal always fails — Temporal refusing it for good (INVALID_ARGUMENT for an oversized
 * payload), or an instance whose `view()` query keeps failing — stops the pass at that line at every poll: the later
 * lines of every source, and all evidence, wait behind it. Nothing is skipped to get past it; its SIGNAL_FAILED row
 * names the line (source, runId, position, id) so a person can see which one it is (design §12).
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
            opened += intake.count
            if (intake.stopped) return PollReport(opened, 0)
        }
        val evidence = carry?.let { carryIn(stable(it)).count } ?: 0   // the carry is last: a stop there ends the pass anyway
        return PollReport(opened, evidence)
    }

    /** [count] lines carried from one directory; [stopped] when a signal failed and the pass must stop. */
    private class Carried(val count: Int, val stopped: Boolean)

    /** The directory's real path; while it cannot be resolved (not there yet), its absolute normal form. */
    private fun stable(dir: Path): Path = try {
        dir.toRealPath()
    } catch (e: IOException) {
        dir.toAbsolutePath().normalize()
    }

    /** Carries [dir]'s new lines in; the lines sent before a failed signal count, and stay carried. */
    private fun intake(dir: Path, rules: List<CorrelationRule>): Carried {
        val export = when (val r = ExportSymptoms.read(dir)) {
            is BundleRead.Ready -> r
            is BundleRead.NotReady -> { once("$dir", "NOT_READY", "why" to r.why); return Carried(0, stopped = false) }
            is BundleRead.Broken -> { once("$dir", "BROKEN", "why" to r.why); return Carried(0, stopped = false) }
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
                try {
                    signals.open("ep:${correlation.key}", EpisodeStart(export.manifestJson), symptom)
                } catch (e: RuntimeException) {
                    signalFailed(source, e, export.runId, line.position, "id" to line.id, "eventId" to symptom.eventId)
                    return Carried(opened, stopped = true)
                }
                // Said only once the symptom was delivered, and once per line: an outage that sends it again adds no row.
                if (correlation is Correlation.Own && correlation.related.isNotEmpty())
                    once(source, "AMBIGUOUS", "eventId" to symptom.eventId, "key" to correlation.key, "related" to correlation.related,
                        key = "${export.runId}|${line.position}")
                store.advance(source, export.runId, line.position, line.id)
                opened++
            }
        }
        return Carried(opened, stopped = false)
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

    /**
     * Carries [dir]'s new JobResponse lines to every episode instance whose remedy intent named the line's order — the
     * core picks the attempt by `executionId` (§12). A line read but with nowhere to go is logged (UNROUTED) and counts
     * as carried. The lines sent before a failed signal count, and stay carried.
     */
    private fun carryIn(dir: Path): Carried {
        val bundle = when (val r = Bundles.read(dir, "carryRunId", CarriedEvidence.SCHEMA_VERSIONS, listOf(CarriedEvidence.FILE), uniqueIds = false)) {
            is BundleRead.Ready -> r
            is BundleRead.NotReady -> { once("$dir", "NOT_READY", "why" to r.why); return Carried(0, stopped = false) }
            is BundleRead.Broken -> { once("$dir", "BROKEN", "why" to r.why); return Carried(0, stopped = false) }
        }
        val source = "$dir#${CarriedEvidence.FILE.name}"
        val lines = bundle.lines.getValue(CarriedEvidence.FILE.name)
        val from = resume(source, bundle.runId, lines) ?: return Carried(0, stopped = false)
        var carried = 0
        for (line in lines.drop(from)) {
            when (val e = CarriedEvidence.of(line.node, clock().toEpochMilli())) {
                is CarriedEvidence.Line.Broken -> { once(source, "BROKEN", "runId" to bundle.runId, "position" to line.position, "why" to e.why); break }
                is CarriedEvidence.Line.Unrouted -> once(source, "UNROUTED", "jobResponseId" to e.jobResponseId,
                    "why" to "no jobOrderId (absent, blank or not text)", key = "${bundle.runId}|${line.position}")
                is CarriedEvidence.Line.Routed -> {
                    // Ended instances stay in this list for good: each later report for the order costs one describe per
                    // ended instance and one ROUTE_ENDED row. Acceptable at PoC scale.
                    val instances = store.instancesForOrder(e.jobOrderId)
                    if (instances.isEmpty())
                        once(source, "UNROUTED", "jobResponseId" to e.signal.jobResponseId, "jobOrderId" to e.jobOrderId,
                            "why" to "no remedy intent named this order", key = "${bundle.runId}|${line.position}")
                    for (instanceId in instances) {
                        val delivered = try {
                            signals.evidence(instanceId, e.signal)
                        } catch (x: RuntimeException) {
                            signalFailed(source, x, bundle.runId, line.position, "id" to line.id, "instanceId" to instanceId)
                            return Carried(carried, stopped = true)
                        }
                        if (!delivered) once(source, "ROUTE_ENDED", "jobResponseId" to e.signal.jobResponseId, "instanceId" to instanceId,
                            key = "${bundle.runId}|${line.position}|$instanceId")   // a line sent again after a failure logs it once
                    }
                }
            }
            store.advance(source, bundle.runId, line.position, line.id)
            carried++
        }
        return Carried(carried, stopped = false)
    }

    /**
     * SIGNAL_FAILED once per line and kind of failure — the line ([source], [runId], [position]), the exception's class
     * and, when Temporal's gRPC call failed, its status code — not per message: messages carry changing text (ids, times)
     * and would log every poll while Temporal is down. One outage on one line is one row; the next line that gets stuck
     * is a new row. The detail names the line ([position], and [line] — its id, and the eventId or the instance sent to)
     * and keeps the message.
     */
    private fun signalFailed(source: String, e: RuntimeException, runId: String, position: Int, vararg line: Pair<String, Any>) {
        val status = generateSequence<Throwable>(e) { it.cause }.take(16)
            .filterIsInstance<StatusRuntimeException>().firstOrNull()?.status?.code
        val detail = buildList<Pair<String, Any>> {
            add("runId" to runId)
            add("position" to position)
            addAll(line)
            add("why" to (e.message ?: e.javaClass.simpleName))
            add("exception" to e.javaClass.name)
            if (status != null) add("status" to status.name)
        }
        once(source, "SIGNAL_FAILED", *detail.toTypedArray(), key = "${e.javaClass.name}|$status|$runId|$position")
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
