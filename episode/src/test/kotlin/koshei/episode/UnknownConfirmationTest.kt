package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import koshei.episode.Episodes.OPERATOR
import koshei.episode.Episodes.REMEDY
import koshei.episode.Episodes.T0
import koshei.episode.Episodes.incident
import koshei.episode.Episodes.open
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnknownConfirmationTest {
    /** CORRELATING with the LINK_BROKEN incident-6 joined, observed at T0+1s. */
    private fun withUnknown(): Step = open().on(EpisodeEvent.SymptomJoined(incident("incident-6", T0.plusSeconds(1))), T0.plusSeconds(1))

    private fun Step.confirmFirst(at: Instant, note: String? = "checked the link"): Step {
        val u = state.currentUnknowns().first()
        return on(EpisodeEvent.ConfirmedUnknown(u.subject, u.what, OPERATOR, note), at)
    }

    @Test fun `a person's confirmation removes the unknown and execution candidates come back`() {
        val joined = withUnknown()
        assertEquals(listOf(UnknownWhat.LINK_BROKEN), joined.state.currentUnknowns().map { it.what })
        val confirmed = joined.confirmFirst(T0.plusSeconds(2))
        assertEquals(Reply.ACCEPTED, confirmed.reply)
        assertTrue(confirmed.state.currentUnknowns().isEmpty())
        val diagnosing = confirmed.expireState()
        assertTrue(diagnosing.request.unknowns.isEmpty())
        assertTrue(REMEDY in diagnosing.request.candidates.map { it.candidateId })
        assertTrue(diagnosing.request.candidates.any { it.candidateId.endsWith(":REWORK") })
    }

    @Test fun `a line that arrives after the confirmation brings the unknown back, whatever its clock says`() {
        val confirmed = withUnknown().confirmFirst(T0.plusSeconds(2))
        val again = mapper.readTree(PicassoRun1.incident("incident-6")) as ObjectNode
        again.put("digest", "digest-later")
        // observed (by the watcher's clock) BEFORE the confirmation, but it arrived after it
        val later = confirmed.on(
            EpisodeEvent.SymptomJoined(Symptom("incident:again", SymptomKind.INCIDENT, again, T0.plusMillis(1500))),
            T0.plusSeconds(3),
        )
        assertEquals(listOf(UnknownWhat.LINK_BROKEN), later.state.currentUnknowns().map { it.what })
    }

    @Test fun `confirming one kind leaves another kind on the same unit unknown`() {
        val line = mapper.readTree(PicassoRun1.incident("incident-6")) as ObjectNode
        (line.get("observation") as ObjectNode).putNull("progressObservable")
        val joined = open().on(EpisodeEvent.SymptomJoined(Symptom("incident:two-kinds", SymptomKind.INCIDENT, line, T0)), T0.plusSeconds(1))
        assertEquals(listOf(UnknownWhat.LINK_BROKEN, UnknownWhat.PROGRESS_UNOBSERVED), joined.state.currentUnknowns().map { it.what })
        val link = joined.state.currentUnknowns().first()
        val s = joined.on(EpisodeEvent.ConfirmedUnknown(link.subject, UnknownWhat.LINK_BROKEN, OPERATOR, null), T0.plusSeconds(2))
        assertEquals(listOf(UnknownWhat.PROGRESS_UNOBSERVED), s.state.currentUnknowns().map { it.what })
        val diagnosing = s.expireState()
        assertTrue(REMEDY !in diagnosing.request.candidates.map { it.candidateId })   // still withheld
    }

    @Test fun `only what is unknown now can be confirmed, and not once escalated`() {
        val nothing = open().on(EpisodeEvent.ConfirmedUnknown(mapOf("unitId" to "u-9"), UnknownWhat.OUTCOME, OPERATOR, null), T0.plusSeconds(1))
        assertEquals(Reply.REFUSED_SUBJECT_NOT_UNKNOWN, nothing.reply)
        val escalated = withUnknown().on(EpisodeEvent.TakenOver(OPERATOR), T0.plusSeconds(2))
        val u = escalated.state.currentUnknowns().first()
        val late = escalated.on(EpisodeEvent.ConfirmedUnknown(u.subject, u.what, OPERATOR, null), T0.plusSeconds(3))
        assertEquals(Reply.REFUSED_SUBJECT_NOT_UNKNOWN, late.reply)
        assertEquals(1, late.state.currentUnknowns().size)
    }

    @Test fun `the candidate's own outcome is confirmed through the outcome check, not here`() {
        val u = dispatched().returned(DispatchResult.Answer(PicassoAnswers.refused("NO_PROPOSAL")))
        val own = u.state.episodeUnknowns.single()
        val s = u.on(EpisodeEvent.ConfirmedUnknown(own.subject, own.what, OPERATOR, null), u.state.enteredAt.plusSeconds(5))
        assertEquals(Reply.REFUSED_SUBJECT_NOT_UNKNOWN, s.reply)
        assertEquals(1, s.state.episodeUnknowns.size)
    }

    @Test fun `a unit a JobResponse left in doubt can be confirmed by a person`() {
        val u = dispatched().returned(DispatchResult.Answer(PicassoAnswers.approved()))
            .let { it.on(EpisodeEvent.EvidenceArrived(jobResponse("jr-1", inDoubt = listOf("u-1"))), it.state.enteredAt.plusSeconds(5)) }
        assertEquals(Phase.UNKNOWN_OUTCOME, u.state.phase)
        val unit = u.state.episodeUnknowns.single { it.subject["unitId"] == "u-1" }
        val s = u.on(EpisodeEvent.ConfirmedUnknown(unit.subject, unit.what, OPERATOR, "on site"), u.state.enteredAt.plusSeconds(10))
        assertEquals(Reply.ACCEPTED, s.reply)
        assertTrue(s.state.episodeUnknowns.none { it.subject["unitId"] == "u-1" })
        assertEquals(Phase.UNKNOWN_OUTCOME, s.state.phase)   // the attempt's own outcome is still the outcome check's to decide
        val record = s.records(RecordKind.CONFIRMATION).single().entry.payload
        assertEquals("UNKNOWN", record.get("kind").textValue())
        assertEquals("op-1", record.get("by").textValue())
        assertEquals("on site", record.get("note").textValue())
        assertEquals("u-1", record.get("subject").get("unitId").textValue())
    }

    @Test fun `a confirmation lets the gate pass`() {
        val revalidating = open().expireState().answer()
        val at = revalidating.state.enteredAt
        val joined = revalidating.on(EpisodeEvent.SymptomJoined(incident("incident-6", at)), at.plusMillis(100))
        val confirmed = joined.confirmFirst(at.plusMillis(200))
        assertEquals(Phase.DISPATCH_PENDING, confirmed.revalidated(TriState.TRUE, at.plusSeconds(1)).state.phase)
    }
}
