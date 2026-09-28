package koshei.authoring

import koshei.registry.BlockIndex
import koshei.registry.Registry
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

@WebMvcTest(PublishController::class)
@TestPropertySource(properties = ["koshei.publish.disabled=1"])
class PublishDisabledNumericTest {
    @Autowired lateinit var mvc: MockMvc
    @MockBean lateinit var registry: Registry
    @MockBean lateinit var index: BlockIndex

    @Test fun `disabled publish (numeric 1) answers 403 and never touches the registry`() {
        val contract = MockMultipartFile("contract", "contract", MediaType.APPLICATION_JSON_VALUE, "{}".toByteArray())
        val jar = MockMultipartFile("jar", "x.jar", "application/java-archive", byteArrayOf(1, 2, 3))
        mvc.perform(multipart("/api/publish").file(contract).file(jar))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.errors[0]").value("publish is disabled on this deployment (KOSHEI_PUBLISH_DISABLED)"))
        verifyNoInteractions(registry)
    }
}
