package koshei.authoring.emit

import koshei.opcua.OpcuaDbConnectionOverride
import koshei.opcua.emit.GovernanceEvent
import koshei.opcua.emit.GovernedNode
import koshei.registry.EmittedEventStore
import koshei.registry.RunStore
import koshei.runtime.CompensationEvent
import koshei.runtime.EnginePort
import koshei.runtime.WorkflowInput
import koshei.runtime.WorkflowOutput
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GovernanceEventEmitterTest {

    // Pure unit test: point OpcuaDb at a dead port so any DB-backed default the emitter inherits fails
    // on every machine, instead of passing only while a local Postgres happens to be up.
    private var savedDbUrl: String? = null
    @BeforeEach fun noDb() { savedDbUrl = OpcuaDbConnectionOverride.url; OpcuaDbConnectionOverride.url = "jdbc:postgresql://127.0.0.1:1/none" }
    @AfterEach fun restoreDb() { OpcuaDbConnectionOverride.url = savedDbUrl }

    // ---- fakes ----

    /** In-memory write-once claim set (no DB). */
    private class FakeEmittedLog : EmittedEventStore({ error("no db in unit test") }) {
        private val claims = HashSet<Pair<String, String>>()
        override fun tryClaim(runId: String, eventType: String, atMillis: Long): Boolean =
            claims.add(runId to eventType)
        override fun claimed(runId: String, eventType: String): Boolean =
            (runId to eventType) in claims
    }

    /** Captures the GovernanceEvents the emitter hands to publishNdata. */
    private class CapturingSession(val events: MutableList<GovernanceEvent> = mutableListOf()) :
        SparkplugEdgeSession(FakeEdgeNodeTransport(), "Koshei", "Governance", { emptyList() }) {
        override fun publishNdata(ev: GovernanceEvent) { events += ev }
    }

    private class ThrowingSession :
        SparkplugEdgeSession(FakeEdgeNodeTransport(), "Koshei", "Governance", { emptyList() }) {
        override fun publishNdata(ev: GovernanceEvent): Unit = throw RuntimeException("boom")
    }

    private class FakePort(
        val status: String,
        val nodeStates: Map<String, String> = emptyMap(),
        val timeline: List<CompensationEvent> = emptyList(),
    ) : EnginePort {
        override fun queryStatus(workflowId: String) = status
        override fun queryNodeStates(workflowId: String) = nodeStates
        override fun queryCompensationTimeline(workflowId: String) = timeline
        override fun start(workflowId: String, input: WorkflowInput): String = nope()
        override fun signalApproval(workflowId: String) = nope()
        override fun signalReject(workflowId: String, reason: String) = nope()
        override fun signalRetry(workflowId: String, nodeId: String) = nope()
        override fun signalAbort(workflowId: String) = nope()
        override fun awaitResult(workflowId: String): WorkflowOutput = nope()
        private fun nope(): Nothing = throw UnsupportedOperationException()
    }

    private fun row(engine: String = "temporal") =
        RunStore.Row("wf-1", "ot-recipe-stage-activate", "1", "{}", 0L, engine)

    private val staged = listOf(GovernedNode("recipe.rpmSetpoint", 1400.0, "STAGED", "ns=2;s=Recipe/Rpm"))
    private fun audit(runId: String) =
        listOf(GovernedNode("recipe.rpmSetpoint", 1450.0, "CONFIRMED", "ns=2;s=Recipe/Rpm"))

    private fun noDefRef(runId: String): String? = null

    private fun emitter(session: SparkplugEdgeSession, log: EmittedEventStore, now: Long = 1000L) =
        GovernanceEventEmitter(session, log, { staged }, ::audit, { now }, ::noDefRef)

    // ---- tests ----

    @Test fun `terminal COMPLETED emits CONFIRMED once then dedups`() {
        val s = CapturingSession(); val log = FakeEmittedLog()
        val e = emitter(s, log)
        val port = FakePort("COMPLETED")
        e.observe("wf-1", row(), port)
        e.observe("wf-1", row(), port)   // second observe → deduped
        assertEquals(1, s.events.size)
        assertEquals("CONFIRMED", s.events[0].type)
        assertEquals(1450.0, s.events[0].nodes.first().value)   // enriched from audit summary
    }

    @Test fun `in-flight non-terminal emits RECONCILING with STAGED nodes`() {
        val s = CapturingSession(); val log = FakeEmittedLog()
        // stage DONE + activate still RUNNING = reconciliation underway (field staged, not yet terminal)
        emitter(s, log).observe("wf-1", row(), FakePort("RUNNING", mapOf("stage" to "DONE", "activate" to "RUNNING")))
        assertEquals(1, s.events.size)
        assertEquals("RECONCILING", s.events[0].type)
        assertEquals("STAGED", s.events[0].nodes.first().outcome)
        assertEquals("IN_FLIGHT", s.events[0].status)
    }

    @Test fun `a run parked at the human gate (AWAITING_APPROVAL) is still in-flight and emits RECONCILING`() {
        val s = CapturingSession(); val log = FakeEmittedLog()
        // B1: the gate node is the last non-terminal node in AWAITING_APPROVAL — must count as in-flight so
        // RECONCILING is not suppressed for the whole (human-paced) parked window.
        emitter(s, log).observe("wf-1", row(), FakePort("RUNNING", mapOf("stage" to "DONE", "activate" to "AWAITING_APPROVAL")))
        assertEquals(1, s.events.size)
        assertEquals("RECONCILING", s.events[0].type)
    }

    @Test fun `not-yet-staged run (no node DONE) does not emit RECONCILING`() {
        val s = CapturingSession(); val log = FakeEmittedLog()
        // nothing finished yet → not "in-flight" → no premature RECONCILING
        emitter(s, log).observe("wf-1", row(), FakePort("RUNNING", mapOf("stage" to "RUNNING", "activate" to "PENDING")))
        assertTrue(s.events.isEmpty())
    }

    @Test fun `ordering guard - RECONCILING not emitted once a terminal was claimed`() {
        val s = CapturingSession(); val log = FakeEmittedLog()
        // terminal already emitted for this run
        log.tryClaim("wf-1", "CONFIRMED", 1L)
        emitter(s, log).observe("wf-1", row(), FakePort("RUNNING", mapOf("stage" to "DONE", "activate" to "RUNNING")))
        assertTrue(s.events.isEmpty())   // no out-of-order RECONCILING after terminal
    }

    @Test fun `terminal falls back to canonical setpoints when run-keyed audit is empty`() {
        val s = CapturingSession(); val log = FakeEmittedLog()
        // summarize returns empty (R1 audit not run-keyed) -> emitter uses staged canonical nodes,
        // re-tagged with the terminal outcome so the event still carries the governed node key.
        val e = GovernanceEventEmitter(s, log, { staged }, { emptyList() }, { 1000L }, ::noDefRef)
        e.observe("wf-1", row(), FakePort("COMPLETED"))
        val node = s.events.single().nodes.single()
        assertEquals("recipe.rpmSetpoint", node.logicalKey)   // twin can correlate the finding
        assertEquals("CONFIRMED", node.outcome)               // outcome mapped from CONFIRMED event
        assertEquals(1400.0, node.value)                      // canonical desired value
    }

    @Test fun `compensated terminal emits RECON_FAILED`() {
        val s = CapturingSession(); val log = FakeEmittedLog()
        val comp = listOf(CompensationEvent(0, "stage", "opcua.write", "1", "RESTORED", 5L))
        emitter(s, log).observe("wf-1", row(), FakePort("COMPLETED", timeline = comp))
        assertEquals("RECON_FAILED", s.events.single().type)
        assertEquals("COMPENSATED", s.events.single().compOutcome)
    }

    @Test fun `failed terminal status emits RECON_FAILED`() {
        val s = CapturingSession(); val log = FakeEmittedLog()
        emitter(s, log).observe("wf-1", row(), FakePort("FAILED"))
        assertEquals("RECON_FAILED", s.events.single().type)
    }

    @Test fun `events carry the run's version reference from the provenance lookup`() {
        val s = CapturingSession(); val log = FakeEmittedLog()
        val sha = "d".repeat(40)
        val e = GovernanceEventEmitter(s, log, { staged }, ::audit, { 1000L }, { runId -> if (runId == "wf-1") sha else null })
        e.observe("wf-1", row(), FakePort("RUNNING", mapOf("stage" to "DONE", "activate" to "RUNNING")))
        e.observe("wf-1", row(), FakePort("COMPLETED"))
        assertEquals(listOf("RECONCILING", "CONFIRMED"), s.events.map { it.type })
        assertEquals(listOf(sha, sha), s.events.map { it.defRef })
    }

    @Test fun `publish throwing does not escape observe (fail-open)`() {
        val log = FakeEmittedLog()
        val e = GovernanceEventEmitter(ThrowingSession(), log, { staged }, ::audit, { 1L }, ::noDefRef)
        assertDoesNotThrow { e.observe("wf-1", row(), FakePort("COMPLETED")) }
    }

    // defRef is optional enrichment (nullable on GovernanceEvent and on the wire), and it is looked up after the
    // write-once claim: a failed lookup must drop the defRef, not the event, or later observes dedup it forever.
    private fun provenanceDown(runId: String): String? =
        throw java.sql.SQLException("relation \"reconciliation_provenance\" does not exist")

    @Test fun `provenance lookup failure still publishes RECONCILING without defRef`() {
        val s = CapturingSession(); val log = FakeEmittedLog()
        val e = GovernanceEventEmitter(s, log, { staged }, ::audit, { 1000L }, ::provenanceDown)
        e.observe("wf-1", row(), FakePort("RUNNING", mapOf("stage" to "DONE", "activate" to "RUNNING")))
        val ev = s.events.single()
        assertEquals("RECONCILING", ev.type)
        assertNull(ev.defRef)
    }

    @Test fun `provenance lookup failure still publishes the terminal event without defRef`() {
        val s = CapturingSession(); val log = FakeEmittedLog()
        val e = GovernanceEventEmitter(s, log, { staged }, ::audit, { 1000L }, ::provenanceDown)
        e.observe("wf-1", row(), FakePort("COMPLETED"))
        val ev = s.events.single()
        assertEquals("CONFIRMED", ev.type)
        assertEquals(1450.0, ev.nodes.first().value)   // audit enrichment unaffected
        assertNull(ev.defRef)
    }
}
