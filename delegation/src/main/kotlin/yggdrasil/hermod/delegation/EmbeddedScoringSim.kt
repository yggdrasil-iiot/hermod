package yggdrasil.hermod.delegation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * Deterministic stub scorer (NOT a real model) for the gate/demo. Serves POST /analytics/score,
 * reads the setpoint `val` from the JSON payload, returns { "qualityScore": clamp(1 - val/SIM_SCALE) }.
 * A hotter setpoint => lower score. SIM_SCALE (3000) matches the demo node's EURange high.
 */
class EmbeddedScoringSim(private val port: Int = 9099, private val scale: Double = 3000.0) : AutoCloseable {
    private val mapper = ObjectMapper().registerKotlinModule()
    private lateinit var server: HttpServer

    fun start(): EmbeddedScoringSim {
        server = HttpServer.create(InetSocketAddress(port), 0)
        server.createContext("/analytics/score") { ex ->
            try {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val v = if (body.isBlank()) 0.0 else (mapper.readTree(body).get("val")?.asText()?.toDoubleOrNull() ?: 0.0)
                val score = (1.0 - v / scale).coerceIn(0.0, 1.0)
                val resp = """{"qualityScore":$score}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } catch (e: Exception) {
                val err = """{"error":"${e.message}"}""".toByteArray()
                ex.sendResponseHeaders(500, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            }
        }
        server.executor = null
        server.start()
        return this
    }

    override fun close() { if (::server.isInitialized) server.stop(0) }

    /** The actually-bound port (meaningful when constructed with port 0). */
    val listenPort: Int get() = server.address.port
}
