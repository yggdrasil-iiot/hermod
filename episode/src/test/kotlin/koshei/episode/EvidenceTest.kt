package koshei.episode

import koshei.episode.Episodes.CONFIRM_DONE
import koshei.episode.Episodes.INSTANCE
import koshei.episode.Episodes.OPERATOR
import koshei.episode.Episodes.REMEDY
import koshei.episode.Episodes.incident
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvidenceTest {
    private fun accepted(): Step = dispatched().returned(DispatchResult.Answer(PicassoAnswers.approved()))

    private fun Step.evidence(e: Evidence, now: Instant = state.enteredAt.plusSeconds(5)): Step = on(EpisodeEvent.EvidenceArrived(e), now)

    /** [s] with an intent that names its target unit "u-1" — no kind does that yet (design §19). */
    private fun withTarget(s: Step): Step {
        val a = s.state.attempt!!
        return Step(s.state.copy(attempt = a.copy(intent = a.intent!!.copy(targetUnit = "u-1"))), emptyList())
    }

    private fun confirm(s: Step, done: Boolean, attempt: Int = 1, candidateId: String = REMEDY): Step =
        s.on(EpisodeEvent.ConfirmedOutcome(candidateId, "$INSTANCE#$attempt", done, OPERATOR), s.state.enteredAt.plusSeconds(10))

    @Test fun `a JobResponse that came before the answer is applied when the answer arrives`() {
        val d = dispatched()
        val early = d.evidence(jobResponse("jr-1", inDoubt = listOf("u-1")), d.state.enteredAt.plusMillis(500))
        assertEquals(Phase.DISPATCHED, early.state.phase)
        assertEquals(1, early.state.bufferedEvidence.size)
        val s = early.returned(DispatchResult.Answer(PicassoAnswers.approved()))
        assertEquals(Phase.UNKNOWN_OUTCOME, s.state.phase)   // the kept report was in doubt
        assertTrue(s.state.bufferedEvidence.isEmpty())
        assertTrue(s.state.episodeUnknowns.any { it.subject == mapOf("executionId" to "exec-42", "unitId" to "u-1") })
    }

    @Test fun `an unlinked or duplicate JobResponse is only recorded`() {
        val a = accepted()
        val other = a.evidence(jobResponse("jr-x", executionId = "exec-other", inDoubt = listOf("u-1")))
        assertEquals(Phase.AWAITING_EVIDENCE, other.state.phase)
        val first = a.evidence(jobResponse("jr-1", completed = listOf("u-2")))
        val dup = first.evidence(jobResponse("jr-1", inDoubt = listOf("u-1")))
        assertEquals(Phase.AWAITING_EVIDENCE, dup.state.phase)
        assertEquals("duplicate jobResponseId", dup.records(RecordKind.EVIDENCE).single().entry.payload.get("why").textValue())
        // a report first seen while the intent was being recorded is a duplicate after the answer too
        val p = pending()
        val early = p.on(EpisodeEvent.EvidenceArrived(jobResponse("jr-early", inDoubt = listOf("u-9"))), p.state.enteredAt.plusMillis(500))
        val answered = early.recorded().returned(DispatchResult.Answer(PicassoAnswers.approved()))
        val again = answered.evidence(jobResponse("jr-early", inDoubt = listOf("u-9")))
        assertEquals(Phase.AWAITING_EVIDENCE, again.state.phase)
        assertEquals("duplicate jobResponseId", again.records(RecordKind.EVIDENCE).single().entry.payload.get("why").textValue())
    }

    @Test fun `anything in doubt or not reported is UNKNOWN, even when the target completed`() {
        val cases = listOf(
            jobResponse("a", inDoubt = listOf("u-1")),
            jobResponse("b", unverified = listOf("u-1")),
            jobResponse("c", operatorRequired = true),
            jobResponse("d", operatorRequired = null),
            jobResponse("e", inDoubt = null),
            jobResponse("f", unverified = null),
        )
        for (e in cases) {
            val s = withTarget(accepted()).evidence(e)
            assertEquals(Phase.UNKNOWN_OUTCOME, s.state.phase, e.jobResponseId)
            assertEquals("UNKNOWN", s.state.attempt!!.evidence!!.outcome, e.jobResponseId)
        }
    }

    @Test fun `in UNKNOWN(OUTCOME) a doubtful report adds unknowns and stays`() {
        val u = accepted().evidence(jobResponse("a", inDoubt = listOf("u-1")))
        val s = u.evidence(jobResponse("b", inDoubt = listOf("u-2", "u-1")))
        assertEquals(Phase.UNKNOWN_OUTCOME, s.state.phase)
        assertEquals(listOf("u-1", "u-2"), s.state.episodeUnknowns.mapNotNull { it.subject["unitId"] })   // u-1 once
    }

    @Test fun `the target unit completed with nothing in doubt resolves`() {
        val s = withTarget(accepted()).evidence(jobResponse("jr-1"))
        assertEquals(Phase.RESOLVED, s.state.phase)
        val entry = s.state.history.single().toJson()
        assertEquals("RESOLVED", entry.get("closedAs").textValue())
        assertEquals("DONE", entry.get("evidence").get("outcome").textValue())
        assertEquals("E2", entry.get("evidence").get("grade").textValue())
        assertEquals("ACCEPTED", entry.get("dispatch").get("result").textValue())
        assertEquals("pick_place", entry.get("dispatch").get("delivered")[0].get("skillType").textValue())
    }

    @Test fun `no JobResponse completes an attempt while the target unit is not known`() {
        val s = accepted().evidence(jobResponse("jr-1"))
        assertEquals(Phase.AWAITING_EVIDENCE, s.state.phase)
        assertEquals("target unit not known (design §19)", s.records(RecordKind.EVIDENCE).single().entry.payload.get("why").textValue())
    }

    @Test fun `a person's DONE resolves with E3 - NOT_DONE re-diagnoses without the outcome unknown`() {
        val u = dispatched().returned(DispatchResult.Answer(PicassoAnswers.refused("NO_PROPOSAL")))
        val done = confirm(u, done = true)
        assertEquals(Reply.ACCEPTED, done.reply)
        assertEquals(Phase.RESOLVED, done.state.phase)
        assertEquals("E3", done.state.history.single().evidence!!.grade)
        assertEquals("done; by whom unknown", done.records(RecordKind.TRANSITION).last().entry.payload.get("detail").textValue())
        assertTrue(done.state.episodeUnknowns.isEmpty())
        val notDone = confirm(u, done = false)
        assertEquals(Phase.DIAGNOSING, notDone.state.phase)
        assertEquals(EvidenceSummary("E3", "NOT_DONE"), notDone.state.history.single().evidence)
        assertTrue(notDone.request.unknowns.isEmpty())
        assertEquals(listOf(REMEDY, "ESCALATE"), notDone.request.candidates.map { it.candidateId })   // proposable again
    }

    @Test fun `a confirmation for another attempt, another target or another phase is refused`() {
        val a = accepted()
        assertEquals(Reply.REFUSED_STALE, confirm(a, done = true, attempt = 0).reply)
        assertEquals(Reply.REFUSED_SUBJECT_NOT_UNKNOWN, confirm(a, done = true, candidateId = CONFIRM_DONE).reply)
        val p = pending()
        assertEquals(Reply.REFUSED_SUBJECT_NOT_UNKNOWN, confirm(p, done = true).reply)
    }

    @Test fun `the deadlines end both waits`() {
        assertEquals(EscalationReason.EVIDENCE_EXPIRED, accepted().expireState().reason)
        val s = dispatched().returned(DispatchResult.Uncertain(null)).expireState()
        assertEquals(EscalationReason.UNKNOWN_UNRESOLVED, s.reason)
        assertEquals(ClosedAs.UNKNOWN, s.state.history.single().closedAs)
    }

    @Test fun `a person task completes only by a person`() {
        val t = dispatched(incident("incident-6")).returned(DispatchResult.PersonTaskIssued)
        val report = t.evidence(jobResponse("jr-1", executionId = null))
        assertEquals(Phase.AWAITING_EVIDENCE, report.state.phase)
        val done = confirm(t, done = true, candidateId = CONFIRM_DONE)
        assertEquals(Phase.RESOLVED, done.state.phase)
        assertEquals("PERSON_TASK", done.state.history.single().dispatch!!.result)
    }

    @Test fun `the same remedy dispatched up to the threshold escalates as REPEATED_REMEDY`() {
        val first = confirm(accepted(), done = false)                       // attempt 2 diagnosing
        val second = first.answer()
            .let { it.revalidated(TriState.TRUE, it.state.enteredAt.plusSeconds(1)) }
            .recorded()
            .returned(DispatchResult.Answer(PicassoAnswers.approved()))
        assertEquals(mapOf(REMEDY to 2), second.state.dispatchCounts)
        val third = confirm(second, done = false, attempt = 2).answer()      // attempt 3 proposes the same remedy
        assertEquals(EscalationReason.REPEATED_REMEDY, third.reason)
    }
}
