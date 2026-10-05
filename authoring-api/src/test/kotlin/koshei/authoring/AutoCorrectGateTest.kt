package koshei.authoring

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoCorrectGateTest {
    private fun gate(vararg env: Pair<String, String>) = AutoCorrectGate.fromEnv(env.toMap())

    @Test fun `DISABLED turns drift auto-correct off, DISPATCH turns auto-dispatch on, unset alarms only`() {
        assertEquals(AutoCorrectGate(AutoCorrectMode.ALARM_ONLY), gate())
        assertEquals(AutoCorrectGate(AutoCorrectMode.DISPATCH), gate("KOSHEI_AUTOCORRECT_DISPATCH" to "1"))
        assertEquals(AutoCorrectGate(AutoCorrectMode.OFF), gate("KOSHEI_AUTOCORRECT_DISABLED" to "1", "KOSHEI_AUTOCORRECT_DISPATCH" to "1"))
    }
}
