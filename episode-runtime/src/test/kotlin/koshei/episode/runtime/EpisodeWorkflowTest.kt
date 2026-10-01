package koshei.episode.runtime

import io.temporal.activity.Activity
import io.temporal.client.ActivityCompletionException
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.client.WorkflowUpdateException
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import koshei.runtime.DataConverterSupport
import koshei.runtime.RawJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class EpisodeWorkflowTest {
    private lateinit var env: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient
    private val acts = FakeEpisodeActivities()
    private val narrator = ProgrammableNarrator()

    @BeforeEach fun up() {
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(DataConverterSupport.clientOptions()).build(),
        )
        env.newWorker(EPISODE_TASK_QUEUE).apply {
            registerWorkflowImplementationTypes(EpisodeWorkflowImpl::class.java)
            registerActivitiesImplementations(acts)
        }
        env.newWorker(NARRATOR_TASK_QUEUE).registerActivitiesImplementations(narrator)
        env.start()
        client = env.workflowClient
    }

    @AfterEach fun down() = env.close()

    private fun open(id: String = "ep:test", symptom: SymptomSignal = searchSignal("search-1")): EpisodeWorkflow {
        val stub = client.newWorkflowStub(
            EpisodeWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(EPISODE_TASK_QUEUE).setWorkflowId(id).build(),
        )
        WorkflowStub.fromTyped(stub).signalWithStart("symptom", arrayOf(symptom), arrayOf(EpisodeStart(Picasso.manifest)))
        return stub
    }

    private fun searchSignal(id: String) = SymptomSignal("search:$id", "SEARCH", Picasso.search(id), 0)

    /** Advances test time a second at a time until [cond] holds on the view (the workflow runs between steps). */
    private fun EpisodeWorkflow.until(seconds: Int = 120, cond: (EpisodeView) -> Boolean): EpisodeView {
        repeat(seconds) {
            val v = view()
            if (cond(v)) return v
            env.sleep(Duration.ofSeconds(1))
        }
        fail("condition not reached; last view ${view()}")
    }

    private fun EpisodeWorkflow.result(): EpisodeResult = WorkflowStub.fromTyped(this).getResult(EpisodeResult::class.java)

    @Test fun `an episode runs through the mock narrator to a person's DONE`() {
        val ep = open()
        val waiting = ep.until { it.phase == "AWAITING_EVIDENCE" }
        assertEquals(1, waiting.attempt)
        val reply = ep.confirm(ConfirmRequest("OUTCOME", waiting.candidateId!!, waiting.proposalId!!, true, operatorId = "op-1"))
        assertEquals("ACCEPTED", reply)
        assertEquals(EpisodeResult("RESOLVED", null), ep.result())
        val intent = acts.calls.indexOfFirst { it.startsWith("intent:") }
        val dispatch = acts.calls.indexOfFirst { it.startsWith("dispatch:") }
        assertTrue(intent in 0 until dispatch, "the intent is recorded before anything is dispatched: ${acts.calls}")
        assertEquals("dispatch:AGENT", acts.calls[dispatch])
    }

    @Test fun `policy v1 waits for a person - a stale decide is refused as a value, the right one approves`() {
        acts.policyJson = TestPolicies.table(autoApprove = false)
        val ep = open()
        val waiting = ep.until { it.phase == "AWAITING_APPROVAL" }
        assertEquals("REFUSED_STALE", ep.decide(DecideRequest("${waiting.instanceId}#0", waiting.candidatesVersion!!, true, "op-1")))
        assertEquals("ACCEPTED", ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1")))
        ep.until { it.phase == "AWAITING_EVIDENCE" }
        assertTrue("dispatch:PERSON" in acts.calls)
    }

    @Test fun `the approval deadline escalates and a late decide is refused`() {
        acts.policyJson = TestPolicies.table(autoApprove = false)
        val ep = open()
        val waiting = ep.until { it.phase == "AWAITING_APPROVAL" }
        env.sleep(Duration.ofMinutes(6))
        val escalated = ep.until { it.phase == "ESCALATED" }
        assertEquals("APPROVAL_EXPIRED", escalated.escalationReason)
        assertEquals("REFUSED_NOT_AWAITING", ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1")))
    }

    @Test fun `a malformed Update is rejected by the validator, not by the episode`() {
        val ep = open()
        ep.until { it.phase == "CORRELATING" }
        assertThrows<WorkflowUpdateException> { ep.decide(DecideRequest(operatorId = "")) }
        assertThrows<WorkflowUpdateException> { ep.confirm(ConfirmRequest("MAYBE", operatorId = "op-1")) }
        assertEquals("CORRELATING", ep.view().phase)
    }

    @Test fun `takeover cancels a diagnosis in flight`() {
        narrator.block = true
        val ep = open()
        ep.until { it.phase == "DIAGNOSING" }
        assertEquals("ACCEPTED", ep.takeover(TakeoverRequest("op-1")))
        assertEquals("TAKEN_OVER", ep.view().escalationReason)
        // The activity learns of the cancellation only from a heartbeat reply, and the SDK sends heartbeats at most every
        // 0.8 × heartbeatTimeout of wall-clock time (24 s for the policy's 30 s) — time skipping does not shorten it.
        repeat(300) { if (!narrator.cancelled) Thread.sleep(200) }
        assertTrue(narrator.cancelled, "the diagnose activity saw the cancellation")
    }

    @Test fun `agentOff escalates and close ends the workflow`() {
        val ep = open()
        ep.until { it.phase == "CORRELATING" }
        ep.agentOff()
        assertEquals("AGENT_LAYER_OFF", ep.until { it.phase == "ESCALATED" }.escalationReason)
        assertEquals("ACCEPTED", ep.close(CloseRequest("op-1", "handled on site")))
        assertEquals(EpisodeResult("CLOSED", "AGENT_LAYER_OFF"), ep.result())
    }

    @Test fun `narrator's answer crosses as raw text, so a duplicated key is caught`() {
        narrator.answer = { r ->
            val mock = MockNarratorActivities().diagnose(r).text
            RawJson(mock.replaceFirst("\"outcome\":", "\"outcome\":\"NO_GROUNDS\",\"outcome\":"))
        }
        val ep = open()
        assertEquals("DIAGNOSIS_FAILED", ep.until { it.phase == "ESCALATED" }.escalationReason)
    }

    @Test fun `no policy at the start escalates at once and retention closes it`() {
        acts.policyJson = null
        val ep = open()
        assertEquals("POLICY_MISSING", ep.until { it.phase == "ESCALATED" }.escalationReason)
        env.sleep(Duration.ofHours(25))
        assertEquals(EpisodeResult("CLOSED", "POLICY_MISSING"), ep.result())
    }
}

/** The mock narrator unless told otherwise: [answer] replaces it, [block] heartbeats until cancelled. */
internal class ProgrammableNarrator : NarratorActivities {
    @Volatile var answer: ((RawJson) -> RawJson)? = null
    @Volatile var block = false
    @Volatile var cancelled = false

    override fun diagnose(request: RawJson): RawJson {
        if (block) {
            val ctx = Activity.getExecutionContext()
            try {
                while (true) {
                    ctx.heartbeat(null)
                    Thread.sleep(100)
                }
            } catch (e: ActivityCompletionException) {
                cancelled = true
                throw e
            }
        }
        return answer?.invoke(request) ?: MockNarratorActivities().diagnose(request)
    }
}
