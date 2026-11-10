package yggdrasil.hermod.authoring.assist

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonSchemaLocalValidation
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import yggdrasil.hermod.opcua.FsmSpec

/**
 * Real LLM-assist via the Anthropic Java SDK. Reliability (design §4): STRUCTURED OUTPUTS (grammar-
 * constrained to [FsmSpecDto]) is the primary guarantee; the domain pack + exemplars raise quality; the
 * caller ([FsmAssistService]) validates + repairs. plan-then-emit: adaptive thinking on. Model
 * claude-opus-4-8. Auth: server ANTHROPIC_API_KEY (fromEnv). Never logs the key or prompt/response.
 *
 * ⚠️ UNVERIFIED AT RUNTIME — no ANTHROPIC_API_KEY in this environment. Confirmed only to compile + wire.
 * The real generation is exercised solely by Chunk 4's keyed smoke gate.
 *
 * GENERATION PATH = **native structured outputs** (NOT the JSON-in-prompt fallback). SDK signatures
 * verified against anthropic-java-2.34.0 (anthropic-java-core) via javap on 2026-07-03 (Task 5):
 *   - client init:  AnthropicOkHttpClient.fromEnv() : AnthropicClient
 *   - request:      MessageCreateParams.builder()
 *                       .model(String).maxTokens(long)
 *                       .thinking(ThinkingConfigAdaptive.builder().build())
 *                       .system(String).addUserMessage(String)
 *                       .outputConfig(Class<T>, JsonSchemaLocalValidation)   // -> StructuredMessageCreateParams.Builder<T>
 *                       .build()                                             // : StructuredMessageCreateParams<T>
 *   - call:         client.messages().create(params) : StructuredMessage<T>  (typed overload)
 *   - stop handling: StructuredMessage.stopReason() : Optional<StopReason>  (StopReason.REFUSAL / MAX_TOKENS)
 *   - typed output: StructuredMessage.content() : List<StructuredContentBlock<T>>;
 *                   StructuredContentBlock.text() : Optional<StructuredTextBlock<T>>; StructuredTextBlock.text() : T
 * NOTE ON EFFORT: `output_config.effort` and `output_config.format` share the OutputConfig object; the
 *   class-based structured-output helper occupies `format` and returns typed params, so `effort` cannot be
 *   added without hand-building the JSON schema and forfeiting typed parsing. Effort is therefore omitted
 *   (structured schema chosen over the effort hint). temperature/top_p/top_k are NOT set (removed on 4.8 → 400).
 * No beta header is needed for the GA structured-output path.
 */
class AnthropicLlmAssistPort(
    private val client: AnthropicClient = AnthropicOkHttpClient.fromEnv(),
    private val model: String = "claude-opus-4-8",
) : LlmAssistPort {
    override fun generate(req: LlmAssistRequest): FsmSpec {
        val system = buildString {
            append(DomainPack.SYSTEM_PROMPT).append("\n\nEXAMPLES:\n")
            DomainPack.exemplars().forEach { append(it).append("\n---\n") }
        }
        val user = buildAssistUserMessage(req, jacksonObjectMapper())
        try {
            val params = MessageCreateParams.builder()
                .model(model)
                // Generous ceiling: adaptive-thinking tokens share this budget, and a MAX_TOKENS stop maps
                // to a 502 that does NOT re-enter the repair loop — so leave headroom for small FSM specs.
                .maxTokens(16384L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(system)
                .addUserMessage(user)
                .outputConfig(FsmSpecDto::class.java, JsonSchemaLocalValidation.NO)
                .build()

            val msg = client.messages().create(params)

            msg.stopReason().ifPresent { sr ->
                when (sr) {
                    StopReason.REFUSAL ->
                        throw LlmAssistException("anthropic refused the request", LlmAssistException.Kind.REFUSAL)
                    StopReason.MAX_TOKENS ->
                        throw LlmAssistException("anthropic response truncated (max_tokens)", LlmAssistException.Kind.TRANSPORT)
                    else -> Unit
                }
            }

            val dto = msg.content().stream()
                .flatMap { it.text().stream() }
                .map { it.text() }
                .findFirst()
                .orElseThrow {
                    LlmAssistException("anthropic returned no structured output", LlmAssistException.Kind.TRANSPORT)
                }
            return dto.normalized().toFsmSpec()
        } catch (e: LlmAssistException) {
            throw e
        } catch (e: Exception) {
            throw LlmAssistException("anthropic call failed: ${e.message}", LlmAssistException.Kind.TRANSPORT, e)
        }
    }
}

/**
 * Build the user message for an assist call. Pure (no network) so the edit framing is unit-testable.
 * Fresh generation (current == null): "Request: <prompt>" (+ optional repair-hint section) — byte-identical
 * to the prior inline behavior. Editing (current != null): the [DomainPack.EDIT_INSTRUCTION] framing + the
 * current spec serialized as JSON (well-formed, like the exemplars) + the request. The system prompt is
 * built separately and is NOT part of this function.
 */
internal fun buildAssistUserMessage(req: LlmAssistRequest, mapper: ObjectMapper): String = buildString {
    req.current?.let {
        append(DomainPack.EDIT_INSTRUCTION).append("\n\nCurrent FSM (edit it):\n")
            .append(mapper.writeValueAsString(it.toDto())).append("\n\n")
    }
    append("Request: ").append(req.prompt)
    req.repairHint?.let {
        append("\n\nThe previous attempt had these validator errors; fix them:\n").append(it)
    }
}
