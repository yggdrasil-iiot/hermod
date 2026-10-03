package koshei.episode.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import io.temporal.api.enums.v1.EventType
import io.temporal.client.WorkflowClient
import io.temporal.common.WorkflowExecutionHistory
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import io.temporal.worker.NonDeterministicException
import io.temporal.worker.WorkerOptions
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R3 (design §7.5): histories stopped in each state, committed, replayed on today's code with koshei's converter. A
 * change that reorders the workflow's commands without `Workflow.getVersion` fails here — as it would fail every open
 * episode on the first deployment after it.
 *
 * **Versioned sets, never overwritten.** `replay/<set>/<name>.json`, one directory per generation. Every file of every
 * set must replay: an episode opened under old code must still replay after a `getVersion`-guarded change. The newest
 * set (by directory name) must hold exactly [EXPECTED]. An old set is removed only together with the `getVersion` branch
 * that keeps it replaying.
 *
 * **Writing a new set** — only on purpose, after a deliberate change guarded by `getVersion`, never to make this test
 * pass: `./gradlew :episode-runtime:test --tests "*CommittedReplayTest*" -Dkoshei.writeReplayHistories=true
 * -Dkoshei.replaySet=<name> --rerun` (the set defaults to today's date; the replay test is off while writing). The
 * generator refuses a set directory that already exists. From PowerShell 5.1 quote each `-D` argument
 * (`"-Dkoshei.writeReplayHistories=true"`) or it is split; bash needs no quotes.
 * - A file's name is its workflow id `cr:<name>`; the replay reads the id from the name and checks it against the
 *   history's first record, so a renamed file fails clearly.
 * - A regeneration churns every file (run ids, timestamps, the order of concurrent completions): review a new set by
 *   replaying it and by its decoded transitions (the `episodeRecord` TRANSITION inputs), not by diff.
 * - Check each new file: the first event is `WorkflowExecutionStarted` of `EpisodeWorkflow`; a stopped history's last
 *   event is no completion, a finished one's is; no host data (the identity is fixed to [IDENTITY]); the payloads are
 *   base64 — decode them to see they carry only test values.
 */
class CommittedReplayTest {
    private val root: Path = Path.of(System.getProperty("koshei.repoRoot"), "episode-runtime", "src", "test", "resources", "replay")

    @Test
    @DisabledIfSystemProperty(named = "koshei.writeReplayHistories", matches = "true")
    fun `every committed history replays on today's code`() {
        assertEquals(emptyList(), root.listDirectoryEntries().filterNot { it.isDirectory() }, "files outside a set directory under $root")
        val sets = root.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }
        assertTrue(sets.isNotEmpty(), "no set directory under $root (write one with the generator)")
        val filesBySet = sets.associateWith { set -> set.listDirectoryEntries().filter { it.extension == "json" }.sortedBy { it.name } }
        filesBySet.forEach { (set, files) -> assertTrue(files.isNotEmpty(), "set ${set.name} is empty") }
        assertEquals(EXPECTED, filesBySet.getValue(sets.last()).map { it.nameWithoutExtension }.toSet(), "the newest set, ${sets.last().name}")

        val replay = episodeEnvironment { }
        try {
            // The deadlock detector judges wall-clock time, not determinism: on a cold JVM the first replay's class loading
            // and JIT can hold a workflow thread past its default second.
            val worker = replay.newWorker("replay", WorkerOptions.newBuilder().setDefaultDeadlockDetectionTimeout(60_000).build())
                .apply { registerWorkflowImplementationTypes(EpisodeWorkflowImpl::class.java) }
            val failures = filesBySet.values.flatten().mapNotNull { f ->
                val label = "${f.parent.name}/${f.name}"
                val name = f.nameWithoutExtension
                // The workflow id as written: the instance id (and narrator's episodeId check) is built from it.
                val history = WorkflowExecutionHistory.fromJson(f.readText(), workflowId(name))
                firstRecordedInstance(history)?.let { id ->
                    if (!id.startsWith("${workflowId(name)}/")) return@mapNotNull "$label: renamed? its first record is for $id, not ${workflowId(name)}/…"
                }
                try {
                    WorkflowReplayer.replayWorkflowExecution(history, worker)
                    null
                } catch (e: Exception) {
                    "$label: ${classify(e)}: $e"
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
        val setName = System.getProperty("koshei.replaySet")?.takeIf { it.isNotBlank() } ?: LocalDate.now().toString()
        val set = root.resolve(setName)
        check(!Files.exists(set)) { "set $set already exists: a set is never overwritten — name a new one with -Dkoshei.replaySet" }
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
            continuedAsNew(),
        )
        assertEquals(EXPECTED, histories.keys)
        Files.createDirectories(set.parent)
        Files.createDirectory(set)   // fails if it appeared meanwhile
        histories.forEach { (name, h) ->
            Files.writeString(set.resolve("$name.json"), h.toJson(true), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }
    }

    /** EpisodeReplayTest (c): the first run ends while dispatch is held; a symptom arrives while it drains. */
    private fun continuedAsNew(): Pair<String, WorkflowExecutionHistory> {
        val gate = CountDownLatch(1)
        return "continued-as-new" to scenario("continued-as-new", autoApprove = true, beforeOpen = { it.dispatchGate = gate }) { ep, env, client, acts ->
            try {
                val first = ep.until(env) { it.phase == "DISPATCHED" }.instanceId
                ep.takeover(TakeoverRequest("op-1"))
                ep.close(CloseRequest("op-1", "replay"))
                ep.symptom(searchSignal("search-2"))
                gate.countDown()
                client.newWorkflowStub(EpisodeWorkflow::class.java, workflowId("continued-as-new"))
                    .until(env) { it.instanceId != first && it.phase != "OPENING" }
                client.fetchHistory(workflowId("continued-as-new"), first.substringAfterLast('/'))
            } finally {
                gate.countDown()
                acts.dispatchGate = null
            }
        }
    }

    /**
     * One environment and one set of fakes per scenario: a shared one would skip time past another run's deadlines.
     * [beforeOpen] sets the fakes up before the episode opens.
     */
    private fun scenario(
        name: String,
        autoApprove: Boolean,
        beforeOpen: (FakeEpisodeActivities) -> Unit = {},
        body: (EpisodeWorkflow, TestWorkflowEnvironment, WorkflowClient, FakeEpisodeActivities) -> WorkflowExecutionHistory,
    ): WorkflowExecutionHistory {
        val acts = FakeEpisodeActivities().apply { policyJson = TestPolicies.table(autoApprove = autoApprove) }
        beforeOpen(acts)
        // A fixed identity: the default (pid@host) would write this machine's name into every committed history.
        val env = episodeEnvironment(identity = IDENTITY) { fakeWorkers(acts, ProgrammableNarrator()) }
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

    /**
     * The history once no workflow task is half done: nothing `WORKFLOW_TASK_SCHEDULED` or `_STARTED` after the last
     * `WORKFLOW_TASK_COMPLETED`. Activities only scheduled after it are fine to replay.
     */
    private fun WorkflowClient.settledHistory(name: String): WorkflowExecutionHistory = eventually(describe = { "history of $name never settled" }) {
        fetchHistory(workflowId(name)).takeIf { h ->
            h.events.takeLastWhile { it.eventType != EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED }
                .none { it.eventType == EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED || it.eventType == EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED }
        }
    }

    private companion object {
        const val UNIT = "remedy-1-pick_place"
        const val IDENTITY = "koshei-replay-generator"

        val EXPECTED = setOf(
            "awaiting-approval", "dispatched", "awaiting-evidence", "unknown-outcome", "resolved-by-report", "resolved-by-person",
            "escalated-agent-off", "closed", "continued-as-new",
        )

        private val mapper = ObjectMapper()

        fun workflowId(name: String) = "cr:$name"

        /** The instance id the first `episodeRecord` was scheduled for (its first argument), or null if none was. */
        fun firstRecordedInstance(h: WorkflowExecutionHistory): String? = h.events
            .firstOrNull { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED && it.activityTaskScheduledEventAttributes.activityType.name == "episodeRecord" }
            ?.activityTaskScheduledEventAttributes?.input?.payloadsList?.firstOrNull()
            ?.let { mapper.readValue(it.data.toStringUtf8(), String::class.java) }

        /** "nondeterministic" only when the SDK says so: anything else is no reason to write a new set. */
        fun classify(e: Throwable): String {
            val chain = generateSequence(e) { it.cause }.take(32).toList()
            return when {
                chain.any { it is NonDeterministicException } -> "nondeterministic"
                // The replayer reports a workflow-task failure as a query failure whose message carries the cause's text.
                chain.any { "NonDeterministicException" in it.message.orEmpty() } -> "nondeterministic"
                else -> "other: ${chain.last().javaClass.name}"
            }
        }
    }
}
