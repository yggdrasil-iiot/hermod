package koshei.episode.runtime

import io.temporal.client.WorkflowClient
import io.temporal.common.WorkflowExecutionHistory
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Histories replayed as fetched. The replayer runs each as run `run_id_in_replay` (SDK 1.25.1), as a reset run replays
 * its base run's events under a new run id: the episode's instance id must not depend on the run id the code runs under
 * (it is the started event's original execution run id, design §7.1), or the core's check of narrator's `episodeId`
 * diverges at the first diagnosis.
 */
class EpisodeReplayTest {
    private val acts = FakeEpisodeActivities()
    private val narrator = ProgrammableNarrator()
    private lateinit var env: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient

    @BeforeEach fun up() {
        env = episodeEnvironment { fakeWorkers(acts, narrator) }
        env.start()
        client = env.workflowClient
    }

    @AfterEach fun down() = env.close()

    /** (a) Auto-approval to a person's DONE: diagnosis (a RawJson payload), revalidation, intent, dispatch, confirm. */
    private fun happyPath(): WorkflowExecutionHistory {
        val ep = client.openEpisode(id = "rp:a")
        val waiting = ep.until(env) { it.phase == "AWAITING_EVIDENCE" }
        ep.confirm(ConfirmRequest("OUTCOME", waiting.candidateId!!, waiting.proposalId!!, true, operatorId = "op-1"))
        ep.result()
        return client.fetchHistory("rp:a")
    }

    /** (b) Policy v1: a person approves; then takeover and close. */
    private fun personApproves(): WorkflowExecutionHistory {
        acts.policyJson = TestPolicies.table(autoApprove = false)
        try {
            val ep = client.openEpisode(id = "rp:b")
            val waiting = ep.until(env) { it.phase == "AWAITING_APPROVAL" }
            ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1"))
            ep.until(env) { it.phase == "AWAITING_EVIDENCE" }
            ep.takeover(TakeoverRequest("op-1"))
            ep.close(CloseRequest("op-1", "replay"))
            ep.result()
        } finally {
            acts.policyJson = TestPolicies.table(autoApprove = true)
        }
        return client.fetchHistory("rp:b")
    }

    /** (c) The first run of a workflow continued as new for a symptom that came while it drained. */
    private fun continuedRun(): WorkflowExecutionHistory {
        val gate = CountDownLatch(1)
        acts.dispatchGate = gate
        val ep = client.openEpisode(id = "rp:c")
        val first = ep.until(env) { it.phase == "DISPATCHED" }.instanceId
        ep.takeover(TakeoverRequest("op-1"))
        ep.close(CloseRequest("op-1", "replay"))
        ep.symptom(searchSignal("search-2"))
        gate.countDown()
        acts.dispatchGate = null
        client.newWorkflowStub(EpisodeWorkflow::class.java, "rp:c").until(env) { it.instanceId != first && it.phase != "OPENING" }
        return client.fetchHistory("rp:c", first.substringAfterLast('/'))
    }

    /** (d) A record that keeps failing: one RECORD_LAG notice, then a person's DONE. */
    private fun recordLag(): WorkflowExecutionHistory {
        acts.recordFault = { it == "PROPOSED" }
        try {
            val ep = client.openEpisode(id = "rp:d")
            val waiting = ep.until(env) { it.phase == "AWAITING_EVIDENCE" }
            // This may also match a RECORD_LAG of rp:c's still-running continued run; harmless: rp:d cannot end before
            // its own failed record and notice settle (the drain waits), so its history holds them either way.
            eventually { acts.notices.toList().takeIf { n -> n.any { "RECORD_LAG" in it } } }
            ep.confirm(ConfirmRequest("OUTCOME", waiting.candidateId!!, waiting.proposalId!!, true, operatorId = "op-1"))
            ep.result()
        } finally {
            acts.recordFault = { false }
        }
        return client.fetchHistory("rp:d")
    }

    @Test fun `every scenario's history replays on today's code with koshei's converter`() {
        val histories = listOf(happyPath(), personApproves(), continuedRun(), recordLag())
        val replay = episodeEnvironment { }
        try {
            val worker = replay.newWorker("replay").apply { registerWorkflowImplementationTypes(EpisodeWorkflowImpl::class.java) }
            histories.forEach { WorkflowReplayer.replayWorkflowExecution(it, worker) }
        } finally {
            replay.close()
        }
    }

    /**
     * The history fails here only because the default converter cannot read narrator's RawJson answer: the diagnosis
     * fails in replay where it returned live, and the commands diverge.
     */
    @Test fun `the default converter cannot replay them - the converter is part of determinism`() {
        val history = happyPath()
        val e = assertThrows<Exception> { WorkflowReplayer.replayWorkflowExecution(history, EpisodeWorkflowImpl::class.java) }
        assertTrue("NonDeterministicException" in e.message.orEmpty(), e.toString())
    }
}
