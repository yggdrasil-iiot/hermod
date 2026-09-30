package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosisResponsesTest {
    private val req = Responses.request01()

    private fun errors(edit: com.fasterxml.jackson.databind.node.ObjectNode.() -> Unit): List<String> {
        val r = parseDiagnosisResponse(Responses.forRequest(req, edit = edit))
        assertIs<ResponseParse.Invalid>(r)
        return r.errors
    }
    private fun List<String>.has(fragment: String) = assertTrue(any { fragment in it }, "no error containing '$fragment' in $this")

    @Test fun `a complete response reads with every field`() {
        val r = parseDiagnosisResponse(Responses.forRequest(req))
        assertIs<ResponseParse.Valid>(r)
        val d = r.response
        assertEquals(DIAGNOSIS_CONTRACT_VERSION, d.contractVersion)
        assertEquals("ep:fixture-01-recommended", d.episodeId)
        assertEquals(1, d.attempt)
        assertEquals(DiagnosisOutcome.RECOMMENDED, d.outcome)
        assertNull(d.picked)
        assertEquals("재고는 있고 경로만 막혔다 [출처: 결품 대응 절차, 3.2]", d.rationale)
        assertEquals("AMR 이송 지연", d.cause)
        assertEquals(emptyList(), d.uncitedSentences)
        assertEquals(emptyList(), d.unverifiedClaims)
        assertEquals("APPROVE_REMEDY:hum-02:PATROL-1:pick_place", d.candidateId)
        assertEquals(req.candidatesVersion, d.sawCandidatesVersion)
        assertEquals(listOf(CardLine("절차", "대체 위치에서 집는다")), d.card)
        assertEquals("claude-sonnet-5", d.versions.modelId)
        assertNull(d.versions.promptVersion)
        assertEquals(12.5, d.elapsedSeconds)
        assertEquals(1, d.citations.size)
        assertTrue(d.clean)
    }

    @Test fun `an unreadable response keeps its wire form too`() {
        val node = Responses.forRequest(req) { put("outcome", "MAYBE") }
        val r = parseDiagnosisResponse(node)
        assertIs<ResponseParse.Invalid>(r)
        assertEquals(node, r.raw)
        node.put("outcome", "NO_GROUNDS")
        assertEquals("MAYBE", r.raw.get("outcome").textValue())
    }

    @Test fun `the raw response is kept verbatim, extra keys included`() {
        val node = Responses.forRequest(req) { put("futureField", 1) }
        val d = (parseDiagnosisResponse(node) as ResponseParse.Valid).response
        assertEquals(node, d.raw)
        node.put("outcome", "UNCITED")
        assertEquals("RECOMMENDED", d.raw.get("outcome").textValue())
    }

    @Test fun `clean needs a reason and nothing unchecked`() {
        fun clean(edit: com.fasterxml.jackson.databind.node.ObjectNode.() -> Unit) =
            (parseDiagnosisResponse(Responses.forRequest(req, edit = edit)) as ResponseParse.Valid).response.clean
        assertFalse(clean { putNull("rationale") })
        assertFalse(clean { putArray("uncitedSentences").add("근거 없는 문장") })
        assertFalse(clean { putArray("unverifiedClaims").addObject().put("kind", "NUMBER").putNull("text").putNull("foundIn") })
    }

    @Test fun `keys are never omitted`() {
        errors { remove("picked") }.has("picked: missing")
        errors { remove("uncitedSentences") }.has("uncitedSentences: missing")
        errors { remove("versions") }.has("versions: missing")
    }

    @Test fun `the two lists are never null`() {
        errors { putNull("uncitedSentences") }.has("uncitedSentences: expected a list")
        errors { putNull("unverifiedClaims") }.has("unverifiedClaims: expected a list")
    }

    @Test fun `candidateId belongs to RECOMMENDED only and picked to OUT_OF_CANDIDATES only`() {
        errors { put("candidateId", null as String?) }.has("candidateId: RECOMMENDED needs a candidate id")
        errors { put("outcome", "NO_GROUNDS") }.has("candidateId: must be null unless RECOMMENDED")
        errors { put("picked", "B-07") }.has("picked: must be null unless OUT_OF_CANDIDATES")
        val oc = parseDiagnosisResponse(Responses.forRequest(req, outcome = "OUT_OF_CANDIDATES", picked = ""))
        assertIs<ResponseParse.Valid>(oc)
        assertEquals("", oc.response.picked)
    }

    @Test fun `values have to have their shapes`() {
        errors { put("outcome", "MAYBE") }.has("outcome: expected one of")
        errors { put("attempt", 0) }.has("attempt: expected an integer >= 1")
        errors { put("sawCandidatesVersion", "sha256:ABC") }.has("sawCandidatesVersion: expected sha256:")
        errors { put("rationale", "") }.has("rationale: expected null or a non-blank string")
        errors { putArray("card").addObject().put("label", "절차") }.has("card[0].text")
        errors { putArray("unverifiedClaims").addObject().put("kind", "GUESS").putNull("text").putNull("foundIn") }
            .has("unverifiedClaims[0].kind")
        errors { putArray("unverifiedClaims").addObject().put("kind", "NUMBER").putNull("text").put("foundIn", "evidence") }
            .has("unverifiedClaims[0].foundIn: expected null or a list of strings")
        errors { putArray("unverifiedClaims").addObject().put("kind", "CITATION").put("text", "SOP-07").putArray("foundIn").add("evidence") }
            .has("unverifiedClaims[0].foundIn: a CITATION claim has foundIn null")
        errors { putArray("citations").add("SOP-07") }.has("citations[0]: expected an object")
        errors { put("outcome", "OUT_OF_CANDIDATES"); putNull("candidateId"); put("picked", 7) }.has("picked: expected null or a string")
        errors { (get("versions") as com.fasterxml.jackson.databind.node.ObjectNode).remove("narratorCommit") }
            .has("versions.narratorCommit: missing")
        errors { put("elapsedSeconds", -1) }.has("elapsedSeconds: expected a number >= 0")
    }

    @Test fun `every violation is reported and a non-object is rejected`() {
        val e = errors { put("outcome", "MAYBE"); put("attempt", 0) }
        e.has("outcome"); e.has("attempt")
        val r = parseDiagnosisResponse(mapper.readTree("[1]"))
        assertIs<ResponseParse.Invalid>(r)
        r.errors.has("response: expected an object")
        assertEquals(mapper.readTree("[1]"), r.raw)
    }
}
