package koshei.episode.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import io.temporal.client.WorkflowClient
import io.temporal.testing.TestWorkflowEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpisodeEndToEndTest {
    private var env: TestWorkflowEnvironment? = null
    private lateinit var client: WorkflowClient
    private lateinit var store: EpisodeStore
    @TempDir lateinit var dir: Path
    private val v1: Path = Path.of(System.getProperty("koshei.repoRoot"), "model", "episode-policy", "active.yaml")

    /** The real activities over the test Postgres, the Mock narrator and Mock picasso, [policy] as the policy file. */
    private fun start(policy: Path, store: EpisodeStore = this.store): TestWorkflowEnvironment {
        val e = episodeEnvironment {
            EpisodeWorkers.register(workerFactory, EpisodeRuntimeConfig(policy, NarratorMode.MOCK, PicassoMode.MOCK), store)
        }
        e.start()
        env = e
        client = e.workflowClient
        return e
    }

    @BeforeEach fun up() { store = EpisodeDb.reset() }
    @AfterEach fun down() { env?.close() }

    /** The instance's records once gap-free from seq 1 and [done] holds on them. */
    private fun stored(instanceId: String, done: (List<StoredEvent>) -> Boolean): List<StoredEvent> =
        eventually(describe = { "events ${store.events(instanceId).map { it.kind }}" }) {
            store.events(instanceId).takeIf { r -> r.map { it.seq } == (1L..r.size.toLong()).toList() && done(r) }
        }

    @Test fun `policy v1 - a person approves, the remedy goes to Mock picasso, everything is in episode_event`() {
        val e = start(v1)
        val ep = client.openEpisode()
        val waiting = ep.until(e) { it.phase == "AWAITING_APPROVAL" }   // auto-approval is off in v1
        assertEquals("ACCEPTED", ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1")))
        ep.until(e) { it.phase == "AWAITING_EVIDENCE" }
        val events = stored(waiting.instanceId) { r -> r.any { it.kind == "DISPATCH_RESULT" } }
        assertEquals("OPENED", events.first().kind)
        val intent = events.indexOfFirst { it.kind == EpisodeActivitiesImpl.INTENT_KIND }
        val result = events.indexOfFirst { it.kind == "DISPATCH_RESULT" }
        assertTrue(intent in 0 until result, "the intent is stored before the result: ${events.map { it.kind }}")
        assertTrue("mock-exec-1" in events[result].payloadJson, events[result].payloadJson)
        eventually { store.notices(waiting.instanceId).takeIf { n -> n.any { "APPROVAL_NEEDED" in it } } }   // the person was told
    }

    @Test fun `R2 - an answer lost before it was stored is an unknown outcome, never a second remedy`() {
        val forgetful = object : EpisodeStore(EpisodeDb::connection) {
            @Volatile var lose = true
            override fun saveOutcome(instanceId: String, key: String, outcome: DispatchOutcome): DispatchOutcome {
                if (lose) { lose = false; throw IllegalStateException("worker died before saving the answer") }
                return super.saveOutcome(instanceId, key, outcome)
            }
        }
        val e = start(v1, forgetful)
        val ep = client.openEpisode()
        val waiting = ep.until(e) { it.phase == "AWAITING_APPROVAL" }
        ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1"))
        ep.until(e) { it.phase == "UNKNOWN_OUTCOME" }
        val events = stored(waiting.instanceId) { r -> r.any { it.kind == "DISPATCH_RESULT" } }
        assertTrue(events.any { "REMEDY_NOT_APPLIED" in it.payloadJson }, "picasso's refusal is kept: ${events.map { it.kind }}")
    }

    @Test fun `a conflicting record fails once without retry, raises RECORD_LAG, and the episode goes on`() {
        val attempts = AtomicInteger()
        val conflicted = object : EpisodeStore(EpisodeDb::connection) {
            override fun append(instanceId: String, seq: Long, kind: String, payloadJson: String): Boolean {
                if (kind == "PROPOSED") { attempts.incrementAndGet(); throw RecordConflict("test: seq $seq is taken") }
                return super.append(instanceId, seq, kind, payloadJson)
            }
        }
        val e = start(v1, conflicted)
        val ep = client.openEpisode()
        val waiting = ep.until(e) { it.phase == "AWAITING_APPROVAL" }
        eventually { store.notices(waiting.instanceId).takeIf { n -> n.any { "RECORD_LAG" in it } } }
        Thread.sleep(2_000)   // a retry, were there one, would have come by now
        assertEquals(1, attempts.get(), "RecordConflict is not retried")
        assertEquals("AWAITING_APPROVAL", ep.view().phase)
    }

    @Test fun `switching the agent layer off in the file reaches a new episode and the next decision of an open one`() {
        val policy = dir.resolve("active.yaml")
        Files.copy(v1, policy)
        val e = start(policy)
        val a = client.openEpisode(id = "ep:a")
        val waiting = a.until(e) { it.phase == "AWAITING_APPROVAL" }
        Files.writeString(policy, Files.readString(v1).replace("agentLayerEnabled: true", "agentLayerEnabled: false"))
        val b = client.openEpisode(id = "ep:b", symptom = searchSignal("search-2"))
        assertEquals("AGENT_LAYER_OFF", b.until(e) { it.phase == "ESCALATED" }.escalationReason)
        eventually { store.notices(b.view().instanceId).takeIf { n -> n.any { "AGENT_LAYER_OFF" in it } } }
        // The person's approval is judged on the table in force (an Update reads no policy: EpisodeWorkflowImpl.update); the next event of
        // the main loop — the revalidation's result — reads the file again and finds the agent layer off.
        assertEquals("ACCEPTED", a.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1")))
        assertEquals("AGENT_LAYER_OFF", a.until(e) { it.phase == "ESCALATED" }.escalationReason)
        // An approval given after the switch never reaches picasso.
        val events = stored(waiting.instanceId) { r -> r.any { it.kind == "TRANSITION" && "AGENT_LAYER_OFF" in it.payloadJson } }
        val dispatched = events.filter { it.kind == EpisodeActivitiesImpl.INTENT_KIND || it.kind == "DISPATCH_RESULT" }
        assertTrue(dispatched.isEmpty(), "nothing is dispatched after the switch: ${events.map { it.kind }}")
    }

    @Test fun `a repeated switch in the file is unreadable - with no valid table yet, the episode is POLICY_MISSING`() {
        val bad = dir.resolve("active.yaml")
        Files.writeString(bad, Files.readString(v1).replace("agentLayerEnabled: true", "agentLayerEnabled: false\nagentLayerEnabled: true"))
        val e = start(bad)
        val ep = client.openEpisode()
        // Unreadable has no tree, so the literal-switch rule cannot apply, and with no last valid table nothing is in
        // force (PolicyResolution.kt:62-65, 87). A reader that kept the first value would give a valid table with
        // `false` instead: AGENT_LAYER_OFF. So this reason tells the two apart.
        assertEquals("POLICY_MISSING", ep.until(e) { it.phase == "ESCALATED" }.escalationReason)
    }
}
