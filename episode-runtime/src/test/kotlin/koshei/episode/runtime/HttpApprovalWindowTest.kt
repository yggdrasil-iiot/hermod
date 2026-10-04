package koshei.episode.runtime

import com.sun.net.httpserver.HttpServer
import io.temporal.failure.ApplicationFailure
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpApprovalWindowTest {
    private val received = CopyOnWriteArrayList<Pair<String, String>>()   // (method + path, body)
    @Volatile private var status = 200
    @Volatile private var answer = PICASSO_APPROVED
    @Volatile private var delayMs = 0L
    /** When set, the answer is these bytes instead of [answer] (bytes that need not be UTF-8). */
    @Volatile private var answerBytes: ByteArray? = null
    /** When set, the server sends the headers and one byte of the body, then stalls until the test ends. */
    @Volatile private var stallAfterHeaders = false
    private val released = CountDownLatch(1)
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { ex ->
            received += "${ex.requestMethod} ${ex.requestURI.path}" to ex.requestBody.readBytes().toString(Charsets.UTF_8)
            if (delayMs > 0) Thread.sleep(delayMs)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            if (stallAfterHeaders) {
                ex.sendResponseHeaders(status, 100)
                ex.responseBody.write('{'.code)
                ex.responseBody.flush()
                released.await(10, TimeUnit.SECONDS)
                ex.close()
                return@createContext
            }
            val bytes = answerBytes ?: answer.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @AfterEach fun down() {
        released.countDown()
        server.stop(0)
    }

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

    @Test fun `a refused body is echoed clean - no control characters, no split surrogate, at most 500 code points`() {
        // Not picasso on that port: bytes that decode to U+FFFD U+FFFD 'a' NUL, then a tail long enough that a 500-char cut
        // would land inside a surrogate pair (7 BMP chars, then pairs: 493 is odd), and longer than 500 code points.
        status = 502
        answerBytes = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x61, 0x00) + ("x\u0001\n" + "😀".repeat(600)).toByteArray(Charsets.UTF_8)
        val e = assertThrows<ApplicationFailure> { window().approve(intent(), "PERSON") }
        assertTrue(e.isNonRetryable)
        assertEquals("PicassoRequestRefused", e.type)
        val m = e.originalMessage
        assertTrue("502" in m, m)
        assertFalse(m.any { it.code < 0x20 || it.code in 0x7f..0x9f }, "no control characters: ${m.take(80)}")
        assertTrue(pairedSurrogates(m), "no unpaired surrogate")
        val echoed = m.substringAfter("502 ")
        assertEquals(500, echoed.codePointCount(0, echoed.length), "the excerpt is cut at 500 code points")
        assertTrue(echoed.startsWith("��ax😀"), echoed.take(10))   // what is kept, kept in order
    }

    @Test fun `a window that sends headers and then stalls the body is bounded by the timeout - a plain failure`() {
        stallAfterHeaders = true
        val started = System.nanoTime()
        val e = assertThrows<Exception> { window(timeoutMs = 1_000).approve(intent(), "PERSON") }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertFalse(e is ApplicationFailure && e.isNonRetryable, "$e")
        assertTrue(tookMs < 1_500, "the whole call took $tookMs ms with a 1000 ms limit")
    }

    /** Every high surrogate is followed by a low one, and every low one follows a high one. */
    private fun pairedSurrogates(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isHighSurrogate() -> { if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return false; i += 2 }
                c.isLowSurrogate() -> return false
                else -> i++
            }
        }
        return true
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
