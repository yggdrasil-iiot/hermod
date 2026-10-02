package koshei.episode.runtime

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeWatcherCarryTest {
    @TempDir lateinit var root: Path
    private lateinit var events: EpisodeStore
    private lateinit var watch: WatchStore
    private val signals = RecordingSignals()
    private lateinit var carrier: MockCarrier

    @BeforeEach fun up() {
        events = EpisodeDb.reset()
        watch = WatchStore(EpisodeDb::connection)
        carrier = MockCarrier(root.resolve("carry"))
        val intent = { order: String -> """{"candidate":{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{"robotId":"hum-02","jobOrderId":"$order","searchId":"search-1"},"sawSkillTypes":["pick_place"]},"idempotencyKey":"k","approval":null,"targetUnit":null}""" }
        events.append("ep:a/r1", 7, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1"))
        events.append("ep:b/r2", 7, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1"))
    }

    private fun watcher(signals: EpisodeSignals = this.signals): EpisodeWatcher {
        val policy = root.resolve("active.yaml").also { Files.writeString(it, TestPolicies.table(autoApprove = false)) }
        return EpisodeWatcher(emptyList(), root.resolve("carry"), PolicyFileReader(policy), watch, signals) { Instant.ofEpochMilli(5) }
    }

    @Test fun `a report goes to every episode whose intent named its order - the core decides which attempt it is`() {
        carrier.report("resp-1", "PATROL-1", "exec-2").snapshot()
        assertEquals(1, watcher().pollOnce().evidence)
        assertEquals(listOf("ep:a/r1", "ep:b/r2"), signals.evidence.map { it.first })
        assertEquals(5, signals.evidence.first().second.receivedAtMillis)
    }

    @Test fun `the same jobResponseId twice is carried twice - the core drops the second`() {
        carrier.report("resp-1", "PATROL-1", "exec-2").report("resp-1", "PATROL-1", "exec-2").snapshot()
        assertEquals(2, watcher().pollOnce().evidence)
        assertEquals(0, watcher().pollOnce().evidence, "the cursor counts lines, so repeated ids do not confuse it")
    }

    @Test fun `a report for no known order is logged, not lost silently`() {
        carrier.report("resp-9", "NOPE", "exec-9").raw("""{"jobResponseId":"resp-10"}""").snapshot()
        assertEquals(2, watcher().pollOnce().evidence)
        assertEquals(emptyList(), signals.evidence)
        assertEquals(2, watch.logs(10).count { it.kind == "UNROUTED" })
    }

    @Test fun `an ended episode is logged as such`() {
        signals.running = { it != "ep:a/r1" }
        carrier.report("resp-1", "PATROL-1", "exec-2").snapshot()
        watcher().pollOnce()
        assertEquals(listOf("ep:b/r2"), signals.evidence.map { it.first })
        assertEquals(1, watch.logs(10).count { it.kind == "ROUTE_ENDED" })
    }

    @Test fun `a broken line stops the carry there`() {
        carrier.report("resp-1", "PATROL-1", "exec-2").raw("""{"jobResponseId":"resp-2","jobOrderId":"PATROL-1","inDoubtUnits":"u"}""")
            .report("resp-3", "PATROL-1", "exec-2").snapshot()
        assertEquals(1, watcher().pollOnce().evidence)
        assertEquals(1, watch.logs(10).count { it.kind == "BROKEN" })
    }

    @Test fun `a carry of an unknown shape is not read`() {
        Files.createDirectories(root.resolve("carry"))
        Files.writeString(root.resolve("carry/job-responses.jsonl"), "")
        Files.writeString(root.resolve("carry/manifest.json"), """{"schemaVersion":"koshei-carry-1","carryRunId":"c","counts":{"jobResponses":0}}""")
        assertEquals(0, watcher().pollOnce().evidence)
        assertEquals(1, watch.logs(10).count { it.kind == "BROKEN" })
    }

    @Test fun `a failed signal stops the carry - the lines before it stay carried and are counted`() {
        carrier.report("resp-1", "PATROL-1", "exec-2").report("resp-2", "PATROL-1", "exec-2").snapshot()
        var failures = 0
        val failing = object : EpisodeSignals {
            override fun open(workflowId: String, start: EpisodeStart, symptom: SymptomSignal): String = error("not used")
            override fun evidence(instanceId: String, signal: EvidenceSignal): Boolean {
                if (signal.jobResponseId == "resp-2") throw IllegalStateException("temporal down at ${++failures}")
                return signals.evidence(instanceId, signal)
            }
        }
        val w = watcher(failing)
        assertEquals(1, w.pollOnce().evidence, "resp-1 went to both episodes before resp-2 failed")
        assertEquals(0, w.pollOnce().evidence)
        assertEquals(1, watch.logs(10).count { it.kind == "SIGNAL_FAILED" }, "keyed by kind of failure, not by its text")
        assertEquals(1, watcher().pollOnce().evidence, "only resp-2 is sent again")
        assertEquals(listOf("resp-1", "resp-1", "resp-2", "resp-2"), signals.evidence.map { it.second.jobResponseId })
    }
}
