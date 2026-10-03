package koshei.episode.runtime

import io.temporal.api.enums.v1.EventType
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.WorkflowExecutionHistory
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import koshei.runtime.DataConverterSupport
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R3 (design §7.5): histories stopped in each state, committed, replayed on today's code with koshei's converter. A
 * change that reorders the workflow's commands without `Workflow.getVersion` fails here — as it would fail every open
 * episode on the first deployment after it.
 *
 * The histories are written only on purpose: `./gradlew :episode-runtime:test --tests "*CommittedReplayTest*"
 * -Dkoshei.writeReplayHistories=true --rerun` (the replay test is off while writing) — after a deliberate change guarded
 * by `getVersion`, never to make this test pass.
 */
class CommittedReplayTest {
    private val dir: Path = Path.of(System.getProperty("koshei.repoRoot"), "episode-runtime", "src", "test", "resources", "replay")

    @Test
    @DisabledIfSystemProperty(named = "koshei.writeReplayHistories", matches = "true")
    fun `every committed history replays on today's code`() {
        val files = dir.listDirectoryEntries().filter { it.extension == "json" }.sortedBy { it.fileName.toString() }
        assertEquals(EXPECTED, files.map { it.nameWithoutExtension }.toSet(), "the committed set (write them with the generator)")
        val replay = episodeEnvironment { }
        try {
            val worker = replay.newWorker("replay").apply { registerWorkflowImplementationTypes(EpisodeWorkflowImpl::class.java) }
            val failures = files.mapNotNull { f ->
                try {
                    // The workflow id as written: the instance id (and narrator's episodeId check) is built from it.
                    WorkflowReplayer.replayWorkflowExecution(WorkflowExecutionHistory.fromJson(f.readText(), workflowId(f.nameWithoutExtension)), worker)
                    null
                } catch (e: Exception) {
                    "${f.fileName}: $e"
                }
            }
            assertEquals(emptyList(), failures)
        } finally {
            replay.close()
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "koshei.writeReplayHistories", matches = "true")
    fun `write the histories`() {
        val histories = linkedMapOf(
            "awaiting-approval" to scenario("awaiting-approval", autoApprove = false) { ep, env, client, _ ->
                ep.until(env) { it.phase == "AWAITING_APPROVAL" }
                client.settledHistory("awaiting-approval")
            },
            "dispatched" to scenario("dispatched", autoApprove = false) { ep, env, client, acts ->
                val waiting = ep.until(env) { it.phase == "AWAITING_APPROVAL" }
                val gate = CountDownLatch(1)
                acts.dispatchGate = gate
                try {
                    ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1"))
                    ep.until(env) { it.phase == "DISPATCHED" }
                    client.settledHistory("dispatched")
                } finally {
                    gate.countDown()
                    acts.dispatchGate = null
                }
            },
            "awaiting-evidence" to scenario("awaiting-evidence", autoApprove = false) { ep, env, client, _ ->
                approve(ep, env)
                client.settledHistory("awaiting-evidence")
            },
            "unknown-outcome" to scenario("unknown-outcome", autoApprove = false) { ep, env, client, _ ->
                approve(ep, env)
                ep.evidence(report("jr-1", inDoubt = listOf(UNIT)))
                ep.until(env) { it.phase == "UNKNOWN_OUTCOME" }
                client.settledHistory("unknown-outcome")
            },
            "resolved-by-report" to scenario("resolved-by-report", autoApprove = false) { ep, env, client, _ ->
                approve(ep, env)
                ep.evidence(report("jr-1", inDoubt = emptyList()))
                assertEquals("RESOLVED", ep.result().phase)
                client.fetchHistory(workflowId("resolved-by-report"))
            },
            "resolved-by-person" to scenario("resolved-by-person", autoApprove = true) { ep, env, client, _ ->
                val waiting = ep.until(env) { it.phase == "AWAITING_EVIDENCE" }
                ep.confirm(ConfirmRequest("OUTCOME", waiting.candidateId!!, waiting.proposalId!!, true, operatorId = "op-1"))
                assertEquals("RESOLVED", ep.result().phase)
                client.fetchHistory(workflowId("resolved-by-person"))
            },
            "escalated-agent-off" to scenario("escalated-agent-off", autoApprove = false) { ep, env, client, _ ->
                ep.until(env) { it.phase == "AWAITING_APPROVAL" }
                ep.agentOff()
                ep.until(env) { it.phase == "ESCALATED" }
                client.settledHistory("escalated-agent-off")
            },
            "closed" to scenario("closed", autoApprove = false) { ep, env, client, _ ->
                ep.until(env) { it.phase == "AWAITING_APPROVAL" }
                ep.takeover(TakeoverRequest("op-1"))
                ep.close(CloseRequest("op-1", "replay"))
                assertEquals("CLOSED", ep.result().phase)
                client.fetchHistory(workflowId("closed"))
            },
            "continued-as-new" to scenario("continued-as-new", autoApprove = true) { ep, env, client, acts ->
                // EpisodeReplayTest (c): the first run ends while dispatch is held; a symptom arrives while it drains.
                val gate = CountDownLatch(1)
                acts.dispatchGate = gate
                val first = ep.until(env) { it.phase == "DISPATCHED" }.instanceId
                ep.takeover(TakeoverRequest("op-1"))
                ep.close(CloseRequest("op-1", "replay"))
                ep.symptom(searchSignal("search-2"))
                gate.countDown()
                acts.dispatchGate = null
                client.newWorkflowStub(EpisodeWorkflow::class.java, workflowId("continued-as-new"))
                    .until(env) { it.instanceId != first && it.phase != "OPENING" }
                client.fetchHistory(workflowId("continued-as-new"), first.substringAfterLast('/'))
            },
        )
        assertEquals(EXPECTED, histories.keys)
        Files.createDirectories(dir)
        histories.forEach { (name, h) -> Files.writeString(dir.resolve("$name.json"), h.toJson(true)) }
    }

    /** One environment and one set of fakes per scenario: a shared one would skip time past another run's deadlines. */
    private fun scenario(
        name: String,
        autoApprove: Boolean,
        body: (EpisodeWorkflow, TestWorkflowEnvironment, WorkflowClient, FakeEpisodeActivities) -> WorkflowExecutionHistory,
    ): WorkflowExecutionHistory {
        val acts = FakeEpisodeActivities().apply { policyJson = TestPolicies.table(autoApprove = autoApprove) }
        // A fixed identity: the default (pid@host) would write this machine's name into every committed history.
        val env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder(DataConverterSupport.clientOptions()).setIdentity(IDENTITY).build())
                .build(),
        ).apply { fakeWorkers(acts, ProgrammableNarrator()) }
        try {
            env.start()
            val client = env.workflowClient
            return body(client.openEpisode(id = workflowId(name)), env, client, acts)
        } finally {
            env.close()
        }
    }

    private fun approve(ep: EpisodeWorkflow, env: TestWorkflowEnvironment) {
        val waiting = ep.until(env) { it.phase == "AWAITING_APPROVAL" }
        ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1"))
        ep.until(env) { it.phase == "AWAITING_EVIDENCE" }
    }

    /** A report linked to PICASSO_APPROVED's execution that completed its one unit, picasso seeing the robot online. */
    private fun report(id: String, inDoubt: List<String>) = EvidenceSignal(
        id, "exec-42", "IDLE", "E2", listOf(UNIT), inDoubt, emptyList(), false, 0,
        picassoInstanceId = "mw-1", connection = "CONNECTION_STATE_ONLINE",
    )

    /** The history once no workflow task is half done (an activity only scheduled is fine to replay). */
    private fun WorkflowClient.settledHistory(name: String): WorkflowExecutionHistory = eventually(describe = { "history of $name never settled" }) {
        fetchHistory(workflowId(name)).takeIf { h ->
            h.lastEvent.eventType !in setOf(EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED, EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED)
        }
    }

    private companion object {
        const val UNIT = "remedy-1-pick_place"
        const val IDENTITY = "koshei-replay-generator"

        val EXPECTED = setOf(
            "awaiting-approval", "dispatched", "awaiting-evidence", "unknown-outcome", "resolved-by-report", "resolved-by-person",
            "escalated-agent-off", "closed", "continued-as-new",
        )

        fun workflowId(name: String) = "cr:$name"
    }
}
