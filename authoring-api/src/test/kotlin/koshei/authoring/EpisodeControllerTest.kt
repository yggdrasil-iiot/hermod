package koshei.authoring

import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.client.WorkflowQueryException
import io.temporal.client.WorkflowServiceException
import jakarta.servlet.ServletException
import koshei.episode.runtime.CloseRequest
import koshei.episode.runtime.ConfirmRequest
import koshei.episode.runtime.DecideRequest
import koshei.episode.runtime.EpisodeReader
import koshei.episode.runtime.EpisodeView
import koshei.episode.runtime.StoredEvent
import koshei.episode.runtime.StoredInstance
import koshei.episode.runtime.StoredNotice
import koshei.episode.runtime.TakeoverRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.time.Instant
import kotlin.test.assertIs

private fun <T> anyNonNull(): T { ArgumentMatchers.any<T>(); @Suppress("UNCHECKED_CAST") return null as T }

@WebMvcTest(EpisodeController::class)
class EpisodeControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @MockBean lateinit var gateway: EpisodeGateway
    @MockBean lateinit var reader: EpisodeReader

    private val t = Instant.parse("2026-10-02T00:00:00Z")
    private val id = "ep:a/r1"
    private val version = "sha256:" + "a".repeat(64)
    // The card's decision needs a proposal (OperatorCards.kt): the attempt's candidate, an APPROVE_REMEDY with a full ref.
    private val waiting = EpisodeView(
        instanceId = id, phase = "AWAITING_APPROVAL", attempt = 1, proposalId = "$id#1", candidateId = "c", candidatesVersion = version,
        candidateJson = """{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{"robotId":"r","jobOrderId":"j","searchId":"s"},"sawSkillTypes":[]}""",
    )

    @BeforeEach fun clear() = reset(gateway, reader)

    private fun postJson(path: String, body: String, operator: String? = "op-1") =
        post(path).contentType(APPLICATION_JSON).content(body).apply { if (operator != null) header("X-Koshei-Operator", operator) }

    @Test fun `the list reads the records`() {
        given(reader.instances(2)).willReturn(listOf(StoredInstance(id, "ep:a", t, t, "AWAITING_APPROVAL", 1, false)))
        mvc.perform(get("/api/episodes?limit=2")).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].instanceId").value(id)).andExpect(jsonPath("$[0].workflowId").value("ep:a"))
            .andExpect(jsonPath("$[0].lastPhase").value("AWAITING_APPROVAL")).andExpect(jsonPath("$[0].notices").value(1))
            .andExpect(jsonPath("$[0].recordLag").value(false))
    }

    @Test fun `the list limit is bounded`() {
        mvc.perform(get("/api/episodes?limit=0")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/episodes?limit=501")).andExpect(status().isBadRequest)
        verifyNoInteractions(reader)
    }

    @Test fun `the detail joins the live view, the card, the records and the notices`() {
        given(gateway.view(id)).willReturn(waiting)
        given(reader.events(id)).willReturn(listOf(StoredEvent(1, "OPENED", """{"eventId":"s-1","kind":"SEARCH","line":{"searchId":"s-1"}}""", t)))
        given(reader.noticeRows(id)).willReturn(listOf(StoredNotice(7, id, """{"kind":"APPROVAL_NEEDED"}""", t)))
        mvc.perform(get("/api/episodes/ep:a/r1")).andExpect(status().isOk)
            .andExpect(jsonPath("$.instanceId").value(id))
            .andExpect(jsonPath("$.view.phase").value("AWAITING_APPROVAL"))
            .andExpect(jsonPath("$.card.decision.update").value("decide"))
            .andExpect(jsonPath("$.events[0].kind").value("OPENED"))
            .andExpect(jsonPath("$.events[0].payload.eventId").value("s-1"))
            .andExpect(jsonPath("$.notices[0].id").value(7))
            .andExpect(jsonPath("$.notices[0].notice.kind").value("APPROVAL_NEEDED"))
    }

    @Test fun `an ended instance answers from its records, and an unknown one is not found`() {
        given(gateway.view(id)).willReturn(null)
        given(reader.events(id)).willReturn(listOf(StoredEvent(1, "OPENED", "{}", t)))
        given(reader.noticeRows(id)).willReturn(emptyList())
        mvc.perform(get("/api/episodes/ep:a/r1")).andExpect(status().isOk)
            .andExpect(jsonPath("$.view").doesNotExist()).andExpect(jsonPath("$.card").doesNotExist())
        given(gateway.view("ep:b/r1")).willThrow(EpisodeNotFound("ep:b"))
        given(reader.events("ep:b/r1")).willReturn(emptyList())
        mvc.perform(get("/api/episodes/ep:b/r1")).andExpect(status().isNotFound)
    }

    @Test fun `the detail still answers from the records when Temporal does not`() {
        given(gateway.view(id)).willThrow(StatusRuntimeException(Status.UNAVAILABLE))
        given(reader.events(id)).willReturn(listOf(StoredEvent(1, "OPENED", "{}", t)))
        given(reader.noticeRows(id)).willReturn(emptyList())
        mvc.perform(get("/api/episodes/ep:a/r1")).andExpect(status().isOk)
            .andExpect(jsonPath("$.view").doesNotExist()).andExpect(jsonPath("$.viewError").exists())
    }

    @Test fun `a decision is relayed under the operator header and its value comes back`() {
        given(gateway.decide(id, DecideRequest("$id#1", version, true, "op-1", null, null))).willReturn("ACCEPTED")
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", """{"proposalId":"$id#1","sawCandidatesVersion":"$version","approve":true}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.reply").value("ACCEPTED"))
        given(gateway.decide(id, DecideRequest("$id#0", version, true, "op-1", null, null))).willReturn("REFUSED_STALE")
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", """{"proposalId":"$id#0","sawCandidatesVersion":"$version","approve":true}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.reply").value("REFUSED_STALE"))
    }

    @Test fun `no operator, a blank one, a long one, an operator in the body, an unknown field or a missing approve is refused`() {
        val ok = """{"proposalId":"p","sawCandidatesVersion":"v","approve":true}"""
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", ok, operator = null)).andExpect(status().isBadRequest)
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", ok, operator = "  ")).andExpect(status().isBadRequest)
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", ok, operator = "x".repeat(129))).andExpect(status().isBadRequest)
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", """{"proposalId":"p","sawCandidatesVersion":"v","approve":true,"operatorId":"someone"}"""))
            .andExpect(status().isBadRequest)
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", """{"proposalId":"p","sawCandidatesVersion":"v","aprove":true}""")).andExpect(status().isBadRequest)
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", """{"proposalId":"p","sawCandidatesVersion":"v"}""")).andExpect(status().isBadRequest)
        verifyNoInteractions(gateway)
    }

    @Test fun `the gateway's refusals map to their statuses`() {
        val body = """{"proposalId":"p","sawCandidatesVersion":"v","approve":true}"""
        // doThrow form: a given(...) on a method already stubbed to throw would call it, and throw, while stubbing.
        fun answer(e: RuntimeException) = willThrow(e).given(gateway).decide(anyNonNull(), anyNonNull())
        answer(InvalidEpisodeRequest("bad"))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("bad"))
        answer(EpisodeMoved(id, "ep:a/r2"))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("EPISODE_MOVED")).andExpect(jsonPath("$.currentInstanceId").value("ep:a/r2"))
        answer(EpisodeEnded(id))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)).andExpect(status().isConflict).andExpect(jsonPath("$.error").value("EPISODE_ENDED"))
        answer(EpisodeNotFound("ep:a"))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)).andExpect(status().isNotFound)
        answer(StatusRuntimeException(Status.UNAVAILABLE))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)).andExpect(status().isServiceUnavailable)
        answer(EpisodeTimeout(id))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)).andExpect(status().isGatewayTimeout)
            .andExpect(jsonPath("$.error").value("EPISODE_TIMEOUT")).andExpect(jsonPath("$.outcome").value("UNKNOWN"))
            .andExpect(jsonPath("$.instanceId").value(id))
        answer(WorkflowQueryException(WorkflowExecution.getDefaultInstance(), "EpisodeWorkflow", StatusRuntimeException(Status.DEADLINE_EXCEEDED)))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)).andExpect(status().isServiceUnavailable)
        answer(WorkflowServiceException(WorkflowExecution.getDefaultInstance(), "EpisodeWorkflow", StatusRuntimeException(Status.UNAVAILABLE)))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)).andExpect(status().isServiceUnavailable)
    }

    @Test fun `a Temporal failure that is not about reaching Temporal stays a server error`() {
        val body = """{"proposalId":"p","sawCandidatesVersion":"v","approve":true}"""
        willThrow(WorkflowServiceException(WorkflowExecution.getDefaultInstance(), "EpisodeWorkflow", StatusRuntimeException(Status.PERMISSION_DENIED)))
            .given(gateway).decide(anyNonNull(), anyNonNull())
        val e = assertThrows<ServletException> { mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)) }
        assertIs<WorkflowServiceException>(e.cause)
        willThrow(StatusRuntimeException(Status.INTERNAL)).given(gateway).decide(anyNonNull(), anyNonNull())
        assertIs<StatusRuntimeException>(assertThrows<ServletException> { mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)) }.cause)
    }

    @Test fun `a body is strict - no number or text for a flag, no duplicated field, nothing after it`() {
        for (body in listOf(
            """{"proposalId":"p","sawCandidatesVersion":"v","approve":0}""",
            """{"proposalId":"p","sawCandidatesVersion":"v","approve":1}""",
            """{"proposalId":"p","sawCandidatesVersion":"v","approve":"true"}""",
            """{"proposalId":"p","sawCandidatesVersion":"v","approve":false,"approve":true}""",
            """{"proposalId":"p","sawCandidatesVersion":"v","approve":true} {"approve":false}""",
            """{"proposalId":"p","sawCandidatesVersion":"v","approve":true}x""",
            "null",
            "",
        )) mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)).andExpect(status().isBadRequest)
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"OUTCOME","candidateId":"c","proposalId":"p","holds":"false"}"""))
            .andExpect(status().isBadRequest)
        verifyNoInteractions(gateway)
    }

    @Test fun `a body over 64 KiB is refused before it is read as JSON`() {
        val note = "x".repeat(64 * 1024)
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", """{"proposalId":"p","sawCandidatesVersion":"v","approve":true,"note":"$note"}"""))
            .andExpect(status().isPayloadTooLarge)
        verifyNoInteractions(gateway)
        val fits = "x".repeat(60 * 1024)
        given(gateway.decide(id, DecideRequest("p", "v", true, "op-1", null, fits))).willReturn("ACCEPTED")
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", """{"proposalId":"p","sawCandidatesVersion":"v","approve":true,"note":"$fits"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.reply").value("ACCEPTED"))
    }

    @Test fun `the operator header is trimmed, and a long one is told so`() {
        given(gateway.decide(id, DecideRequest("p", "v", true, "op-1", null, null))).willReturn("ACCEPTED")
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", """{"proposalId":"p","sawCandidatesVersion":"v","approve":true}""", operator = "  op-1 \t"))
            .andExpect(status().isOk).andExpect(jsonPath("$.reply").value("ACCEPTED"))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", """{"proposalId":"p","sawCandidatesVersion":"v","approve":true}""", operator = "x".repeat(129)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("X-Koshei-Operator is longer than 128 characters (design §7.2)"))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", """{"proposalId":"p","sawCandidatesVersion":"v","approve":true}""", operator = null))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("X-Koshei-Operator is required (design §7.2)"))
    }

    @Test fun `the notice feed limit is bounded`() {
        mvc.perform(get("/api/episodes/notices?limit=0")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/episodes/notices?limit=501")).andExpect(status().isBadRequest)
        verifyNoInteractions(reader)
    }

    @Test fun `a workflow Temporal no longer has still answers from its records`() {
        given(gateway.view(id)).willThrow(EpisodeNotFound("ep:a"))
        given(reader.events(id)).willReturn(listOf(StoredEvent(1, "OPENED", "{}", t)))
        given(reader.noticeRows(id)).willReturn(emptyList())
        mvc.perform(get("/api/episodes/ep:a/r1")).andExpect(status().isOk)
            .andExpect(jsonPath("$.view").doesNotExist()).andExpect(jsonPath("$.events[0].kind").value("OPENED"))
    }

    @Test fun `an unknown run of a known workflow is not found`() {
        given(gateway.view("ep:a/nope")).willReturn(null)   // the workflow runs, but this is not its run
        given(reader.events("ep:a/nope")).willReturn(emptyList())
        given(reader.noticeRows("ep:a/nope")).willReturn(emptyList())
        mvc.perform(get("/api/episodes/ep:a/nope")).andExpect(status().isNotFound)
    }

    @Test fun `any Temporal failure on the live view leaves the detail to its records`() {
        given(reader.events(id)).willReturn(listOf(StoredEvent(1, "OPENED", "{}", t)))
        given(reader.noticeRows(id)).willReturn(emptyList())
        for (e in listOf(
            WorkflowServiceException(WorkflowExecution.getDefaultInstance(), "EpisodeWorkflow", StatusRuntimeException(Status.PERMISSION_DENIED)),
            WorkflowQueryException(WorkflowExecution.getDefaultInstance(), "EpisodeWorkflow", RuntimeException("no worker")),
            EpisodeTimeout(id),
        )) {
            reset(gateway)
            given(gateway.view(id)).willThrow(e)
            mvc.perform(get("/api/episodes/ep:a/r1")).andExpect(status().isOk)
                .andExpect(jsonPath("$.view").doesNotExist()).andExpect(jsonPath("$.viewError").exists())
        }
    }

    @Test fun `unreachable database connections are a 503`() {
        given(reader.instances(50)).willAnswer { throw SQLTransientConnectionException("pool exhausted") }
        mvc.perform(get("/api/episodes")).andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.error").value("pool exhausted"))
    }

    @Test fun `a precondition confirmation that does not name the card's proposal goes on without a proposition`() {
        val unknown = waiting.copy(phase = "UNKNOWN_PRECONDITION")   // the card has a proposition for c / ep:a/r1#1
        given(gateway.view(id)).willReturn(unknown)
        given(reader.events(id)).willReturn(emptyList())
        given(gateway.confirm(id, ConfirmRequest("PRECONDITION", "c", "$id#0", true, emptyMap(), "", "op-1", null, null))).willReturn("REFUSED_STALE")
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"PRECONDITION","candidateId":"c","proposalId":"$id#0","holds":true}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.reply").value("REFUSED_STALE"))
        given(gateway.confirm(id, ConfirmRequest("PRECONDITION", "d", "$id#1", true, emptyMap(), "", "op-1", null, null))).willReturn("REFUSED_SUBJECT_NOT_UNKNOWN")
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"PRECONDITION","candidateId":"d","proposalId":"$id#1","holds":true}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.reply").value("REFUSED_SUBJECT_NOT_UNKNOWN"))
    }

    @Test fun `'the precondition does not hold' needs no proposition`() {
        val noProposition = waiting.copy(
            phase = "UNKNOWN_PRECONDITION",
            candidateJson = """{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{"robotId":"r","jobOrderId":"j","searchId":null},"sawSkillTypes":[]}""",
        )
        given(gateway.view(id)).willReturn(noProposition)
        given(reader.events(id)).willReturn(emptyList())
        given(gateway.confirm(id, ConfirmRequest("PRECONDITION", "c", "$id#1", false, emptyMap(), "", "op-1", null, null))).willReturn("ACCEPTED")
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"PRECONDITION","candidateId":"c","proposalId":"$id#1","holds":false}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.reply").value("ACCEPTED"))
    }

    @Test fun `missing tables are a 503, not a 500`() {
        given(reader.instances(50)).willAnswer { throw SQLException("relation \"episode_event\" does not exist", "42P01") }
        mvc.perform(get("/api/episodes")).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").value("episode tables not found — no episode worker has run (KOSHEI_PICASSO)"))
    }

    @Test fun `a precondition confirmation carries the card's proposition, never the client's`() {
        val unknown = waiting.copy(
            phase = "UNKNOWN_PRECONDITION",
            candidateJson = """{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{"robotId":"r","jobOrderId":"j","searchId":"s"},"sawSkillTypes":[]}""",
        )
        given(gateway.view(id)).willReturn(unknown)
        given(reader.events(id)).willReturn(emptyList())
        val sent = ConfirmRequest("PRECONDITION", "c", "$id#1", true, emptyMap(), "", "op-1", null, "(r, j)에 searchId s 뒤로 더 새 탐색 줄이 없다")
        given(gateway.confirm(id, sent)).willReturn("ACCEPTED")
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"PRECONDITION","candidateId":"c","proposalId":"$id#1","holds":true}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.reply").value("ACCEPTED"))
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"PRECONDITION","candidateId":"c","proposalId":"$id#1","holds":true,"proposition":"x"}"""))
            .andExpect(status().isBadRequest)
    }

    @Test fun `a precondition confirmation outside UNKNOWN_PRECONDITION is relayed and refused by the core as a value`() {
        given(gateway.view(id)).willReturn(waiting)   // AWAITING_APPROVAL
        given(reader.events(id)).willReturn(emptyList())
        given(gateway.confirm(id, ConfirmRequest("PRECONDITION", "c", "$id#1", true, emptyMap(), "", "op-1", null, null))).willReturn("REFUSED_SUBJECT_NOT_UNKNOWN")
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"PRECONDITION","candidateId":"c","proposalId":"$id#1","holds":true}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.reply").value("REFUSED_SUBJECT_NOT_UNKNOWN"))
    }

    @Test fun `with no proposition on the card, 'the precondition holds' is refused`() {
        // The candidate is named but its ref lacks the searchId: no proposition can be filled (§8.3).
        val noProposition = waiting.copy(
            phase = "UNKNOWN_PRECONDITION",
            candidateJson = """{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{"robotId":"r","jobOrderId":"j","searchId":null},"sawSkillTypes":[]}""",
        )
        given(gateway.view(id)).willReturn(noProposition)
        given(reader.events(id)).willReturn(emptyList())
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"PRECONDITION","candidateId":"c","proposalId":"$id#1","holds":true}"""))
            .andExpect(status().isBadRequest)
        verify(gateway, never()).confirm(anyNonNull(), anyNonNull())
    }

    @Test fun `outcome and unknown confirmations, takeover and close reach the gateway`() {
        given(gateway.confirm(id, ConfirmRequest("OUTCOME", "c", "$id#1", false, emptyMap(), "", "op-1", null, null))).willReturn("ACCEPTED")
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"OUTCOME","candidateId":"c","proposalId":"$id#1","holds":false}"""))
            .andExpect(jsonPath("$.reply").value("ACCEPTED"))
        given(gateway.confirm(id, ConfirmRequest("UNKNOWN", "", "", true, mapOf("robotId" to "r"), "OBSERVATION_ABSENT", "op-1", "seen", null))).willReturn("ACCEPTED")
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"UNKNOWN","subject":{"robotId":"r"},"what":"OBSERVATION_ABSENT","holds":true,"note":"seen"}"""))
            .andExpect(jsonPath("$.reply").value("ACCEPTED"))
        given(gateway.takeover(id, TakeoverRequest("op-1"))).willReturn("ACCEPTED")
        mvc.perform(post("/api/episodes/ep:a/r1/takeover").header("X-Koshei-Operator", "op-1")).andExpect(jsonPath("$.reply").value("ACCEPTED"))
        given(gateway.close(id, CloseRequest("op-1", "handled"))).willReturn("REFUSED_NOT_ESCALATED")
        mvc.perform(postJson("/api/episodes/ep:a/r1/close", """{"outcome":"handled"}""")).andExpect(jsonPath("$.reply").value("REFUSED_NOT_ESCALATED"))
    }

    @Test fun `the notice feed continues after an id and does not collide with an episode path`() {
        given(reader.noticesAfter(5, 10)).willReturn(listOf(StoredNotice(6, id, """{"kind":"PERSON_TASK","idempotencyKey":"k","intent":{}}""", t)))
        mvc.perform(get("/api/episodes/notices?after=5&limit=10")).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(6)).andExpect(jsonPath("$[0].instanceId").value(id))
            .andExpect(jsonPath("$[0].notice.kind").value("PERSON_TASK"))
    }
}
