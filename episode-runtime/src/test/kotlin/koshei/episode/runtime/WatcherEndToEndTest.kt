package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.temporal.client.WorkflowClient
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Mock window, held at `approve` until [gate] opens: the episode stays DISPATCHED meanwhile. [asked] opens when it is
 * reached. A gate that stays shut for 30 s fails the call rather than letting it through unnoticed.
 */
private class GatedWindow(private val inner: ApprovalWindow, val gate: CountDownLatch) : ApprovalWindow {
    val asked = CountDownLatch(1)
    override fun revalidate(candidateJson: String): String = inner.revalidate(candidateJson)
    override fun approve(intent: JsonNode, approverKind: String?): String {
        asked.countDown()
        check(gate.await(30, TimeUnit.SECONDS)) { "test: the gate was never opened" }
        return inner.approve(intent, approverKind)
    }
}

/**
 * Plan C1 end to end: the watcher over a picasso-shaped export and the Mock host's carry, sending through
 * [TemporalEpisodeSignals] into the real activities (test Postgres, Mock narrator, Mock picasso).
 */
class WatcherEndToEndTest {
    private var env: TestWorkflowEnvironment? = null
    private lateinit var client: WorkflowClient
    private lateinit var store: EpisodeStore
    private lateinit var watch: WatchStore
    private lateinit var export: ExportDir
    private lateinit var carrier: MockCarrier
    @TempDir lateinit var root: Path
    private val v1: Path = Path.of(System.getProperty("koshei.repoRoot"), "model", "episode-policy", "active.yaml")

    /** Where v1's merge rule puts both PATROL-1 lines of robot hum-02 (`Picasso.search`, `Picasso.incident`). */
    private val merged = "ep:run-A:by:robotId:hum-02:jobOrderId:PATROL-1"
    private val mapper = ObjectMapper()

    /** The real activities over the test Postgres, the Mock narrator and [picasso], [policy] as the policy file. */
    private fun start(policy: Path, picasso: ApprovalWindow = MockPicasso()): TestWorkflowEnvironment {
        val e = episodeEnvironment {
            EpisodeWorkers.register(workerFactory, EpisodeRuntimeConfig(policy, NarratorMode.MOCK, PicassoMode.MOCK), store, picasso)
        }
        e.start()
        env = e
        client = e.workflowClient
        return e
    }

    @BeforeEach fun up() {
        store = EpisodeDb.reset()
        watch = WatchStore(EpisodeDb::connection)
        export = ExportDir(Files.createDirectories(root.resolve("export")))
        carrier = MockCarrier(root.resolve("carry"))
    }

    @AfterEach fun down() { env?.close() }

    private fun watcher(policy: Path) =
        EpisodeWatcher(listOf(export.dir), carrier.dir, PolicyFileReader(policy), watch, TemporalEpisodeSignals(client))

    private fun episode(workflowId: String): EpisodeWorkflow = client.newWorkflowStub(EpisodeWorkflow::class.java, workflowId)

    /** The instance's records once gap-free from seq 1 and [done] holds on them. */
    private fun stored(instanceId: String, done: (List<StoredEvent>) -> Boolean): List<StoredEvent> =
        eventually(describe = { "events ${store.events(instanceId).map { it.kind }}" }) {
            store.events(instanceId).takeIf { r -> r.map { it.seq } == (1L..r.size.toLong()).toList() && done(r) }
        }

    private fun StoredEvent.json(): JsonNode = mapper.readTree(payloadJson)

    /** The instance's EVIDENCE records as (jobResponseId, why, counted), in order. */
    private fun List<StoredEvent>.evidence(): List<Triple<String, String, Boolean>> = filter { it.kind == "EVIDENCE" }
        .map { it.json() }.map { Triple(it.path("jobResponseId").asText(), it.path("why").asText(), it.path("counted").asBoolean()) }

    private fun logs(kind: String) = watch.logs(1000).filter { it.kind == kind }

    /** search-1 carried in by [w], approved by a person: the episode [merged] waits for evidence. */
    private fun approvedEpisode(e: TestWorkflowEnvironment, w: EpisodeWatcher): EpisodeWorkflow {
        export.searches += Picasso.search("search-1")
        export.snapshot()
        assertEquals(1, w.pollOnce().opened)
        val ep = episode(merged)
        val waiting = ep.until(e) { it.phase == "AWAITING_APPROVAL" }   // auto-approval is off in v1
        assertEquals("ACCEPTED", ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1")))
        ep.until(e) { it.phase == "AWAITING_EVIDENCE" }
        return ep
    }

    @Test fun `v1's merge rule carries an incident and a search into one episode, which reaches a person`() {
        val e = start(v1)
        val w = watcher(v1)
        export.incidents += Picasso.incident("incident-1")
        export.searches += Picasso.search("search-1")
        export.snapshot()
        assertEquals(2, w.pollOnce().opened)
        val ep = episode(merged)
        eventually(describe = { "view ${ep.view()}" }) { ep.view().takeIf { it.symptoms == 2 } }   // wall clock: test time stays put
        ep.until(e) { it.phase == "AWAITING_APPROVAL" }
        assertEquals(0, w.pollOnce().opened)
        assertEquals(emptyList(), logs("SIGNAL_FAILED"))
    }

    @Disabled("plan C2 Task 4: the carry has no instanceId until ResultExport")
    @Test fun `a doubtful report makes the outcome unknown - a repeat, another execution and an unknown order are only recorded`() {
        val e = start(v1)
        val w = watcher(v1)
        val ep = approvedEpisode(e, w)
        val instanceId = ep.view().instanceId
        carrier.report("resp-1", "PATROL-1", "mock-exec-1", inDoubtUnits = listOf("u-1"))
            .report("resp-1", "PATROL-1", "mock-exec-1", inDoubtUnits = listOf("u-1"))
            .report("resp-2", "PATROL-1", "exec-other")
            .report("resp-3", "NOPE", "exec-3")
            .snapshot()
        assertEquals(4, w.pollOnce().evidence)
        ep.until(e) { it.phase == "UNKNOWN_OUTCOME" }
        val events = stored(instanceId) { r -> r.count { it.kind == "EVIDENCE" } >= 3 }
        val result = events.single { it.kind == "DISPATCH_RESULT" }.json()
        assertTrue("mock-exec-1" in result.path("answer").asText(), "the B3b execution id: $result")
        assertEquals(
            listOf(
                Triple("resp-1", "in doubt", true),
                Triple("resp-1", "duplicate jobResponseId", false),
                Triple("resp-2", "not linked to this attempt's execution", false),
            ),
            events.evidence(),
        )
        val transition = events.single { it.kind == "TRANSITION" && it.json().path("to").asText() == "UNKNOWN_OUTCOME" }.json()
        assertEquals("AWAITING_EVIDENCE", transition.path("from").asText(), "$transition")
        assertEquals("JobResponse resp-1 in doubt", transition.path("detail").asText(), "the doubtful report is the cause: $transition")
        val unrouted = logs("UNROUTED")
        assertEquals(1, unrouted.size, "$unrouted")
        assertTrue("resp-3" in unrouted.single().detailJson && "NOPE" in unrouted.single().detailJson, unrouted.single().detailJson)
        assertEquals(emptyList(), logs("ROUTE_ENDED"))
        assertEquals(0, w.pollOnce().evidence, "nothing is carried twice")
    }

    @Disabled("plan C2 Task 4: the carry has no instanceId until ResultExport")
    @Test fun `a report before picasso's answer is kept and counted once the answer comes`() {
        val window = GatedWindow(MockPicasso(), CountDownLatch(1))
        val e = start(v1, window)
        val w = watcher(v1)
        export.searches += Picasso.search("search-1")
        export.snapshot()
        w.pollOnce()
        val ep = episode(merged)
        val waiting = ep.until(e) { it.phase == "AWAITING_APPROVAL" }
        val instanceId = waiting.instanceId
        try {
            ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1"))
            // While the gate is closed, wall clock only: advancing test time past dispatchMs would end the dispatch as
            // Uncertain and reach UNKNOWN_OUTCOME for the wrong reason.
            // Each wait is 8 s at most, so all of them end well inside the gate's 30 s.
            assertTrue(window.asked.await(8, TimeUnit.SECONDS), "picasso was asked")
            eventually(seconds = 8, describe = { "view ${ep.view()}" }) { ep.view().takeIf { it.phase == "DISPATCHED" } }
            carrier.report("resp-1", "PATROL-1", "mock-exec-1", inDoubtUnits = listOf("u-1")).snapshot()
            assertEquals(1, w.pollOnce().evidence)
            assertEquals(emptyList(), logs("UNROUTED"), "the intent is stored before picasso is asked, so the order finds it (§12)")
            eventually(seconds = 8, describe = { "events ${store.events(instanceId).map { it.kind }}" }) {
                store.events(instanceId).takeIf { r -> r.evidence().any { it == Triple("resp-1", "kept until the answer", false) } }
            }
            assertEquals("DISPATCHED", ep.view().phase)
        } finally {
            window.gate.countDown()
        }
        ep.until(e) { it.phase == "UNKNOWN_OUTCOME" }
        val events = stored(instanceId) { r -> r.evidence().any { it.second == "in doubt" } && r.any { it.kind == "DISPATCH_RESULT" } }
        val result = events.single { it.kind == "DISPATCH_RESULT" }.json()
        assertEquals("ACCEPTED", result.path("judgement").asText(), "the answer came, not a timeout: $result")
        assertTrue("mock-exec-1" in result.path("answer").asText(), "$result")
        val kept = events.indexOfFirst { it.kind == "EVIDENCE" && it.json().path("why").asText() == "kept until the answer" }
        val counted = events.indexOfFirst { it.kind == "EVIDENCE" && it.json().path("why").asText() == "in doubt" }
        assertTrue(kept in 0 until counted, "kept, then counted: ${events.map { it.kind }}")
        val doubt = events[counted].json()
        assertEquals("resp-1", doubt.path("jobResponseId").asText())
        assertTrue(doubt.path("counted").asBoolean(), "$doubt")
    }

    @Test fun `a report for an ended episode goes nowhere, and never to the next episode on the same key`() {
        val e = start(v1)
        val w = watcher(v1)
        val ep = approvedEpisode(e, w)
        val first = ep.view().instanceId
        ep.takeover(TakeoverRequest("op-1"))
        ep.close(CloseRequest("op-1", "checked on site"))
        assertEquals("CLOSED", ep.result().phase)
        carrier.report("resp-9", "PATROL-1", "mock-exec-1").snapshot()
        assertEquals(1, w.pollOnce().evidence)
        val ended = logs("ROUTE_ENDED")
        assertEquals(1, ended.size, "$ended")
        assertTrue("resp-9" in ended.single().detailJson && first in ended.single().detailJson, ended.single().detailJson)

        // The next episode on the same workflow id, before any decision: policy v1 gives it no intent yet.
        val nextRun = TemporalEpisodeSignals(client).open(merged, EpisodeStart(Picasso.manifest), searchSignal("search-2"))
        assertTrue(nextRun != first.substringAfterLast('/'), "a new run")
        episode(merged).until(e) { it.instanceId != first && it.phase != "OPENING" }
        carrier.report("resp-10", "PATROL-1", "mock-exec-1").snapshot()
        assertEquals(1, w.pollOnce().evidence)
        val endedAgain = logs("ROUTE_ENDED")
        assertEquals(2, endedAgain.size, "$endedAgain")
        assertTrue("resp-10" in endedAgain.first().detailJson && first in endedAgain.first().detailJson, endedAgain.first().detailJson)
        // The run's history does not lag as the records may: no evidence signal ever reached the successor's run.
        val signaled = client.fetchHistory(merged, nextRun).events
            .filter { it.hasWorkflowExecutionSignaledEventAttributes() }.map { it.workflowExecutionSignaledEventAttributes.signalName }
        assertTrue("symptom" in signaled, "the history is the successor's: $signaled")
        assertTrue("evidence" !in signaled, "the earlier instance's reports stay out: $signaled")
        assertEquals(emptyList(), logs("UNROUTED"))
    }
}
