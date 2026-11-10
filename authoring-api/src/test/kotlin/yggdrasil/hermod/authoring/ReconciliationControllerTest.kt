package yggdrasil.hermod.authoring

import yggdrasil.hermod.opcua.CanonicalSetpoint
import yggdrasil.hermod.opcua.CanonicalSetpoints
import yggdrasil.hermod.registry.RunStore
import yggdrasil.hermod.registry.WorkflowStore
import yggdrasil.hermod.runtime.EnginePort
import org.mockito.ArgumentMatchers
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

@Suppress("UNCHECKED_CAST") private fun <T> anyNonNull(): T = ArgumentMatchers.any<T>() as T
@Suppress("UNCHECKED_CAST") private fun <T> eqOf(v: T): T = ArgumentMatchers.eq(v) as T

@WebMvcTest(ReconciliationController::class)
@Import(ReconciliationControllerTest.Beans::class)
class ReconciliationControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @MockBean lateinit var runStore: RunStore
    @MockBean lateinit var workflowStore: WorkflowStore
    @MockBean lateinit var seeder: SourceRowSeeder
    @MockBean lateinit var canonical: CanonicalSetpoints
    @MockBean lateinit var provenance: ProvenanceService

    @Autowired lateinit var cfg: Beans
    private val temporal: EnginePort get() = cfg.temporal

    @org.junit.jupiter.api.BeforeEach fun reset() { org.mockito.Mockito.reset(temporal) }

    @TestConfiguration
    class Beans {
        val temporal: EnginePort = mock(EnginePort::class.java)
        @Bean fun engineRouter(): EngineRouter = EngineRouter(mapOf("temporal" to lazyOf(temporal)))
    }

    private val rpm = CanonicalSetpoint("recipe.rpmSetpoint", "ns=2;s=Recipe/Rpm", 1500.0, 1.0)

    @Test fun `valid signal seeds source_rows and starts the saga`() {
        given(canonical.byKey("recipe.rpmSetpoint")).willReturn(rpm)
        given(workflowStore.get("ot-recipe-stage-activate", "1.0.0"))
            .willReturn(yggdrasil.hermod.core.WorkflowDef("ot-recipe-stage-activate", emptyList()))
        given(temporal.start(eqOf("r1"), anyNonNull())).willReturn("run-xyz")
        given(provenance.resolve()).willReturn(ProvenanceService.Result.Ok("d".repeat(40), "csha"))
        mvc.perform(post("/api/reconciliations").contentType(MediaType.APPLICATION_JSON)
            .content("""{"reconciliationId":"r1","nodes":["recipe.rpmSetpoint"],"source":"resequence-drift"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.runId").value("run-xyz"))
        verify(seeder).seed(eqOf(mapOf("recipe.rpmSetpoint" to "1500.0")))
        verify(temporal).start(eqOf("r1"), anyNonNull())
        verify(runStore).record(eqOf("run-xyz"), eqOf("ot-recipe-stage-activate"), eqOf("1.0.0"), anyNonNull(), eqOf("temporal"))
    }

    @Test fun `unknown or ungoverned node is rejected 400 with no run started`() {
        given(canonical.byKey("recipe.secretValve")).willReturn(null)
        mvc.perform(post("/api/reconciliations").contentType(MediaType.APPLICATION_JSON)
            .content("""{"nodes":["recipe.secretValve"],"source":"x"}"""))
            .andExpect(status().isBadRequest)
        verify(seeder, never()).seed(anyNonNull())
        verify(temporal, never()).start(anyNonNull(), anyNonNull())
    }

    @Test fun `empty nodes is rejected 400`() {
        mvc.perform(post("/api/reconciliations").contentType(MediaType.APPLICATION_JSON)
            .content("""{"nodes":[],"source":"x"}"""))
            .andExpect(status().isBadRequest)
    }

    @Test fun `503 when saga not deployed`() {
        given(canonical.byKey("recipe.rpmSetpoint")).willReturn(rpm)
        given(workflowStore.get("ot-recipe-stage-activate", "1.0.0")).willReturn(null)
        mvc.perform(post("/api/reconciliations").contentType(MediaType.APPLICATION_JSON)
            .content("""{"nodes":["recipe.rpmSetpoint"],"source":"x"}"""))
            .andExpect(status().isServiceUnavailable)
        verify(temporal, never()).start(anyNonNull(), anyNonNull())
    }

    @Test fun `unresolvable canonical rejected 409, no run started`() {
        given(canonical.byKey("recipe.rpmSetpoint")).willReturn(rpm)
        given(workflowStore.get("ot-recipe-stage-activate", "1.0.0"))
            .willReturn(yggdrasil.hermod.core.WorkflowDef("ot-recipe-stage-activate", emptyList()))
        given(provenance.resolve()).willReturn(ProvenanceService.Result.Unresolvable)
        mvc.perform(post("/api/reconciliations").contentType(MediaType.APPLICATION_JSON)
            .content("""{"nodes":["recipe.rpmSetpoint"],"source":"x"}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("canonical-unresolvable"))
        verify(temporal, never()).start(anyNonNull(), anyNonNull())
    }

    @Test fun `tampered canonical rejected 409, no run started`() {
        given(canonical.byKey("recipe.rpmSetpoint")).willReturn(rpm)
        given(workflowStore.get("ot-recipe-stage-activate", "1.0.0"))
            .willReturn(yggdrasil.hermod.core.WorkflowDef("ot-recipe-stage-activate", emptyList()))
        given(provenance.resolve()).willReturn(ProvenanceService.Result.Tampered)
        mvc.perform(post("/api/reconciliations").contentType(MediaType.APPLICATION_JSON)
            .content("""{"nodes":["recipe.rpmSetpoint"],"source":"x"}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.error").value("canonical-tampered"))
        verify(temporal, never()).start(anyNonNull(), anyNonNull())
    }
}
