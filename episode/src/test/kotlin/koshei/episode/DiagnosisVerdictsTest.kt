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

    @Test fun `a verdict carries the response it was judged from`() {
        val proposed = judge(req01, Responses.forRequest(req01))
        assertIs<DiagnosisVerdict.Proposed>(proposed)
        assertEquals("claude-sonnet-5", proposed.response.versions.modelId)
        assertEquals(1, proposed.response.citations.size)

        val escalated = judge(req01, Responses.forRequest(req01, outcome = "NO_GROUNDS"))
        assertIs<DiagnosisVerdict.Escalate>(escalated)
        assertEquals(DiagnosisOutcome.NO_GROUNDS, escalated.response!!.outcome)
        assertNull(escalated.raw, "raw is only for an unreadable response")

        val node = Responses.forRequest(req01) { put("outcome", "MAYBE") }
        val failed = judge(req01, node)
        assertIs<DiagnosisVerdict.Escalate>(failed)
        assertNull(failed.response)
        assertEquals(node, failed.raw)
        (failed.raw as com.fasterxml.jackson.databind.node.ObjectNode).put("outcome", "RECOMMENDED")
        assertEquals("MAYBE", failed.raw!!.get("outcome").textValue())
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
        val saw = "sha256:" + "0".repeat(64)
        val v = judge(req01, Responses.forRequest(req01) { put("sawCandidatesVersion", saw) })
        assertIs<DiagnosisVerdict.Escalate>(v)
        assertEquals(DiagnosisEscalation.CANDIDATES_VERSION_MISMATCH, v.reason)
        assertEquals("saw $saw, sent ${req01.candidatesVersion}", v.detail)
    }

    @Test fun `a foreign response with a different candidates version fails on identity first`() {
        val v = judge(req01, Responses.forRequest(req01) { put("episodeId", "ep:other"); put("sawCandidatesVersion", "sha256:" + "0".repeat(64)) })
        assertEquals(DiagnosisEscalation.DIAGNOSIS_FAILED, escalation(v))
    }

    @Test fun `RECOMMENDED without a verified citation is UNCITED - koshei re-checks contract rule 3`() {
        fun reason(edit: com.fasterxml.jackson.databind.node.ObjectNode.() -> Unit): DiagnosisVerdict.Escalate {
            val v = judge(req01, Responses.forRequest(req01, edit = edit))
            assertIs<DiagnosisVerdict.Escalate>(v)
            assertEquals(DiagnosisEscalation.UNCITED, v.reason)
            return v
        }
        val none = reason { putArray("citations") }
        assertEquals("RECOMMENDED without a verified citation (contract §4 rule 3)", none.detail)
        reason { putArray("citations").addObject().put("title", "결품 대응 절차").put("section", "3.2").put("verified", false) }
        reason { putArray("citations").addObject().put("title", "결품 대응 절차").put("section", "3.2").put("verified", "true") }
        reason { putArray("citations").addObject().put("title", "결품 대응 절차").put("section", "3.2").put("verified", 1) }
        reason { putArray("citations").addObject().put("title", "결품 대응 절차").put("section", "3.2") }
    }

    @Test fun `one verified citation among unverified ones is enough`() {
        val v = judge(req01, Responses.forRequest(req01) {
            putArray("citations").apply {
                addObject().put("title", "SOP-07").put("section", "1").put("verified", false)
                addObject().put("title", "결품 대응 절차").put("section", "3.2").put("verified", true)
            }
        })
        assertIs<DiagnosisVerdict.Proposed>(v)
    }

    @Test fun `ESCALATE recommended goes to a person even without a verified citation`() {
        val v = judge(req01, Responses.forRequest(req01, candidateId = "ESCALATE") { putArray("citations") })
        assertEquals(DiagnosisEscalation.ESCALATE_RECOMMENDED, escalation(v))
    }

    @Test fun `an id is matched exactly - no trimming, no case folding`() {
        val offered = "APPROVE_REMEDY:hum-02:PATROL-1:pick_place"
        for (id in listOf(" $offered", "$offered ", "escalate")) {
            val v = judge(req01, Responses.forRequest(req01, candidateId = id))
            assertIs<DiagnosisVerdict.Escalate>(v, "for '$id'")
            assertEquals(DiagnosisEscalation.OUT_OF_CANDIDATES, v.reason, "for '$id'")
            assertEquals(id, v.picked)
        }
    }

    @Test fun `a RECOMMENDED response without an id fails the diagnosis instead of crashing`() {
        val d = (parseDiagnosisResponse(Responses.forRequest(req01)) as ResponseParse.Valid).response
        val broken = DiagnosisResponse(
            d.contractVersion, d.episodeId, d.attempt, d.outcome, null, d.picked, d.sawCandidatesVersion, d.rationale, d.card,
            d.cause, d.citations, d.unverifiedClaims, d.uncitedSentences, d.versions, d.elapsedSeconds, d.raw,
        )
        assertEquals(DiagnosisEscalation.DIAGNOSIS_FAILED, escalation(judgeDiagnosis(req01, ResponseParse.Valid(broken))))
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
        assertIs<DiagnosisVerdict.Escalate>(v)
        assertEquals(DiagnosisEscalation.UNKNOWN_BLOCKS_EXECUTION, v.reason)
        assertNull(v.picked, "picked is for OUT_OF_CANDIDATES only")
        assertEquals(remedy.candidateId, v.response!!.candidateId)
    }
}
