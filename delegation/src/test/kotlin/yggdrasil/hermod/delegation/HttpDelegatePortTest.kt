package yggdrasil.hermod.delegation

import com.sun.net.httpserver.HttpServer
import yggdrasil.hermod.sdk.DelegationRequest
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the fail-soft branches of [HttpDelegatePort.call] that the Chunk-3 sim test never hits
 * (the sim only ever returns 200 with a qualityScore). Every branch must return
 * DelegationResult(ok=false, ...) — the call must NEVER throw.
 */
class HttpDelegatePortTest {

    /** Tiny inline stub bound to an ephemeral port; each context returns a fixed status/body. */
    private fun stub(status: Int, body: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/analytics/score") { ex ->
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.executor = null
        server.start()
        return server
    }

    private fun policyFor(url: String): DelegationPolicy = DelegationPolicy.parse(
        """{ "default":"deny","endpoints":[{"id":"quality-scorer","url":"$url","metric":"qualityScore","threshold":0.8,"allow":true}] }"""
    )

    @Test fun `non-2xx status returns ok=false without throwing`() {
        val server = stub(500, """{"error":"boom"}""")
        try {
            val url = "http://localhost:${server.address.port}/analytics/score"
            val res = HttpDelegatePort(policyFor(url)).call(DelegationRequest("quality-scorer", mapOf("val" to "300")))
            assertFalse(res.ok)
            assertNull(res.score)
            assertTrue(res.detail.contains("500"), "detail should mention the HTTP status: ${res.detail}")
        } finally { server.stop(0) }
    }

    @Test fun `200 missing the policy metric returns ok=false`() {
        val server = stub(200, """{"somethingElse":0.99}""")
        try {
            val url = "http://localhost:${server.address.port}/analytics/score"
            val res = HttpDelegatePort(policyFor(url)).call(DelegationRequest("quality-scorer", mapOf("val" to "300")))
            assertFalse(res.ok)
            assertNull(res.score)
            assertTrue(res.detail.contains("qualityScore"), "detail should mention the missing metric: ${res.detail}")
        } finally { server.stop(0) }
    }

    @Test fun `blank url returns ok=false without a network call`() {
        val res = HttpDelegatePort(policyFor("")).call(DelegationRequest("quality-scorer", mapOf("val" to "300")))
        assertFalse(res.ok)
        assertNull(res.score)
        assertEquals("", res.raw)
        assertTrue(res.detail.contains("no url"), "detail should note the missing url: ${res.detail}")
    }
}
