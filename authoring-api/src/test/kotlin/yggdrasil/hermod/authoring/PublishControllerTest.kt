package yggdrasil.hermod.authoring

import yggdrasil.hermod.core.*
import yggdrasil.hermod.registry.BlockIndex
import yggdrasil.hermod.registry.ManifestLoader
import yggdrasil.hermod.registry.PublishResult
import yggdrasil.hermod.registry.Registry
import org.mockito.ArgumentMatchers
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File
import kotlin.test.Test

// Kotlin-null-safe wrappers: Mockito's any()/eq() return null, which Kotlin rejects for non-null params
// at the stubbing call site. These register the matcher (the side effect Mockito records) and return a
// value typed as non-null; the `as T` cast is erased at runtime so the actual null passes through harmlessly.
@Suppress("UNCHECKED_CAST")
private fun <T> anyNonNull(): T = ArgumentMatchers.any<T>() as T
@Suppress("UNCHECKED_CAST")
private fun <T> eqOf(v: T): T = ArgumentMatchers.eq(v) as T

@WebMvcTest(PublishController::class, ValidateController::class, BlockController::class)
class PublishControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @MockBean lateinit var registry: Registry
    @MockBean lateinit var index: BlockIndex

    private fun completeContract() = BlockContract(
        id = "io.example.demo", version = "1.0.0", category = BlockCategory.transform,
        displayName = "데모", description = "데모 블록",
        params = listOf(ParamSpec("who", "string", required = true, label = "대상")),
        inputs = listOf(IoSpec("rows", "Record[]", label = "행")),
        forwardHandler = "io.example.DemoBlock",
        idempotency = IdempotencySpec(IdempotencyStrategy.NATURAL),
        compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.NONE),
        retry = RetrySpec(3, 100, 1000),
    )

    private fun incompleteContract() = completeContract().copy(
        params = listOf(ParamSpec("who", "string", required = true)),   // no label → C3
    )

    @Test fun `validate returns ContractValidator + CanvasReadiness diagnostics and complete flag`() {
        val json = ManifestLoader.toJson(incompleteContract())
        mvc.perform(post("/api/contracts/validate").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.complete").value(false))
            .andExpect(jsonPath("$.readiness[?(@.code == 'C3')]").exists())
    }

    @Test fun `validate of a complete contract is valid and complete`() {
        val json = ManifestLoader.toJson(completeContract())
        mvc.perform(post("/api/contracts/validate").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.complete").value(true))
    }

    @Test fun `publish multipart calls registry-publish and returns ok`() {
        given(registry.publish(anyNonNull<File>(), anyNonNull<BlockContract>()))
            .willReturn(PublishResult(true))
        val contract = MockMultipartFile("contract", "contract", MediaType.APPLICATION_JSON_VALUE,
            ManifestLoader.toJson(completeContract()).toByteArray())
        val jar = MockMultipartFile("jar", "demo.jar", "application/java-archive", byteArrayOf(1, 2, 3))
        mvc.perform(multipart("/api/publish").file(contract).file(jar))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ok").value(true))
    }

    @Test fun `publish returning errors maps to 400`() {
        given(registry.publish(anyNonNull<File>(), anyNonNull<BlockContract>()))
            .willReturn(PublishResult(false, listOf("boom")))
        val contract = MockMultipartFile("contract", "contract", MediaType.APPLICATION_JSON_VALUE,
            ManifestLoader.toJson(completeContract()).toByteArray())
        val jar = MockMultipartFile("jar", "demo.jar", "application/java-archive", byteArrayOf(1, 2, 3))
        mvc.perform(multipart("/api/publish").file(contract).file(jar))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.errors[0]").value("boom"))
    }

    @Test fun `deprecate known block returns 204`() {
        given(index.deprecate(eqOf("io.example.demo"), eqOf("1.0.0"))).willReturn(1)
        mvc.perform(post("/api/blocks/io.example.demo/1.0.0/deprecate"))
            .andExpect(status().isNoContent)
    }

    @Test fun `deprecate unknown block returns 404`() {
        given(index.deprecate(anyNonNull<String>(), anyNonNull<String>())).willReturn(0)
        mvc.perform(post("/api/blocks/ghost/9.9.9/deprecate"))
            .andExpect(status().isNotFound)
    }
}
