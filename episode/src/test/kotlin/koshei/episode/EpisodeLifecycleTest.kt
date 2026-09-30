package koshei.episode

import koshei.episode.Episodes.INSTANCE
import koshei.episode.Episodes.OPERATOR
import koshei.episode.Episodes.T0
import koshei.episode.Episodes.incident
import koshei.episode.Episodes.open
import koshei.episode.Episodes.policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeLifecycleTest {
    @Test fun `a first symptom under a valid policy correlates, with the episode and merge-window timers`() {
        val s = open()
        assertEquals(Phase.CORRELATING, s.state.phase)
        assertEquals(T0.plusMillis(3_600_000), s.timers(Timer.EPISODE).single().at)
        assertEquals(T0.plusMillis(5_000), s.timers(Timer.STATE).single().at)
        assertEquals(s.state.token, s.timers(Timer.STATE).single().token)
        val records = s.commands.filterIsInstance<Command.Record>()
        assertEquals(listOf(RecordKind.OPENED, RecordKind.TRANSITION), records.map { it.entry.kind })
        assertEquals(listOf(1L, 2L), records.map { it.seq })
        assertTrue(s.records(RecordKind.TRANSITION).single().entry.payload.get("from").isNull)
        assertNull(s.reply)
    }

    @Test fun `no policy, an expired policy or the agent layer off escalate at once and still close in time`() {
        val missing = open(read = PolicyRead.Missing)
        assertEquals(Phase.ESCALATED, missing.state.phase)
        assertEquals(EscalationReason.POLICY_MISSING, missing.reason)
        assertEquals(T0.plusMillis(FALLBACK_ESCALATED_RETENTION_MS), missing.timers(Timer.RETENTION).single().at)
        assertTrue(missing.timers(Timer.EPISODE).isEmpty())
        assertEquals(NoticeKind.ESCALATED, missing.notices().single().kind)

        val off = open(read = policy { put("agentLayerEnabled", false) })
        assertEquals(EscalationReason.AGENT_LAYER_OFF, off.reason)
        assertEquals(T0.plusMillis(86_400_000), off.timers(Timer.RETENTION).single().at)

        assertEquals(EscalationReason.POLICY_EXPIRED, open(read = policy { put("expiresAt", "2026-09-30T00:00:00Z") }).reason)

        // A rejected first read with no earlier table: POLICY_MISSING, and the rejection is announced from ESCALATED.
        val rejected = open(read = policy { put("bogus", 1) })
        assertEquals(EscalationReason.POLICY_MISSING, rejected.reason)
        assertEquals(Phase.ESCALATED, rejected.notices().single { it.kind == NoticeKind.POLICY_REJECTED }.phase)
    }

    @Test fun `a malformed opening line or manifest escalates at once`() {
        val bad = Symptom("search:bad", SymptomKind.SEARCH, mapper.createObjectNode().put("outcome", "FOUND"), T0)
        val line = open(bad)
        assertEquals(Escalation(EscalationReason.DIAGNOSIS_FAILED, "opening symptom: search line without searchId"), line.state.escalation)
        val manifest = startEpisode(EpisodeEvent.Detected(INSTANCE, "{\"schemaVersion\":\"4\"}", Episodes.search("search-1")), policy(), T0)
        assertEquals(EscalationReason.DIAGNOSIS_FAILED, manifest.reason)
        assertTrue(manifest.state.escalation!!.detail!!.startsWith("snapshot:"))
    }

    @Test fun `a table that expires mid-episode stops decisions even without a fresh read`() {
        val s = open(read = policy { put("expiresAt", "2026-10-01T00:00:10Z") })
        assertEquals(Phase.CORRELATING, s.state.phase)
        val later = s.on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(10))
        assertEquals(EscalationReason.POLICY_EXPIRED, later.reason)
    }

    @Test fun `a joined symptom is kept once and a duplicate eventId is only recorded`() {
        val joined = open().on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(1))
        assertEquals(listOf("search:search-1", "incident:incident-1"), joined.state.symptoms.map { it.eventId })
        assertEquals(1, joined.records(RecordKind.SYMPTOM_JOINED).size)
        val again = joined.on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(2))
        assertEquals(2, again.state.symptoms.size)
        assertEquals(1, again.records(RecordKind.IGNORED).size)
        assertEquals(Phase.CORRELATING, again.state.phase)
    }

    @Test fun `a line without its key is not joined`() {
        val bad = Symptom("search:bad", SymptomKind.SEARCH, mapper.createObjectNode().put("outcome", "FOUND"), T0)
        val s = open().on(EpisodeEvent.SymptomJoined(bad), T0.plusSeconds(1))
        assertEquals(1, s.state.symptoms.size)
        assertEquals("search line without searchId", s.records(RecordKind.IGNORED).single().entry.payload.get("why").textValue())
    }

    @Test fun `takeover escalates and is accepted`() {
        val s = open().on(EpisodeEvent.TakenOver(OPERATOR), T0.plusSeconds(1))
        assertEquals(Phase.ESCALATED, s.state.phase)
        assertEquals(Escalation(EscalationReason.TAKEN_OVER, "by op-1"), s.state.escalation)
        assertEquals(Reply.ACCEPTED, s.reply)
        assertEquals(T0.plusSeconds(1).plusMillis(86_400_000), s.timers(Timer.RETENTION).single().at)
    }

    @Test fun `the episode deadline and the agent-off broadcast escalate`() {
        val expired = open().on(EpisodeEvent.DeadlineExpired(Timer.EPISODE, EPISODE_TOKEN), T0.plusMillis(3_600_000))
        assertEquals(EscalationReason.EPISODE_EXPIRED, expired.reason)
        assertEquals(EscalationReason.AGENT_LAYER_OFF, open().on(EpisodeEvent.AgentOff, T0.plusSeconds(1)).reason)
    }

    @Test fun `a timer from an earlier phase is ignored`() {
        val s = open()
        val stale = s.on(EpisodeEvent.DeadlineExpired(Timer.STATE, s.state.token - 1), T0.plusSeconds(5))
        assertEquals(Phase.CORRELATING, stale.state.phase)
        assertEquals("stale timer", stale.records(RecordKind.IGNORED).single().entry.payload.get("why").textValue())
    }

    @Test fun `a policy read that is off, missing or expired escalates mid-episode`() {
        val off = open().on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(1), read = policy { put("agentLayerEnabled", false) })
        assertEquals(EscalationReason.AGENT_LAYER_OFF, off.reason)
        assertEquals(2, off.state.symptoms.size)   // the symptom is still kept
        val missing = open().on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(1), read = PolicyRead.Missing)
        assertEquals(EscalationReason.POLICY_MISSING, missing.reason)
    }

    @Test fun `a rejected policy read is announced once and suspends auto-approval until a valid read`() {
        val bad = policy { put("bogus", 1) }
        val first = open().on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(1), read = bad)
        assertEquals(Phase.CORRELATING, first.state.phase)
        assertTrue(first.state.autoApproveSuspended)
        assertEquals(NoticeKind.POLICY_REJECTED, first.notices().single().kind)
        val second = first.on(EpisodeEvent.SymptomJoined(incident("incident-2")), T0.plusSeconds(2), read = bad)
        assertTrue(second.notices().isEmpty())
        val healed = second.on(EpisodeEvent.SymptomJoined(incident("incident-4")), T0.plusSeconds(3), read = policy())
        assertFalse(healed.state.autoApproveSuspended)
    }

    @Test fun `escalated - the agent does nothing, a person closes, retention closes unattended`() {
        val escalated = open().on(EpisodeEvent.TakenOver(OPERATOR), T0.plusSeconds(1))
        val decide = escalated.on(EpisodeEvent.ApprovalDecided("$INSTANCE#1", "sha256:x", true, OPERATOR, null, null), T0.plusSeconds(2))
        assertEquals(Reply.REFUSED_NOT_AWAITING, decide.reply)
        assertEquals(Phase.ESCALATED, decide.state.phase)
        val joined = decide.on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(3))
        assertEquals(2, joined.state.symptoms.size)
        val closed = joined.on(EpisodeEvent.Closed(OPERATOR, "handled on site"), T0.plusSeconds(4))
        assertEquals(Phase.CLOSED, closed.state.phase)
        assertEquals(Reply.ACCEPTED, closed.reply)
        assertEquals(EscalationReason.TAKEN_OVER, closed.reason)   // the reason survives the close

        val retention = escalated.timers(Timer.RETENTION).single()
        val stale = escalated.on(EpisodeEvent.DeadlineExpired(Timer.RETENTION, retention.token - 1), retention.at)
        assertEquals(Phase.ESCALATED, stale.state.phase)
        val unattended = escalated.on(EpisodeEvent.DeadlineExpired(Timer.RETENTION, retention.token), retention.at)
        assertEquals(Phase.CLOSED, unattended.state.phase)
        assertEquals("UNATTENDED", unattended.records(RecordKind.TRANSITION).single().entry.payload.get("detail").textValue())
    }

    @Test fun `close outside ESCALATED and anything after the end are refused as values`() {
        assertEquals(Reply.REFUSED_NOT_ESCALATED, open().on(EpisodeEvent.Closed(OPERATOR, "x"), T0.plusSeconds(1)).reply)
        val closed = open().on(EpisodeEvent.TakenOver(OPERATOR), T0.plusSeconds(1)).on(EpisodeEvent.Closed(OPERATOR, "x"), T0.plusSeconds(2))
        assertEquals(Reply.REFUSED_CLOSED, closed.on(EpisodeEvent.TakenOver(OPERATOR), T0.plusSeconds(3)).reply)
        assertEquals(Reply.REFUSED_CLOSED, closed.on(EpisodeEvent.Closed(OPERATOR, "y"), T0.plusSeconds(3)).reply)
        val late = closed.on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(3))
        assertNull(late.reply)
        assertEquals(1, late.state.symptoms.size)
        assertEquals(1, late.records(RecordKind.IGNORED).size)
    }
}
