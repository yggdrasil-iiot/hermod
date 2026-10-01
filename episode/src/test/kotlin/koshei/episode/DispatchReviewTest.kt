package koshei.episode

import koshei.episode.Episodes.INSTANCE
import koshei.episode.Episodes.OPERATOR
import koshei.episode.Episodes.REMEDY
import koshei.episode.Episodes.incident
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review round of plan B2b-2b: what the first quality review found in 7ba0a2a. */
class DispatchReviewTest {
    private fun accepted(): Step = dispatched().returned(DispatchResult.Answer(PicassoAnswers.approved()))

    private fun Step.evidence(e: Evidence, now: Instant = state.enteredAt.plusSeconds(5)): Step = on(EpisodeEvent.EvidenceArrived(e), now)

    private fun withTarget(s: Step): Step {
        val a = s.state.attempt!!
        return Step(s.state.copy(attempt = a.copy(intent = a.intent!!.copy(targetUnit = "u-1"))), emptyList())
    }

    private fun notDone(s: Step, attempt: Int = 1): Step =
        s.on(EpisodeEvent.ConfirmedOutcome(REMEDY, "$INSTANCE#$attempt", false, OPERATOR), s.state.enteredAt.plusSeconds(10))

    private fun Step.evidenceWhy(): List<String> = records(RecordKind.EVIDENCE).map { it.entry.payload.get("why").textValue() }

    // ---- 1: the history must not say "never dispatched" after Dispatch went out ----

    @Test fun `an episode that ends while DISPATCHED says in its history that the dispatch is uncertain`() {
        val taken = dispatched().on(EpisodeEvent.TakenOver(OPERATOR), Episodes.T0.plusSeconds(80))
        val d = taken.state.history.single().toJson().get("dispatch")
        assertEquals("UNCERTAIN", d.get("result").textValue())
        assertEquals("AGENT", d.get("approverKind").textValue())
        assertTrue(d.get("idempotencyKey").textValue().isNotBlank())
        assertTrue(d.get("delivered").isNull)
    }

    @Test fun `the episode deadline while DISPATCHED does too`() {
        val s = dispatched().let { it.on(EpisodeEvent.DeadlineExpired(Timer.EPISODE, EPISODE_TOKEN), Episodes.T0.plusMillis(3_600_000)) }
        assertEquals(EscalationReason.EPISODE_EXPIRED, s.reason)
        assertEquals("UNCERTAIN", s.state.history.single().dispatch!!.result)
    }

    // ---- 2: a stale JobResponse never links to a later attempt ----

    /** Attempt 1 accepted as exec-42, NOT_DONE; [jr-old] arrives in attempt 2's DIAGNOSING; attempt 2 is accepted as exec-42 too. */
    private fun secondAttemptSameExecution(): Pair<Step, Step> {
        val a1 = accepted()
        val diagnosing = notDone(a1)
        val old = diagnosing.on(EpisodeEvent.EvidenceArrived(jobResponse("jr-old")), diagnosing.state.enteredAt.plusSeconds(1))
        val a2 = old.answer()
            .let { it.revalidated(TriState.TRUE, it.state.enteredAt.plusSeconds(1)) }
            .recorded()
            .returned(DispatchResult.Answer(PicassoAnswers.approved()))
        return a1 to a2
    }

    @Test fun `a JobResponse ignored in another phase is a duplicate when it comes again`() {
        val (_, a2) = secondAttemptSameExecution()
        assertTrue("jr-old" in a2.state.seenJobResponseIds)
        val again = withTarget(a2).evidence(jobResponse("jr-old"))
        assertEquals(Phase.AWAITING_EVIDENCE, again.state.phase)
        assertEquals(listOf("duplicate jobResponseId"), again.evidenceWhy())
    }

    @Test fun `a JobResponse ignored in ESCALATED is seen too`() {
        val taken = dispatched().on(EpisodeEvent.TakenOver(OPERATOR), Episodes.T0.plusSeconds(80))
        val late = taken.on(EpisodeEvent.EvidenceArrived(jobResponse("jr-late")), Episodes.T0.plusSeconds(90))
        assertTrue("jr-late" in late.state.seenJobResponseIds)
    }

    @Test fun `an execution id an earlier attempt already used links nothing`() {
        val (a1, a2) = secondAttemptSameExecution()
        assertEquals(setOf("exec-42"), a1.state.usedExecutionIds)
        assertFalse(a1.records(RecordKind.DISPATCH_RESULT).single().entry.payload.has("executionIdReused"))
        assertNull(a2.state.attempt!!.executionId)
        assertEquals(setOf("exec-42"), a2.state.usedExecutionIds)
        assertTrue(a2.records(RecordKind.DISPATCH_RESULT).single().entry.payload.get("executionIdReused").booleanValue())
        assertEquals(Phase.AWAITING_EVIDENCE, a2.state.phase)   // still awaited: only a person or the deadline ends it
        // a report with a new id for the reused execution, target completed, still does not resolve
        val fresh = withTarget(a2).evidence(jobResponse("jr-new"))
        assertEquals(Phase.AWAITING_EVIDENCE, fresh.state.phase)
        assertEquals(listOf("not linked to this attempt's execution"), fresh.evidenceWhy())
    }

    // ---- 3: a batch replay honours doubt ----

    @Test fun `a kept clean report does not resolve while a later kept report is in doubt`() {
        val d = withTarget(dispatched())
        val a = d.evidence(jobResponse("jr-a"), d.state.enteredAt.plusMillis(300))
        val b = a.evidence(jobResponse("jr-b", inDoubt = listOf("u-2")), a.state.enteredAt.plusMillis(300))
        assertEquals(2, b.state.bufferedEvidence.size)
        val s = b.returned(DispatchResult.Answer(PicassoAnswers.approved()))
        assertEquals(Phase.UNKNOWN_OUTCOME, s.state.phase)
        assertTrue(s.records(RecordKind.EVIDENCE).any { it.entry.payload.get("why").textValue() == "not resolved: a kept report is in doubt" })
    }

    // ---- 4: guard tests ----

    @Test fun `a person task with a target unit is not completed by a JobResponse without an execution`() {
        val t = withTarget(dispatched(incident("incident-6")).returned(DispatchResult.PersonTaskIssued))
        val s = t.evidence(jobResponse("jr-1", executionId = null))
        assertEquals(Phase.AWAITING_EVIDENCE, s.state.phase)
        assertEquals(listOf("not linked to this attempt's execution"), s.evidenceWhy())
    }

    @Test fun `a second doubtful report in UNKNOWN(OUTCOME) neither re-enters nor re-notifies nor moves the deadline`() {
        val u = accepted().evidence(jobResponse("a", inDoubt = listOf("u-1")))
        assertEquals(Phase.UNKNOWN_OUTCOME, u.state.phase)
        val s = u.evidence(jobResponse("b", inDoubt = listOf("u-2")))
        assertEquals(Phase.UNKNOWN_OUTCOME, s.state.phase)
        assertTrue(s.commands.none { it is Command.SetTimer || it is Command.Notify })
        assertEquals(u.state.stateDeadline, s.state.stateDeadline)
        assertEquals(u.state.token, s.state.token)
    }

    @Test fun `an unknown of the episode itself blocks the gate`() {
        val r = Episodes.open().expireState().answer()
        val primed = Step(
            r.state.copy(episodeUnknowns = listOf(Unknown(mapOf("candidateId" to REMEDY), UnknownWhat.OUTCOME, null, "picasso"))),
            emptyList(),
        )
        val s = primed.revalidated(TriState.TRUE, primed.state.enteredAt.plusSeconds(1))
        assertEquals(EscalationReason.UNKNOWN_BLOCKS_EXECUTION, s.reason)
        assertTrue(s.commands.none { it is Command.RecordIntent })
    }

    @Test fun `a RecordFailed for another record escalates without dispatching and names both seqs`() {
        val p = pending()
        val seq = p.state.attempt!!.intentSeq!!
        val s = p.on(EpisodeEvent.RecordFailed(seq + 100, "someone else's"), p.state.enteredAt.plusSeconds(1))
        assertEquals(Escalation(EscalationReason.RECORD_FAILED, "RecordFailed(${seq + 100}), expected $seq"), s.state.escalation)
        assertTrue(s.commands.none { it is Command.Dispatch })
    }

    @Test fun `reports kept for a phase the episode already left are recorded as such`() {
        val d = withTarget(dispatched())
        val a = d.evidence(jobResponse("jr-a"), d.state.enteredAt.plusMillis(300))
        val b = a.evidence(jobResponse("jr-b"), a.state.enteredAt.plusMillis(300))
        val s = b.returned(DispatchResult.Answer(PicassoAnswers.approved()))
        assertEquals(Phase.RESOLVED, s.state.phase)   // the first resolved ...
        assertTrue("kept, but the episode left for RESOLVED" in s.evidenceWhy())   // ... the second found nobody waiting
    }

    // ---- 5 to 11 ----

    @Test fun `the gate before Dispatch also counts earlier dispatches against the threshold`() {
        val second = notDone(accepted()).answer().let { it.revalidated(TriState.TRUE, it.state.enteredAt.plusSeconds(1)) }
        val seq = second.state.attempt!!.intentSeq!!
        val s = second.on(EpisodeEvent.Recorded(seq), second.state.enteredAt.plusSeconds(1), read = Episodes.policy { put("repeatedRemedyThreshold", 1) })
        assertEquals(EscalationReason.REPEATED_REMEDY, s.reason)
        assertTrue(s.commands.none { it is Command.Dispatch })
        assertEquals(mapOf(REMEDY to 1), s.state.dispatchCounts)
    }

    @Test fun `an ignored Recorded or RecordFailed keeps its seq, and the message`() {
        val taken = dispatched().on(EpisodeEvent.TakenOver(OPERATOR), Episodes.T0.plusSeconds(80))
        val recorded = taken.on(EpisodeEvent.Recorded(7), Episodes.T0.plusSeconds(90)).records(RecordKind.IGNORED).single().entry.payload
        assertEquals(7, recorded.get("seq").longValue())
        val failed = taken.on(EpisodeEvent.RecordFailed(8, "db down"), Episodes.T0.plusSeconds(90)).records(RecordKind.IGNORED).single().entry.payload
        assertEquals(8, failed.get("seq").longValue())
        assertEquals("db down", failed.get("message").textValue())
    }

    @Test fun `escalating while JobResponses are kept records them and clears the buffer`() {
        val d = dispatched()
        val early = d.evidence(jobResponse("jr-1"), d.state.enteredAt.plusMillis(300))
        assertEquals(1, early.state.bufferedEvidence.size)
        val taken = early.on(EpisodeEvent.TakenOver(OPERATOR), Episodes.T0.plusSeconds(80))
        assertTrue(taken.state.bufferedEvidence.isEmpty())
        val rec = taken.records(RecordKind.EVIDENCE).single().entry.payload
        assertEquals("jr-1", rec.get("jobResponseId").textValue())
        assertEquals("kept, but the episode escalated", rec.get("why").textValue())
        assertFalse(rec.get("counted").booleanValue())
    }

    @Test fun `a grade picasso did not report stays unreported - never invented`() {
        val u = accepted().evidence(jobResponse("a", inDoubt = listOf("u-1"), reached = null))
        assertNull(u.state.attempt!!.evidence!!.grade)
        val closed = u.expireState()
        assertTrue(closed.state.history.single().toJson().get("evidence").get("grade").isNull)
        val done = withTarget(accepted()).evidence(jobResponse("b", reached = null))
        assertEquals(EvidenceSummary(null, "DONE"), done.state.history.single().evidence)
    }

    @Test fun `an attempt with no proposed candidate in the outcome phases escalates as an internal error`() {
        val a = accepted()
        val broken = Step(a.state.copy(attempt = a.state.attempt!!.copy(verdict = null)), emptyList())
        val s = broken.expireState()
        assertEquals(Escalation(EscalationReason.DISPATCH_ANSWER_UNKNOWN, "internal: no proposed candidate"), s.state.escalation)
    }

    @Test fun `the dispatch result record names the judgement in fixed words`() {
        fun label(result: DispatchResult, first: Symptom = Episodes.search("search-1")): String =
            dispatched(first).returned(result).records(RecordKind.DISPATCH_RESULT).single().entry.payload.get("judgement").textValue()
        assertEquals("ACCEPTED", label(DispatchResult.Answer(PicassoAnswers.approved())))
        assertEquals("PERSON_TASK", label(DispatchResult.PersonTaskIssued, incident("incident-6")))
        assertEquals("MAY_HAVE_LANDED", label(DispatchResult.Answer(PicassoAnswers.refused("NO_PROPOSAL"))))
        assertEquals("UNCERTAIN", label(DispatchResult.Uncertain("x")))
        assertEquals("REFUSED", label(DispatchResult.Answer(PicassoAnswers.refused("REFUSED_BY_GATE"))))
        assertEquals("ANSWER_UNKNOWN", label(DispatchResult.Answer(PicassoAnswers.refused("REFUSED_BY_GATE", schema = "3"))))
    }

    @Test fun `a JobResponse and a Symptom are values`() {
        val e = jobResponse("jr-1", completed = listOf("u-1", "u-2"))
        assertEquals(e, jobResponse("jr-1", completed = listOf("u-1", "u-2")))
        assertEquals(e.hashCode(), jobResponse("jr-1", completed = listOf("u-1", "u-2")).hashCode())
        assertNotEquals(e, jobResponse("jr-1", completed = listOf("u-1")))
        assertNotEquals(e, jobResponse("jr-1", completed = listOf("u-1", "u-2"), operatorRequired = null))
        assertNotEquals(e, jobResponse("jr-2", completed = listOf("u-1", "u-2")))
        val s = Episodes.search("search-1")
        assertEquals(s, Episodes.search("search-1"))
        assertEquals(s.hashCode(), Episodes.search("search-1").hashCode())
        assertNotEquals(s, Episodes.search("search-1", at = Episodes.T0.plusSeconds(1)))
        assertNotEquals(s, Episodes.search("search-2"))
    }
}
