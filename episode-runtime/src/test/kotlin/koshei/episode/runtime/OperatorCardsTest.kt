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

    // --- B3c chunk 1 review -----------------------------------------------------------------------------------------

    private val noGrounds = response
        .replace("\"outcome\":\"RECOMMENDED\",\"candidateId\":\"APPROVE_REMEDY:hum-02:PATROL-1:pick_place\"", "\"outcome\":\"NO_GROUNDS\",\"candidateId\":null")
        .replace("\"rationale\":\"다시 한다 [출처: SOP-01, §5.1].\"", "\"rationale\":\"근거가 모자라다.\"")
        .also { assertTrue("NO_GROUNDS" in it && "근거가 모자라다." in it, "the replacements took") }

    /** A DIAGNOSIS_RESULT record as `Diagnosing.kt` writes it; [responseJson] is the JSON put under `response`. */
    private fun result(seq: Long, attempt: Int, reason: String?, responseJson: String, detail: String? = null) = StoredEvent(
        seq, "DIAGNOSIS_RESULT",
        """{"attempt":$attempt,"verdict":"${if (reason == null) "PROPOSED" else "ESCALATE"}","candidateId":null,
            "reason":${reason?.let { "\"$it\"" } ?: "null"},"clean":null,"detail":${detail?.let { json.writeValueAsString(it) } ?: "null"},
            "response":$responseJson,"rawText":null}""",
    )

    /** An ESCALATED view after the attempt closed: no attempt, no candidate, no live diagnosis (the core's own shape). */
    private fun escalated(reason: String = "NO_GROUNDS") = view("ESCALATED", candidate = null, diagnosis = null)
        .copy(attempt = null, proposalId = null, candidatesVersion = null, escalationReason = reason)

    @Test fun `an escalated card reads its diagnosis from the latest DIAGNOSIS_RESULT record`() {
        val events = listOf(opened(1, "s-1"), requested(2, 1), result(3, 1, null, response), requested(4, 2), result(5, 2, "NO_GROUNDS", noGrounds))
        val card = operatorCard(escalated(), events.reversed())
        assertEquals("근거가 모자라다.", card.rationale)
        assertTrue(card.diagnosisFromRecord)
        assertEquals(listOf("절차", "금지"), card.guidance.map { it.label })
        assertFalse(card.responseUnreadable)
        assertNull(card.diagnosisRefused)
        assertNull(card.decision)
    }

    @Test fun `a recorded answer the judge refused for its identity or candidates version is not shown as narration`() {
        val otherEpisode = response.replace("\"episodeId\":\"ep:x/r1\"", "\"episodeId\":\"ep:other/r9\"")
        assertTrue("ep:other/r9" in otherEpisode)
        for ((reason, answer) in listOf("DIAGNOSIS_FAILED" to otherEpisode, "CANDIDATES_VERSION_MISMATCH" to response)) {
            val card = operatorCard(escalated(reason), listOf(opened(1, "s-1"), requested(2, 1), result(3, 1, reason, answer)))
            assertNull(card.rationale, reason)
            assertTrue(card.guidance.isEmpty(), reason)
            assertTrue(card.citations.isEmpty(), reason)
            assertTrue(card.uncitedSentences.isEmpty(), reason)
            assertFalse(card.rationaleMissing, reason)
            assertFalse(card.responseUnreadable, reason)
            assertEquals(reason, card.diagnosisRefused, reason)
            assertEquals(listOf("s-1"), card.symptoms.map { it["searchId"].textValue() }, reason)
        }
    }

    @Test fun `a recorded answer that cannot be read is said to be unreadable`() {
        for (answer in listOf("null", """{"contractVersion":"0.6"}""")) {
            val card = operatorCard(escalated("DIAGNOSIS_FAILED"), listOf(requested(1, 1), result(2, 1, "DIAGNOSIS_FAILED", answer)))
            assertTrue(card.responseUnreadable, answer)
            assertTrue(card.diagnosisFromRecord, answer)
            assertNull(card.diagnosisRefused, answer)
            assertTrue(card.guidance.isEmpty(), answer)
        }
        val missing = StoredEvent(2, "DIAGNOSIS_RESULT", """{"attempt":1,"verdict":"ESCALATE","reason":"DIAGNOSIS_FAILED"}""")
        assertTrue(operatorCard(escalated("DIAGNOSIS_FAILED"), listOf(requested(1, 1), missing)).responseUnreadable)
    }

    @Test fun `the record is read only for an escalated card without a live diagnosis`() {
        val events = listOf(requested(1, 1), result(2, 1, "NO_GROUNDS", noGrounds))
        val diagnosing = operatorCard(view("DIAGNOSING", candidate = null, diagnosis = null).copy(attempt = 2), events)
        assertNull(diagnosing.rationale)
        assertFalse(diagnosing.diagnosisFromRecord)
        val live = operatorCard(view("ESCALATED"), events)
        assertEquals("다시 한다 [출처: SOP-01, §5.1].", live.rationale)
        assertFalse(live.diagnosisFromRecord)
    }

    @Test fun `a withheld episode shows no proposal and reads no diagnosis from the record`() {
        val events = listOf(opened(1, "s-1"), requested(2, 1), result(3, 1, "NO_GROUNDS", noGrounds))
        val card = operatorCard(view("ESCALATED", diagnosis = null).copy(escalationReason = "WITHHELD_HUMAN_FIRST"), events)
        assertNull(card.proposal)
        assertNull(card.rationale)
        assertFalse(card.diagnosisFromRecord)
        assertNull(card.decision)
        assertEquals(1, card.symptoms.size)
    }

    @Test fun `facts that have not landed yet are flagged`() {
        val events = listOf(opened(1, "s-1"))
        assertTrue(operatorCard(view("AWAITING_APPROVAL").copy(symptoms = 2), events).factsBehind)
        assertFalse(operatorCard(view("AWAITING_APPROVAL").copy(symptoms = 1), events).factsBehind)
    }

    @Test fun `the live count of symptoms joined after the snapshot goes with the remedy precondition only`() {
        val lagging = listOf(opened(1, "s-1"), requested(2, 1))   // the joined line's record has not landed
        val card = operatorCard(view("UNKNOWN_PRECONDITION").copy(joinedAfterSnapshot = 1, symptoms = 2), lagging)
        assertEquals(1, card.joinedAfterSnapshotCount)
        assertTrue(card.joinedAfterSnapshot.isEmpty())
        assertTrue(card.factsBehind)
        assertNull(operatorCard(view("AWAITING_APPROVAL").copy(joinedAfterSnapshot = 1), lagging).joinedAfterSnapshotCount)
        assertNull(operatorCard(view("UNKNOWN_PRECONDITION", candidate = source).copy(joinedAfterSnapshot = 1), lagging).joinedAfterSnapshotCount)
    }

    @Test fun `joined-after-snapshot counts from this attempt's diagnosis request, not another's`() {
        val v = view("UNKNOWN_PRECONDITION").copy(attempt = 2, proposalId = "ep:x/r1#2")
        val inOrder = listOf(opened(1, "s-1"), requested(2, 1), joined(3, "s-3"), requested(4, 2), joined(5, "s-5"))
        assertEquals(listOf("s-5"), operatorCard(v, inOrder).joinedAfterSnapshot.map { it["searchId"].textValue() })
        val reversed = listOf(opened(1, "s-1"), requested(2, 2), joined(3, "s-3"), requested(4, 1), joined(5, "s-5"))
        assertEquals(listOf("s-3", "s-5"), operatorCard(v, reversed).joinedAfterSnapshot.map { it["searchId"].textValue() })
    }

    @Test fun `a blank ref value leaves no proposition and no confirm decision`() {
        for (blank in listOf("  ", "")) {
            val card = operatorCard(view("UNKNOWN_PRECONDITION", candidate = remedy.replace("\"searchId\":\"search-7\"", "\"searchId\":\"$blank\"")), emptyList())
            assertNull(card.precondition, "'$blank'")
            assertNull(card.decision, "'$blank'")
        }
    }

    @Test fun `joined-after-snapshot is for a remedy precondition only, and holds joined lines only`() {
        val events = listOf(requested(1, 1), opened(2, "s-x"), joined(3, "s-3"))   // an OPENED after the request is never "joined"
        assertEquals(listOf("s-3"), operatorCard(view("UNKNOWN_PRECONDITION"), events).joinedAfterSnapshot.map { it["searchId"].textValue() })
        assertTrue(operatorCard(view("UNKNOWN_PRECONDITION", candidate = source), events).joinedAfterSnapshot.isEmpty())
        assertTrue(operatorCard(view("AWAITING_APPROVAL"), events).joinedAfterSnapshot.isEmpty())
    }

    @Test fun `symptoms are in seq order whatever order the records came in, and a line must be an object`() {
        val events = listOf(joined(3, "s-3"), opened(1, "s-1"), joined(2, "s-2"),
            StoredEvent(4, "SYMPTOM_JOINED", """{"eventId":"e-4","kind":"SEARCH","line":"not an object"}"""),
            StoredEvent(5, "SYMPTOM_JOINED", """{"eventId":"e-5","kind":"SEARCH","line":null}"""))
        assertEquals(listOf("s-1", "s-2", "s-3"), operatorCard(view("AWAITING_APPROVAL"), events).symptoms.map { it["searchId"].textValue() })
    }

    // --- B3c chunk 1 re-review -------------------------------------------------------------------------------------

    @Test fun `a recorded answer that did not escalate the episode is not shown`() {
        val events = listOf(opened(1, "s-1"), requested(2, 1), result(3, 1, null, response))   // attempt 1 was PROPOSED
        for (reason in listOf("DIAGNOSIS_EXPIRED", "TAKEN_OVER", "APPROVAL_EXPIRED", "REPEATED_REMEDY")) {
            val card = operatorCard(escalated(reason), events)
            assertNull(card.rationale, reason)
            assertTrue(card.guidance.isEmpty(), reason)
            assertFalse(card.diagnosisFromRecord, reason)
            assertFalse(card.responseUnreadable, reason)
            assertNull(card.diagnosisRefused, reason)
            assertNull(card.diagnosisAttempt, reason)
        }
    }

    @Test fun `an escalating answer is used only for the last attempt and the reason the episode escalated for`() {
        val earlier = listOf(requested(1, 1), result(2, 1, "NO_GROUNDS", noGrounds), requested(3, 2))   // attempt 2 never answered
        val stale = operatorCard(escalated("NO_GROUNDS"), earlier)
        assertNull(stale.rationale)
        assertFalse(stale.diagnosisFromRecord)
        val other = operatorCard(escalated("TAKEN_OVER"), listOf(requested(1, 1), result(2, 1, "NO_GROUNDS", noGrounds)))
        assertNull(other.rationale)
        assertFalse(other.diagnosisFromRecord)
        val unreadable = operatorCard(escalated("DIAGNOSIS_EXPIRED"), listOf(requested(1, 1), result(2, 1, "DIAGNOSIS_FAILED", "null")))
        assertFalse(unreadable.responseUnreadable, "an answer that did not escalate is not read at all")
    }

    @Test fun `a refused answer carries the judge's detail`() {
        val detail = "RECOMMENDED without a verified citation (contract §4 rule 3)"
        val card = operatorCard(escalated("DIAGNOSIS_FAILED"), listOf(requested(1, 1), result(2, 1, "DIAGNOSIS_FAILED", response, detail)))
        assertEquals("DIAGNOSIS_FAILED", card.diagnosisRefused)
        assertEquals(detail, card.diagnosisRefusedDetail)
        val shown = operatorCard(escalated(), listOf(requested(1, 1), result(2, 1, "NO_GROUNDS", noGrounds, "not refused")))
        assertNull(shown.diagnosisRefusedDetail, "the detail is only for a refusal")
    }

    @Test fun `the card names the attempt whose answer it shows`() {
        assertEquals(1, operatorCard(view("AWAITING_APPROVAL"), emptyList()).diagnosisAttempt)
        val recorded = operatorCard(escalated(), listOf(requested(1, 1), result(2, 1, null, response), requested(3, 2), result(4, 2, "NO_GROUNDS", noGrounds)))
        assertEquals("근거가 모자라다.", recorded.rationale)
        assertEquals(2, recorded.diagnosisAttempt)
        assertNull(operatorCard(view("CORRELATING", candidate = null, diagnosis = null).copy(attempt = null), emptyList()).diagnosisAttempt)
    }

    @Test fun `there is no decision without a proposal`() {
        assertNull(operatorCard(view("AWAITING_APPROVAL", candidate = null), emptyList()).decision)
        assertNull(operatorCard(view("UNKNOWN_PRECONDITION", candidate = null), emptyList()).decision)
    }
}
