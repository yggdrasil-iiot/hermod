package koshei.authoring

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.temporal.client.WorkflowQueryException
import io.temporal.client.WorkflowServiceException
import koshei.episode.runtime.CloseRequest
import koshei.episode.runtime.ConfirmRequest
import koshei.episode.runtime.DecideRequest
import koshei.episode.runtime.EpisodeReader
import koshei.episode.runtime.EpisodeView
import koshei.episode.runtime.StoredNotice
import koshei.episode.runtime.TakeoverRequest
import koshei.episode.runtime.operatorCard
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.sql.SQLException
import java.sql.SQLTransientConnectionException

/** `decide` body (design §7.2). [approve] is required: a missing or misspelt field must not read as a rejection. */
data class DecideBody(val proposalId: String = "", val sawCandidatesVersion: String = "", val approve: Boolean? = null,
                      val reason: String? = null, val note: String? = null)

/**
 * `confirm` body (design §7.2). [holds] is required for every kind, so that one rule keeps a missing field from reading
 * as "does not hold". There is no proposition field: the control plane fills it from its own card.
 */
data class ConfirmBody(val kind: String = "", val candidateId: String = "", val proposalId: String = "", val holds: Boolean? = null,
                       val subject: Map<String, String?> = emptyMap(), val what: String = "", val note: String? = null)

data class CloseBody(val outcome: String = "")

/** A request this controller refuses before anything reaches the episode: 400. */
private class BadEpisodeBody(message: String) : RuntimeException(message)

private const val OPERATOR_HEADER = "X-Koshei-Operator"
private const val MAX_LIMIT = 500
private const val TABLES_MISSING = "episode tables not found — no episode worker has run (KOSHEI_PICASSO)"

/**
 * The episode control plane (design §4.2, §7.2, §7.3, §9.5): it reads and relays, and decides nothing. Lists and
 * records come from the episode tables (read only, R14); the live view from the workflow's running current run. A
 * decision goes to the gateway under the operator header, and the core's reply — a refusal included — comes back as a
 * value. The instance id `<workflowId>/<run>` is the path's two segments.
 */
@RestController
@RequestMapping("/api/episodes")
class EpisodeController(private val gateway: EpisodeGateway, private val reader: EpisodeReader) {
    /**
     * Bodies are read here, not by Spring's mapper: that one ignores unknown fields, and a class annotation does not turn
     * it back on. A fresh Kotlin mapper refuses them, so an `operatorId` in the body or a misspelt field is a 400.
     */
    private val bodies = jacksonObjectMapper()
    private val trees = ObjectMapper()

    @GetMapping
    fun list(@RequestParam(defaultValue = "50") limit: Int): ResponseEntity<Any> {
        if (limit !in 1..MAX_LIMIT) return ResponseEntity.badRequest().body(mapOf("error" to "limit must be 1..$MAX_LIMIT"))
        return ResponseEntity.ok(reader.instances(limit))
    }

    /** The whole notice flow in id order (design §8.5). Ids are not commit order: a reader overlaps and de-duplicates by id. */
    @GetMapping("/notices")
    fun notices(@RequestParam(defaultValue = "0") after: Long, @RequestParam(defaultValue = "100") limit: Int): ResponseEntity<Any> {
        if (limit !in 1..MAX_LIMIT) return ResponseEntity.badRequest().body(mapOf("error" to "limit must be 1..$MAX_LIMIT"))
        return ResponseEntity.ok(reader.noticesAfter(after, limit).map { linkedMapOf("id" to it.id, "instanceId" to it.instanceId, "at" to it.at, "notice" to trees.readTree(it.noticeJson)) })
    }

    /** The records and notices, with the live view and its card while the run holds this instance; Temporal down → records only. */
    @GetMapping("/{workflowId}/{run}")
    fun one(@PathVariable workflowId: String, @PathVariable run: String): ResponseEntity<Any> {
        val instanceId = "$workflowId/$run"
        var unknownToTemporal = false
        var viewError: String? = null
        val view: EpisodeView? = try {
            gateway.view(instanceId)
        } catch (e: EpisodeNotFound) {
            unknownToTemporal = true
            null
        } catch (e: Exception) {
            if (e !is StatusRuntimeException && e !is WorkflowServiceException && e !is WorkflowQueryException && e !is EpisodeTimeout) throw e
            viewError = e.message ?: e.javaClass.simpleName
            null
        }
        val events = reader.events(instanceId)
        if (unknownToTemporal && events.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "no episode $instanceId"))
        }
        val notices = reader.noticeRows(instanceId)
        return ResponseEntity.ok(
            linkedMapOf(
                "instanceId" to instanceId,
                "view" to view,
                "viewError" to viewError,
                "card" to view?.let { operatorCard(it, events) },
                "events" to events.map { linkedMapOf("seq" to it.seq, "kind" to it.kind, "payload" to trees.readTree(it.payloadJson), "at" to it.at) },
                "notices" to notices.map(::noticeRow),
            ),
        )
    }

    @PostMapping("/{workflowId}/{run}/decide")
    fun decide(@PathVariable workflowId: String, @PathVariable run: String,
               @RequestHeader(OPERATOR_HEADER, required = false) operator: String?, @RequestBody body: String): ResponseEntity<Any> {
        val operatorId = operatorOf(operator)
        val b = read(body, DecideBody::class.java)
        val approve = b.approve ?: throw BadEpisodeBody("approve is required")
        return reply(gateway.decide("$workflowId/$run", DecideRequest(b.proposalId, b.sawCandidatesVersion, approve, operatorId, b.reason, b.note)))
    }

    @PostMapping("/{workflowId}/{run}/confirm")
    fun confirm(@PathVariable workflowId: String, @PathVariable run: String,
                @RequestHeader(OPERATOR_HEADER, required = false) operator: String?, @RequestBody body: String): ResponseEntity<Any> {
        val operatorId = operatorOf(operator)
        val b = read(body, ConfirmBody::class.java)
        val holds = b.holds ?: throw BadEpisodeBody("holds is required")
        val instanceId = "$workflowId/$run"
        val proposition = if (b.kind == "PRECONDITION") propositionFor(instanceId, b, holds) else null
        return reply(gateway.confirm(instanceId, ConfirmRequest(b.kind, b.candidateId, b.proposalId, holds, b.subject, b.what, operatorId, b.note, proposition)))
    }

    @PostMapping("/{workflowId}/{run}/takeover")
    fun takeover(@PathVariable workflowId: String, @PathVariable run: String,
                 @RequestHeader(OPERATOR_HEADER, required = false) operator: String?): ResponseEntity<Any> {
        val operatorId = operatorOf(operator)
        return reply(gateway.takeover("$workflowId/$run", TakeoverRequest(operatorId)))
    }

    @PostMapping("/{workflowId}/{run}/close")
    fun close(@PathVariable workflowId: String, @PathVariable run: String,
              @RequestHeader(OPERATOR_HEADER, required = false) operator: String?, @RequestBody body: String): ResponseEntity<Any> {
        val operatorId = operatorOf(operator)
        val b = read(body, CloseBody::class.java)
        return reply(gateway.close("$workflowId/$run", CloseRequest(operatorId, b.outcome)))
    }

    /**
     * The proposition the card shows (design §7.2, §8.3), only when the body names the card's own proposal in
     * UNKNOWN_PRECONDITION; "it holds" with no proposition there is a 400. Any other confirmation of a precondition goes
     * on without one, and the core refuses it as a value — the control plane does not stand in for the core.
     */
    private fun propositionFor(instanceId: String, b: ConfirmBody, holds: Boolean): String? {
        val view = gateway.view(instanceId) ?: return null
        if (view.phase != "UNKNOWN_PRECONDITION") return null
        val card = operatorCard(view, reader.events(instanceId))
        if (b.candidateId != card.proposal?.candidateId || b.proposalId != view.proposalId) return null
        if (holds && card.precondition == null) throw BadEpisodeBody("no proposition to confirm (design §8.3)")
        return card.precondition
    }

    private fun operatorOf(header: String?): String {
        val operatorId = header?.trim().orEmpty()
        if (operatorId.isEmpty() || operatorId.length > 128) throw BadEpisodeBody("$OPERATOR_HEADER is required (design §7.2)")
        return operatorId
    }

    private fun <T> read(body: String, type: Class<T>): T = bodies.readValue(body, type) ?: throw BadEpisodeBody("a JSON object is required")

    private fun reply(value: String): ResponseEntity<Any> = ResponseEntity.ok(mapOf("reply" to value))

    private fun noticeRow(n: StoredNotice) = linkedMapOf("id" to n.id, "at" to n.at, "notice" to trees.readTree(n.noticeJson))

    private fun error(status: HttpStatus, message: String?): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(status).body(mapOf("error" to message))

    @ExceptionHandler(BadEpisodeBody::class, InvalidEpisodeRequest::class)
    fun badRequest(e: RuntimeException) = error(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(JsonProcessingException::class)
    fun unreadable(e: JsonProcessingException) = error(HttpStatus.BAD_REQUEST, e.originalMessage)

    @ExceptionHandler(EpisodeMoved::class)
    fun moved(e: EpisodeMoved): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "EPISODE_MOVED", "currentInstanceId" to e.currentInstanceId))

    @ExceptionHandler(EpisodeEnded::class)
    fun ended(e: EpisodeEnded) = error(HttpStatus.CONFLICT, "EPISODE_ENDED")

    @ExceptionHandler(EpisodeNotFound::class)
    fun notFound(e: EpisodeNotFound) = error(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(StatusRuntimeException::class)
    fun unreachable(e: StatusRuntimeException): ResponseEntity<Map<String, String?>> =
        if (e.status.code == Status.Code.UNAVAILABLE || e.status.code == Status.Code.DEADLINE_EXCEEDED) error(HttpStatus.SERVICE_UNAVAILABLE, e.message)
        else throw e

    @ExceptionHandler(WorkflowServiceException::class, SQLTransientConnectionException::class)
    fun unavailable(e: Exception) = error(HttpStatus.SERVICE_UNAVAILABLE, e.message)

    /** The control plane never creates the tables (R14): with none yet, it says so rather than failing. Anything else stays a 500. */
    @ExceptionHandler(SQLException::class)
    fun database(e: SQLException): ResponseEntity<Map<String, String?>> =
        if (e.sqlState == "42P01") error(HttpStatus.SERVICE_UNAVAILABLE, TABLES_MISSING) else throw e

    @ExceptionHandler(EpisodeTimeout::class)
    fun timeout(e: EpisodeTimeout) = error(HttpStatus.GATEWAY_TIMEOUT, e.message)
}
