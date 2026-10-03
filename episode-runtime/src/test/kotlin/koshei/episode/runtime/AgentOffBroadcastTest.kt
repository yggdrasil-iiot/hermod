package koshei.episode.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A workflow of another type, open until cancelled: the broadcast must not list it, whatever its id. */
@WorkflowInterface
internal interface NotAnEpisode {
    @WorkflowMethod fun run()
}

internal class NotAnEpisodeImpl : NotAnEpisode {
    override fun run() = Workflow.await { false }
}

class AgentOffBroadcastTest {
    private val acts = FakeEpisodeActivities().apply { policyJson = TestPolicies.table(autoApprove = false) }
    private val json = ObjectMapper()
    private lateinit var env: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient

    @BeforeEach fun up() {
        env = episodeEnvironment {
            fakeWorkers(acts, ProgrammableNarrator())
            newWorker(OTHER_TASK_QUEUE).registerWorkflowImplementationTypes(NotAnEpisodeImpl::class.java)
        }
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

    /** Waits until [id] is ESCALATED and checks that the agent switch is why. */
    private fun switchedOff(id: String) {
        val view = stub(id).until(env) { it.phase == "ESCALATED" }
        assertEquals("AGENT_LAYER_OFF", view.escalationReason, id)
    }

    private fun records(instanceId: String, kind: String): List<Rec> =
        synchronized(acts.records) { acts.records.filter { it.instanceId == instanceId && it.kind == kind } }

    /** TRANSITION records of [instanceId] into ESCALATED. */
    private fun escalations(instanceId: String): List<Rec> =
        records(instanceId, "TRANSITION").filter { json.readTree(it.payload)["to"]?.textValue() == "ESCALATED" }

    /** ESCALATED notices for the agent switch (notices carry no instance; only an agent-off escalation has this reason). */
    private fun offNotices(): List<String> = synchronized(acts.notices) {
        acts.notices.filter { json.readTree(it).let { n -> n["kind"]?.textValue() == "ESCALATED" && n["reason"]?.textValue() == "AGENT_LAYER_OFF" } }
    }

    @Test fun `every open episode is switched off - one broadcast reaches them all`() {
        waiting("ep:b-1", "search-1")
        waiting("ep:b-2", "search-1")
        val report = AgentOffBroadcast(client).send()
        assertEquals(setOf("ep:b-1", "ep:b-2"), report.sent.toSet())
        assertEquals(emptyList(), report.failed)
        for (id in listOf("ep:b-1", "ep:b-2")) switchedOff(id)
    }

    @Test fun `an ended episode is not sent to - and a second broadcast changes nothing`() {
        waiting("ep:b-3", "search-1")
        stub("ep:b-3").takeover(TakeoverRequest("op-1"))
        stub("ep:b-3").close(CloseRequest("op-1", "done"))
        stub("ep:b-3").result()
        val instance = waiting("ep:b-4", "search-1")
        assertEquals(listOf("ep:b-4"), AgentOffBroadcast(client).send().sent)
        switchedOff("ep:b-4")
        eventually(describe = { "records ${acts.records} notices ${acts.notices}" }) {
            Unit.takeIf { escalations(instance).size == 1 && offNotices().size == 1 }
        }

        val again = AgentOffBroadcast(client).send()
        assertEquals(AgentOffBroadcast.Report(listOf("ep:b-4"), emptyList(), emptyList()), again, "ESCALATED is still open; it only records the second agentOff")
        val ignored = eventually(describe = { "records ${records(instance, "IGNORED")}" }) {
            records(instance, "IGNORED").singleOrNull { json.readTree(it.payload)["event"]?.textValue() == "AgentOff" }
        }
        assertEquals("ESCALATED", json.readTree(ignored.payload)["phase"].textValue(), ignored.payload)
        env.sleep(Duration.ofSeconds(1))
        switchedOff("ep:b-4")
        assertEquals(1, escalations(instance).size, "no second transition: ${acts.records}")
        assertEquals(1, offNotices().size, "no second notice: ${acts.notices}")
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

    @Test fun `a workflow of another type is not listed - even under an episode-like id`() {
        waiting("ep:b-6", "search-1")
        val other = client.newWorkflowStub(
            NotAnEpisode::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(OTHER_TASK_QUEUE).setWorkflowId("ep:not-an-episode").build(),
        )
        WorkflowClient.start(other::run)
        val ids = AgentOffBroadcast.openEpisodeIds(client)
        assertTrue("ep:b-6" in ids, "$ids")
        assertFalse("ep:not-an-episode" in ids, "$ids")
    }

    private companion object {
        const val OTHER_TASK_QUEUE = "not-an-episode-tq"
    }
}
