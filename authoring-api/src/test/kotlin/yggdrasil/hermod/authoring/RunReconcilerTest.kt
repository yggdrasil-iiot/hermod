package yggdrasil.hermod.authoring

import yggdrasil.hermod.authoring.emit.FakeEdgeNodeTransport
import yggdrasil.hermod.authoring.emit.GovernanceEventEmitter
import yggdrasil.hermod.authoring.emit.SparkplugEdgeSession
import yggdrasil.hermod.registry.EmittedEventStore
import yggdrasil.hermod.registry.RunStore
import yggdrasil.hermod.runtime.CompensationEvent
import yggdrasil.hermod.runtime.EnginePort
import yggdrasil.hermod.runtime.WorkflowInput
import yggdrasil.hermod.runtime.WorkflowOutput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The reconcile choke-point emit hook (spec 2026-07-01 §3.2): fires for parked + terminal runs; null = no-op. */
class RunReconcilerTest {

    private fun row(engine: String = "temporal") =
        RunStore.Row("wf-1", "ot-recipe-stage-activate", "1", "{}", 0L, engine)

    /** In-memory RunStore: serves one row, records terminal archive, no DB. */
    private class FakeRunStore(private val stored: RunStore.Row?) : RunStore({ error("no db") }) {
        var markTerminalCalls = 0
        override fun get(runId: String): RunStore.Row? = stored
        override fun markTerminal(runId: String, finalStatus: String, compOutcome: String) { markTerminalCalls++ }
        override fun refreshCompOutcome(runId: String, compOutcome: String) {}
        override fun snapshotNodeStates(runId: String, states: Map<String, String>) {}
        override fun snapshotCompEvents(runId: String, events: List<CompEventRow>) {}
    }

    private class FakePort(
        val status: String,
        val nodeStates: Map<String, String> = emptyMap(),
    ) : EnginePort {
        override fun queryStatus(workflowId: String) = status
        override fun queryNodeStates(workflowId: String) = nodeStates
        override fun queryCompensationTimeline(workflowId: String): List<CompensationEvent> = emptyList()
        override fun start(workflowId: String, input: WorkflowInput): String = nope()
        override fun signalApproval(workflowId: String) = nope()
        override fun signalReject(workflowId: String, reason: String) = nope()
        override fun signalRetry(workflowId: String, nodeId: String) = nope()
        override fun signalAbort(workflowId: String) = nope()
        override fun awaitResult(workflowId: String): WorkflowOutput = nope()
        private fun nope(): Nothing = throw UnsupportedOperationException()
    }

    private fun router(port: EnginePort) = EngineRouter(mapOf("temporal" to lazy { port }))

    /** Records which runIds observe() saw, without touching a broker/DB. */
    private class RecordingEmitter : GovernanceEventEmitter(
        SparkplugEdgeSession(FakeEdgeNodeTransport(), "Hermod", "Governance", { emptyList() }),
        object : EmittedEventStore({ error("no db") }) {},
        { emptyList() },
    ) {
        val observed = mutableListOf<String>()
        override fun observe(runId: String, row: RunStore.Row, port: EnginePort) { observed += runId }
    }

    @Test fun `hook fires for a parked (non-terminal) run`() {
        val emitter = RecordingEmitter()
        val port = FakePort("RUNNING", mapOf("stage" to "PARKED"))
        RunReconciler(FakeRunStore(row()), router(port), emitter).reconcile("wf-1")
        assertEquals(listOf("wf-1"), emitter.observed)   // observed even though non-terminal
    }

    @Test fun `hook fires for a terminal run`() {
        val emitter = RecordingEmitter()
        RunReconciler(FakeRunStore(row()), router(FakePort("COMPLETED")), emitter).reconcile("wf-1")
        assertEquals(listOf("wf-1"), emitter.observed)
    }

    @Test fun `null emitter is a no-op and archive still runs`() {
        val store = FakeRunStore(row())
        RunReconciler(store, router(FakePort("COMPLETED")), null).reconcile("wf-1")   // no NPE
        assertEquals(1, store.markTerminalCalls)   // archive path unaffected
    }
}
