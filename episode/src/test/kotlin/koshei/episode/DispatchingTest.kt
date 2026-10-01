package koshei.episode

import koshei.episode.Episodes.INSTANCE
import koshei.episode.Episodes.OPERATOR
import koshei.episode.Episodes.REMEDY
import koshei.episode.Episodes.incident
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DispatchingTest {
    @Test fun `the intent's Recorded dispatches once, counted`() {
        val s = pending().recorded()
        assertEquals(Phase.DISPATCHED, s.state.phase)
        val d = s.commands.filterIsInstance<Command.Dispatch>().single()
        assertEquals(REMEDY, d.intent.candidate.candidateId)
        assertEquals("AGENT", d.approverKind)
        assertEquals(30_000, d.timeoutMs)
        assertEquals(s.state.token, d.token)
        assertEquals(mapOf(REMEDY to 1), s.state.dispatchCounts)
        assertEquals(1, s.records(RecordKind.DISPATCH_SENT).size)
    }

    @Test fun `another seq or a failed record never dispatches`() {
        val p = pending()
        val seq = p.state.attempt!!.intentSeq!!
        val other = p.on(EpisodeEvent.Recorded(seq + 100), p.state.enteredAt.plusSeconds(1))
        assertEquals(EscalationReason.RECORD_FAILED, other.reason)
        assertTrue(other.commands.none { it is Command.Dispatch })
        val failed = p.on(EpisodeEvent.RecordFailed(seq, "db down"), p.state.enteredAt.plusSeconds(1))
        assertEquals(Escalation(EscalationReason.RECORD_FAILED, "db down"), failed.state.escalation)
        assertTrue(failed.commands.none { it is Command.Dispatch })
    }

    @Test fun `the gate runs again at Recorded`() {
        // an unknown joined while the intent was being recorded
        val joined = pending().let { it.on(EpisodeEvent.SymptomJoined(incident("incident-6")), it.state.enteredAt.plusMillis(500)) }
        val blocked = joined.recorded()
        assertEquals(EscalationReason.UNKNOWN_BLOCKS_EXECUTION, blocked.reason)
        assertTrue(blocked.commands.none { it is Command.Dispatch })
        // the table stopped auto-approving
        val p = pending()
        val revoked = p.on(EpisodeEvent.Recorded(p.state.attempt!!.intentSeq!!), p.state.enteredAt.plusSeconds(1), read = Episodes.policyV1())
        assertEquals(Phase.AWAITING_APPROVAL, revoked.state.phase)
        assertTrue(revoked.commands.none { it is Command.Dispatch })
        // the approval lapsed
        val lapsed = p.recorded(now = p.state.attempt!!.approval!!.at.plusMillis(600_000))
        assertEquals(Phase.DIAGNOSING, lapsed.state.phase)
        assertTrue(lapsed.commands.none { it is Command.Dispatch })
    }

    @Test fun `a JobResponse while the intent is being recorded is only recorded`() {
        val p = pending()
        val s = p.on(EpisodeEvent.EvidenceArrived(jobResponse("jr-early", inDoubt = listOf("u-9"))), p.state.enteredAt.plusMillis(500))
        assertEquals(Phase.DISPATCH_PENDING, s.state.phase)
        assertTrue(s.state.bufferedEvidence.isEmpty())
        assertFalse(s.records(RecordKind.EVIDENCE).single().entry.payload.get("counted").booleanValue())
        val accepted = s.recorded().returned(DispatchResult.Answer(PicassoAnswers.approved()))
        assertEquals(Phase.AWAITING_EVIDENCE, accepted.state.phase)   // it was not kept for later
        assertTrue("jr-early" in accepted.state.seenJobResponseIds)     // a redelivered copy is a duplicate (EvidenceTest)
    }

    @Test fun `an approved answer waits for evidence and keeps what went out`() {
        val s = dispatched().returned(DispatchResult.Answer(PicassoAnswers.approved()))
        assertEquals(Phase.AWAITING_EVIDENCE, s.state.phase)
        val a = s.state.attempt!!
        assertEquals("exec-42", a.executionId)
        assertEquals("ACCEPTED", a.dispatch!!.result)
        assertEquals("AGENT", a.dispatch!!.approverKind)
        assertEquals("pick_place", a.dispatch!!.delivered!![0].get("skillType").textValue())
        assertEquals(s.state.enteredAt.plusMillis(600_000), s.timers(Timer.STATE).single().at)
    }

    @Test fun `a person task is issued without an approver kind`() {
        val d = dispatched(incident("incident-6"))
        assertNull(d.commands.filterIsInstance<Command.Dispatch>().single().approverKind)
        val s = d.returned(DispatchResult.PersonTaskIssued)
        assertEquals(Phase.AWAITING_EVIDENCE, s.state.phase)
        assertEquals("PERSON_TASK", s.state.attempt!!.dispatch!!.result)
    }

    @Test fun `NO_PROPOSAL, REMEDY_NOT_APPLIED and an uncertain dispatch leave the outcome unknown, never retried`() {
        val results = listOf(
            DispatchResult.Answer(PicassoAnswers.refused("NO_PROPOSAL")),
            DispatchResult.Answer(PicassoAnswers.refused("REMEDY_NOT_APPLIED")),
            DispatchResult.Uncertain("retries exhausted"),
        )
        for (result in results) {
            val s = dispatched().returned(result)
            assertEquals(Phase.UNKNOWN_OUTCOME, s.state.phase, "$result")
            assertTrue(s.commands.none { it is Command.Dispatch || it is Command.RecordIntent }, "$result")
            assertTrue(s.state.attempt!!.authorUnknown, "$result")
            assertEquals(listOf(UnknownWhat.OUTCOME), s.state.episodeUnknowns.map { it.what }, "$result")
            assertEquals(NoticeKind.CONFIRM_OUTCOME, s.notices().single().kind, "$result")
            assertEquals(s.state.enteredAt.plusMillis(900_000), s.timers(Timer.STATE).single().at, "$result")
        }
    }

    @Test fun `a refusal goes to a person with its value, also in the history`() {
        for (refusal in listOf("REFUSED_BY_GATE", "NOT_DECLARED", "PROPOSAL_CHANGED", "WITHHELD")) {
            val s = dispatched().returned(DispatchResult.Answer(PicassoAnswers.refused(refusal)))
            assertEquals(Escalation(EscalationReason.DISPATCH_REFUSED, refusal), s.state.escalation)
            assertEquals(refusal, s.state.history.single().toJson().get("dispatch").get("result").textValue())
        }
    }

    @Test fun `an answer koshei does not understand goes to a person`() {
        val s = dispatched().returned(DispatchResult.Answer(PicassoAnswers.refused("REFUSED_BY_GATE", schema = "3")))
        assertEquals(EscalationReason.DISPATCH_ANSWER_UNKNOWN, s.reason)
        assertEquals("ANSWER_UNKNOWN", s.state.history.single().toJson().get("dispatch").get("result").textValue())
    }

    @Test fun `a dispatch result from an earlier phase is ignored`() {
        val d = dispatched()
        val s = d.on(EpisodeEvent.DispatchReturned(DispatchResult.Answer(PicassoAnswers.approved()), d.state.token - 1), d.state.enteredAt.plusSeconds(1))
        assertEquals(Phase.DISPATCHED, s.state.phase)
        assertEquals("stale dispatch result", s.records(RecordKind.IGNORED).single().entry.payload.get("why").textValue())
    }

    @Test fun `after escalation the answer and JobResponses are still recorded in full`() {
        val taken = dispatched().on(EpisodeEvent.TakenOver(OPERATOR), Episodes.T0.plusSeconds(80))
        assertEquals(Phase.ESCALATED, taken.state.phase)
        val answer = taken.returned(DispatchResult.Answer(PicassoAnswers.approved()))
        assertTrue(answer.records(RecordKind.IGNORED).single().entry.payload.get("answer").textValue().contains("exec-42"))
        val report = answer.on(EpisodeEvent.EvidenceArrived(jobResponse("jr-late")), Episodes.T0.plusSeconds(90))
        assertEquals("jr-late", report.records(RecordKind.IGNORED).single().entry.payload.get("evidence").get("jobResponseId").textValue())
    }

    @Test fun `a person's approval is sent as PERSON`() {
        val waiting = Episodes.open(read = Episodes.policyV1()).expireState().answer()
        val approved = waiting.on(
            EpisodeEvent.ApprovalDecided("$INSTANCE#1", waiting.request.candidatesVersion, true, OPERATOR, null, null),
            waiting.state.enteredAt.plusSeconds(10),
        )
        val s = approved.revalidated(TriState.TRUE, approved.state.enteredAt.plusSeconds(1)).recorded()
        assertEquals("PERSON", s.commands.filterIsInstance<Command.Dispatch>().single().approverKind)
    }
}
