package koshei.episode.runtime

import io.temporal.client.WorkflowClient
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentOffBroadcastTest {
    private val acts = FakeEpisodeActivities().apply { policyJson = TestPolicies.table(autoApprove = false) }
    private lateinit var env: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient

    @BeforeEach fun up() {
        env = episodeEnvironment { fakeWorkers(acts, ProgrammableNarrator()) }
        env.start()
        client = env.workflowClient
    }

    @AfterEach fun down() = env.close()

    private fun stub(id: String) = client.newWorkflowStub(EpisodeWorkflow::class.java, id)

    /** Opens [id] and waits at AWAITING_APPROVAL. Only `search-1` of run-1 is FOUND; the others end in NO_GROUNDS. */
    private fun waiting(id: String, search: String): String {
        TemporalEpisodeSignals(client).open(id, EpisodeStart(Picasso.manifest), searchSignal(search))
        return stub(id).until(env) { it.phase == "AWAITING_APPROVAL" }.instanceId
    }

    @Test fun `every open episode is switched off - one broadcast reaches them all`() {
        waiting("ep:b-1", "search-1")
        waiting("ep:b-2", "search-1")
        val report = AgentOffBroadcast(client).send()
        assertEquals(setOf("ep:b-1", "ep:b-2"), report.sent.toSet())
        assertEquals(emptyList(), report.failed)
        for (id in listOf("ep:b-1", "ep:b-2")) assertEquals("ESCALATED", stub(id).until(env) { it.phase == "ESCALATED" }.phase, id)
    }

    @Test fun `an ended episode is not sent to - and a second broadcast changes nothing`() {
        waiting("ep:b-3", "search-1")
        stub("ep:b-3").takeover(TakeoverRequest("op-1"))
        stub("ep:b-3").close(CloseRequest("op-1", "done"))
        stub("ep:b-3").result()
        waiting("ep:b-4", "search-1")
        assertEquals(listOf("ep:b-4"), AgentOffBroadcast(client).send().sent)
        stub("ep:b-4").until(env) { it.phase == "ESCALATED" }
        val again = AgentOffBroadcast(client).send()
        assertEquals(AgentOffBroadcast.Report(listOf("ep:b-4"), emptyList(), emptyList()), again, "ESCALATED is still open; it only records the second agentOff")
        assertEquals("ESCALATED", stub("ep:b-4").view().phase)
    }

    @Test fun `a run that ended between the listing and the signal is counted as ended`() {
        waiting("ep:b-5", "search-1")
        stub("ep:b-5").takeover(TakeoverRequest("op-1"))
        stub("ep:b-5").close(CloseRequest("op-1", "done"))
        stub("ep:b-5").result()
        val report = AgentOffBroadcast(client) { listOf("ep:b-5") }.send()   // a listing taken before it ended
        assertEquals(AgentOffBroadcast.Report(emptyList(), listOf("ep:b-5"), emptyList()), report)
    }

    @Test fun `no open episode - nothing is sent`() {
        val report = AgentOffBroadcast(client).send()
        assertEquals(AgentOffBroadcast.Report(emptyList(), emptyList(), emptyList()), report)
    }
}
