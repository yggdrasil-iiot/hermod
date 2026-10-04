package koshei.episode.runtime

import com.sun.net.httpserver.HttpServer
import io.temporal.failure.ApplicationFailure
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpApprovalWindowTest {
    private val received = CopyOnWriteArrayList<Pair<String, String>>()   // (method + path, body)
    @Volatile private var status = 200
    @Volatile private var answer = PICASSO_APPROVED
    @Volatile private var delayMs = 0L
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { ex ->
            received += "${ex.requestMethod} ${ex.requestURI.path}" to ex.requestBody.readBytes().toString(Charsets.UTF_8)
            if (delayMs > 0) Thread.sleep(delayMs)
            val bytes = answer.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @AfterEach fun down() = server.stop(0)

    private fun window(timeoutMs: Long = 2_000) =
        HttpApprovalWindow(PicassoWindowConfig(URI("http://127.0.0.1:${server.address.port}/approvals"), "narrator-1", timeoutMs))

    private fun intent(approval: String = """{"by":"PERSON","approverId":"op-7","assurance":"HEADER","at":"2026-10-04T00:00:00Z"}""") = strictJson.readTree(
        """{"candidate":{"candidateId":"c-1","kind":"APPROVE_REMEDY","ref":{"robotId":"hum-02","jobOrderId":"PATROL-APPROVES","searchId":"search-1"},""" +
            """"sawSkillTypes":["pick_place","place"]},"idempotencyKey":"ep:k/r:1:APPROVE_REMEDY","approval":$approval}""",
    )

    @Test fun `a person's approval posts exactly picasso's five fields to slash approvals and returns the answer verbatim`() {
        assertEquals(PICASSO_APPROVED, window().approve(intent(), "PERSON"))
        val (where, body) = received.single()
        assertEquals("POST /approvals", where)
        assertEquals(
            strictJson.readTree("""{"approverId":"op-7","approverKind":"PERSON","robotId":"hum-02","jobOrderId":"PATROL-APPROVES","sawSkillTypes":["pick_place","place"]}"""),
            strictJson.readTree(body),
        )
    }

    @Test fun `a policy approval goes as AGENT under the configured agent id`() {
        window().approve(intent("""{"by":"POLICY","approverId":null,"assurance":null,"at":"2026-10-04T00:00:00Z"}"""), "AGENT")
        val body = strictJson.readTree(received.single().second)
        assertEquals("AGENT", body["approverKind"].textValue())
        assertEquals("narrator-1", body["approverId"].textValue())
    }

    @Test fun `a refusal is an answer too - 200 is returned verbatim, the core judges it`() {
        answer = """{"schemaVersion":"4","contractSemver":"0.9.0","instanceId":"mw-1","outcome":"REFUSED","refusal":"NOT_DECLARED","reason":"선언 없음","consumed":null}"""
        assertEquals(answer, window().approve(intent(), "PERSON"))
    }

    @Test fun `a request picasso cannot read is not retried - 400, 405 and anything not 200`() {
        for ((s, body) in listOf(400 to """{"error":"승인 요청에 «robotId» 이 없다"}""", 405 to """{"error":"승인 시도는 POST 다"}""", 404 to "<h1>404 Not Found</h1>")) {
            status = s
            answer = body
            val e = assertThrows<ApplicationFailure>("$s") { window().approve(intent(), "PERSON") }
            assertTrue(e.isNonRetryable, "$s")
            assertEquals("PicassoRequestRefused", e.type, "$s")
            assertTrue("$s" in e.originalMessage && body.take(20) in e.originalMessage, e.originalMessage)
        }
    }

    @Test fun `a person approval without the operator's id is a bad intent - never invented`() {
        val e = assertThrows<ApplicationFailure> { window().approve(intent("""{"by":"PERSON","approverId":null,"assurance":null,"at":"x"}"""), "PERSON") }
        assertEquals("BadIntent", e.type)
        assertTrue(e.isNonRetryable)
        assertTrue(received.isEmpty(), "nothing was sent")
    }

    @Test fun `an intent without robot, order or skills is a bad intent`() {
        val noRobot = strictJson.readTree("""{"candidate":{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{"jobOrderId":"O"},"sawSkillTypes":["a"]},"idempotencyKey":"k","approval":{"by":"PERSON","approverId":"op"}}""")
        val noSkills = strictJson.readTree("""{"candidate":{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{"robotId":"R","jobOrderId":"O"},"sawSkillTypes":[]},"idempotencyKey":"k","approval":{"by":"PERSON","approverId":"op"}}""")
        for (i in listOf(noRobot, noSkills)) assertEquals("BadIntent", assertThrows<ApplicationFailure> { window().approve(i, "PERSON") }.type)
        assertEquals("BadIntent", assertThrows<ApplicationFailure> { window().approve(intent(), null) }.type)
        assertTrue(received.isEmpty())
    }

    @Test fun `a window that does not answer in time is retried by the activity - a plain failure`() {
        delayMs = 1_500
        val e = assertThrows<Exception> { window(timeoutMs = 300).approve(intent(), "PERSON") }
        assertFalse(e is ApplicationFailure && e.isNonRetryable, "$e")
    }

    @Test fun `a closed port is retried by the activity - a plain failure`() {
        val closed = HttpApprovalWindow(PicassoWindowConfig(URI("http://127.0.0.1:${server.address.port}/approvals"), "a", 1_000))
        server.stop(0)
        val e = assertThrows<Exception> { closed.approve(intent(), "PERSON") }
        assertFalse(e is ApplicationFailure && e.isNonRetryable, "$e")
    }

    @Test fun `revalidation is UNKNOWN - there is no surface to read the ledgers now (design 8_3)`() {
        assertEquals("UNKNOWN", window().revalidate("""{"candidateId":"c","kind":"APPROVE_REMEDY"}"""))
        assertTrue(received.isEmpty())
    }
}
