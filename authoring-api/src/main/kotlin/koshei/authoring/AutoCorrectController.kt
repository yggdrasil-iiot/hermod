package koshei.authoring

import koshei.opcua.AutoCorrectAction
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.File

/**
 * Deterministic one-shot trigger for the auto-correct sweep+dispatch cycle (design 2026-07-04). This is the
 * gate driver (the @Scheduled bean is disabled with KOSHEI_AUTOCORRECT_DISABLED=1 so it can't race). It is an
 * explicit operator/ops action, so it always dispatches — safe because the dispatched ot-safe-hold parks at the
 * human approval gate (it never actuates). FSM specs resolve from KOSHEI_MODEL_DIR; unset => 503 (nothing to
 * sweep), matching the bean (authoring-api reads other model artifacts from the classpath, not a fs dir).
 */
@RestController
@RequestMapping("/api")
class AutoCorrectController(private val sweeper: OneShotSweep, private val gate: AutoCorrectGate) {
    private val modelDir: File? = System.getenv("KOSHEI_MODEL_DIR")?.let(::File)

    @PostMapping("/autocorrect/sweep")
    fun sweep(): ResponseEntity<Any> {
        if (!gate.oneShotAllowed) return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf(
            "error" to "EPISODE_MODE",
            "detail" to "KOSHEI_PICASSO is not off: drift never dispatches in episode mode (design §14)",
        ))
        val dir = modelDir?.takeIf { File(it, "fsm").isDirectory }
            ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("error" to "KOSHEI_MODEL_DIR not set / no fsm/ dir"))
        val actions = sweeper.runOnce(dir)
        return ResponseEntity.ok(mapOf(
            "correctable" to actions.filterIsInstance<AutoCorrectAction.DriftCorrectable>().map { it.unit },
            "blocked" to actions.filterIsInstance<AutoCorrectAction.DriftBlocked>().map { it.unit },
        ))
    }
}
