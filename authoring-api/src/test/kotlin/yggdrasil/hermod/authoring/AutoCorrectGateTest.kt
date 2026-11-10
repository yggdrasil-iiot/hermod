package yggdrasil.hermod.authoring

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoCorrectGateTest {
    private fun gate(vararg env: Pair<String, String>) = AutoCorrectGate.fromEnv(env.toMap())

    @Test fun `DISABLED turns drift auto-correct off, DISPATCH turns auto-dispatch on, unset alarms only`() {
        assertEquals(AutoCorrectGate(AutoCorrectMode.ALARM_ONLY), gate())
        assertEquals(AutoCorrectGate(AutoCorrectMode.DISPATCH), gate("HERMOD_AUTOCORRECT_DISPATCH" to "1"))
        assertEquals(AutoCorrectGate(AutoCorrectMode.OFF), gate("HERMOD_AUTOCORRECT_DISABLED" to "1", "HERMOD_AUTOCORRECT_DISPATCH" to "1"))
    }
}
