package yggdrasil.hermod.authoring.assist

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Selects the LlmAssistPort by env (mirrors EngineConfig/ApplyPortFactory). Default = the deterministic
 * fake (safe keyless default). HERMOD_LLM_ASSIST=anthropic wires the real port (Chunk 2).
 */
@Configuration
class LlmAssistConfig {
    @Bean
    fun llmAssistPort(): LlmAssistPort = when (System.getenv("HERMOD_LLM_ASSIST")) {
        "anthropic" -> AnthropicLlmAssistPort()
        else -> FakeLlmAssistPort(listOf(FakeLlmAssistPort.DEMO))
    }

    @Bean
    fun fsmAssistService(port: LlmAssistPort): FsmAssistService = FsmAssistService(port)
}
