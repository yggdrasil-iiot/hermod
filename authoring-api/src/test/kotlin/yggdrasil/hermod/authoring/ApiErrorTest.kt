package yggdrasil.hermod.authoring

import yggdrasil.hermod.registry.BlockIndex
import yggdrasil.hermod.registry.Registry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/**
 * The validate/publish controllers parse the client body via `ManifestLoader.fromJson` synchronously in
 * the controller method. A malformed body throws (Jackson `JsonProcessingException` / `IllegalArgumentException`
 * from enum parsing) and must surface as 400, not 500. [ApiExceptionHandler] is imported explicitly so the
 * @WebMvcTest slice registers the advice.
 */
@WebMvcTest(ValidateController::class)
@Import(ApiExceptionHandler::class)
class ApiErrorTest {
    @Autowired lateinit var mvc: MockMvc
    @MockBean lateinit var registry: Registry
    @MockBean lateinit var index: BlockIndex

    @Test fun `malformed JSON to validate returns 400 not 500`() {
        mvc.perform(post("/api/contracts/validate").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").exists())
    }
}
