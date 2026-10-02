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
}
