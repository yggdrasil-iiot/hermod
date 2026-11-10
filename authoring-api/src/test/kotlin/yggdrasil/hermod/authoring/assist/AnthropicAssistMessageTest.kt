package yggdrasil.hermod.authoring.assist

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import yggdrasil.hermod.opcua.FsmSpec
import yggdrasil.hermod.opcua.FsmState
import yggdrasil.hermod.opcua.FsmTransition
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit tests for buildAssistUserMessage — proves the edit FRAMING is present in the user message when
 * a current spec is supplied, and absent for fresh generation. No AnthropicClient, no network. NOTE: this
 * proves the framing is SENT; it does not (and cannot) prove the model obeys it — that is the keyed live
 * smoke's job (the Fake port ignores `current`; structured output is non-deterministic). See design §3.3.
 */
class AnthropicAssistMessageTest {
    private val mapper = jacksonObjectMapper()
    private val demo = FsmSpec(
        name = "packml-lineX", unit = "lineX", stateNode = "lineX.stateCurrent",
        states = listOf(FsmState("Idle", 4), FsmState("Execute", 6)),
        transitions = listOf(FsmTransition("start", "Idle", "Execute", "Start", "field", null)),
        version = "v1",
    )

    @Test fun `fresh generation omits the edit framing and current spec`() {
        val msg = buildAssistUserMessage(LlmAssistRequest(prompt = "make a packml fsm"), mapper)
        assertTrue(msg.contains("Request: make a packml fsm"))
        assertFalse(msg.contains(DomainPack.EDIT_INSTRUCTION))
        assertFalse(msg.contains("Current FSM"))
    }

    @Test fun `edit includes the framing, the serialized current spec, and the prompt`() {
        val msg = buildAssistUserMessage(LlmAssistRequest(prompt = "make Start hermod-governed", current = demo), mapper)
        assertTrue(msg.contains(DomainPack.EDIT_INSTRUCTION), "edit framing must be present")
        assertTrue(msg.contains("packml-lineX"), "serialized current spec must be present")
        assertTrue(msg.contains("\"id\":\"start\""), "current transition must be serialized as JSON")
        assertTrue(msg.contains("Request: make Start hermod-governed"))
    }

    @Test fun `repair hint is appended when present`() {
        val msg = buildAssistUserMessage(
            LlmAssistRequest(prompt = "x", repairHint = "transition 'start': some error"), mapper)
        assertTrue(msg.contains("transition 'start': some error"))
    }
}
