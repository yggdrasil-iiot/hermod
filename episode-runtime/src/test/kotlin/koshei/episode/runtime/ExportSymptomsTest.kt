package koshei.episode.runtime

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ExportSymptomsTest {
    private val run1: Path = Path.of(checkNotNull(javaClass.getResource("/picasso/run-1/manifest.json")).toURI()).parent
    private val runId = "run-2026-09-22T16:47:37.854173400Z-1"

    @Test fun `run-1 reads as nine incidents and four searches`() {
        val r = assertIs<BundleRead.Ready>(ExportSymptoms.read(run1))
        assertEquals(runId, r.runId)
        assertEquals(9, r.lines.getValue(ExportKind.INCIDENT.file.name).size)
        assertEquals(4, r.lines.getValue(ExportKind.SEARCH.file.name).size)
    }

    @Test fun `a search is keyed by run and searchId, an incident by run and digest`() {
        val r = assertIs<BundleRead.Ready>(ExportSymptoms.read(run1))
        val search = r.lines.getValue(ExportKind.SEARCH.file.name).first()
        val s = ExportSymptoms.symptom(ExportKind.SEARCH, r, search, observedAtMillis = 7)!!
        assertEquals("search:$runId:search-1", s.eventId)
        assertEquals("SEARCH", s.kind)
        assertEquals(search.text, s.lineJson)
        assertEquals(r.manifestJson, s.manifestJson)
        assertEquals(7, s.observedAtMillis)
        val incident = r.lines.getValue(ExportKind.INCIDENT.file.name).first()
        assertEquals("incident:$runId:d04ac2a20a63afc2ba147218bae871ef69746c990e3993deb00cfb4ddd262143",
            ExportSymptoms.symptom(ExportKind.INCIDENT, r, incident, 0)!!.eventId)
    }

    @Test fun `an incident without a digest has no symptom`() {
        val line = BundleLine(1, "incident-x", """{"incidentId":"incident-x"}""", strictJson.readTree("""{"incidentId":"incident-x"}"""))
        val r = BundleRead.Ready("run", "{}", emptyMap())
        assertNull(ExportSymptoms.symptom(ExportKind.INCIDENT, r, line, 0))
        val numeric = """{"incidentId":"incident-x","digest":5}"""
        assertNull(ExportSymptoms.symptom(ExportKind.INCIDENT, r, BundleLine(1, "incident-x", numeric, strictJson.readTree(numeric)), 0))
    }
}
