package koshei.episode.runtime

import io.temporal.api.enums.v1.RetryState
import io.temporal.api.enums.v1.TimeoutType
import io.temporal.api.failure.v1.ApplicationFailureInfo
import io.temporal.api.failure.v1.Failure
import io.temporal.api.failure.v1.TimeoutFailureInfo
import io.temporal.common.converter.GlobalDataConverter
import io.temporal.failure.ActivityFailure
import io.temporal.failure.DefaultFailureConverter
import io.temporal.failure.ApplicationFailure
import io.temporal.failure.TimeoutFailure
import koshei.episode.DispatchResult
import koshei.episode.EpisodeEvent
import koshei.episode.PolicyRead
import koshei.episode.RejectReason
import koshei.episode.SymptomKind
import koshei.episode.UnknownWhat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConversionsTest {
    @Test fun `a failed dispatch is detailed by the application's cause, not the activity wrapper - through a timeout too`() {
        val refused = ApplicationFailure.newNonRetryableFailure("picasso's window refused the request: 400 {}", "PicassoRequestRefused")
        fun activity(cause: Throwable) = ActivityFailure(
            "Activity task failed", 5, 6, "episodeDispatch", "7", RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE, "worker@host", cause,
        )
        assertEquals(refused.message, dispatchFailureDetail(activity(refused)))
        // ScheduleToClose ran out mid-retry: the timeout says when, its cause (the last attempt's failure) says why. Built as
        // the workflow receives it — decoded from the server's Failure (TimeoutFailure's constructor with a cause is not public).
        val timeout = DefaultFailureConverter().failureToException(
            Failure.newBuilder()
                .setMessage("activity ScheduleToClose timeout")
                .setTimeoutFailureInfo(TimeoutFailureInfo.newBuilder().setTimeoutType(TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE))
                .setCause(
                    Failure.newBuilder()
                        .setMessage("picasso's window refused the request: 400 {}")
                        .setApplicationFailureInfo(ApplicationFailureInfo.newBuilder().setType("PicassoRequestRefused").setNonRetryable(true)),
                )
                .build(),
            GlobalDataConverter.get(),
        )
        assertIs<TimeoutFailure>(timeout)
        val timedOut = dispatchFailureDetail(activity(timeout))
        assertTrue("TIMEOUT_TYPE_SCHEDULE_TO_CLOSE" in timedOut && "PicassoRequestRefused" in timedOut && "400" in timedOut, timedOut)
        assertTrue("Activity task failed" !in timedOut && "worker@host" !in timedOut, timedOut)
        assertEquals("boom", dispatchFailureDetail(IllegalStateException("boom")))
    }

    @Test fun `a symptom signal becomes a symptom, and an unreadable one an empty line the core will refuse`() {
        val ok = SymptomSignal("search:search-1", "SEARCH", Picasso.search("search-1"), 1_000).toSymptom()
        assertEquals(SymptomKind.SEARCH, ok.kind)
        assertEquals("search-1", ok.line.get("searchId").asText())
        assertEquals(1_000, ok.observedAt.toEpochMilli())
        val bad = SymptomSignal("x", "SEARCH", "{not json", 0).toSymptom()
        assertEquals(0, bad.line.size())
        assertEquals(SymptomKind.SEARCH, SymptomSignal("y", "NOT_A_KIND", Picasso.search("search-1"), 0).toSymptom().kind)
        assertEquals(0, SymptomSignal("y", "NOT_A_KIND", Picasso.search("search-1"), 0).toSymptom().line.size())
    }

    @Test fun `a policy read becomes the core's read, and anything odd is unreadable`() {
        assertIs<PolicyRead.Found>(PolicyReadResult("FOUND", TestPolicies.table(true)).toRead())
        assertEquals(PolicyRead.Missing, PolicyReadResult("MISSING").toRead())
        assertIs<PolicyRead.Unreadable>(PolicyReadResult("FOUND", """{"a":1,"a":2}""").toRead())   // duplicate key
        assertIs<PolicyRead.Unreadable>(PolicyReadResult("FOUND", null).toRead())
        assertIs<PolicyRead.Unreadable>(PolicyReadResult("UNREADABLE", null, "yaml: bad indent").toRead())
        assertIs<PolicyRead.Unreadable>(PolicyReadResult("SOMETHING").toRead())
    }

    @Test fun `Update requests become events, and the validators refuse only malformed ones`() {
        val decide = DecideRequest("ep#1", "sha256:x", false, "op-1", "WRONG_TARGET", "note").toEvent()
        assertEquals(EpisodeEvent.ApprovalDecided("ep#1", "sha256:x", false, koshei.episode.Approver("op-1"), RejectReason.WRONG_TARGET, "note"), decide)
        assertFailsWith<IllegalArgumentException> { DecideRequest(operatorId = "").validate() }
        assertFailsWith<IllegalArgumentException> { DecideRequest(proposalId = "p", sawCandidatesVersion = "v", operatorId = "op", reason = "NOPE").validate() }

        assertIs<EpisodeEvent.ConfirmedPrecondition>(ConfirmRequest("PRECONDITION", "c", "ep#1", true, operatorId = "op").toEvent())
        // "It holds" with no proposition is well-formed: the core refuses it as a value in UNKNOWN(PRECONDITION) only, and in
        // any other phase the control plane's relay must still get the core's own refusal, not a validator's 400 (§7.2, §8.3).
        ConfirmRequest("PRECONDITION", "c", "ep#1", true, operatorId = "op").validate()
        assertIs<EpisodeEvent.ConfirmedOutcome>(ConfirmRequest("OUTCOME", "c", "ep#1", true, operatorId = "op").toEvent())
        val unknown = ConfirmRequest("UNKNOWN", subject = mapOf("unitId" to "u-1"), what = "LINK_BROKEN", operatorId = "op").toEvent()
        assertIs<EpisodeEvent.ConfirmedUnknown>(unknown)
        assertEquals(UnknownWhat.LINK_BROKEN, unknown.what)
        assertFailsWith<IllegalArgumentException> { ConfirmRequest("MAYBE", operatorId = "op").validate() }
        assertFailsWith<IllegalArgumentException> { ConfirmRequest("UNKNOWN", what = "LINK_BROKEN", operatorId = "op").validate() }   // no subject
        assertFailsWith<IllegalArgumentException> { ConfirmRequest("OUTCOME", candidateId = "c", operatorId = "op").validate() }        // no proposal
        assertFailsWith<IllegalArgumentException> { TakeoverRequest("").validate() }
        assertFailsWith<IllegalArgumentException> { CloseRequest("op", "").validate() }
    }

    @Test fun `a precondition confirmation carries the proposition the control plane filled in`() {
        val event = ConfirmRequest("PRECONDITION", "c", "p", true, operatorId = "op", proposition = "명제").toEvent()
        assertIs<EpisodeEvent.ConfirmedPrecondition>(event)
        assertEquals("명제", event.proposition)
    }

    @Test fun `a dispatch outcome becomes the core's result, and anything odd is uncertain`() {
        assertEquals(DispatchResult.Answer(PICASSO_APPROVED), DispatchOutcome("ANSWER", PICASSO_APPROVED).toResult())
        assertEquals(DispatchResult.PersonTaskIssued, DispatchOutcome("PERSON_TASK").toResult())
        assertIs<DispatchResult.Uncertain>(DispatchOutcome("ANSWER", null).toResult())
        assertIs<DispatchResult.Uncertain>(DispatchOutcome("SOMETHING").toResult())
    }

    @Test fun `an evidence signal keeps null as not reported`() {
        val e = EvidenceSignal("jr-1", "exec-42", null, null, listOf("u-1"), null, emptyList(), null, 5).toEvidence()
        assertEquals(listOf("u-1"), e.completedUnits)
        assertEquals(null, e.inDoubtUnits)
        assertEquals(null, e.operatorRequired)
        assertTrue(e.unverifiedUnits!!.isEmpty())
    }
}
