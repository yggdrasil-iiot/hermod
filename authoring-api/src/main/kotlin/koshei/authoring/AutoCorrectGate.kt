package koshei.authoring

import koshei.episode.runtime.PicassoMode
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

enum class AutoCorrectMode { OFF, ALARM_ONLY, DISPATCH }

/**
 * How the drift auto-correct may act (design §14 D5, §16 C). In episode mode (`KOSHEI_PICASSO` ≠ off) drift never
 * dispatches: the episode loop is the one way to act, and drift is at most an alarm. Read once at start; a setting
 * that asks for both stops the start, so neither side silently loses.
 */
data class AutoCorrectGate(val mode: AutoCorrectMode, val episodeMode: Boolean) {
    /** `POST /api/autocorrect/sweep` always dispatches, so episode mode refuses it. */
    val oneShotAllowed: Boolean get() = !episodeMode

    companion object {
        fun fromEnv(env: Map<String, String>): AutoCorrectGate {
            val episodeMode = PicassoMode.fromEnv(env) != PicassoMode.OFF
            val disabled = env["KOSHEI_AUTOCORRECT_DISABLED"] == "1"
            val dispatch = env["KOSHEI_AUTOCORRECT_DISPATCH"] == "1"
            require(!(episodeMode && dispatch && !disabled)) {
                "KOSHEI_AUTOCORRECT_DISPATCH=1 with KOSHEI_PICASSO=${env["KOSHEI_PICASSO"]}: episode mode turns drift auto-dispatch off " +
                    "(design §14) — unset one of them"
            }
            val mode = when {
                disabled -> AutoCorrectMode.OFF
                dispatch -> AutoCorrectMode.DISPATCH
                else -> AutoCorrectMode.ALARM_ONLY
            }
            return AutoCorrectGate(mode, episodeMode)
        }
    }
}

@Configuration
class AutoCorrectGateConfig {
    @Bean fun autoCorrectGate(): AutoCorrectGate = AutoCorrectGate.fromEnv(System.getenv())
}
