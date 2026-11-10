package yggdrasil.hermod.delegation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import yggdrasil.hermod.sdk.DelegatePort
import yggdrasil.hermod.sdk.DelegationRequest
import yggdrasil.hermod.sdk.DelegationResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** HTTP/REST implementation of [DelegatePort] over the JDK HttpClient (no third-party dep). */
class HttpDelegatePort(private val policy: DelegationPolicy) : DelegatePort {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val mapper = ObjectMapper().registerKotlinModule()

    override fun call(req: DelegationRequest): DelegationResult {
        val url = System.getenv("HERMOD_DELEGATION_URL") ?: policy.urlFor(req.endpointId)
        if (url.isBlank()) return DelegationResult(false, null, "", "no url for endpoint '${req.endpointId}'")
        val metric = policy.metricFor(req.endpointId).ifBlank { "qualityScore" }
        return try {
            val body = mapper.writeValueAsString(req.payload)
            val httpReq = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299)
                return DelegationResult(false, null, resp.body(), "scorer HTTP ${resp.statusCode()}")
            val node = mapper.readTree(resp.body())
            val score = node.get(metric)?.asDouble()
                ?: return DelegationResult(false, null, resp.body(), "response missing metric '$metric'")
            DelegationResult(true, score, resp.body(), "scored $score")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return DelegationResult(false, null, "", "delegation call interrupted")
        } catch (e: Exception) {
            DelegationResult(false, null, "", "delegation call failed: ${e.message}")
        }
    }

    companion object { fun default(): HttpDelegatePort = HttpDelegatePort(DelegationPolicy.default()) }
}
