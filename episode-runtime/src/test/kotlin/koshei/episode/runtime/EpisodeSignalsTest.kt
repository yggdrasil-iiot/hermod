package koshei.episode.runtime

import io.temporal.client.WorkflowClient
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EpisodeSignalsTest {
    private val acts = FakeEpisodeActivities()
    private lateinit var env: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient
    private lateinit var signals: TemporalEpisodeSignals

    @BeforeEach fun up() {
        env = episodeEnvironment { fakeWorkers(acts, ProgrammableNarrator()) }
        env.start()
        client = env.workflowClient
        signals = TemporalEpisodeSignals(client)
    }

    @AfterEach fun down() = env.close()

    private fun stub(id: String) = client.newWorkflowStub(EpisodeWorkflow::class.java, id)

    /** Opens [id] and waits until the episode exists (`view()` says OPENING before the state is built). */
    private fun opened(id: String, search: String = "search-1"): String {
        signals.open(id, EpisodeStart(Picasso.manifest), searchSignal(search))
        return stub(id).until(env) { it.phase != "OPENING" }.instanceId
    }

    @Test fun `open starts the episode once and joins the second symptom`() {
        signals.open("ep:sig", EpisodeStart(Picasso.manifest), searchSignal("search-1"))
        signals.open("ep:sig", EpisodeStart(Picasso.manifest), searchSignal("search-2"))
        assertEquals(2, stub("ep:sig").until(env) { it.symptoms == 2 }.symptoms)
    }

    @Test fun `evidence reaches the instance and says false for none`() {
        val instance = opened("ep:ev")
        assertTrue(signals.evidence(instance, EvidenceSignal(jobResponseId = "resp-1")))
        assertFalse(signals.evidence("ep:never/r0", EvidenceSignal(jobResponseId = "resp-1")))
    }

    @Test fun `an ended instance says false, and its successor on the same key does not receive what was meant for it`() {
        val first = opened("ep:done")
        assertEquals("ACCEPTED", stub("ep:done").takeover(TakeoverRequest("op-1")))
        assertEquals("ACCEPTED", stub("ep:done").close(CloseRequest("op-1", "replay")))
        stub("ep:done").result()
        assertFalse(signals.evidence(first, EvidenceSignal(jobResponseId = "resp-1")))
        val second = opened("ep:done", "search-2")
        assertNotEquals(first, second)
        assertFalse(signals.evidence(first, EvidenceSignal(jobResponseId = "resp-1")))
        assertTrue(signals.evidence(second, EvidenceSignal(jobResponseId = "resp-2")))
    }
}
