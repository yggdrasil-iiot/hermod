package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import koshei.episode.Episodes.CONFIRM_DONE
import koshei.episode.Episodes.INSTANCE
import koshei.episode.Episodes.OPERATOR
import koshei.episode.Episodes.REMEDY
import koshei.episode.Episodes.T0
import koshei.episode.Episodes.incident
import koshei.episode.Episodes.open
import koshei.episode.Episodes.policy
import koshei.episode.Episodes.policyV1
import koshei.episode.Episodes.search
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosingTest {
    @Test fun `the merge window closes into the first diagnosis`() {
        val s = open().expireState()
        assertEquals(Phase.DIAGNOSING, s.state.phase)
        val d = s.commands.filterIsInstance<Command.Diagnose>().single()
        assertEquals(1, d.request.attempt)
        assertEquals(INSTANCE, d.request.episodeId)
        assertEquals(listOf(REMEDY, "ESCALATE"), d.request.candidates.map { it.candidateId })
        assertEquals(1_200_000, d.budget.scheduleToCloseMs)
        assertTrue(s.timers(Timer.STATE).isEmpty())   // the activity's own ScheduleToClose is DIAGNOSING's deadline
        assertEquals(1, s.records(RecordKind.DIAGNOSIS_REQUESTED).size)
    }

    @Test fun `a WITHHELD search goes to a person without a diagnosis`() {
        val s = open(search("search-3")).expireState()
        assertEquals(EscalationReason.WITHHELD_HUMAN_FIRST, s.reason)
        assertTrue(s.commands.none { it is Command.Diagnose })
        assertTrue(s.state.history.isEmpty())
    }

    @Test fun `a snapshot over the policy limit is not sent`() {
        val s = open(read = policy { put("snapshotMaxBytes", 100) }).expireState()
        assertEquals(EscalationReason.SNAPSHOT_TOO_LARGE, s.reason)
        assertTrue(s.commands.none { it is Command.Diagnose })
    }

    @Test fun `more candidates than narrator can letter are not sent`() {
        val line = mapper.readTree(PicassoRun1.search("search-4")) as ObjectNode
        line.putArray("alternatives").apply { (1..27).forEach { add("SEQ-IN-%02d.BIN-Z".format(it)) } }
        val s = open(Symptom("search:wide", SymptomKind.SEARCH, line, T0)).expireState()
        assertEquals(Escalation(EscalationReason.CANDIDATES_TOO_MANY, "27 candidates besides ESCALATE"), s.state.escalation)
        assertTrue(s.commands.none { it is Command.Diagnose })
    }

    @Test fun `a clean recommendation under an auto-approving policy goes to revalidation`() {
        val s = open().expireState().answer()
        assertEquals(Phase.REVALIDATING, s.state.phase)
        assertEquals("POLICY", s.records(RecordKind.PROPOSED).single().entry.payload.get("route").textValue())
        assertEquals(ApprovalBy.POLICY, s.state.attempt!!.approval!!.by)
        val revalidate = s.commands.filterIsInstance<Command.Revalidate>().single()
        assertEquals(REMEDY, revalidate.candidate.candidateId)
        assertEquals(30_000, revalidate.timeoutMs)
        assertEquals(1, s.records(RecordKind.PROPOSED).size)
        assertEquals(1, s.records(RecordKind.APPROVAL).size)
    }

    @Test fun `policy v1 sends every recommendation to a person`() {
        val diagnosing = open(read = policyV1()).expireState()
        val s = diagnosing.answer()
        assertEquals(Phase.AWAITING_APPROVAL, s.state.phase)
        assertEquals("AWAITING_APPROVAL", s.records(RecordKind.PROPOSED).single().entry.payload.get("route").textValue())
        assertEquals(diagnosing.state.enteredAt.plusSeconds(60).plusMillis(300_000), s.timers(Timer.STATE).single().at)
        assertEquals(Notice(NoticeKind.APPROVAL_NEEDED, Phase.AWAITING_APPROVAL, null, REMEDY), s.notices().single())
    }

    @Test fun `no auto-approval while suspended, for an unclean answer, or for a skill off the allowlist`() {
        assertEquals(Phase.AWAITING_APPROVAL, open().expireState().answer(read = policy { put("bogus", 1) }).state.phase)
        assertEquals(Phase.AWAITING_APPROVAL, open().expireState().answer { putNull("rationale") }.state.phase)
        val otherSkill = policy { ((get("autoApprove") as ObjectNode).get("APPROVE_REMEDY") as ObjectNode).putArray("skills").add("weld") }
        assertEquals(Phase.AWAITING_APPROVAL, open(read = otherSkill).expireState().answer().state.phase)
    }

    @Test fun `every non-proposal verdict escalates with its own reason and closes the attempt`() {
        val cases: Map<EscalationReason, Step.() -> Step> = mapOf(
            EscalationReason.NO_GROUNDS to { answer("NO_GROUNDS", candidateId = null) },
            EscalationReason.UNCITED to { answer("UNCITED", candidateId = null) },
            EscalationReason.ESCALATE_RECOMMENDED to { answer(candidateId = "ESCALATE") },
            EscalationReason.OUT_OF_CANDIDATES to { answer("OUT_OF_CANDIDATES", candidateId = null, edit = { put("picked", "B-07") }) },
            EscalationReason.DIAGNOSIS_FAILED to { answer { putArray("citations") } },   // RECOMMENDED with no verified citation
        )
        for ((reason, act) in cases) {
            val s = open().expireState().act()
            assertEquals(reason, s.reason, "$reason")
            assertTrue(s.commands.none { it is Command.CancelDiagnosis }, "$reason")
            val entry = s.state.history.single().toJson()
            assertEquals("ESCALATED", entry.get("closedAs").textValue(), "$reason")
            assertTrue(entry.get("dispatch").isNull, "$reason")
        }
        val out = open().expireState().answer("OUT_OF_CANDIDATES", candidateId = null, edit = { put("picked", "B-07") })
        assertEquals("picked: B-07", out.state.escalation!!.detail)
        assertEquals("B-07", out.state.history.single().toJson().get("diagnosis").get("picked").textValue())
    }

    @Test fun `an unreadable answer and a failed or expired activity escalate`() {
        val d = open().expireState()
        val unreadable = d.on(EpisodeEvent.DiagnosisReturned("{"), d.state.enteredAt.plusSeconds(1))
        assertEquals(EscalationReason.DIAGNOSIS_FAILED, unreadable.reason)
        assertTrue(unreadable.state.history.single().toJson().get("diagnosis").isNull)
        val expired = d.on(EpisodeEvent.DiagnosisFailed(expired = true, message = null), d.state.enteredAt.plusSeconds(1200))
        assertEquals(EscalationReason.DIAGNOSIS_EXPIRED, expired.reason)
        val quota = d.on(EpisodeEvent.DiagnosisFailed(expired = false, message = "quota"), d.state.enteredAt.plusSeconds(1))
        assertEquals(Escalation(EscalationReason.DIAGNOSIS_FAILED, "quota"), quota.state.escalation)
        assertTrue(quota.commands.none { it is Command.CancelDiagnosis })
    }

    @Test fun `takeover during a diagnosis cancels it, and a late answer is kept verbatim`() {
        val d = open().expireState()
        val s = d.on(EpisodeEvent.TakenOver(OPERATOR), T0.plusSeconds(10))
        assertTrue(s.commands.first() is Command.CancelDiagnosis)
        assertEquals(EscalationReason.TAKEN_OVER, s.reason)
        val json = Responses.forRequest(d.request).toString()
        val late = s.on(EpisodeEvent.DiagnosisReturned(json), T0.plusSeconds(20))
        assertEquals(Phase.ESCALATED, late.state.phase)
        assertEquals(json, late.records(RecordKind.IGNORED).single().entry.payload.get("json").textValue())
    }

    @Test fun `an operator decision is a person task without an approval step`() {
        val s = open(incident("incident-6")).expireState().answer()
        assertEquals(CONFIRM_DONE, s.state.attempt!!.candidate!!.candidateId)
        assertEquals(Phase.REVALIDATING, s.state.phase)
        assertNull(s.state.attempt!!.approval)
        assertTrue(s.records(RecordKind.APPROVAL).isEmpty())
        assertEquals("PERSON_TASK", s.records(RecordKind.PROPOSED).single().entry.payload.get("route").textValue())
    }

    @Test fun `a remedy dispatched as often as the threshold escalates as REPEATED_REMEDY`() {
        val d = open().expireState()
        val primed = Step(d.state.copy(dispatchCounts = mapOf(REMEDY to 2)), emptyList())
        assertEquals(EscalationReason.REPEATED_REMEDY, primed.answer().reason)
    }

    @Test fun `the proposal a person saw is approved - a stale one is refused as a value`() {
        val waiting = open(read = policyV1()).expireState().answer()
        val version = waiting.request.candidatesVersion
        val staleId = waiting.on(EpisodeEvent.ApprovalDecided("$INSTANCE#0", version, true, OPERATOR, null, null), T0.plusSeconds(90))
        assertEquals(Reply.REFUSED_STALE, staleId.reply)
        assertEquals(Phase.AWAITING_APPROVAL, staleId.state.phase)
        val staleVersion = waiting.on(EpisodeEvent.ApprovalDecided("$INSTANCE#1", "sha256:" + "0".repeat(64), true, OPERATOR, null, null), T0.plusSeconds(90))
        assertEquals(Reply.REFUSED_STALE, staleVersion.reply)
        val ok = waiting.on(EpisodeEvent.ApprovalDecided("$INSTANCE#1", version, true, OPERATOR, null, null), T0.plusSeconds(90))
        assertEquals(Reply.ACCEPTED, ok.reply)
        assertEquals(Phase.REVALIDATING, ok.state.phase)
        assertEquals(Approval(ApprovalBy.PERSON, OPERATOR, T0.plusSeconds(90)), ok.state.attempt!!.approval)
    }

    @Test fun `a rejection re-diagnoses and hands narrator the closed attempt`() {
        val waiting = open(read = policyV1()).expireState().answer()
        val s = waiting.on(
            EpisodeEvent.ApprovalDecided("$INSTANCE#1", waiting.request.candidatesVersion, false, OPERATOR, RejectReason.WRONG_TARGET, "wrong robot"),
            T0.plusSeconds(90),
        )
        assertEquals(Phase.DIAGNOSING, s.state.phase)
        val request = s.commands.filterIsInstance<Command.Diagnose>().single().request
        assertEquals(2, request.attempt)
        val entry = request.history.single()
        assertEquals(
            listOf("attempt", "candidatesVersion", "diagnosis", "approval", "dispatch", "evidence", "closedAs", "at"),
            entry.fieldNames().asSequence().toList(),
        )
        assertEquals("RECOMMENDED", entry.get("diagnosis").get("outcome").textValue())
        assertEquals(REMEDY, entry.get("diagnosis").get("candidateId").textValue())
        assertEquals("REJECTED", entry.get("approval").get("result").textValue())
        assertEquals("PERSON", entry.get("approval").get("by").textValue())
        assertEquals("WRONG_TARGET", entry.get("approval").get("reason").textValue())
        assertEquals("REDIAGNOSE", entry.get("closedAs").textValue())
        assertTrue(entry.get("dispatch").isNull && entry.get("evidence").isNull)
        assertTrue("wrong robot" !in request.toJson().toString())   // the operator's free text never reaches narrator
        val rediagnose = s.records(RecordKind.REDIAGNOSE).single().entry.payload
        assertEquals("APPROVAL_REJECTED", rediagnose.get("reason").textValue())
        assertEquals("by op-1: wrong robot", rediagnose.get("detail").textValue())
    }

    @Test fun `the approval deadline escalates with the approval recorded as expired`() {
        val s = open(read = policyV1()).expireState().answer().expireState()
        assertEquals(EscalationReason.APPROVAL_EXPIRED, s.reason)
        val approval = s.state.history.single().toJson().get("approval")
        assertEquals("EXPIRED", approval.get("result").textValue())
        assertTrue(approval.get("by").isNull)
    }

    @Test fun `attempts beyond the policy maximum escalate instead of diagnosing`() {
        val one = policyV1 { put("maxAttemptsPerEpisode", 1); put("repeatedRemedyThreshold", 1) }
        val waiting = open(read = one).expireState().answer()
        val s = waiting.on(EpisodeEvent.ApprovalDecided("$INSTANCE#1", waiting.request.candidatesVersion, false, OPERATOR, RejectReason.NOT_NOW, null), T0.plusSeconds(90))
        assertEquals(EscalationReason.ATTEMPTS_EXHAUSTED, s.reason)
        assertTrue(s.commands.none { it is Command.Diagnose })
        assertEquals(ClosedAs.REDIAGNOSE, s.state.history.single().closedAs)
    }

    @Test fun `a decision outside the approval wait is refused as not awaiting`() {
        val s = open().expireState().on(EpisodeEvent.ApprovalDecided("$INSTANCE#1", "sha256:x", true, OPERATOR, null, null), T0.plusSeconds(6))
        assertEquals(Reply.REFUSED_NOT_AWAITING, s.reply)
        assertEquals(Phase.DIAGNOSING, s.state.phase)
    }
}
