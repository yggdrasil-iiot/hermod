package koshei.episode.runtime

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val intent = { order: String -> """{"candidate":{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{"robotId":"hum-02","jobOrderId":"$order","searchId":"search-1"},"sawSkillTypes":["pick_place"]},"idempotencyKey":"k","approval":null}""" }
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
        carrier.report("resp-9", "NOPE", "exec-9").raw("""{"schemaVersion":"1","instanceId":"${MockPicasso.INSTANCE}","jobResponseId":"resp-10"}""").snapshot()
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
        carrier.report("resp-1", "PATROL-1", "exec-2").raw("""{"schemaVersion":"1","instanceId":"${MockPicasso.INSTANCE}","jobResponseId":"resp-2","jobOrderId":"PATROL-1","inDoubtUnits":"u"}""")
            .report("resp-3", "PATROL-1", "exec-2").snapshot()
        val w = watcher()
        assertEquals(1, w.pollOnce().evidence)
        assertEquals(0, w.pollOnce().evidence, "the broken line still stops it")
        assertEquals(1, watch.logs(10).count { it.kind == "BROKEN" }, "said once")
        assertEquals(2, signals.evidence.size, "resp-1 to both episodes; resp-3 never sent")
    }

    @Test fun `a line of another schema stops the carry there - and is said once`() {
        carrier.report("resp-1", "PATROL-1", "exec-2")
            .raw("""{"schemaVersion":"2","instanceId":"${MockPicasso.INSTANCE}","jobResponseId":"resp-2","jobOrderId":"PATROL-1"}""")
            .report("resp-3", "PATROL-1", "exec-2").snapshot()
        val w = watcher()
        assertEquals(1, w.pollOnce().evidence)
        assertEquals(0, w.pollOnce().evidence)
        val broken = watch.logs(10).filter { it.kind == "BROKEN" }
        assertEquals(1, broken.size, "$broken")
        val d = strictJson.readTree(broken.single().detailJson)   // jsonb text has its own spacing: parse, never match text
        assertEquals(2, d.get("position").intValue(), "$d")
        assertTrue("schemaVersion" in d.get("why").textValue(), "$d")
    }

    @Test fun `the signal carries picasso's instance and connection`() {
        carrier.report("resp-1", "PATROL-1", "exec-2", connection = "CONNECTION_STATE_OFFLINE").snapshot()
        watcher().pollOnce()
        val s = signals.evidence.first().second
        assertEquals(MockPicasso.INSTANCE, s.picassoInstanceId)
        assertEquals("CONNECTION_STATE_OFFLINE", s.connection)
    }

    @Test fun `a half-written last line waits for its newline`() {
        carrier.report("resp-1", "PATROL-1", "exec-2").snapshot()
            .append("""{"schemaVersion":"1","instanceId":"${MockPicasso.INSTANCE}","jobResponseId":"resp-2","jobOrderId":"PAT""")
        val w = watcher()
        assertEquals(1, w.pollOnce().evidence)
        assertEquals(0, w.pollOnce().evidence, "not a line yet")
        carrier.append("ROL-1\"}\n")
        assertEquals(1, w.pollOnce().evidence)
        assertEquals(listOf("resp-1", "resp-1", "resp-2", "resp-2"), signals.evidence.map { it.second.jobResponseId })
        assertEquals(emptyList(), watch.logs(10).filter { it.kind == "BROKEN" || it.kind == "STUCK" })
    }

    @Test fun `a file replaced by another instance's is a new run - carried from its first line`() {
        carrier.report("resp-1", "PATROL-1", "exec-2").report("resp-2", "PATROL-1", "exec-2").snapshot()
        val w = watcher()
        assertEquals(2, w.pollOnce().evidence)
        MockCarrier(root.resolve("carry"), instanceId = "mw-restarted").report("resp-1", "PATROL-1", "exec-1").snapshot()
        assertEquals(1, w.pollOnce().evidence)
        assertEquals("mw-restarted", signals.evidence.last().second.picassoInstanceId)
        assertEquals(emptyList(), watch.logs(10).filter { it.kind == "STUCK" })
    }

    @Test fun `the same instance's file changed under the cursor is stuck`() {
        carrier.report("resp-1", "PATROL-1", "exec-2").report("resp-2", "PATROL-1", "exec-2").snapshot()
        val w = watcher()
        assertEquals(2, w.pollOnce().evidence)
        MockCarrier(root.resolve("carry")).report("resp-1", "PATROL-1", "exec-2").report("resp-9", "PATROL-1", "exec-2").snapshot()
        assertEquals(0, w.pollOnce().evidence)
        assertEquals(1, watch.logs(10).count { it.kind == "STUCK" })
    }

    @Test fun `no file yet is said once and carries nothing`() {
        val w = watcher()
        assertEquals(0, w.pollOnce().evidence)
        assertEquals(0, w.pollOnce().evidence)
        assertEquals(1, watch.logs(10).count { it.kind == "NOT_READY" })
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
        // keyed by line and kind of failure, not by its text
        val failed = strictJson.readTree(watch.logs(10).single { it.kind == "SIGNAL_FAILED" }.detailJson)
        assertEquals(listOf("2", "${MockPicasso.INSTANCE}/resp-2", "ep:a/r1"),listOf("position", "id", "instanceId").map { failed.get(it).asText() }, "$failed")
        assertEquals(1, watcher().pollOnce().evidence, "only resp-2 is sent again")
        assertEquals(listOf("resp-1", "resp-1", "resp-2", "resp-2"), signals.evidence.map { it.second.jobResponseId })
    }

    @Test fun `a failure partway through one line's episodes sends the whole line again - at least once`() {
        carrier.report("resp-1", "PATROL-1", "exec-2").snapshot()
        val failing = object : EpisodeSignals {
            override fun open(workflowId: String, start: EpisodeStart, symptom: SymptomSignal): String = error("not used")
            override fun evidence(instanceId: String, signal: EvidenceSignal): Boolean {
                if (instanceId == "ep:b/r2" && signal.jobResponseId == "resp-1") throw IllegalStateException("temporal down")
                return signals.evidence(instanceId, signal)
            }
        }
        assertEquals(0, watcher(failing).pollOnce().evidence, "the line is not carried until every episode took it")
        assertEquals(1, watch.logs(10).count { it.kind == "SIGNAL_FAILED" })
        assertEquals(1, watcher().pollOnce().evidence)
        assertEquals(listOf("ep:a/r1", "ep:a/r1", "ep:b/r2"), signals.evidence.map { it.first }, "ep:a/r1 got resp-1 twice; the core drops the second")
    }
}
