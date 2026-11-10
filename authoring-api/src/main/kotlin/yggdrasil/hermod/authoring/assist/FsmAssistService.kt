package yggdrasil.hermod.authoring.assist

import yggdrasil.hermod.opcua.FsmSpec
import yggdrasil.hermod.opcua.FsmValidator

/** Outcome of an assist call. Ok = structurally valid draft; Invalid = repair budget exhausted. */
sealed interface AssistOutcome {
    data class Ok(val spec: FsmSpec) : AssistOutcome
    data class Invalid(val errors: List<String>) : AssistOutcome
}

/**
 * Generate → FsmValidator (structural) → bounded repair. Hard cap = [maxGenerations] TOTAL generations
 * (initial + up to maxGenerations-1 re-asks). Fail-closed to Invalid on exhaustion — never returns an
 * unvalidated draft. Cross-artifact validation stays the conformance gate's job (advisory boundary).
 */
class FsmAssistService(
    private val port: LlmAssistPort,
    private val maxGenerations: Int = 3,
) {
    init { require(maxGenerations >= 1) { "maxGenerations must be >= 1" } }

    fun assist(prompt: String, current: FsmSpec?): AssistOutcome {
        var hint: String? = null
        var lastErrors: List<String> = emptyList()
        repeat(maxGenerations) {
            val spec = port.generate(LlmAssistRequest(prompt = prompt, current = current, repairHint = hint))
            val errors = FsmValidator.validate(spec).errors
            if (errors.isEmpty()) return AssistOutcome.Ok(spec)
            lastErrors = errors
            hint = errors.joinToString("\n")
        }
        return AssistOutcome.Invalid(lastErrors)
    }
}
