package koshei.episode

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * narrator's four response fixtures (contract §8; narrator owns them, koshei keeps byte copies) read and judged against
 * koshei's own request fixtures. If narrator changes a response, recopy it; if koshei changes a request, narrator
 * rebuilds the response.
 */
class DiagnosisResponseFixturesTest {
    private fun dir(): File {
        val root = System.getProperty("koshei.contractFixturesDir")
        assertTrue(!root.isNullOrBlank(), "system property koshei.contractFixturesDir is not set")
        return File(root)
    }

    // Same inputs as DiagnosisRequestFixturesTest, which pins these requests byte for byte against requests/.
    private val inputs = mapOf(
        "01-recommended.json" to (listOf("incident-1") to listOf("search-1")),
        "02-no-grounds.json" to (emptyList<String>() to listOf("search-2")),
        "03-out-of-candidates.json" to (emptyList<String>() to listOf("search-4")),
        "04-unknown.json" to (listOf("incident-6") to emptyList<String>()),
    )

    private fun verdict(name: String): DiagnosisVerdict {
        val (incidents, searches) = inputs.getValue(name)
        val request = diagnosisRequest("ep:fixture-" + name.removeSuffix(".json"), 1, PicassoRun1.snapshot(incidents, searches))
        // Through the String entry point: the production path (the workflow hands the activity result over as text).
        val response = parseDiagnosisResponse(File(dir(), "responses/$name").readText(Charsets.UTF_8))
        assertIs<ResponseParse.Valid>(response, "$name does not read: $response")
        return judgeDiagnosis(request, response)
    }

    @Test fun `01 recommends the found remedy`() {
        val v = verdict("01-recommended.json")
        assertIs<DiagnosisVerdict.Proposed>(v)
        assertEquals(CandidateKind.APPROVE_REMEDY, v.candidate.kind)
        assertTrue(v.clean)
    }

    @Test fun `02 has no grounds`() {
        val v = verdict("02-no-grounds.json")
        assertIs<DiagnosisVerdict.Escalate>(v)
        assertEquals(DiagnosisEscalation.NO_GROUNDS, v.reason)
    }

    @Test fun `03 picks outside the candidates`() {
        val v = verdict("03-out-of-candidates.json")
        assertIs<DiagnosisVerdict.Escalate>(v)
        assertEquals(DiagnosisEscalation.OUT_OF_CANDIDATES, v.reason)
        assertEquals("SEQ-IN-05.BIN-A 로 옮겨 집는다", v.picked)
    }

    @Test fun `04 recommends the physical check under the unknown`() {
        val v = verdict("04-unknown.json")
        assertIs<DiagnosisVerdict.Proposed>(v)
        assertEquals("OPERATOR_DECISION:exec-8:RACK-204.S06:CONFIRM_DONE", v.candidate.candidateId)
    }
}
