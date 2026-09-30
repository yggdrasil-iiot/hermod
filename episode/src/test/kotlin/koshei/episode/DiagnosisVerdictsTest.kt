package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosisVerdictsTest {
    private val req01 = Responses.request01()
    private val req04 = Responses.request04()

    private fun judge(req: DiagnosisRequest, node: com.fasterxml.jackson.databind.node.ObjectNode) =
        judgeDiagnosis(req, parseDiagnosisResponse(node))

    private fun escalation(v: DiagnosisVerdict): DiagnosisEscalation {
        assertIs<DiagnosisVerdict.Escalate>(v)
        return v.reason
    }

    @Test fun `a clean recommendation of an offered candidate is proposed`() {
        val v = judge(req01, Responses.forRequest(req01))
        assertIs<DiagnosisVerdict.Proposed>(v)
        assertEquals("APPROVE_REMEDY:hum-02:PATROL-1:pick_place", v.candidate.candidateId)
        assertTrue(v.clean)
    }

    @Test fun `a recommendation without a reason is proposed but not clean`() {
        val v = judge(req01, Responses.forRequest(req01) { putNull("rationale") })
        assertIs<DiagnosisVerdict.Proposed>(v)
        assertFalse(v.clean)
    }

    @Test fun `CONFIRM_DONE under an unknown is proposed - it resolves the unknown`() {
        val v = judge(req04, Responses.forRequest(req04, candidateId = "OPERATOR_DECISION:exec-8:RACK-204.S06:CONFIRM_DONE"))
        assertIs<DiagnosisVerdict.Proposed>(v)
        assertEquals(CandidateKind.OPERATOR_DECISION, v.candidate.kind)
    }

    @Test fun `ESCALATE recommended goes to a person`() {
        assertEquals(DiagnosisEscalation.ESCALATE_RECOMMENDED, escalation(judge(req01, Responses.forRequest(req01, candidateId = "ESCALATE"))))
    }

    @Test fun `NO_GROUNDS and UNCITED escalate under their own names`() {
        assertEquals(DiagnosisEscalation.NO_GROUNDS, escalation(judge(req01, Responses.forRequest(req01, outcome = "NO_GROUNDS"))))
        assertEquals(DiagnosisEscalation.UNCITED, escalation(judge(req01, Responses.forRequest(req01, outcome = "UNCITED"))))
    }

    @Test fun `OUT_OF_CANDIDATES carries what was picked, even an empty or null pick`() {
        val v = judge(req01, Responses.forRequest(req01, outcome = "OUT_OF_CANDIDATES", picked = "B-07"))
        assertIs<DiagnosisVerdict.Escalate>(v)
        assertEquals(DiagnosisEscalation.OUT_OF_CANDIDATES, v.reason)
        assertEquals("B-07", v.picked)
        val none = judge(req01, Responses.forRequest(req01, outcome = "OUT_OF_CANDIDATES", picked = null))
        assertIs<DiagnosisVerdict.Escalate>(none)
        assertNull(none.picked)
        val empty = judge(req01, Responses.forRequest(req01, outcome = "OUT_OF_CANDIDATES", picked = ""))
        assertIs<DiagnosisVerdict.Escalate>(empty)
        assertEquals("", empty.picked)
    }

    @Test fun `a recommended id koshei did not offer is out of candidates - both judges agree`() {
        val v = judge(req01, Responses.forRequest(req01, candidateId = "APPROVE_REMEDY:hum-99:X:pick_place"))
        assertIs<DiagnosisVerdict.Escalate>(v)
        assertEquals(DiagnosisEscalation.OUT_OF_CANDIDATES, v.reason)
        assertEquals("APPROVE_REMEDY:hum-99:X:pick_place", v.picked)
    }

    @Test fun `a different candidates version is a contract violation, not a retry`() {
        val v = judge(req01, Responses.forRequest(req01) { put("sawCandidatesVersion", "sha256:" + "0".repeat(64)) })
        assertEquals(DiagnosisEscalation.CANDIDATES_VERSION_MISMATCH, escalation(v))
    }

    @Test fun `an unreadable or foreign response is a failed diagnosis`() {
        val broken = judge(req01, Responses.forRequest(req01) { remove("versions") })
        assertIs<DiagnosisVerdict.Escalate>(broken)
        assertEquals(DiagnosisEscalation.DIAGNOSIS_FAILED, broken.reason)
        assertTrue(broken.detail!!.contains("versions"))
        assertEquals(DiagnosisEscalation.DIAGNOSIS_FAILED, escalation(judge(req01, Responses.forRequest(req01) { put("episodeId", "ep:other") })))
        assertEquals(DiagnosisEscalation.DIAGNOSIS_FAILED, escalation(judge(req01, Responses.forRequest(req01) { put("attempt", 2) })))
        assertEquals(DiagnosisEscalation.DIAGNOSIS_FAILED, escalation(judge(req01, Responses.forRequest(req01) { put("contractVersion", "0.5") })))
    }

    @Test fun `an execution candidate recommended under an unknown is a projection defect signal`() {
        // Projection never offers this combination; build the request by hand to exercise the second line of defence.
        val base = Responses.request04()
        val remedy = Candidate.approveRemedy("hum-07", "SEQ-6", listOf("pick_place"), "search-x")
        val candidates = (base.candidates + remedy).sortedBy { it.candidateId }
        val req = DiagnosisRequest(
            base.episodeId, base.attempt, base.snapshot, base.unknowns, candidates, candidatesVersion(candidates), emptyList(),
        )
        val v = judge(req, Responses.forRequest(req, candidateId = remedy.candidateId))
        assertEquals(DiagnosisEscalation.UNKNOWN_BLOCKS_EXECUTION, escalation(v))
    }
}
