package yggdrasil.hermod.authoring

import org.mockito.ArgumentMatchers
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * HERMOD_RECIPE_SETPOINTS unset (the test task strips it): the FULL context must boot with the real
 * canonical beans (nothing mocked), and a reconciliation must fail closed 409 canonical-unresolvable
 * before any side effect — the contract CanonicalConfig documents.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CanonicalAbsentTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var cc: CanonicalConfig
    @MockBean lateinit var seeder: SourceRowSeeder

    @Test fun `context boots with no published canonical`() {
        assertNull(cc.rawBytes, "precondition: HERMOD_RECIPE_SETPOINTS must be unset for this test")
    }

    @Test fun `reconciliation fails closed 409 canonical-unresolvable, nothing seeded`() {
        mvc.perform(post("/api/reconciliations").contentType(MediaType.APPLICATION_JSON)
            .content("""{"nodes":["recipe.rpmSetpoint"],"source":"x"}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("canonical-unresolvable"))
        @Suppress("UNCHECKED_CAST")
        verify(seeder, never()).seed(ArgumentMatchers.anyMap<String, String>() as Map<String, String>)
    }
}
