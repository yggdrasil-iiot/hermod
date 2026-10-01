package koshei.episode.runtime

import io.temporal.activity.ActivityCancellationType
import io.temporal.activity.ActivityOptions
import io.temporal.activity.LocalActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.failure.TimeoutFailure
import io.temporal.workflow.Async
import io.temporal.workflow.CancellationScope
import io.temporal.workflow.Promise
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.UpdateValidatorMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import koshei.episode.Command
import koshei.episode.DiagnosisBudget
import koshei.episode.DispatchResult
import koshei.episode.EpisodeEvent
import koshei.episode.EpisodeState
import koshei.episode.Phase
import koshei.episode.PolicyRead
import koshei.episode.Reply
import koshei.episode.Step
import koshei.episode.startEpisode
import koshei.episode.transition
import koshei.runtime.RawJson
import java.time.Duration
import java.time.Instant

/** One episode (design §5, §7). Started only by signalWithStart with its first `symptom` (design §7.1). */
@WorkflowInterface
interface EpisodeWorkflow {
    @WorkflowMethod
    fun run(start: EpisodeStart): EpisodeResult

    @SignalMethod
    fun symptom(signal: SymptomSignal)

    @SignalMethod
    fun evidence(signal: EvidenceSignal)

    @SignalMethod
    fun agentOff()

    @UpdateMethod
    fun decide(request: DecideRequest): String

    @UpdateValidatorMethod(updateName = "decide")
    fun validateDecide(request: DecideRequest)

    @UpdateMethod
    fun confirm(request: ConfirmRequest): String

    @UpdateValidatorMethod(updateName = "confirm")
    fun validateConfirm(request: ConfirmRequest)

    @UpdateMethod
    fun takeover(request: TakeoverRequest): String

    @UpdateValidatorMethod(updateName = "takeover")
    fun validateTakeover(request: TakeoverRequest)

    @UpdateMethod
    fun close(request: CloseRequest): String

    @UpdateValidatorMethod(updateName = "close")
    fun validateClose(request: CloseRequest)

    @QueryMethod
    fun view(): EpisodeView
}

/**
 * The imperative shell around the pure core (design §6). It decides nothing: every event goes through [transition] and
 * every command it returns is executed without blocking — activities and timers report back as events in [inbox].
 * Update handlers apply their event at once and return the core's reply; the core has no blocking call, so a handler
 * and the main loop never interleave inside one transition.
 */
class EpisodeWorkflowImpl : EpisodeWorkflow {
    private var state: EpisodeState? = null
    /** Symptoms that arrived before the episode opened; the first opens it (signalWithStart may deliver it first). */
    private val early = mutableListOf<SymptomSignal>()
    /** Events waiting for the main loop, each with whether it is a decision point that wants a fresh policy read. */
    private val inbox = ArrayDeque<Pair<EpisodeEvent, Boolean>>()
    private var diagnosis: CancellationScope? = null
    /**
     * Records and notices still in flight. Nothing waits for them during the episode (design §8.5), but the workflow
     * waits for them before it returns: a workflow that ends abandons its activities, and the last step's records (the
     * transition to RESOLVED or CLOSED) would be lost.
     */
    private val unsettled = mutableListOf<Promise<Unit>>()

    private val local: EpisodeActivities = Workflow.newLocalActivityStub(
        EpisodeActivities::class.java,
        LocalActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build(),
    )

    override fun run(start: EpisodeStart): EpisodeResult {
        Workflow.await { early.isNotEmpty() }
        val first = early.removeAt(0)
        apply(startEpisode(EpisodeEvent.Detected(instanceId(), start.manifestJson, first.toSymptom()), readPolicy(), now()))
        early.forEach { enqueue(EpisodeEvent.SymptomJoined(it.toSymptom()), decision = false) }
        early.clear()
        while (true) {
            val current = state ?: break
            if (current.phase.terminal) break
            Workflow.await { inbox.isNotEmpty() || state?.phase?.terminal == true }
            if (state?.phase?.terminal == true) break
            val (event, decision) = inbox.removeFirst()
            val phase = state?.phase
            val read = if (decision && phase != null && phase != Phase.ESCALATED && !phase.terminal) readPolicy() else null
            val latest = state ?: break
            if (latest.phase.terminal) break
            apply(transition(latest, event, read, now()))
        }
        Workflow.await { unsettled.all { it.isCompleted } }
        val end = state
        return EpisodeResult(end?.phase?.name ?: "CLOSED", end?.escalation?.reason?.name)
    }

    override fun symptom(signal: SymptomSignal) {
        if (state == null) early += signal else enqueue(EpisodeEvent.SymptomJoined(signal.toSymptom()), decision = false)
    }

    override fun evidence(signal: EvidenceSignal) = enqueue(EpisodeEvent.EvidenceArrived(signal.toEvidence()), decision = false)

    override fun agentOff() = enqueue(EpisodeEvent.AgentOff, decision = false)

    override fun validateDecide(request: DecideRequest) = request.validate()
    override fun decide(request: DecideRequest): String = update(request.toEvent())

    override fun validateConfirm(request: ConfirmRequest) = request.validate()
    override fun confirm(request: ConfirmRequest): String = update(request.toEvent())

    override fun validateTakeover(request: TakeoverRequest) = request.validate()
    override fun takeover(request: TakeoverRequest): String = update(EpisodeEvent.TakenOver(koshei.episode.Approver(request.operatorId)))

    override fun validateClose(request: CloseRequest) = request.validate()
    override fun close(request: CloseRequest): String =
        update(EpisodeEvent.Closed(koshei.episode.Approver(request.operatorId), request.outcome))

    override fun view(): EpisodeView = state?.let { viewOf(it) } ?: EpisodeView(instanceId = instanceId(), phase = "OPENING")

    /** An Update applies its event now and answers with the core's reply (design §7.2: refusals are values). */
    private fun update(event: EpisodeEvent): String {
        Workflow.await { state != null }
        val current = state ?: return Reply.REFUSED_CLOSED.name
        val step = transition(current, event, null, now())
        apply(step)
        return (step.reply ?: Reply.ACCEPTED).name
    }

    private fun enqueue(event: EpisodeEvent, decision: Boolean) {
        inbox.addLast(event to decision)
    }

    private fun apply(step: Step) {
        state = step.state
        step.commands.forEach(::execute)
    }

    private fun execute(command: Command) {
        val id = instanceId()
        when (command) {
            is Command.Diagnose -> {
                val narrator = narrator(command.budget)
                val request = RawJson(command.request.toJson().toString())
                val scope = Workflow.newCancellationScope(Runnable {
                    Async.function { narrator.diagnose(request) }.handle { answer, failure ->
                        enqueue(
                            if (failure == null) EpisodeEvent.DiagnosisReturned(answer.text)
                            else EpisodeEvent.DiagnosisFailed(expired(failure), failure.message),
                            decision = true,
                        )
                    }
                })
                diagnosis = scope
                scope.run()
            }
            Command.CancelDiagnosis -> diagnosis?.cancel()
            is Command.Revalidate -> Async.function { activities(command.timeoutMs).revalidate(command.candidate.toJson().toString()) }
                .handle { result, failure -> enqueue(EpisodeEvent.Revalidated(triState(if (failure == null) result else null), command.token), decision = true) }
            is Command.SetTimer -> {
                val delay = maxOf(1L, command.at.toEpochMilli() - Workflow.currentTimeMillis())
                Workflow.newTimer(Duration.ofMillis(delay)).thenApply { enqueue(EpisodeEvent.DeadlineExpired(command.which, command.token), decision = true) }
            }
            is Command.Notify -> settle(Async.procedure { activities(30_000).sendNotice(id, noticeJson(command.notice)) }.handle { _, _ -> })
            is Command.Record -> settle(
                Async.procedure {
                    activities(30_000).record(id, command.seq, command.entry.kind.name, command.entry.payload.toString())
                }.handle { _, _ -> },
            )
            is Command.RecordIntent -> Async.procedure { activities(command.timeoutMs).recordIntent(id, command.seq, intentJson(command.intent)) }
                .handle { _, failure ->
                    enqueue(if (failure == null) EpisodeEvent.Recorded(command.seq) else EpisodeEvent.RecordFailed(command.seq, failure.message), decision = true)
                }
            is Command.Dispatch -> Async.function { activities(command.timeoutMs).dispatch(intentJson(command.intent), command.approverKind) }
                .handle { outcome, failure ->
                    val result = if (failure == null) outcome.toResult() else DispatchResult.Uncertain(failure.message)
                    enqueue(EpisodeEvent.DispatchReturned(result, command.token), decision = true)
                }
        }
    }

    /** Keeps a fire-and-forget activity until it settles (its failure is already swallowed by `handle`). */
    private fun settle(promise: Promise<Unit>) {
        unsettled.removeAll { it.isCompleted }
        unsettled += promise
    }

    private fun readPolicy(): PolicyRead = try {
        local.readPolicy().toRead()
    } catch (e: ActivityFailure) {
        PolicyRead.Unreadable("readPolicy failed: ${e.message}")
    }

    private fun activities(timeoutMs: Long): EpisodeActivities = Workflow.newActivityStub(
        EpisodeActivities::class.java,
        ActivityOptions.newBuilder()
            .setScheduleToCloseTimeout(Duration.ofMillis(timeoutMs))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build(),
    )

    /** narrator's activity with the policy's diagnosis budget (design §8.1, contract 0.6 §2). */
    private fun narrator(budget: DiagnosisBudget): NarratorActivities = Workflow.newActivityStub(
        NarratorActivities::class.java,
        ActivityOptions.newBuilder()
            .setTaskQueue(NARRATOR_TASK_QUEUE)
            .setScheduleToCloseTimeout(Duration.ofMillis(budget.scheduleToCloseMs))
            .setStartToCloseTimeout(Duration.ofMillis(budget.startToCloseMs))
            .setHeartbeatTimeout(Duration.ofMillis(budget.heartbeatMs))
            .setCancellationType(ActivityCancellationType.TRY_CANCEL)
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(budget.maxAttempts).build())
            .build(),
    )

    private fun expired(failure: RuntimeException): Boolean = (failure as? ActivityFailure)?.cause is TimeoutFailure

    private fun instanceId(): String = "${Workflow.getInfo().workflowId}/${Workflow.getInfo().runId}"

    private fun now(): Instant = Instant.ofEpochMilli(Workflow.currentTimeMillis())
}
