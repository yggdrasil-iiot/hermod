package koshei.episode.runtime

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Records what the watcher sent; [failOpen] makes `open` throw; [running] says which instances still take evidence. */
internal class RecordingSignals : EpisodeSignals {
    val opened = mutableListOf<Pair<String, SymptomSignal>>()
    val evidence = mutableListOf<Pair<String, EvidenceSignal>>()
    var failOpen: RuntimeException? = null
    var running: (String) -> Boolean = { true }
    override fun open(workflowId: String, start: EpisodeStart, symptom: SymptomSignal): String {
        failOpen?.let { throw it }
        opened += workflowId to symptom
        return "run"
    }
    override fun evidence(instanceId: String, signal: EvidenceSignal): Boolean {
        if (!running(instanceId)) return false
        evidence += instanceId to signal
        return true
    }
}

/** Writes a picasso-shaped export (lines first, manifest last), as picasso's BundleWriter does. */
internal class ExportDir(val dir: Path, var runId: String = "run-A") {
    val incidents = mutableListOf<String>()
    val searches = mutableListOf<String>()
    fun snapshot(): ExportDir {
        write("incidents.jsonl", incidents.joinToString("") { "$it\n" })
        write("remedy-searches.jsonl", searches.joinToString("") { "$it\n" })
        write("manifest.json", """{"schemaVersion":"5","runId":"$runId","writtenAt":"x","virtualNow":"x","contractSemver":"0.9.0","counts":{"incidents":${incidents.size},"remedySearches":${searches.size}}}""")
        return this
    }
    private fun write(name: String, text: String) {
        val tmp = dir.resolve("$name.tmp")
        Files.writeString(tmp, text)
        Files.move(tmp, dir.resolve(name), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

class EpisodeWatcherIntakeTest {
    @TempDir lateinit var root: Path
    private lateinit var watch: WatchStore
    private val signals = RecordingSignals()
    private lateinit var export: ExportDir
    private lateinit var policy: Path

    @BeforeEach fun up() {
        EpisodeDb.reset()
        watch = WatchStore(EpisodeDb::connection)
        export = ExportDir(Files.createDirectories(root.resolve("export")))
        policy = root.resolve("active.yaml")
        Files.writeString(policy, TestPolicies.table(autoApprove = false))   // JSON is YAML
    }

    private fun watcher() = EpisodeWatcher(listOf(export.dir), carry = null, PolicyFileReader(policy), watch, signals) { Instant.ofEpochMilli(42) }
    private fun withRules(rules: String) = Files.writeString(policy, TestPolicies.table(autoApprove = false).replace("\"correlation\": []", "\"correlation\": $rules"))

    @Test fun `each new line opens its own episode once - a second poll sends nothing`() {
        export.incidents += Picasso.incident("incident-1")
        export.searches += Picasso.search("search-1")
        export.snapshot()
        val w = watcher()
        assertEquals(2, w.pollOnce().opened)
        assertEquals(listOf("ep:run-A:incident-1", "ep:run-A:search-1"), signals.opened.map { it.first })
        assertEquals("incident:run-A:d04ac2a20a63afc2ba147218bae871ef69746c990e3993deb00cfb4ddd262143", signals.opened[0].second.eventId)
        assertEquals(42L, signals.opened[0].second.observedAtMillis)
        assertEquals(0, w.pollOnce().opened)
        assertEquals(0, watcher().pollOnce().opened, "the cursor is in Postgres, not in the process")
    }

    @Test fun `a rewritten export sends only its new lines`() {
        export.searches += Picasso.search("search-1"); export.snapshot()
        val w = watcher(); w.pollOnce()
        export.searches += Picasso.search("search-2"); export.snapshot()
        assertEquals(1, w.pollOnce().opened)
        assertEquals("ep:run-A:search-2", signals.opened.last().first)
    }

    @Test fun `a new run in the same directory is new symptoms`() {
        export.searches += Picasso.search("search-1"); export.snapshot()
        val w = watcher(); w.pollOnce()
        export.runId = "run-B"; export.snapshot()
        assertEquals(1, w.pollOnce().opened)
        assertEquals("search:run-B:search-1", signals.opened.last().second.eventId)
    }

    @Test fun `a merge rule puts one robot and order into one episode`() {
        withRules("""[{"kinds":["SEARCH","INCIDENT"],"by":["robotId","jobOrderId"]}]""")
        export.incidents += Picasso.incident("incident-1")
        export.searches += Picasso.search("search-1")
        export.searches += Picasso.search("search-2")
        export.snapshot()
        watcher().pollOnce()
        assertEquals(
            listOf("ep:run-A:by:robotId:hum-02:jobOrderId:PATROL-1", "ep:run-A:by:robotId:hum-02:jobOrderId:PATROL-1",
                "ep:run-A:by:robotId:hum-03:jobOrderId:PATROL-2"),
            signals.opened.map { it.first },
        )
    }

    @Test fun `rules that disagree merge nothing and leave a cross-reference`() {
        withRules("""[{"kinds":["SEARCH"],"by":["robotId"]},{"kinds":["SEARCH"],"by":["jobOrderId"]}]""")
        export.searches += Picasso.search("search-1"); export.snapshot()
        watcher().pollOnce()
        assertEquals("ep:run-A:search-1", signals.opened.single().first)
        val log = watch.logs(10).single { it.kind == "AMBIGUOUS" }
        assertTrue("run-A:by:robotId:hum-02" in log.detailJson && "run-A:by:jobOrderId:PATROL-1" in log.detailJson, log.detailJson)
    }

    @Test fun `an unusable policy merges nothing, and says so once`() {
        Files.writeString(policy, "version: [")
        export.searches += Picasso.search("search-1"); export.snapshot()
        val w = watcher(); w.pollOnce(); w.pollOnce()
        assertEquals("ep:run-A:search-1", signals.opened.single().first)
        assertEquals(1, watch.logs(10).count { it.kind == "POLICY_UNUSABLE" })
    }

    @Test fun `a failed signal moves nothing - the next poll sends it again`() {
        export.searches += Picasso.search("search-1"); export.snapshot()
        val w = watcher()
        signals.failOpen = IllegalStateException("temporal down")
        assertEquals(0, w.pollOnce().opened)
        signals.failOpen = null
        assertEquals(1, w.pollOnce().opened)
        assertEquals(1, watch.logs(10).count { it.kind == "SIGNAL_FAILED" })
    }

    @Test fun `a source that changed under the cursor stops, it is not re-guessed`() {
        export.searches += Picasso.search("search-1"); export.searches += Picasso.search("search-2"); export.snapshot()
        val w = watcher(); w.pollOnce()
        export.searches.clear(); export.searches += Picasso.search("search-3"); export.searches += Picasso.search("search-4"); export.snapshot()
        assertEquals(0, w.pollOnce().opened)
        assertEquals(0, w.pollOnce().opened)
        assertEquals(1, watch.logs(10).count { it.kind == "STUCK" })
    }

    @Test fun `an incident without digest stops its file at that line`() {
        export.incidents += """{"incidentId":"incident-x","robotId":"hum-01","jobOrderId":"J"}"""
        export.incidents += Picasso.incident("incident-1")
        export.searches += Picasso.search("search-1")
        export.snapshot()
        watcher().pollOnce()
        assertEquals(listOf("ep:run-A:search-1"), signals.opened.map { it.first }, "the other file goes on")
        assertEquals(1, watch.logs(10).count { it.kind == "BROKEN" })
    }

    @Test fun `a line that is not JSON stops the whole export - nothing before it, nothing in the other file`() {
        export.incidents += Picasso.incident("incident-1")
        export.incidents += "{not json"
        export.searches += Picasso.search("search-1")
        export.snapshot()
        val w = watcher()
        assertEquals(0, w.pollOnce().opened)
        assertEquals(0, w.pollOnce().opened)
        assertEquals(1, watch.logs(10).count { it.kind == "BROKEN" })
    }

    @Test fun `an export without its manifest is not read`() {
        Files.writeString(export.dir.resolve("remedy-searches.jsonl"), Picasso.search("search-1") + "\n")
        assertEquals(0, watcher().pollOnce().opened)
    }
}
