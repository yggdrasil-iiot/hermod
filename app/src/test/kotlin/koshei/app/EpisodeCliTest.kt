package koshei.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EpisodeCliTest {
    private val export: Path = Path.of(checkNotNull(javaClass.getResource("/picasso/run-1/manifest.json")).toURI()).parent
    private val runId = "run-2026-09-22T16:47:37.854173400Z-1"

    private fun open(vararg args: String) = EpisodeCli.symptom(EpisodeCli.parseOpen(arrayOf("--export", export.toString(), *args)))

    @Test fun `a search line becomes a symptom keyed like the watcher's`() {
        val s = open("--search", "search-1")
        assertEquals("SEARCH", s.signal.kind)
        assertEquals("search:$runId:search-1", s.signal.eventId)
        assertEquals("ep:$runId:search-1", s.workflowId)
        assertTrue(s.signal.manifestJson!!.contains(runId))
        assertTrue(s.signal.lineJson.contains("\"searchId\""))
    }

    @Test fun `an incident line is keyed by its digest`() {
        val s = open("--incident", "incident-1")
        assertEquals("INCIDENT", s.signal.kind)
        assertEquals("incident:$runId:d04ac2a20a63afc2ba147218bae871ef69746c990e3993deb00cfb4ddd262143", s.signal.eventId)
    }

    @Test fun `a key names the workflow`() = assertEquals("ep:k-1", open("--search", "search-1", "--key", "k-1").workflowId)

    @Test fun `what cannot be read is refused, never guessed`() {
        assertFailsWith<IllegalArgumentException> { EpisodeCli.symptom(EpisodeCli.parseOpen(arrayOf("--export", Files.createTempDirectory("empty").toString(), "--search", "search-1"))) }
        assertFailsWith<IllegalArgumentException> { open("--search", "nope") }
        assertFailsWith<IllegalArgumentException> { open("--search", "search-1", "--key", "a/b") }
        assertFailsWith<IllegalArgumentException> { open("--search", "search-1", "--key", "a?b") }
        assertFailsWith<IllegalArgumentException> { open("--search", "search-1", "--incident", "incident-1") }
        assertFailsWith<IllegalArgumentException> { open() }
        val noRun = Files.createTempDirectory("norun")
        Files.writeString(noRun.resolve("manifest.json"), """{"schemaVersion":"5"}""")
        Files.copy(export.resolve("remedy-searches.jsonl"), noRun.resolve("remedy-searches.jsonl"))
        assertFailsWith<IllegalArgumentException> { EpisodeCli.symptom(EpisodeCli.parseOpen(arrayOf("--export", noRun.toString(), "--search", "search-1"))) }
    }

    /** A hand-made export: [manifest] (none if null) and the two jsonl files, lines joined by LF. */
    private fun exportOf(manifest: String? = """{"schemaVersion":"5","runId":"r1"}""", searches: List<String> = emptyList(), incidents: List<String> = emptyList()): Path {
        val dir = Files.createTempDirectory("export")
        if (manifest != null) Files.writeString(dir.resolve("manifest.json"), manifest)
        Files.writeString(dir.resolve("remedy-searches.jsonl"), searches.joinToString("\n"))
        Files.writeString(dir.resolve("incidents.jsonl"), incidents.joinToString("\n"))
        return dir
    }

    private fun openIn(dir: Path, vararg args: String) = EpisodeCli.symptom(EpisodeCli.parseOpen(arrayOf("--export", dir.toString(), *args)))

    private val s1 = """{"searchId":"s1","outcome":"FOUND"}"""

    @Test fun `the lines are not read before the manifest is there`() {
        val dir = Files.createTempDirectory("nomanifest")
        Files.copy(export.resolve("remedy-searches.jsonl"), dir.resolve("remedy-searches.jsonl"))
        assertFailsWith<IllegalArgumentException> { openIn(dir, "--search", "search-1") }
    }

    @Test fun `the line and the manifest travel as written, the start carrying the same manifest`() {
        val line = """{ "searchId" : "s1",  "outcome": "FOUND", "score": 1.50 }"""
        val manifest = """{ "schemaVersion": "5", "runId": "r1" }"""
        val s = openIn(exportOf(manifest, searches = listOf("""{"searchId":"s0"}""", line)), "--search", "s1")
        assertEquals(line, s.signal.lineJson)
        assertEquals(manifest, s.signal.manifestJson)
        assertEquals(s.signal.manifestJson, s.start.manifestJson)
        assertTrue(s.signal.observedAtMillis > 0)
    }

    @Test fun `a key cannot carry what the control plane's path refuses`() {
        assertFailsWith<IllegalArgumentException> { open("--search", "search-1", "--key", "a%b") }
        assertFailsWith<IllegalArgumentException> { open("--search", "search-1", "--key", "a;b") }
    }

    @Test fun `a run id the key cannot carry needs a key`() {
        val dir = exportOf("""{"schemaVersion":"5","runId":"r/1"}""", searches = listOf(s1))
        assertFailsWith<IllegalArgumentException> { openIn(dir, "--search", "s1") }
        val s = openIn(dir, "--search", "s1", "--key", "k")
        assertEquals("ep:k", s.workflowId)
        assertEquals("search:r/1:s1", s.signal.eventId)
    }

    @Test fun `a blank run id is refused`() {
        assertFailsWith<IllegalArgumentException> { openIn(exportOf("""{"schemaVersion":"5","runId":"  "}""", searches = listOf(s1)), "--search", "s1") }
    }

    @Test fun `two lines with the same id are refused, not picked between`() {
        assertFailsWith<IllegalArgumentException> { openIn(exportOf(searches = listOf(s1, """{"searchId":"s1","outcome":"NONE"}""")), "--search", "s1") }
    }

    @Test fun `an incident without a digest is refused`() {
        assertFailsWith<IllegalArgumentException> { openIn(exportOf(incidents = listOf("""{"incidentId":"i1"}""")), "--incident", "i1") }
        assertFailsWith<IllegalArgumentException> { openIn(exportOf(incidents = listOf("""{"incidentId":"i1","digest":""}""")), "--incident", "i1") }
    }

    @Test fun `an unknown flag is refused`() {
        assertFailsWith<IllegalArgumentException> { open("--search", "search-1", "--bogus", "x") }
    }

    @Test fun `duplicate keys are refused as the core refuses them`() {
        assertFailsWith<IllegalArgumentException> { openIn(exportOf(searches = listOf("""{"searchId":"other","searchId":"s1"}""")), "--search", "s1") }
        assertFailsWith<IllegalArgumentException> { openIn(exportOf("""{"runId":"r0","runId":"r1"}""", searches = listOf(s1)), "--search", "s1") }
    }

    @Test fun `a BOM is named, and refused`() {
        val bom = Char(0xFEFF).toString()
        val manifest = assertFailsWith<IllegalArgumentException> { openIn(exportOf(bom + """{"runId":"r1"}""", searches = listOf(s1)), "--search", "s1") }
        assertTrue(manifest.message!!.contains("starts with a UTF-8 BOM"), manifest.message)
        val line = assertFailsWith<IllegalArgumentException> { openIn(exportOf(searches = listOf(bom + s1)), "--search", "s1") }
        assertTrue(line.message!!.contains("starts with a UTF-8 BOM"), line.message)
    }

    @Test fun `agent-off takes only an episode's workflow id`() {
        assertEquals("ep:a", EpisodeCli.checkWorkflowId("ep:a"))
        assertFailsWith<IllegalArgumentException> { EpisodeCli.checkWorkflowId("a") }
        assertFailsWith<IllegalArgumentException> { EpisodeCli.checkWorkflowId("ep:") }
        assertFailsWith<IllegalArgumentException> { EpisodeCli.checkWorkflowId("ep:a/b") }
    }
}
