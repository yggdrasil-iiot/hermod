package koshei.authoring

import koshei.opcua.AutoCorrectAction
import org.springframework.http.HttpStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoCorrectGateTest {
    private fun gate(vararg env: Pair<String, String>) = AutoCorrectGate.fromEnv(env.toMap())

    @Test fun `outside episode mode nothing changes`() {
        assertEquals(AutoCorrectGate(AutoCorrectMode.ALARM_ONLY, episodeMode = false), gate())
        assertEquals(AutoCorrectGate(AutoCorrectMode.DISPATCH, episodeMode = false), gate("KOSHEI_AUTOCORRECT_DISPATCH" to "1"))
        assertEquals(AutoCorrectGate(AutoCorrectMode.OFF, episodeMode = false), gate("KOSHEI_AUTOCORRECT_DISABLED" to "1", "KOSHEI_AUTOCORRECT_DISPATCH" to "1"))
        assertEquals(AutoCorrectGate(AutoCorrectMode.ALARM_ONLY, episodeMode = false), gate("KOSHEI_PICASSO" to "off"))
        assertTrue(gate().oneShotAllowed)
    }

    @Test fun `episode mode alarms only, and refuses the one-shot dispatch`() {
        val g = gate("KOSHEI_PICASSO" to "mock")
        assertEquals(AutoCorrectGate(AutoCorrectMode.ALARM_ONLY, episodeMode = true), g)
        assertFalse(g.oneShotAllowed)
        assertEquals(AutoCorrectMode.OFF, gate("KOSHEI_PICASSO" to "mock", "KOSHEI_AUTOCORRECT_DISABLED" to "1").mode)
    }

    @Test fun `episode mode with auto-dispatch asked for stops the start - neither silently loses`() {
        assertFailsWith<IllegalArgumentException> { gate("KOSHEI_PICASSO" to "mock", "KOSHEI_AUTOCORRECT_DISPATCH" to "1") }
    }

    @Test fun `episode mode with DISABLED and DISPATCH both set is OFF - how the gate script runs if KOSHEI_PICASSO leaks in`() {
        val g = gate("KOSHEI_PICASSO" to "mock", "KOSHEI_AUTOCORRECT_DISABLED" to "1", "KOSHEI_AUTOCORRECT_DISPATCH" to "1")
        assertEquals(AutoCorrectGate(AutoCorrectMode.OFF, episodeMode = true), g)
    }

    @Test fun `an unknown KOSHEI_PICASSO stops the start, as it stops the worker`() {
        assertFailsWith<IllegalArgumentException> { gate("KOSHEI_PICASSO" to "real") }
    }

    @Test fun `the one-shot endpoint answers 409 in episode mode and never sweeps`() {
        var swept = false
        val controller = AutoCorrectController({ _: File -> swept = true; emptyList<AutoCorrectAction>() }, gate("KOSHEI_PICASSO" to "mock"))
        val r = controller.sweep()
        assertEquals(HttpStatus.CONFLICT, r.statusCode)
        assertEquals("EPISODE_MODE", (r.body as Map<*, *>)["error"])
        assertFalse(swept)
    }
}
