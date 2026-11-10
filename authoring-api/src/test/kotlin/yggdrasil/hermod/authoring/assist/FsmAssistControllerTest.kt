package yggdrasil.hermod.authoring.assist

import yggdrasil.hermod.opcua.FsmSpec
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest(FsmAssistController::class)
@Import(FsmAssistControllerTest.Cfg::class)
class FsmAssistControllerTest {
    @Autowired lateinit var mvc: MockMvc

    @Test fun `valid draft returns 200 with the spec`() {
        mvc.perform(post("/api/fsm/assist").contentType("application/json").content("""{"prompt":"ok"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.states[0].id").exists())
    }

    @Test fun `unrepairable draft returns 422 with issues`() {
        mvc.perform(post("/api/fsm/assist").contentType("application/json").content("""{"prompt":"bad"}"""))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.issues").isArray)
    }

    // Locks the LlmAssistException.kind -> HTTP status mapping (deferred from the Chunk 1 review).
    @Test fun `disabled boundary returns 503`() {
        mvc.perform(post("/api/fsm/assist").contentType("application/json").content("""{"prompt":"disabled"}"""))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").exists())
    }

    @Test fun `transport boundary returns 502`() {
        mvc.perform(post("/api/fsm/assist").contentType("application/json").content("""{"prompt":"transport"}"""))
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.error").exists())
    }

    @TestConfiguration
    class Cfg {
        // "bad" prompt → always-invalid fake (empty states/transitions ⇒ validator errors);
        // "disabled"/"transport" → the port throws LlmAssistException with that kind; else → valid demo.
        // FsmSpec is a plain class (no .copy) — construct the invalid one fresh.
        private val BAD = FsmSpec("x", "x", "x.s", emptyList(), emptyList(), "v1")
        @Bean fun port(): LlmAssistPort = object : LlmAssistPort {
            override fun generate(req: LlmAssistRequest) = when (req.prompt) {
                "bad" -> BAD
                "disabled" -> throw LlmAssistException("assist disabled", LlmAssistException.Kind.DISABLED)
                "transport" -> throw LlmAssistException("upstream unreachable", LlmAssistException.Kind.TRANSPORT)
                else -> FakeLlmAssistPort.DEMO
            }
        }
        @Bean fun svc(p: LlmAssistPort) = FsmAssistService(p)
    }
}
