package yggdrasil.hermod.authoring

import yggdrasil.hermod.opcua.AutoCorrectAction
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.File

/** Thin production wrapper over AutoCorrectDispatcher. Fail-open; HERMOD_AUTOCORRECT_DISABLED=1 silences the
 *  whole bean (mirrors HERMOD_SOAK_DISABLED). HERMOD_AUTOCORRECT_DISPATCH=1 enables auto-dispatch => park-for-
 *  approval (design 2026-07-04); unset => alarm-only, byte-identical to the merged poller. FSM specs resolve
 *  from a checked-out repo model dir via HERMOD_MODEL_DIR; unset/absent => no-op (authoring-api otherwise reads
 *  model from the classpath, not a fs dir). See designs 2026-07-03 (poller) + 2026-07-04 (dispatch). */
@Component
class AutoCorrectBean(private val dispatcher: AutoCorrectDispatcher, private val gate: AutoCorrectGate) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val modelDir: File? = System.getenv("HERMOD_MODEL_DIR")?.let(::File)

    @Scheduled(fixedDelayString = "\${hermod.autocorrect.fixedDelayMs:20000}",
               initialDelayString = "\${hermod.autocorrect.initialDelayMs:20000}")
    fun sweep() {
        if (gate.mode == AutoCorrectMode.OFF) return
        val dir = modelDir?.takeIf { File(it, "fsm").isDirectory } ?: return
        try {
            val actions = if (gate.mode == AutoCorrectMode.DISPATCH) dispatcher.runOnce(dir) else dispatcher.sweepAlarmOnly(dir)
            actions.filterIsInstance<AutoCorrectAction.DriftCorrectable>().forEach {
                log.warn("AUTO-CORRECT alarm: drift on {} ({}->{}) — corrective {} available, awaiting operator", it.unit, it.from, it.to, it.workflow)
            }
            actions.filterIsInstance<AutoCorrectAction.DriftBlocked>().forEach {
                log.warn("AUTO-CORRECT alarm: drift on {} ({}->{}) — NO corrective path: {}", it.unit, it.from, it.to, it.governReason)
            }
        } catch (e: Exception) { log.warn("auto-correct sweep failed: {}", e.message) }
    }
}
