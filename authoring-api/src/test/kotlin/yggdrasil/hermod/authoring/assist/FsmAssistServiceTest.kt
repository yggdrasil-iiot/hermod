package yggdrasil.hermod.authoring.assist

import yggdrasil.hermod.opcua.FsmAction
import yggdrasil.hermod.opcua.FsmSpec
import yggdrasil.hermod.opcua.FsmState
import yggdrasil.hermod.opcua.FsmTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FsmAssistServiceTest {
    private val valid = FakeLlmAssistPort.DEMO
    // structurally invalid: a transition references an undeclared state 'Ghost'.
    // FsmSpec is a plain class (no .copy) — construct a fresh one.
    private val invalid = FsmSpec(
        name = valid.name, unit = valid.unit, stateNode = valid.stateNode,
        states = valid.states,
        transitions = listOf(FsmTransition("start", "Idle", "Ghost", "Start", "field", null)),
        version = valid.version,
    )

    @Test fun `valid first attempt returns Ok with one generation`() {
        val port = CountingPort(listOf(valid))
        val out = FsmAssistService(port).assist("make a packml fsm", null)
        assertTrue(out is AssistOutcome.Ok)
        assertEquals(1, port.calls)
    }

    @Test fun `invalid then valid repairs and returns Ok on the second generation`() {
        val port = CountingPort(listOf(invalid, valid))
        val out = FsmAssistService(port).assist("x", null)
        assertTrue(out is AssistOutcome.Ok)
        assertEquals(2, port.calls)
    }

    @Test fun `always-invalid fails closed after the hard cap of 3 generations`() {
        val port = CountingPort(listOf(invalid))   // repeats
        val out = FsmAssistService(port).assist("x", null)
        assertTrue(out is AssistOutcome.Invalid)
        assertEquals(3, port.calls)
        assertTrue((out as AssistOutcome.Invalid).errors.isNotEmpty())
    }

    @Test fun `repair hint carries the validator errors into the retry`() {
        val port = CountingPort(listOf(invalid, valid))
        FsmAssistService(port).assist("x", null)
        assertEquals(null, port.hints[0])                 // first call: no hint
        assertTrue(port.hints[1]?.contains("Ghost") == true) // retry: validator error text
    }

    private class CountingPort(private val script: List<FsmSpec>) : LlmAssistPort {
        var calls = 0
        val hints = mutableListOf<String?>()
        private var i = 0
        override fun generate(req: LlmAssistRequest): FsmSpec {
            calls++; hints += req.repairHint
            return script[minOf(i++, script.lastIndex)]
        }
    }
}
