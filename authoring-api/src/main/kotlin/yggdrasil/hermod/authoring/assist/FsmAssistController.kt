package yggdrasil.hermod.authoring.assist

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * POST /api/fsm/assist — natural language -> validated FSM draft (a PROPOSAL). The LLM is advisory:
 * the draft is accepted by the user, committed to model/fsm, and finally validated fail-closed by the
 * conformance gate. 200 = valid draft; 422 = repair exhausted; 502 = LLM transport/refusal; 503 = disabled.
 */
@RestController
@RequestMapping("/api/fsm")
class FsmAssistController(private val service: FsmAssistService) {
    private val log = org.slf4j.LoggerFactory.getLogger(FsmAssistController::class.java)

    @PostMapping("/assist")
    fun assist(@RequestBody req: FsmAssistRequest): ResponseEntity<Any> = try {
        when (val out = service.assist(req.prompt, req.context?.toFsmSpec())) {
            is AssistOutcome.Ok -> ResponseEntity.ok(out.spec.toDto())
            is AssistOutcome.Invalid -> ResponseEntity.unprocessableEntity().body(mapOf("issues" to out.errors))
        }
    } catch (e: LlmAssistException) {
        val status = when (e.kind) {
            LlmAssistException.Kind.DISABLED -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.BAD_GATEWAY
        }
        // Log the boundary FAILURE only — kind + message. NEVER the prompt, the response, or the API key.
        log.warn("fsm-assist LLM boundary failure: kind={} status={} message={}", e.kind, status.value(), e.message)
        ResponseEntity.status(status).body(mapOf("error" to (e.message ?: e.kind.name)))
    }
}
