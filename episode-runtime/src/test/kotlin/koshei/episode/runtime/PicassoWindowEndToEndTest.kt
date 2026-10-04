package koshei.episode.runtime

import com.sun.net.httpserver.HttpServer
import io.temporal.client.WorkflowClient
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real activities over the test Postgres, the Mock narrator and policy v1 — with the window the worker picks from
 * `KOSHEI_PICASSO=picasso`: [HttpApprovalWindow] against an in-test `HttpServer` standing in for picasso's `ApprovalHost`.
 */
class PicassoWindowEndToEndTest {
    private var env: TestWorkflowEnvironment? = null
    private lateinit var client: WorkflowClient
    private lateinit var store: EpisodeStore
    private val v1: Path = Path.of(System.getProperty("koshei.repoRoot"), "model", "episode-policy", "active.yaml")

    private val received = CopyOnWriteArrayList<Pair<String, String>>()   // (method + path, body)
    @Volatile private var status = 200
    @Volatile private var answer = PICASSO_APPROVED
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { ex ->
            received += "${ex.requestMethod} ${ex.requestURI.path}" to ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val bytes = answer.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @BeforeEach fun up() { store = EpisodeDb.reset() }
    @AfterEach fun down() {
        env?.close()
        server.stop(0)
    }

    /** The worker as `app` registers it: no window passed, so the configuration chooses one. */
    private fun start(): TestWorkflowEnvironment {
        val config = EpisodeRuntimeConfig.fromEnv(mapOf(
            "KOSHEI_PICASSO" to "picasso",
            "KOSHEI_PICASSO_URL" to "http://127.0.0.1:${server.address.port}",
            "KOSHEI_PICASSO_AGENT_ID" to "narrator-1",
            "KOSHEI_EPISODE_POLICY" to v1.toString(),
        ))
        val e = episodeEnvironment { EpisodeWorkers.register(workerFactory, config, store) }
        e.start()
        env = e
        client = e.workflowClient
        return e
    }

    /** Approve as op-7, meet UNKNOWN(PRECONDITION) (the window cannot revalidate), and confirm the proposition the card shows. */
    private fun approveAndConfirm(e: TestWorkflowEnvironment): Pair<EpisodeWorkflow, EpisodeView> {
        val ep = client.openEpisode()
        val waiting = ep.until(e) { it.phase == "AWAITING_APPROVAL" }   // auto-approval is off in v1
        assertEquals("ACCEPTED", ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-7")))
        val unknown = ep.until(e) { it.phase == "UNKNOWN_PRECONDITION" }
        assertTrue(received.isEmpty(), "nothing goes to the window before the precondition is confirmed: $received")
        val confirm = ConfirmRequest(
            "PRECONDITION", unknown.candidateId!!, unknown.proposalId!!, true, operatorId = "op-7",
            proposition = "(hum-02, PATROL-1)에 searchId search-1 뒤로 더 새 탐색 줄이 없다",
        )
        assertEquals("ACCEPTED", ep.confirm(confirm))
        return ep to waiting
    }

    private fun dispatchResult(instanceId: String) = eventually(describe = { "events ${store.events(instanceId).map { it.kind }}" }) {
        store.events(instanceId).firstOrNull { it.kind == "DISPATCH_RESULT" }
    }.let { strictJson.readTree(it.payloadJson) }

    @Test fun `with KOSHEI_PICASSO=picasso a person's approval reaches the window over HTTP - after a person confirms the precondition`() {
        val e = start()
        val (ep, waiting) = approveAndConfirm(e)
        ep.until(e) { it.phase == "AWAITING_EVIDENCE" }
        val (where, body) = received.single()
        assertEquals("POST /approvals", where)
        assertEquals(
            strictJson.readTree("""{"approverId":"op-7","approverKind":"PERSON","robotId":"hum-02","jobOrderId":"PATROL-1","sawSkillTypes":["pick_place"]}"""),
            strictJson.readTree(body),
        )
        val result = dispatchResult(waiting.instanceId)
        assertEquals("ACCEPTED", result["judgement"].textValue())
        assertEquals(PICASSO_APPROVED, result["answer"].textValue())   // the window's text, verbatim
    }

    @Test fun `a request the window refuses with 400 ends the dispatch as unknown, once, without retries`() {
        status = 400
        answer = """{"error":"승인 요청에 «robotId» 이 없다"}"""
        val e = start()
        val (ep, waiting) = approveAndConfirm(e)
        ep.until(e) { it.phase == "UNKNOWN_OUTCOME" }
        val result = dispatchResult(waiting.instanceId)
        assertEquals("UNCERTAIN", result["judgement"].textValue())
        val detail = result["detail"].textValue()
        assertTrue("PicassoRequestRefused" in detail && "400" in detail, detail)
        // DISPATCH_RESULT is recorded only once the dispatch activity has failed for good, so every attempt it made — a
        // retry included — reached the server before it: the count is final here, with nothing to wait for.
        assertEquals(1, received.size, "the refused request is not retried: $received")
    }
}
