package yggdrasil.hermod.authoring

import yggdrasil.hermod.core.WorkflowDef
import yggdrasil.hermod.dispatch.DispatchAssembly
import yggdrasil.hermod.registry.Registry
import yggdrasil.hermod.registry.Resolution
import yggdrasil.hermod.registry.WorkflowStore
import org.mockito.ArgumentMatchers
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import kotlin.test.Test

// Kotlin-null-safe Mockito matcher wrappers — the project has NO mockito-kotlin, so `org.mockito.kotlin.*`
// does NOT resolve. Copy the established idiom from PublishControllerTest.kt (top-level, outside the class).
@Suppress("UNCHECKED_CAST") private fun <T> anyNonNull(): T = ArgumentMatchers.any<T>() as T
@Suppress("UNCHECKED_CAST") private fun <T> eqOf(v: T): T = ArgumentMatchers.eq(v) as T

@WebMvcTest(WorkflowController::class)
class WorkflowControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @MockBean lateinit var store: WorkflowStore
    @MockBean lateinit var registry: Registry            // WorkflowController compiles against it (final class — Mockito 5 inline mocks it)

    private val diamondJson = """
      {"name":"diamond","steps":[
        {"blockId":"db.read","pinnedVersion":"1.0.0","id":"src","params":{"table":"source_rows"}},
        {"blockId":"db.upsert","pinnedVersion":"1.2.0","id":"sink","params":{"table":"target_rows"},"wiring":{"rows":"src.rows"}}
      ]}"""

    // WorkflowCompiler.compile calls registry.resolveSpec(id, pinnedVersion) (NOT resolve). Stub the two
    // builtins the diamond uses so resolution succeeds DB-free; the bad-def test then fails on WIRING
    // (unknown upstream "ghost"), not on resolution. Builtin contracts come from DispatchAssembly (DB-free).
    private fun stubResolve() {
        given(registry.resolveSpec("db.read", "1.0.0"))
            .willReturn(Resolution.Builtin(DispatchAssembly.builtinContracts["db.read#1.0.0"]!!))
        given(registry.resolveSpec("db.upsert", "1.2.0"))
            .willReturn(Resolution.Builtin(DispatchAssembly.builtinContracts["db.upsert#1.2.0"]!!))
    }

    @Test fun `validate returns valid=true and nodeCount for a compilable def`() {
        stubResolve()
        mvc.perform(post("/api/workflows/validate").contentType(MediaType.APPLICATION_JSON).content(diamondJson))
            .andExpect(status().isOk).andExpect(jsonPath("$.valid").value(true))
    }

    @Test fun `validate returns valid=false with a diagnostic for a bad def`() {
        val bad = diamondJson.replace("\"src.rows\"", "\"ghost.rows\"")   // unknown upstream → CompileException
        stubResolve()
        mvc.perform(post("/api/workflows/validate").contentType(MediaType.APPLICATION_JSON).content(bad))
            .andExpect(status().isOk).andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.diagnostics").isNotEmpty)
    }

    @Test fun `save persists a compilable def and returns SaveResponse`() {
        stubResolve()
        given(store.save(anyNonNull(), eqOf("1.0.0"))).willReturn(WorkflowStore.SaveResult(true))
        mvc.perform(post("/api/workflows?version=1.0.0").contentType(MediaType.APPLICATION_JSON).content(diamondJson))
            .andExpect(status().isOk).andExpect(jsonPath("$.name").value("diamond"))
        verify(store).save(anyNonNull(), eqOf("1.0.0"))
    }

    @Test fun `save duplicate returns 400 with ValidateResult shape`() {
        stubResolve()
        given(store.save(anyNonNull(), eqOf("1.0.0")))
            .willReturn(WorkflowStore.SaveResult(false, "diamond@1.0.0 already exists (immutable)"))
        mvc.perform(post("/api/workflows?version=1.0.0").contentType(MediaType.APPLICATION_JSON).content(diamondJson))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.diagnostics").isNotEmpty)
    }

    @Test fun `save compile-fail returns 400 with valid=false`() {
        val bad = diamondJson.replace("\"src.rows\"", "\"ghost.rows\"")   // unknown upstream → CompileException
        stubResolve()
        mvc.perform(post("/api/workflows?version=1.0.0").contentType(MediaType.APPLICATION_JSON).content(bad))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.valid").value(false))
    }

    @Test fun `get returns the def`() {
        given(store.get("diamond", "1.0.0")).willReturn(WorkflowDef("diamond", emptyList()))
        mvc.perform(get("/api/workflows/diamond/1.0.0"))
            .andExpect(status().isOk).andExpect(jsonPath("$.name").value("diamond"))
    }

    @Test fun `get missing returns 404`() {
        given(store.get("nope", "9.9.9")).willReturn(null)
        mvc.perform(get("/api/workflows/nope/9.9.9")).andExpect(status().isNotFound)
    }

    @Test fun `list returns store rows`() {
        given(store.list()).willReturn(listOf(WorkflowStore.Row("diamond","1.0.0",true)))
        mvc.perform(get("/api/workflows")).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").value("diamond"))
    }
}
