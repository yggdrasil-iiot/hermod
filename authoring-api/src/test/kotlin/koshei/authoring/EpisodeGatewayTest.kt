package koshei.authoring

import com.fasterxml.jackson.databind.ObjectMapper
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import koshei.episode.runtime.CloseRequest
import koshei.episode.runtime.DecideRequest
import koshei.episode.runtime.DispatchOutcome
import koshei.episode.runtime.EPISODE_TASK_QUEUE
import koshei.episode.runtime.EpisodeActivities
import koshei.episode.runtime.EpisodeStart
import koshei.episode.runtime.EpisodeView
import koshei.episode.runtime.EpisodeWorkflow
import koshei.episode.runtime.EpisodeWorkflowImpl
import koshei.episode.runtime.MockNarratorActivities
import koshei.episode.runtime.MockPicasso
import koshei.episode.runtime.NARRATOR_TASK_QUEUE
import koshei.episode.runtime.PolicyFileReader
import koshei.episode.runtime.PolicyReadResult
import koshei.episode.runtime.SymptomSignal
import koshei.episode.runtime.TakeoverRequest
import koshei.runtime.DataConverterSupport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** koshei-side activities for the gateway test: the committed v1 policy (auto-approval off), Mock picasso, no records. */
private class TestActivities : EpisodeActivities {
    private val policy = PolicyFileReader(Path.of(System.getProperty("koshei.repoRoot"), "model", "episode-policy", "active.yaml"))
    private val picasso = MockPicasso()
    private val json = ObjectMapper()
    override fun readPolicy(): PolicyReadResult = policy.read()
    override fun revalidate(candidateJson: String): String = picasso.revalidate(candidateJson)
    override fun record(instanceId: String, seq: Long, kind: String, payloadJson: String) {}
    override fun recordIntent(instanceId: String, seq: Long, intentJson: String) {}
    override fun dispatch(instanceId: String, intentJson: String, approverKind: String?): DispatchOutcome =
        DispatchOutcome("ANSWER", picasso.approve(json.readTree(intentJson), approverKind))
    override fun sendNotice(instanceId: String, noticeJson: String) {}
}

class EpisodeGatewayTest {
    private lateinit var env: TestWorkflowEnvironment
    private lateinit var gateway: EpisodeGateway
    private val json = ObjectMapper()

    @BeforeEach fun up() {
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(DataConverterSupport.clientOptions()).build(),
        )
        env.newWorker(EPISODE_TASK_QUEUE).apply {
            registerWorkflowImplementationTypes(EpisodeWorkflowImpl::class.java)
            registerActivitiesImplementations(TestActivities())
        }
        env.newWorker(NARRATOR_TASK_QUEUE).registerActivitiesImplementations(MockNarratorActivities())
        env.start()
        gateway = EpisodeGateway(lazyOf(env.workflowClient))
    }

    @AfterEach fun down() = env.close()

    private fun resource(name: String) = checkNotNull(javaClass.getResource("/picasso/run-1/$name")).readText()

    private fun searchLine(id: String) =
        resource("remedy-searches.jsonl").lines().first { it.isNotBlank() && json.readTree(it)["searchId"].textValue() == id }

    /** signalWithStart, as the watcher (plan C) and the dev CLI do. */
    private fun open(workflowId: String) {
        val options = WorkflowOptions.newBuilder().setTaskQueue(EPISODE_TASK_QUEUE).setWorkflowId(workflowId)
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE).build()
        val stub = env.workflowClient.newWorkflowStub(EpisodeWorkflow::class.java, options)
        WorkflowStub.fromTyped(stub).signalWithStart(
            "symptom", arrayOf(SymptomSignal("search:search-1", "SEARCH", searchLine("search-1"), 0)), arrayOf(EpisodeStart(resource("manifest.json"))),
        )
    }

    private fun until(workflowId: String, cond: (EpisodeView) -> Boolean): EpisodeView {
        repeat(120) {
            val v = gateway.current(workflowId).view
            if (v != null && cond(v)) return v
            env.sleep(Duration.ofSeconds(1))
        }
        fail("not reached: ${gateway.current(workflowId).view}")
    }

    @Test fun `the current run's view names its instance`() {
        open("ep:g1")
        val v = until("ep:g1") { it.phase == "AWAITING_APPROVAL" }
        assertTrue(v.instanceId.startsWith("ep:g1/"))
        assertTrue(gateway.current("ep:g1").running)
        assertEquals(v.instanceId, gateway.view(v.instanceId)!!.instanceId)
    }

    @Test fun `an unknown workflow is not found`() {
        assertThrows<EpisodeNotFound> { gateway.current("ep:nobody") }
    }

    @Test fun `a decision reaches the episode and its reply comes back as a value`() {
        open("ep:g2")
        val v = until("ep:g2") { it.phase == "AWAITING_APPROVAL" }
        assertEquals("REFUSED_STALE", gateway.decide(v.instanceId, DecideRequest("${v.instanceId}#0", v.candidatesVersion!!, true, "op-1")))
        assertEquals("ACCEPTED", gateway.decide(v.instanceId, DecideRequest(v.proposalId!!, v.candidatesVersion!!, true, "op-1")))
        until("ep:g2") { it.phase == "AWAITING_EVIDENCE" }
    }

    @Test fun `a malformed decision is refused by the validator, before the episode`() {
        open("ep:g3")
        val v = until("ep:g3") { it.phase == "AWAITING_APPROVAL" }
        val e = assertThrows<InvalidEpisodeRequest> { gateway.decide(v.instanceId, DecideRequest(v.proposalId!!, "", true, "op-1")) }
        assertTrue("sawCandidatesVersion" in e.message!! || "proposalId" in e.message!!, e.message)
        assertEquals("AWAITING_APPROVAL", gateway.current("ep:g3").view!!.phase)
    }

    @Test fun `a decision for an instance that is not the current run's is refused - never sent to another episode`() {
        open("ep:g4")
        val v = until("ep:g4") { it.phase == "AWAITING_APPROVAL" }
        val moved = assertThrows<EpisodeMoved> { gateway.takeover("ep:g4/someone-else", TakeoverRequest("op-1")) }
        assertEquals(v.instanceId, moved.currentInstanceId)
        assertEquals("AWAITING_APPROVAL", gateway.current("ep:g4").view!!.phase)
    }

    @Test fun `an ended run has no live view, and a decision on it is refused as ended`() {
        open("ep:g5")
        val v = until("ep:g5") { it.phase == "AWAITING_APPROVAL" }
        assertEquals("ACCEPTED", gateway.takeover(v.instanceId, TakeoverRequest("op-1")))
        assertEquals("ACCEPTED", gateway.close(v.instanceId, CloseRequest("op-1", "handled on site")))
        repeat(30) {
            if (!gateway.current("ep:g5").running) {
                assertNull(gateway.current("ep:g5").view)
                assertNull(gateway.view(v.instanceId))
                assertThrows<EpisodeEnded> { gateway.takeover(v.instanceId, TakeoverRequest("op-1")) }
                return
            }
            env.sleep(Duration.ofSeconds(1))
        }
        fail("the run did not end")
    }

    @Test fun `a past instance has no live view`() {
        open("ep:g6")
        val v = until("ep:g6") { it.phase == "AWAITING_APPROVAL" }
        assertNull(gateway.view("ep:g6/an-earlier-run"))
        assertEquals(v.instanceId, gateway.current("ep:g6").view!!.instanceId)
    }
}
