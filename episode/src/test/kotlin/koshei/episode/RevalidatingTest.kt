package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import koshei.episode.Episodes.CONFIRM_DONE
import koshei.episode.Episodes.INSTANCE
import koshei.episode.Episodes.OPERATOR
import koshei.episode.Episodes.REMEDY
import koshei.episode.Episodes.incident
import koshei.episode.Episodes.open
import koshei.episode.Episodes.search
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevalidatingTest {
    /** Auto-approved by the test policy (or a person task), revalidating one minute after DIAGNOSING began. */
    private fun revalidating(first: Symptom = search("search-1")): Step = open(first).expireState().answer()

    /** REVALIDATING was entered at the approval (or proposal) time. */
    private val Step.at: Instant get() = state.enteredAt

    private fun unknown(): Step = revalidating().let { it.revalidated(TriState.UNKNOWN, it.at.plusSeconds(1)) }

    @Test fun `a precondition that holds records the dispatch intent and nothing else`() {
        val r = revalidating()
        val s = r.revalidated(TriState.TRUE, r.at.plusSeconds(1))
        assertEquals(Phase.DISPATCH_PENDING, s.state.phase)
        val intent = s.commands.filterIsInstance<Command.RecordIntent>().single()
        assertEquals("$INSTANCE:1:APPROVE_REMEDY", intent.intent.idempotencyKey)
        assertEquals(REMEDY, intent.intent.candidate.candidateId)
        assertEquals(ApprovalBy.POLICY, intent.intent.approval!!.by)
        assertEquals(10_000, intent.timeoutMs)
        assertEquals(intent.seq, s.state.attempt!!.intentSeq)
        assertTrue(s.commands.none { it is Command.Revalidate || it is Command.Diagnose })
    }

    @Test fun `an approval past its validity is not acted on - a new diagnosis`() {
        val r = revalidating()
        val s = r.revalidated(TriState.TRUE, r.at.plusMillis(600_000))
        assertEquals(Phase.DIAGNOSING, s.state.phase)
        assertEquals(2, s.request.attempt)
        assertEquals("APPROVED", s.state.history.single().toJson().get("approval").get("result").textValue())
        assertEquals("APPROVAL_LAPSED", s.records(RecordKind.REDIAGNOSE).single().entry.payload.get("reason").textValue())
    }

    @Test fun `an unknown that appeared after the diagnosis blocks an execution candidate`() {
        val r = revalidating().let { it.on(EpisodeEvent.SymptomJoined(incident("incident-6")), it.at.plusSeconds(1)) }
        val s = r.revalidated(TriState.TRUE, r.at.plusSeconds(2))
        assertEquals(EscalationReason.UNKNOWN_BLOCKS_EXECUTION, s.reason)
        assertTrue(s.commands.none { it is Command.RecordIntent })
    }

    @Test fun `a broken precondition supersedes, unless a symptom joined since the diagnosis`() {
        val r = revalidating()
        val superseded = r.revalidated(TriState.FALSE, r.at.plusSeconds(1))
        assertEquals(Phase.SUPERSEDED, superseded.state.phase)
        assertEquals(ClosedAs.SUPERSEDED, superseded.state.history.single().closedAs)
        val joined = r.on(EpisodeEvent.SymptomJoined(search("search-2")), r.at.plusSeconds(1))
        val again = joined.revalidated(TriState.FALSE, r.at.plusSeconds(2))
        assertEquals(Phase.DIAGNOSING, again.state.phase)
        assertEquals(2, again.request.attempt)
        assertEquals(2, again.request.snapshot.searches.size)
    }

    @Test fun `an undecidable precondition waits for a recheck or a person`() {
        val r = revalidating()
        val s = r.revalidated(TriState.UNKNOWN, r.at.plusSeconds(1))
        assertEquals(Phase.UNKNOWN_PRECONDITION, s.state.phase)
        assertEquals(r.at.plusSeconds(1).plusMillis(900_000), s.timers(Timer.STATE).single().at)
        assertEquals(r.at.plusSeconds(1).plusMillis(30_000), s.timers(Timer.RECHECK).single().at)
        assertEquals(Notice(NoticeKind.CONFIRM_PRECONDITION, Phase.UNKNOWN_PRECONDITION, null, REMEDY), s.notices().single())
    }

    @Test fun `a recheck revalidates again, reschedules on UNKNOWN and records the intent on TRUE`() {
        val u = unknown()
        val recheck = u.timers(Timer.RECHECK).single()
        val asked = u.on(EpisodeEvent.DeadlineExpired(Timer.RECHECK, recheck.token), recheck.at)
        val again = asked.commands.filterIsInstance<Command.Revalidate>().single()
        assertEquals(REMEDY, again.candidate.candidateId)
        assertEquals(asked.state.token, again.token)
        assertEquals(Phase.UNKNOWN_PRECONDITION, asked.state.phase)
        val still = asked.revalidated(TriState.UNKNOWN, recheck.at.plusSeconds(1))
        assertEquals(recheck.at.plusSeconds(1).plusMillis(30_000), still.timers(Timer.RECHECK).single().at)
        val holds = still.revalidated(TriState.TRUE, recheck.at.plusSeconds(2))
        assertEquals(Phase.DISPATCH_PENDING, holds.state.phase)
    }

    @Test fun `a person's confirmation decides the precondition - another subject is refused`() {
        val u = unknown()
        val t = u.state.enteredAt.plusSeconds(10)
        val other = u.on(EpisodeEvent.ConfirmedPrecondition(CONFIRM_DONE, "$INSTANCE#1", true, OPERATOR), t)
        assertEquals(Reply.REFUSED_SUBJECT_NOT_UNKNOWN, other.reply)
        assertEquals(Phase.UNKNOWN_PRECONDITION, other.state.phase)
        val holds = u.on(EpisodeEvent.ConfirmedPrecondition(REMEDY, "$INSTANCE#1", true, OPERATOR), t)
        assertEquals(Reply.ACCEPTED, holds.reply)
        assertEquals(Phase.DISPATCH_PENDING, holds.state.phase)
        assertEquals("op-1", holds.records(RecordKind.CONFIRMATION).single().entry.payload.get("by").textValue())
        assertEquals("PERSON", holds.records(RecordKind.REVALIDATION).single().entry.payload.get("source").textValue())
        val broken = u.on(EpisodeEvent.ConfirmedPrecondition(REMEDY, "$INSTANCE#1", false, OPERATOR), t)
        assertEquals(Phase.SUPERSEDED, broken.state.phase)
    }

    @Test fun `an unresolved precondition escalates at its deadline`() {
        assertEquals(EscalationReason.UNKNOWN_UNRESOLVED, unknown().expireState().reason)
    }

    @Test fun `a person task carries no approval and lapses from the proposal time`() {
        val r = revalidating(incident("incident-6"))
        val s = r.revalidated(TriState.TRUE, r.at.plusSeconds(1))
        assertEquals(Phase.DISPATCH_PENDING, s.state.phase)
        val intent = s.commands.filterIsInstance<Command.RecordIntent>().single().intent
        assertNull(intent.approval)
        assertEquals("$INSTANCE:1:OPERATOR_DECISION", intent.idempotencyKey)
        assertEquals(Phase.DIAGNOSING, r.revalidated(TriState.TRUE, r.at.plusMillis(600_000)).state.phase)
    }

    @Test fun `a person's approval counts from the approval time, not the proposal time`() {
        val waiting = open(read = Episodes.policyV1()).expireState().answer()          // proposed at T0+65s
        val approvedAt = waiting.state.enteredAt.plusSeconds(25)                       // T0+90s
        val approved = waiting.on(EpisodeEvent.ApprovalDecided("$INSTANCE#1", waiting.request.candidatesVersion, true, OPERATOR, null, null), approvedAt)
        val s = approved.revalidated(TriState.TRUE, waiting.state.enteredAt.plusMillis(600_000))   // past the proposal's window
        assertEquals(Phase.DISPATCH_PENDING, s.state.phase)
    }

    @Test fun `a late revalidation result from an earlier phase is ignored`() {
        val r = revalidating()
        val stale = r.on(EpisodeEvent.Revalidated(TriState.TRUE, r.state.token - 1), r.at.plusSeconds(1))
        assertEquals(Phase.REVALIDATING, stale.state.phase)
        assertEquals("stale revalidation", stale.records(RecordKind.IGNORED).single().entry.payload.get("why").textValue())
    }

    @Test fun `a policy approval the table no longer allows goes to a person before any intent`() {
        for (read in listOf(Episodes.policyV1(), Episodes.policy { put("bogus", 1) })) {
            val u = unknown()
            val s = u.on(EpisodeEvent.ConfirmedPrecondition(REMEDY, "$INSTANCE#1", true, OPERATOR), u.state.enteredAt.plusSeconds(10), read)
            assertEquals(Phase.AWAITING_APPROVAL, s.state.phase)
            assertNull(s.state.attempt!!.approval)
            assertTrue(s.commands.none { it is Command.RecordIntent })
            assertTrue(s.records(RecordKind.APPROVAL).single().entry.payload.get("revoked").booleanValue())
            assertTrue(s.notices().any { it.kind == NoticeKind.APPROVAL_NEEDED })
            assertEquals("2026-09-30.1", s.records(RecordKind.APPROVAL).single().entry.payload.get("policyVersion").textValue())
        }
    }

    @Test fun `a confirmation from an earlier attempt's card is refused as stale`() {
        // Attempt 1 waits in UNKNOWN(PRECONDITION); a symptom joins; FALSE re-diagnoses; attempt 2 proposes the same remedy.
        val u1 = unknown()
        val joined = u1.on(EpisodeEvent.SymptomJoined(search("search-2")), u1.state.enteredAt.plusSeconds(1))
        val attempt2 = joined.revalidated(TriState.FALSE, joined.state.enteredAt.plusSeconds(2))
        assertEquals(Phase.DIAGNOSING, attempt2.state.phase)
        val r2 = attempt2.answer(candidateId = REMEDY)
        assertEquals(Phase.REVALIDATING, r2.state.phase)
        assertEquals(REMEDY, r2.state.attempt!!.candidate!!.candidateId)
        val u2 = r2.revalidated(TriState.UNKNOWN, r2.at.plusSeconds(1))
        assertEquals(Phase.UNKNOWN_PRECONDITION, u2.state.phase)

        val stale = u2.on(EpisodeEvent.ConfirmedPrecondition(REMEDY, "$INSTANCE#1", true, OPERATOR), u2.state.enteredAt.plusSeconds(5))
        assertEquals(Reply.REFUSED_STALE, stale.reply)
        assertEquals(Phase.UNKNOWN_PRECONDITION, stale.state.phase)
        assertTrue(stale.commands.none { it is Command.RecordIntent })
        val current = u2.on(EpisodeEvent.ConfirmedPrecondition(REMEDY, "$INSTANCE#2", true, OPERATOR), u2.state.enteredAt.plusSeconds(5))
        assertEquals(Reply.ACCEPTED, current.reply)
        assertEquals(Phase.DISPATCH_PENDING, current.state.phase)
    }

    @Test fun `a stale revalidation result while the precondition is unknown is ignored`() {
        val u = unknown()
        val stale = u.on(EpisodeEvent.Revalidated(TriState.TRUE, u.state.token - 1), u.state.enteredAt.plusSeconds(1))
        assertEquals(Phase.UNKNOWN_PRECONDITION, stale.state.phase)
        assertEquals("stale revalidation", stale.records(RecordKind.IGNORED).single().entry.payload.get("why").textValue())
    }

    @Test fun `the episode deadline is enforced by time even when its timer has not been delivered`() {
        val long = Episodes.policy { (get("deadlines") as ObjectNode).put("approvalValidityMs", 3_600_000) }
        val r = open(read = long).expireState().answer()
        assertEquals(Phase.REVALIDATING, r.state.phase)
        val s = r.on(EpisodeEvent.Revalidated(TriState.TRUE, r.state.token), r.state.episodeDeadline!!.plusSeconds(1))
        assertEquals(EscalationReason.EPISODE_EXPIRED, s.reason)
        assertTrue(s.commands.none { it is Command.RecordIntent })
    }

    @Test fun `an approval stays valid only as long as the table said when it was given`() {
        val r = revalidating()   // POLICY approval under the 600 s table
        val longer = Episodes.policy { (get("deadlines") as ObjectNode).put("approvalValidityMs", 3_000_000) }
        val s = r.on(EpisodeEvent.Revalidated(TriState.TRUE, r.state.token), r.at.plusSeconds(700), longer)
        assertEquals(Phase.DIAGNOSING, s.state.phase)
        assertEquals("APPROVAL_LAPSED", s.records(RecordKind.REDIAGNOSE).single().entry.payload.get("reason").textValue())
        assertTrue(s.commands.none { it is Command.RecordIntent })
    }

    @Test fun `an approval is also cut short by a table that shortens the validity`() {
        val r = revalidating()
        val shorter = Episodes.policy { (get("deadlines") as ObjectNode).put("approvalValidityMs", 100_000) }
        val s = r.on(EpisodeEvent.Revalidated(TriState.TRUE, r.state.token), r.at.plusSeconds(200), shorter)
        assertEquals(Phase.DIAGNOSING, s.state.phase)
    }
}
