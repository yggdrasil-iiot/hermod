package koshei.episode.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperatorCardsTest {
    private val json = ObjectMapper()
    private val version = "sha256:" + "a".repeat(64)

    private val remedy = """{"candidateId":"APPROVE_REMEDY:hum-02:PATROL-1:pick_place","kind":"APPROVE_REMEDY",
        "ref":{"robotId":"hum-02","jobOrderId":"PATROL-1","searchId":"search-7"},"sawSkillTypes":["pick_place"]}"""
    private val source = """{"candidateId":"CHOOSE_SOURCE:PATROL-1:B-07","kind":"CHOOSE_SOURCE",
        "ref":{"jobOrderId":"PATROL-1","material":"M-1","missingSource":"B-03","alternative":"B-07","searchId":"search-7"}}"""
    private val decision = """{"candidateId":"OPERATOR_DECISION:exec-1:u-2:CONFIRM_DONE","kind":"OPERATOR_DECISION",
        "ref":{"executionId":"exec-1","unitId":"u-2","decision":"CONFIRM_DONE"}}"""

    private val response = """
        {"contractVersion":"0.6","episodeId":"ep:x/r1","attempt":1,"outcome":"RECOMMENDED","candidateId":"APPROVE_REMEDY:hum-02:PATROL-1:pick_place",
         "picked":null,"sawCandidatesVersion":"$version","rationale":"다시 한다 [출처: SOP-01, §5.1].",
         "card":[{"label":"절차","text":"먼저 본다."},{"label":"금지","text":"반복하지 않는다."}],
         "cause":"## 원인 후보 — 기록만 한다",
         "citations":[{"title":"SOP-01","section":"§5.1","verified":true,"provenance_tier":"authored","provenance_mark":""}],
         "unverifiedClaims":[{"kind":"CITATION","text":"SOP-99 §2","foundIn":null},{"kind":"CITATION","text":null,"foundIn":null},
                             {"kind":"NUMBER","text":"30초","foundIn":["SOP-01"]},
                             {"kind":"NUMBER","text":null,"foundIn":null},{"kind":"NUMBER","text":null,"foundIn":null}],
         "uncitedSentences":["근거 없는 문장."],
         "versions":{"modelId":"m","promptVersion":"p","corpusVersion":"c","searchFingerprint":"s","narratorCommit":"n"},"elapsedSeconds":1.0}
    """.trimIndent()

    private fun view(phase: String, candidate: String? = remedy, diagnosis: String? = response, unknowns: String = "[]") = EpisodeView(
        instanceId = "ep:x/r1", phase = phase, attempt = 1, proposalId = "ep:x/r1#1", candidateId = "c",
        candidatesVersion = version, stateDeadlineMillis = 1_000L, unknownsJson = unknowns,
        candidateJson = candidate, diagnosisJson = diagnosis,
    )

    private fun opened(seq: Long, eventId: String) = StoredEvent(seq, "OPENED", """{"eventId":"$eventId","kind":"SEARCH","line":{"searchId":"$eventId"}}""")
    private fun requested(seq: Long, attempt: Int) = StoredEvent(seq, "DIAGNOSIS_REQUESTED", """{"attempt":$attempt,"candidatesVersion":"$version"}""")
    private fun joined(seq: Long, eventId: String) = StoredEvent(seq, "SYMPTOM_JOINED", """{"eventId":"$eventId","kind":"SEARCH","line":{"searchId":"$eventId"}}""")

    @Test fun `a proposal awaiting approval shows what the person decides on, in §9-5 order`() {
        val card = operatorCard(view("AWAITING_APPROVAL"), listOf(opened(1, "s-1")))
        assertEquals("APPROVE_REMEDY", card.proposal!!.kind)
        assertEquals("hum-02", card.proposal!!.ref["robotId"])
        assertEquals("다시 한다 [출처: SOP-01, §5.1].", card.rationale)
        assertFalse(card.rationaleMissing)
        assertEquals(listOf("절차" to "먼저 본다.", "금지" to "반복하지 않는다."), card.guidance.map { it.label to it.text })
        assertEquals("SOP-01", card.citations.single()["title"].textValue())
        assertEquals(listOf("근거 없는 문장."), card.uncitedSentences)
        assertEquals(listOf("SOP-99 §2"), card.unverifiedCitations.map { it.text })
        assertEquals(1, card.unlocatedCitations)
        assertEquals(listOf("30초"), card.unverifiedNumbers.map { it.text })
        assertEquals(listOf("SOP-01"), card.unverifiedNumbers.single().foundIn)
        assertEquals(2, card.unlocatedNumbers)
        assertEquals(CardDecision("decide", "ep:x/r1#1", version, 1_000L), card.decision)
        assertNull(card.precondition)
        assertEquals(listOf("s-1"), card.symptoms.map { it["searchId"].textValue() })
        assertEquals("SELF_ASSERTED", card.identityAssurance)
        assertFalse("원인" in json.writeValueAsString(card), "cause is recorded, never shown (§9.5)")
    }

    @Test fun `unknowns come first and a missing rationale is said, not hidden`() {
        val noReason = response.replace("\"rationale\":\"다시 한다 [출처: SOP-01, §5.1].\"", "\"rationale\":null")
        val unknowns = """[{"subject":{"robotId":"hum-02"},"what":"OBSERVATION_ABSENT","since":null,"source":"x"}]"""
        val card = operatorCard(view("AWAITING_APPROVAL", diagnosis = noReason, unknowns = unknowns), emptyList())
        assertEquals("OBSERVATION_ABSENT", card.unknowns.single()["what"].textValue())
        assertNull(card.rationale)
        assertTrue(card.rationaleMissing)
    }

    @Test fun `an unknown remedy precondition shows the proposition and the symptoms that joined after the snapshot`() {
        val events = listOf(opened(1, "s-1"), joined(2, "s-2"), requested(3, 1), joined(7, "s-3"))
        val card = operatorCard(view("UNKNOWN_PRECONDITION"), events)
        assertEquals("(hum-02, PATROL-1)에 searchId search-7 뒤로 더 새 탐색 줄이 없다", card.precondition)
        assertEquals(listOf("s-3"), card.joinedAfterSnapshot.map { it["searchId"].textValue() })
        assertEquals(CardDecision("confirm", "ep:x/r1#1", version, 1_000L), card.decision)
    }

    @Test fun `the other kinds' propositions are filled from their refs`() {
        assertEquals(
            "주문 PATROL-1 의 자재 M-1 이 아직 결품이고 대체 위치 B-07 가 아직 쓸 수 있다",
            operatorCard(view("UNKNOWN_PRECONDITION", candidate = source), emptyList()).precondition,
        )
        assertEquals(
            "실행 exec-1 의 단위 u-2 가 아직 운영자 보류이고 판단이 나지 않았다",
            operatorCard(view("UNKNOWN_PRECONDITION", candidate = decision), emptyList()).precondition,
        )
    }

    @Test fun `a proposition is never invented - a missing ref value leaves none, and no confirm decision`() {
        val unnamed = remedy.replace("\"searchId\":\"search-7\"", "\"searchId\":null")
        val card = operatorCard(view("UNKNOWN_PRECONDITION", candidate = unnamed), emptyList())
        assertNull(card.precondition)
        assertNull(card.decision)
    }

    @Test fun `outside approval and precondition there is no decision to make`() {
        for (phase in listOf("DIAGNOSING", "DISPATCHED", "AWAITING_EVIDENCE", "UNKNOWN_OUTCOME", "ESCALATED", "CLOSED")) {
            assertNull(operatorCard(view(phase), emptyList()).decision, phase)
        }
    }

    @Test fun `with no candidate and no diagnosis the card holds the facts only`() {
        val card = operatorCard(view("ESCALATED", candidate = null, diagnosis = null).copy(attempt = null, proposalId = null), listOf(opened(1, "s-1")))
        assertNull(card.proposal)
        assertTrue(card.guidance.isEmpty())
        assertFalse(card.rationaleMissing)
        assertNull(card.decision)
        assertEquals(listOf("s-1"), card.symptoms.map { it["searchId"].textValue() })
    }

    @Test fun `an episode withheld for a person shows the facts and no earlier diagnosis`() {
        val v = view("ESCALATED").copy(escalationReason = "WITHHELD_HUMAN_FIRST")
        val card = operatorCard(v, listOf(opened(1, "s-1"), joined(2, "s-2")))
        assertNull(card.rationale)
        assertTrue(card.guidance.isEmpty())
        assertTrue(card.citations.isEmpty())
        assertFalse(card.rationaleMissing)
        assertEquals(2, card.symptoms.size)
    }

    @Test fun `an unreadable diagnosis leaves the narrator fields empty instead of failing`() {
        val card = operatorCard(view("AWAITING_APPROVAL", diagnosis = """{"contractVersion":"0.6"}"""), emptyList())
        assertTrue(card.guidance.isEmpty())
        assertTrue(card.responseUnreadable)
        assertFalse(card.rationaleMissing)
    }
}
