package koshei.authoring

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

enum class AutoCorrectMode { OFF, ALARM_ONLY, DISPATCH }

/**
 * How the drift auto-correct may act. Read once at start.
 */
data class AutoCorrectGate(val mode: AutoCorrectMode) {
    companion object {
        fun fromEnv(env: Map<String, String>): AutoCorrectGate {
            val disabled = env["KOSHEI_AUTOCORRECT_DISABLED"] == "1"
            val dispatch = env["KOSHEI_AUTOCORRECT_DISPATCH"] == "1"
            val mode = when {
                disabled -> AutoCorrectMode.OFF
                dispatch -> AutoCorrectMode.DISPATCH
                else -> AutoCorrectMode.ALARM_ONLY
            }
            return AutoCorrectGate(mode)
        }
    }
}

@Configuration
class AutoCorrectGateConfig {
    @Bean fun autoCorrectGate(): AutoCorrectGate = AutoCorrectGate.fromEnv(System.getenv())
}
