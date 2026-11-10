package yggdrasil.hermod.authoring.assist

import yggdrasil.hermod.opcua.FsmSpec

/**
 * The LLM-assist boundary. Consumers (FsmAssistService) depend ONLY on this interface; the config injects
 * an [AnthropicLlmAssistPort] when HERMOD_LLM_ASSIST=anthropic, otherwise a [FakeLlmAssistPort]. Mirrors
 * the DelegatePort/ApplyPort seam idiom. Phase-B ready: `current` carries the spec being edited.
 */
interface LlmAssistPort {
    /** Generate a draft FsmSpec from `prompt` (+ optional `current` for edits, + optional `repairHint`). */
    fun generate(req: LlmAssistRequest): FsmSpec
}

data class LlmAssistRequest(
    val prompt: String,
    val current: FsmSpec? = null,
    /** On a repair retry, the validator's error text from the previous attempt (else null). */
    val repairHint: String? = null,
)

/** LLM boundary failure. `kind` drives the controller's HTTP status. */
class LlmAssistException(message: String, val kind: Kind, cause: Throwable? = null) : RuntimeException(message, cause) {
    enum class Kind { DISABLED, REFUSAL, TRANSPORT }
}
