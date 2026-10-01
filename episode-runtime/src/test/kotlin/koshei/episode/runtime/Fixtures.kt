package koshei.episode.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import io.temporal.activity.Activity
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.ActivityCompletionException
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import koshei.runtime.DataConverterSupport
import koshei.runtime.RawJson
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.fail

/** A test environment with koshei's converter (as every worker and client in production), workers not yet started. */
internal fun episodeEnvironment(register: TestWorkflowEnvironment.() -> Unit): TestWorkflowEnvironment =
    TestWorkflowEnvironment.newInstance(
        TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(DataConverterSupport.clientOptions()).build(),
    ).apply(register)

/** The workflow with [acts] on the episode queue and [narrator] on narrator-tq. */
internal fun TestWorkflowEnvironment.fakeWorkers(acts: EpisodeActivities, narrator: NarratorActivities) {
    newWorker(EPISODE_TASK_QUEUE).apply {
        registerWorkflowImplementationTypes(EpisodeWorkflowImpl::class.java)
        registerActivitiesImplementations(acts)
    }
    newWorker(NARRATOR_TASK_QUEUE).registerActivitiesImplementations(narrator)
}

internal fun episodeOptions(id: String): WorkflowOptions = WorkflowOptions.newBuilder()
    .setTaskQueue(EPISODE_TASK_QUEUE)
    .setWorkflowId(id)
    .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
    .build()

internal fun searchSignal(id: String) = SymptomSignal("search:$id", "SEARCH", Picasso.search(id), 0)

/** signalWithStart, as the watcher will (design §7.1). */
internal fun WorkflowClient.openEpisode(
    id: String = "ep:test",
    symptom: SymptomSignal = searchSignal("search-1"),
    start: EpisodeStart = EpisodeStart(Picasso.manifest),
): EpisodeWorkflow {
    val stub = newWorkflowStub(EpisodeWorkflow::class.java, episodeOptions(id))
    WorkflowStub.fromTyped(stub).signalWithStart("symptom", arrayOf(symptom), arrayOf(start))
    return stub
}

/** Advances test time a second at a time until [cond] holds on the view (the workflow runs between steps). */
internal fun EpisodeWorkflow.until(env: TestWorkflowEnvironment, seconds: Int = 120, cond: (EpisodeView) -> Boolean): EpisodeView {
    repeat(seconds) {
        val v = view()
        if (cond(v)) return v
        env.sleep(Duration.ofSeconds(1))
    }
    fail("condition not reached; last view ${view()}")
}

internal fun EpisodeWorkflow.result(): EpisodeResult = WorkflowStub.fromTyped(this).getResult(EpisodeResult::class.java)

/** Polls in wall-clock time (activities run on worker threads, outside test time) until [probe] yields a value. */
internal fun <T : Any> eventually(seconds: Int = 20, describe: () -> String = { "" }, probe: () -> T?): T {
    repeat(seconds * 10) {
        probe()?.let { return it }
        Thread.sleep(100)
    }
    return probe() ?: fail("not reached within ${seconds}s; ${describe()}")
}

/** picasso run-1 lines (byte copies of the `:episode` test resources; the picasso repo is read-only). */
internal object Picasso {
    private val mapper = ObjectMapper()
    private fun res(name: String): String =
        requireNotNull(javaClass.getResource("/picasso/run-1/$name")) { "missing test resource run-1/$name" }.readText(Charsets.UTF_8)

    val manifest: String get() = res("manifest.json")

    private fun line(file: String, key: String, id: String): String =
        res(file).lines().filter { it.isNotBlank() }.single { mapper.readTree(it).get(key)?.asText() == id }

    fun search(id: String): String = line("remedy-searches.jsonl", "searchId", id)
    fun incident(id: String): String = line("incidents.jsonl", "incidentId", id)
}

/** Policy tables as `readPolicy` would return them (design §10.1; the same values as the `:episode` test table). */
internal object TestPolicies {
    /** [heartbeatMs] must stay below `startToCloseMs` (540000) for the table to be valid. */
    fun table(autoApprove: Boolean, heartbeatMs: Long = 30_000): String = """
        {
          "version": "2026-10-02.1",
          "expiresAt": null,
          "agentLayerEnabled": true,
          "autoApprove": { "APPROVE_REMEDY": { "allowed": $autoApprove, "requireClean": true, "skills": ["pick_place"] } },
          "actionCatalog": [],
          "deadlines": {
            "correlatingMs": 5000, "approvalMs": 300000, "approvalValidityMs": 600000, "revalidateMs": 30000,
            "recordMs": 10000, "dispatchMs": 30000, "childMaxMs": 600000, "evidenceMs": 600000,
            "unknownRecheckMs": 30000, "unknownMs": 900000, "escalatedRetentionMs": 86400000, "episodeMs": 3600000
          },
          "diagnosis": { "scheduleToCloseMs": 1200000, "startToCloseMs": 540000, "heartbeatMs": $heartbeatMs, "maxAttempts": 2 },
          "maxAttemptsPerEpisode": 3,
          "repeatedRemedyThreshold": 2,
          "snapshotMaxBytes": 262144,
          "correlation": []
        }
    """.trimIndent()
}

/** picasso's approval-window answer for an approved remedy (picasso `ApprovalWire.encode`, schema 2). */
internal const val PICASSO_APPROVED =
    """{"schemaVersion":"2","contractSemver":"1.0.0","outcome":"APPROVED","executionId":"exec-42","steps":[{"skillType":"pick_place","parameters":{"slot":"B-03"}}]}"""

/** One `record` call as the activity received it. */
internal data class Rec(val instanceId: String, val seq: Long, val kind: String, val payload: String)

/** koshei-side activities for tests: programmable answers, every call remembered in order. */
internal class FakeEpisodeActivities : EpisodeActivities {
    @Volatile var policyJson: String? = TestPolicies.table(autoApprove = true)
    /** Whether the n-th `readPolicy` call (1-based, retries included) throws. */
    @Volatile var policyFault: (Int) -> Boolean = { false }
    @Volatile var revalidation: String? = "TRUE"
    @Volatile var dispatchOutcome: DispatchOutcome? = DispatchOutcome("ANSWER", PICASSO_APPROVED)
    /** Thrown by every `dispatch` call when set. */
    @Volatile var dispatchError: RuntimeException? = null
    /** When set, `dispatch` waits for it after remembering the call. */
    @Volatile var dispatchGate: CountDownLatch? = null
    /** Whether a `record` call for this kind throws (every attempt, so the activity fails for good). */
    @Volatile var recordFault: (String) -> Boolean = { false }
    /** Whether every `sendNotice` throws (after the notice is remembered as attempted). */
    @Volatile var noticeFault: Boolean = false
    /** Every notice attempted, in order, retries included. */
    val notices: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** The `instanceId` of every `dispatch` call, in order, retries included. */
    val dispatchedFor: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val records: MutableList<Rec> = Collections.synchronizedList(mutableListOf())
    private val policyReads = AtomicInteger()

    override fun readPolicy(): PolicyReadResult {
        val n = policyReads.incrementAndGet()
        if (policyFault(n)) throw IllegalStateException("policy store down (read $n)")
        return policyJson?.let { PolicyReadResult("FOUND", it) } ?: PolicyReadResult("MISSING")
    }

    override fun revalidate(candidateJson: String): String? {
        calls += "revalidate"
        return revalidation
    }

    override fun record(instanceId: String, seq: Long, kind: String, payloadJson: String) {
        if (recordFault(kind)) { calls += "record-failed:$kind"; throw IllegalStateException("store down for $kind") }
        calls += "record:$kind"
        records += Rec(instanceId, seq, kind, payloadJson)
    }

    override fun recordIntent(instanceId: String, seq: Long, intentJson: String) {
        calls += "intent:$seq"
    }

    override fun dispatch(instanceId: String, intentJson: String, approverKind: String?): DispatchOutcome? {
        val gate = dispatchGate
        calls += "dispatch:$approverKind"
        dispatchedFor += instanceId
        gate?.await(60, TimeUnit.SECONDS)
        dispatchError?.let { throw it }
        return dispatchOutcome
    }

    override fun sendNotice(instanceId: String, noticeJson: String) {
        calls += "notice"
        notices += noticeJson
        if (noticeFault) throw IllegalStateException("channel down")
    }
}

/**
 * The mock narrator unless told otherwise: [answer] replaces it, [block] heartbeats until cancelled, [stallMs] sleeps
 * that long without a heartbeat first.
 */
internal class ProgrammableNarrator : NarratorActivities {
    @Volatile var answer: ((RawJson) -> RawJson?)? = null
    @Volatile var block = false
    @Volatile var cancelled = false
    @Volatile var stallMs = 0L

    override fun diagnose(request: RawJson): RawJson? {
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
        if (stallMs > 0) Thread.sleep(stallMs)
        val programmed = answer
        return if (programmed != null) programmed(request) else MockNarratorActivities().diagnose(request)
    }
}
