package yggdrasil.hermod.authoring

import yggdrasil.hermod.core.WorkflowDef
import yggdrasil.hermod.registry.RunStore
import yggdrasil.hermod.registry.WorkflowStore
import yggdrasil.hermod.runtime.EnginePort
import yggdrasil.hermod.runtime.WorkflowOutput
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyInt
import kotlin.test.Test
import kotlin.test.assertTrue

// Kotlin-null-safe Mockito matcher wrappers — NO mockito-kotlin on the classpath (`org.mockito.kotlin.*`
// will not resolve); same idiom as PublishControllerTest.kt. Top-level, outside the class.
@Suppress("UNCHECKED_CAST") private fun <T> anyNonNull(): T = ArgumentMatchers.any<T>() as T
@Suppress("UNCHECKED_CAST") private fun <T> eqOf(v: T): T = ArgumentMatchers.eq(v) as T

/**
 * RunController now injects an [EngineRouter] (not `ObjectProvider<EnginePort>`). We can't @MockBean an
 * EngineRouter and still steer which port it returns per engine, so we build a REAL EngineRouter over two
 * mock ports (temporal + conductor) via a @TestConfiguration. `engineOf` on the (mocked) RunStore decides
 * which port a query/signal routes to.
 */
@WebMvcTest(RunController::class)
@Import(RunControllerTest.RouterTestConfig::class)
class RunControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @MockBean lateinit var store: WorkflowStore
    @MockBean lateinit var runStore: RunStore

    // The two engine ports the router routes between, held on the @TestConfiguration so the SAME mock
    // instances are wired into the EngineRouter bean AND reachable here for given()/verify(). They are NOT
    // separate EnginePort @Beans (that would make EngineRouter's no-arg @Bean ambiguous / pollute the context).
    @Autowired lateinit var cfg: RouterTestConfig
    private val temporal: EnginePort get() = cfg.temporal
    private val conductor: EnginePort get() = cfg.conductor

    // The router's port mocks live on a context-singleton @TestConfiguration, so their invocation/stub state
    // would leak ACROSS test methods (several use runId "r1"). Reset them before each test for isolation.
    @org.junit.jupiter.api.BeforeEach fun resetPorts() {
        org.mockito.Mockito.reset(temporal, conductor)
    }

    // Default engine for a runId is "temporal" unless a test overrides engineOf. So queries route to `temporal`.
    private fun routeTemporal() { given(runStore.engineOf(anyNonNull())).willReturn("temporal") }

    @TestConfiguration
    class RouterTestConfig {
        val temporal: EnginePort = mock(EnginePort::class.java)
        val conductor: EnginePort = mock(EnginePort::class.java)
        @Bean fun engineRouter(): EngineRouter =
            EngineRouter(mapOf("temporal" to lazyOf(temporal), "conductor" to lazyOf(conductor)))
        // RunController now injects a RunReconciler; @WebMvcTest won't scan the @Component, so supply a real one
        // over the mocked RunStore + this router (its reconcile/isFrozen are exercised by the live gate, not here).
        @Bean fun runReconciler(runStore: RunStore, router: EngineRouter): RunReconciler = RunReconciler(runStore, router)
    }

    @Test fun `run starts the workflow when the def exists`() {
        given(store.get("diamond","1.0.0")).willReturn(WorkflowDef("diamond", emptyList()))
        given(temporal.start(eqOf("r1"), anyNonNull())).willReturn("r1")
        mvc.perform(post("/api/workflows/diamond/1.0.0/run").contentType(MediaType.APPLICATION_JSON).content("""{"runId":"r1"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.runId").value("r1"))
        verify(temporal).start(eqOf("r1"), anyNonNull())   // start takes 2 non-null params → both must be matchers
    }

    @Test fun `run returns 404 when the def is absent`() {
        given(store.get("nope","9.9.9")).willReturn(null)
        mvc.perform(post("/api/workflows/nope/9.9.9/run").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound)
    }

    @Test fun `status returns the engine status`() {
        routeTemporal()
        given(temporal.queryStatus("r1")).willReturn("RUNNING")
        mvc.perform(get("/api/runs/r1")).andExpect(status().isOk).andExpect(jsonPath("$.status").value("RUNNING"))
    }

    @Test fun `approve signals the engine`() {
        routeTemporal()
        mvc.perform(post("/api/runs/r1/approve")).andExpect(status().isOk)
        verify(temporal).signalApproval("r1")
    }

    @Test fun `reject signals the engine with the reason`() {
        routeTemporal()
        mvc.perform(post("/api/runs/r1/reject").contentType(MediaType.APPLICATION_JSON).content("""{"reason":"too risky"}"""))
            .andExpect(status().isOk)
        verify(temporal).signalReject("r1", "too risky")
    }

    @Test fun `reject with no body defaults the reason`() {
        routeTemporal()
        mvc.perform(post("/api/runs/r1/reject")).andExpect(status().isOk)
        verify(temporal).signalReject("r1", "rejected")
    }

    @Test fun `status with wait=true returns completed`() {
        routeTemporal()
        given(temporal.awaitResult("r1")).willReturn(WorkflowOutput(completed = true, compensatedInReverseOrder = emptyList()))
        mvc.perform(get("/api/runs/r1?wait=true")).andExpect(status().isOk).andExpect(jsonPath("$.completed").value(true))
    }

    @Test fun `node states returns the engine map`() {
        routeTemporal()
        given(temporal.queryNodeStates("r1")).willReturn(mapOf("src" to "DONE", "sink" to "RUNNING"))
        mvc.perform(get("/api/runs/r1/nodes"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.src").value("DONE"))
            .andExpect(jsonPath("$.sink").value("RUNNING"))
    }

    @Test fun `node states returns empty map when the engine query fails`() {
        routeTemporal()
        given(temporal.queryNodeStates("gone")).willThrow(RuntimeException("workflow not found"))
        mvc.perform(get("/api/runs/gone/nodes"))
            .andExpect(status().isOk)
            .andExpect(content().json("{}"))
    }

    @Test fun `run records the run after starting it`() {
        given(store.get("diamond","1.0.0")).willReturn(WorkflowDef("diamond", emptyList()))
        given(temporal.start(eqOf("r1"), anyNonNull())).willReturn("r1")
        mvc.perform(post("/api/workflows/diamond/1.0.0/run").contentType(MediaType.APPLICATION_JSON).content("""{"runId":"r1","slowMs":600}"""))
            .andExpect(status().isOk)
        verify(temporal).start(eqOf("r1"), anyNonNull())
        // records the RETURNED id + the (default) temporal engine
        verify(runStore).record(eqOf("r1"), eqOf("diamond"), eqOf("1.0.0"), anyNonNull(), eqOf("temporal"))
    }

    @Test fun `run with engine conductor routes to the conductor port and records engine conductor`() {
        given(store.get("diamond","1.0.0")).willReturn(WorkflowDef("diamond", emptyList()))
        // Conductor generates its own id; record THAT (here "cwf-1"), not the caller runId.
        given(conductor.start(eqOf("rc"), anyNonNull())).willReturn("cwf-1")
        mvc.perform(post("/api/workflows/diamond/1.0.0/run").contentType(MediaType.APPLICATION_JSON)
            .content("""{"runId":"rc","engine":"conductor"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.runId").value("cwf-1"))
        verify(conductor).start(eqOf("rc"), anyNonNull())
        verify(runStore).record(eqOf("cwf-1"), eqOf("diamond"), eqOf("1.0.0"), anyNonNull(), eqOf("conductor"))
    }

    @Test fun `query routes by engineOf to the conductor port`() {
        given(runStore.engineOf("cwf-1")).willReturn("conductor")
        given(conductor.queryStatus("cwf-1")).willReturn("RUNNING")
        mvc.perform(get("/api/runs/cwf-1")).andExpect(status().isOk).andExpect(jsonPath("$.status").value("RUNNING"))
        verify(conductor).queryStatus("cwf-1")
    }

    @Test fun `runs lists records enriched with live status and engine`() {
        given(runStore.list(anyInt())).willReturn(listOf(
            RunStore.Row("r2", "b", "1.0.0", "{}", 2000L, "conductor"),
            RunStore.Row("r1", "a", "1.0.0", "{}", 1000L, "temporal"),
        ))
        given(runStore.engineOf("r2")).willReturn("conductor")
        given(runStore.engineOf("r1")).willReturn("temporal")
        given(conductor.queryStatus("r2")).willReturn("RUNNING")
        given(temporal.queryStatus("r1")).willReturn("COMPLETED")
        mvc.perform(get("/api/runs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].runId").value("r2"))
            .andExpect(jsonPath("$[0].name").value("b"))
            .andExpect(jsonPath("$[0].status").value("RUNNING"))
            .andExpect(jsonPath("$[0].engine").value("conductor"))
            .andExpect(jsonPath("$[1].runId").value("r1"))
            .andExpect(jsonPath("$[1].status").value("COMPLETED"))
            .andExpect(jsonPath("$[1].engine").value("temporal"))
    }

    @Test fun `runs maps a failed status query to UNKNOWN`() {
        given(runStore.list(anyInt())).willReturn(listOf(RunStore.Row("gone", "a", "1.0.0", "{}", 1L, "temporal")))
        given(runStore.engineOf("gone")).willReturn("temporal")
        given(temporal.queryStatus("gone")).willThrow(RuntimeException("not found"))
        mvc.perform(get("/api/runs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].status").value("UNKNOWN"))
    }

    @Test fun `retry signals the engine with the nodeId`() {
        routeTemporal()
        mvc.perform(post("/api/runs/r1/retry").contentType(MediaType.APPLICATION_JSON).content("""{"nodeId":"sink"}"""))
            .andExpect(status().isOk)
        verify(temporal).signalRetry("r1", "sink")
    }

    @Test fun `retry with empty body signals the engine with an empty nodeId`() {
        given(runStore.engineOf("r1")).willReturn("conductor")   // route to the conductor mock
        mvc.perform(post("/api/runs/r1/retry"))                  // no body at all
            .andExpect(status().isOk)
        verify(conductor).signalRetry("r1", "")
    }

    @Test fun `abort signals the engine`() {
        routeTemporal()
        mvc.perform(post("/api/runs/r1/abort")).andExpect(status().isOk)
        verify(temporal).signalAbort("r1")
    }

    @Test fun `compensation returns the engine timeline`() {
        routeTemporal()
        given(temporal.queryCompensationTimeline("r1")).willReturn(listOf(
            yggdrasil.hermod.runtime.CompensationEvent(0, "n", "notify.email", "1.0.0", "COMPENSATED", 1000L),
            yggdrasil.hermod.runtime.CompensationEvent(1, "m", "db.upsert", "1.2.0", "FAILED", 1001L),
        ))
        mvc.perform(get("/api/runs/r1/compensation"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].blockId").value("notify.email"))
            .andExpect(jsonPath("$[0].outcome").value("COMPENSATED"))
            .andExpect(jsonPath("$[1].outcome").value("FAILED"))
    }

    @Test fun `compensation returns empty list when the engine query fails`() {
        routeTemporal()
        given(temporal.queryCompensationTimeline("gone")).willThrow(RuntimeException("not found"))
        mvc.perform(get("/api/runs/gone/compensation"))
            .andExpect(status().isOk)
            .andExpect(content().json("[]"))
    }

    @Test fun `run passes the interactive flag into WorkflowInput`() {
        given(store.get("diamond","1.0.0")).willReturn(WorkflowDef("diamond", emptyList()))
        given(temporal.start(eqOf("r9"), anyNonNull())).willReturn("r9")
        mvc.perform(post("/api/workflows/diamond/1.0.0/run").contentType(MediaType.APPLICATION_JSON)
            .content("""{"runId":"r9","interactive":true}""")).andExpect(status().isOk)
        val cap = org.mockito.ArgumentCaptor.forClass(yggdrasil.hermod.runtime.WorkflowInput::class.java)
        // capture() returns null at match time; the elvis satisfies Kotlin's non-null param (same idiom as anyNonNull)
        verify(temporal).start(eqOf("r9"), cap.capture() ?: yggdrasil.hermod.runtime.WorkflowInput())
        assertTrue(cap.value.interactive)
    }
}
