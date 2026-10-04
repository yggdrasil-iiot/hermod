package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode
import io.temporal.failure.ApplicationFailure
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * picasso's approval window over HTTP (design §8.4, plan C3): `POST /approvals` on a loopback URL, the request of picasso
 * §7.1 (`ApprovalWire.decode`: approverId, approverKind, robotId, jobOrderId, sawSkillTypes — nothing else is read).
 *
 * - 200 is an answer, an approval or a refusal alike (picasso `ApprovalHost`): returned as the text it is; the core judges it.
 * - Any other status is a request picasso could not take (400: unreadable, 405: not POST, and anything else): not retried,
 *   since the same request gets the same answer. The core reads the failure as an uncertain dispatch (UNKNOWN(OUTCOME)).
 * - No answer (connection refused, timeout) is an ordinary failure: the activity retries, and picasso refuses a second
 *   approval of a consumed proposal (CONSUMED, §11), so a retry cannot dispatch twice.
 *
 * [revalidate] is always UNKNOWN: no surface reads the ledgers as of now (§8.3), so a real episode passes UNKNOWN(PRECONDITION)
 * and a person confirms the precondition.
 *
 * Thread-safe: [HttpClient] is, and nothing else is shared.
 */
class HttpApprovalWindow(private val config: PicassoWindowConfig) : ApprovalWindow {
    private val client: HttpClient = HttpClient.newBuilder()
        .proxy(HttpClient.Builder.NO_PROXY)   // loopback only: an approver id never goes through a configured proxy
        .connectTimeout(Duration.ofMillis(config.timeoutMs))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    override fun revalidate(candidateJson: String): String = "UNKNOWN"

    override fun approve(intent: JsonNode, approverKind: String?): String {
        val body = request(intent, approverKind)
        val response = exchange(
            HttpRequest.newBuilder(config.url)
                .timeout(Duration.ofMillis(config.timeoutMs))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
        )
        if (response.statusCode() == 200) return response.body()
        throw ApplicationFailure.newNonRetryableFailure(
            "picasso's window refused the request: ${response.statusCode()} ${excerpt(response.body())}", "PicassoRequestRefused",
        )
    }

    /**
     * One exchange, bounded as a whole by [PicassoWindowConfig.timeoutMs]: the request's own timeout ends at the response
     * headers, so a responder that sends them and then stalls the body would hold the call (and the activity) past it.
     * A timeout cancels the exchange and is a plain, retryable failure, like a refused connection.
     */
    private fun exchange(request: HttpRequest): HttpResponse<String> {
        val pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        try {
            return pending.get(config.timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.cancel(true)
            throw HttpTimeoutException("picasso's window did not answer in full within ${config.timeoutMs} ms")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            pending.cancel(true)
            Thread.currentThread().interrupt()
            throw e
        }
    }

    /**
     * The refused body as the failure may carry it: it lands in the episode's records (jsonb), which hold neither NUL nor
     * an unpaired surrogate, and a responder that is not picasso can send anything. By code points: control characters
     * and lone surrogates are dropped, at most [EXCERPT_CODE_POINTS] are kept, so no cut splits a pair.
     */
    private fun excerpt(body: String): String {
        val out = StringBuilder()
        var kept = 0
        for (cp in body.codePoints().iterator()) {
            if (kept == EXCERPT_CODE_POINTS) break
            if (Character.isISOControl(cp) || cp in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) continue
            out.appendCodePoint(cp)
            kept++
        }
        return out.toString()
    }

    /** picasso §7.1's five fields, from the intent the activity holds; anything missing is a bad intent, never invented. */
    private fun request(intent: JsonNode, approverKind: String?): String {
        val candidate = intent.path("candidate")
        fun text(node: JsonNode, field: String): String =
            node.path(field).takeIf { it.isTextual && it.textValue().isNotBlank() }?.textValue() ?: bad("no $field")
        val skills = candidate.path("sawSkillTypes").takeIf { it.isArray && it.size() > 0 && it.all { s -> s.isTextual } }
            ?.map { it.textValue() } ?: bad("no sawSkillTypes")
        val approverId = when (approverKind) {
            "PERSON" -> text(intent.path("approval"), "approverId")
            "AGENT" -> config.agentId
            else -> bad("approverKind $approverKind")
        }
        return strictJson.createObjectNode().apply {
            put("approverId", approverId)
            put("approverKind", approverKind)
            put("robotId", text(candidate.path("ref"), "robotId"))
            put("jobOrderId", text(candidate.path("ref"), "jobOrderId"))
            putArray("sawSkillTypes").apply { skills.forEach { add(it) } }
        }.toString()
    }

    private fun bad(why: String): Nothing = throw ApplicationFailure.newNonRetryableFailure("intent for picasso: $why", "BadIntent")

    private companion object {
        const val EXCERPT_CODE_POINTS = 500
    }
}
