package koshei.episode.runtime

import io.temporal.api.common.v1.WorkflowExecution
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
 * A fetched history as the replayer should run it: as the run it came from. The replayer otherwise runs every history
 * as run `run_id_in_replay` (SDK 1.25.1, `internal.common.WorkflowExecutionHistory.getWorkflowExecution`), while the
 * episode's instance id is `workflowId/runId` and the core checks narrator's answer against it: the replay would
 * diverge at the first diagnosis for a reason no worker ever meets (a worker replays a run under its own id).
 * The run id is the started event's `originalExecutionRunId`, which is the run's own id.
 */
private class RunHistory(fetched: WorkflowExecutionHistory) :
    io.temporal.internal.common.WorkflowExecutionHistory(fetched.history, fetched.workflowExecution.workflowId) {
    private val runId = fetched.events.first().workflowExecutionStartedEventAttributes.originalExecutionRunId

    override fun getWorkflowExecution(): WorkflowExecution = super.getWorkflowExecution().toBuilder().setRunId(runId).build()
}

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
    private fun happyPath(): RunHistory {
        val ep = client.openEpisode(id = "rp:a")
        val waiting = ep.until(env) { it.phase == "AWAITING_EVIDENCE" }
        ep.confirm(ConfirmRequest("OUTCOME", waiting.candidateId!!, waiting.proposalId!!, true, operatorId = "op-1"))
        ep.result()
        return RunHistory(client.fetchHistory("rp:a"))
    }

    /** (b) Policy v1: a person approves; then takeover and close. */
    private fun personApproves(): RunHistory {
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
        return RunHistory(client.fetchHistory("rp:b"))
    }

    /** (c) The first run of a workflow continued as new for a symptom that came while it drained. */
    private fun continuedRun(): RunHistory {
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
        return RunHistory(client.fetchHistory("rp:c", first.substringAfterLast('/')))
    }

    /** (d) A record that keeps failing: one RECORD_LAG notice, then a person's DONE. */
    private fun recordLag(): RunHistory {
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
        return RunHistory(client.fetchHistory("rp:d"))
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
     * Run under its own id (see [RunHistory]), the history fails here only because the default converter cannot read
     * narrator's RawJson answer: the diagnosis fails in replay where it returned live, and the commands diverge.
     */
    @Test fun `the default converter cannot replay them - the converter is part of determinism`() {
        val history = happyPath()
        val e = assertThrows<Exception> { WorkflowReplayer.replayWorkflowExecution(history, EpisodeWorkflowImpl::class.java) }
        assertTrue("NonDeterministicException" in e.message.orEmpty(), e.toString())
    }
}
