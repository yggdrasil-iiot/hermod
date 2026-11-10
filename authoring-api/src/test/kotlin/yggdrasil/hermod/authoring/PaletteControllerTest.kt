package yggdrasil.hermod.authoring

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.mockito.BDDMockito.given
import kotlin.test.Test

@WebMvcTest(PaletteController::class)
class PaletteControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @MockBean lateinit var palette: PaletteService          // final Kotlin class — Mockito 5 inline maker mocks it

    @Test fun `GET palette returns the service cards as JSON`() {
        given(palette.palette()).willReturn(listOf(
            PaletteCard("ready.block", "1.0.0", listOf("1.0.0"), "transform", "이름", "설명", "green",
                emptyList(), emptyList(), emptyList(), complete = true)))
        mvc.perform(get("/api/palette"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value("ready.block"))
            .andExpect(jsonPath("$[0].risk").value("green"))
    }
}
