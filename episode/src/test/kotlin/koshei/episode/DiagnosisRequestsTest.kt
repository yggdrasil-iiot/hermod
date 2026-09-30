package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiagnosisRequestsTest {
    private val snapshot = PicassoRun1.snapshot(incidentIds = listOf("incident-1"), searchIds = listOf("search-1"))

    @Test fun `top-level keys follow the contract order`() {
        val r = diagnosisRequest("ep:t", 1, snapshot)
        assertEquals(
            listOf("contractVersion", "episodeId", "attempt", "snapshot", "candidates", "candidatesVersion", "unknowns", "history"),
            r.fieldNames().asSequence().toList(),
        )
        assertEquals("0.6", r.get("contractVersion").textValue())
        assertEquals("ep:t", r.get("episodeId").textValue())
        assertEquals(1, r.get("attempt").intValue())
    }

    @Test fun `snapshot is carried verbatim`() {
        assertEquals(snapshot.toJson(), diagnosisRequest("ep:t", 1, snapshot).get("snapshot"))
    }

    @Test fun `candidates and candidatesVersion agree`() {
        val r = diagnosisRequest("ep:t", 1, snapshot)
        val ids = r.get("candidates").map { it.get("candidateId").textValue() }
        assertEquals(listOf("APPROVE_REMEDY:hum-02:PATROL-1:pick_place", "ESCALATE"), ids)
        assertEquals(candidatesVersion(projectCandidates(snapshot, emptyList())), r.get("candidatesVersion").textValue())
    }

    @Test fun `the contract's reading rules hold on every request`() {
        val r = diagnosisRequest("ep:t", 1, PicassoRun1.snapshot(incidentIds = listOf("incident-6"), searchIds = listOf("search-4")))
        val ids = r.get("candidates").map { it.get("candidateId").textValue() }
        assertTrue(ids.isNotEmpty() && "ESCALATE" in ids)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(Regex("sha256:[0-9a-f]{64}").matches(r.get("candidatesVersion").textValue()))
        r.get("unknowns").forEach { assertEquals(listOf("subject", "what", "since", "source"), it.fieldNames().asSequence().toList()) }
        assertTrue(r.get("history").isArray && r.get("history").size() == 0)
    }

    @Test fun `episode-level unknowns are reported and block execution`() {
        val outcome = Unknown(mapOf("robotId" to "hum-02"), UnknownWhat.OUTCOME, null, "picasso")
        val r = diagnosisRequest("ep:t", 2, snapshot, episodeUnknowns = listOf(outcome))
        assertEquals(listOf("ESCALATE"), r.get("candidates").map { it.get("candidateId").textValue() })
        assertEquals("OUTCOME", r.get("unknowns").get(0).get("what").textValue())
    }

    @Test fun `a blank episode id or an attempt below 1 is rejected`() {
        assertFailsWith<IllegalArgumentException> { diagnosisRequest(" ", 1, snapshot) }
        assertFailsWith<IllegalArgumentException> { diagnosisRequest("ep:t", 0, snapshot) }
    }
}
