package koshei.authoring

import com.fasterxml.jackson.databind.ObjectMapper
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.client.WorkflowUpdateException
import io.temporal.common.interceptors.WorkerInterceptorBase
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.WorkerFactoryOptions
import io.temporal.workflow.Workflow
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
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
    /** When set, `dispatch` waits on it: the run stays DISPATCHED and, once ended, drains (design §7.1). */
    @Volatile var dispatchGate: CountDownLatch? = null
    override fun readPolicy(): PolicyReadResult = policy.read()
    override fun revalidate(candidateJson: String): String = picasso.revalidate(candidateJson)
    override fun record(instanceId: String, seq: Long, kind: String, payloadJson: String) {}
    override fun recordIntent(instanceId: String, seq: Long, intentJson: String) {}
    override fun dispatch(instanceId: String, intentJson: String, approverKind: String?): DispatchOutcome {
        dispatchGate?.await(60, TimeUnit.SECONDS)
        return DispatchOutcome("ANSWER", picasso.approve(json.readTree(intentJson), approverKind))
    }
    override fun sendNotice(instanceId: String, noticeJson: String) {}
}

/** Workflow ids whose Updates the worker interceptor below slows down or refuses, as a worker could. */
private const val SLOW = "ep:slow"
private const val ODD = "ep:odd"

/**
 * On [SLOW], an Update's handler waits 6 s of workflow time before it runs (time is not skipped while a client waits on an
 * Update). On [ODD], the validator throws something other than the form checks' IllegalArgumentException.
 */
private class TroubleInterceptor : WorkerInterceptorBase() {
    override fun interceptWorkflow(next: WorkflowInboundCallsInterceptor): WorkflowInboundCallsInterceptor =
        object : WorkflowInboundCallsInterceptorBase(next) {
            override fun validateUpdate(input: WorkflowInboundCallsInterceptor.UpdateInput) {
                if (Workflow.getInfo().workflowId == ODD) throw IllegalStateException("not a form check")
                super.validateUpdate(input)
            }

            override fun executeUpdate(input: WorkflowInboundCallsInterceptor.UpdateInput): WorkflowInboundCallsInterceptor.UpdateOutput {
                if (Workflow.getInfo().workflowId == SLOW) Workflow.sleep(Duration.ofSeconds(6))
                return super.executeUpdate(input)
            }
        }
}

class EpisodeGatewayTest {
    private lateinit var env: TestWorkflowEnvironment
    private lateinit var gateway: EpisodeGateway
    private val acts = TestActivities()
    private val json = ObjectMapper()

    @BeforeEach fun up() {
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(DataConverterSupport.clientOptions())
                .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder().setWorkerInterceptors(TroubleInterceptor()).build())
                .build(),
        )
        env.newWorker(EPISODE_TASK_QUEUE).apply {
            registerWorkflowImplementationTypes(EpisodeWorkflowImpl::class.java)
            registerActivitiesImplementations(acts)
        }
        env.newWorker(NARRATOR_TASK_QUEUE).registerActivitiesImplementations(MockNarratorActivities())
        env.start()
        gateway = EpisodeGateway(lazyOf(env.workflowClient))
    }

    @AfterEach fun down() = env.close()

    private fun resource(name: String) = checkNotNull(javaClass.getResource("/picasso/run-1/$name")).readText()

    private fun searchLine(id: String) =
        resource("remedy-searches.jsonl").lines().first { it.isNotBlank() && json.readTree(it)["searchId"].textValue() == id }

    private fun search1(eventId: String = "search:search-1") = SymptomSignal(eventId, "SEARCH", searchLine("search-1"), 0)

    /** signalWithStart, as the watcher (plan C) and the dev CLI do. */
    private fun open(workflowId: String) {
        val options = WorkflowOptions.newBuilder().setTaskQueue(EPISODE_TASK_QUEUE).setWorkflowId(workflowId)
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE).build()
        val stub = env.workflowClient.newWorkflowStub(EpisodeWorkflow::class.java, options)
        WorkflowStub.fromTyped(stub).signalWithStart("symptom", arrayOf(search1()), arrayOf(EpisodeStart(resource("manifest.json"))))
    }

    private fun ended(workflowId: String) {
        repeat(30) {
            if (!gateway.current(workflowId).running) return
            env.sleep(Duration.ofSeconds(1))
        }
        fail("the run did not end")
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

    // --- B3c chunk 2 review -----------------------------------------------------------------------------------------

    @Test fun `an Update past its bound is a timeout whose outcome is unknown - the decision may still land`() {
        open(SLOW)
        val v = until(SLOW) { it.phase == "AWAITING_APPROVAL" }
        val quick = EpisodeGateway(lazyOf(env.workflowClient), Duration.ofSeconds(3))
        val started = System.nanoTime()
        val e = assertThrows<EpisodeTimeout> { quick.decide(v.instanceId, DecideRequest(v.proposalId!!, v.candidatesVersion!!, true, "op-1")) }
        assertEquals(v.instanceId, e.instanceId)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 6_000, "the gateway waited past its bound")
        // The Update was sent, and it is applied when its handler runs: a 504 does not mean "not decided".
        repeat(40) {
            val now = gateway.current(SLOW).view!!
            if (now.phase != "AWAITING_APPROVAL") {
                assertNull(now.escalationReason, "the decision landed as an approval: $now")
                return
            }
            Thread.sleep(500)
        }
        fail("the decision never landed")
    }

    @Test fun `an Update failure that is not the validator's form check is not passed off as a bad request`() {
        open(ODD)
        val v = until(ODD) { it.phase == "AWAITING_APPROVAL" }
        assertThrows<WorkflowUpdateException> { gateway.takeover(v.instanceId, TakeoverRequest("op-1")) }
        assertEquals("AWAITING_APPROVAL", gateway.current(ODD).view!!.phase)
    }

    @Test fun `a decision sent on a run that ended after it was checked is refused as ended`() {
        open("ep:g7")
        val v = until("ep:g7") { it.phase == "AWAITING_APPROVAL" }
        val checked = gateway.current("ep:g7")
        assertEquals("ACCEPTED", gateway.takeover(v.instanceId, TakeoverRequest("op-1")))
        assertEquals("ACCEPTED", gateway.close(v.instanceId, CloseRequest("op-1", "handled on site")))
        ended("ep:g7")
        assertThrows<EpisodeEnded> { gateway.send(checked, v.instanceId) { it.takeover(TakeoverRequest("op-1")) } }
    }

    @Test fun `a decision sent on a run that continued as new after it was checked is refused, and the next episode never gets it`() {
        val gate = CountDownLatch(1)
        acts.dispatchGate = gate
        open("ep:g8")
        val v = until("ep:g8") { it.phase == "AWAITING_APPROVAL" }
        assertEquals("ACCEPTED", gateway.decide(v.instanceId, DecideRequest(v.proposalId!!, v.candidatesVersion!!, true, "op-1")))
        until("ep:g8") { it.phase == "DISPATCHED" }
        val checked = gateway.current("ep:g8")
        assertEquals("ACCEPTED", gateway.takeover(v.instanceId, TakeoverRequest("op-1")))
        assertEquals("ACCEPTED", gateway.close(v.instanceId, CloseRequest("op-1", "handled on site")))
        // A fresh line while the ended run drains (its dispatch is held): the workflow continues as new with a new episode.
        env.workflowClient.newWorkflowStub(EpisodeWorkflow::class.java, "ep:g8").symptom(search1("search:search-1@late"))
        gate.countDown()
        val next = until("ep:g8") { it.instanceId != v.instanceId && it.phase == "AWAITING_APPROVAL" }
        assertThrows<EpisodeEnded> { gateway.send(checked, v.instanceId) { it.takeover(TakeoverRequest("op-1")) } }
        val after = gateway.current("ep:g8").view!!
        assertEquals(next.instanceId, after.instanceId)
        assertEquals("AWAITING_APPROVAL", after.phase)
        assertNull(after.escalationReason)
    }

    @Test fun `closing the gateway stops its Update threads and leaves a client it never made alone`() {
        val untouched = EpisodeGateway(lazy<WorkflowClient> { fail("closing must not make a client") })
        untouched.close()
        open("ep:g9")
        val v = until("ep:g9") { it.phase == "AWAITING_APPROVAL" }
        assertThrows<RejectedExecutionException> { untouched.send(gateway.current("ep:g9"), v.instanceId) { it.takeover(TakeoverRequest("op-1")) } }
        assertEquals("AWAITING_APPROVAL", gateway.current("ep:g9").view!!.phase)

        val stubs = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget("127.0.0.1:1").build())
        val made = lazy { WorkflowClient.newInstance(stubs) }
        made.value
        EpisodeGateway(made).close()
        assertTrue(stubs.isShutdown)
    }
}
