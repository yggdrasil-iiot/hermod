package koshei.episode.runtime

import io.temporal.activity.ActivityCancellationType
import io.temporal.activity.ActivityOptions
import io.temporal.activity.LocalActivityOptions
import io.temporal.api.enums.v1.TimeoutType
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.failure.TemporalFailure
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
import koshei.episode.Approver
import koshei.episode.Command
import koshei.episode.DiagnosisBudget
import koshei.episode.DispatchResult
import koshei.episode.EpisodeEvent
import koshei.episode.EpisodeState
import koshei.episode.Notice
import koshei.episode.NoticeKind
import koshei.episode.Phase
import koshei.episode.PolicyRead
import koshei.episode.Reply
import koshei.episode.Step
import koshei.episode.TriState
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
 *
 * The workflow never ends with work in flight: once the episode is over it keeps feeding what still arrives to the core
 * (which only records it) until every activity has reported back and every record is written. A symptom among that
 * belongs to a new episode, which the workflow then opens by continuing as new.
 */
class EpisodeWorkflowImpl : EpisodeWorkflow {
    /** One event for the main loop. [decision]: it wants a fresh policy read. [line]: the signal a joined symptom came from. */
    private class Inbound(val event: EpisodeEvent, val decision: Boolean, val line: SymptomSignal? = null)

    private var state: EpisodeState? = null
    /**
     * Signals that arrived before the episode opened, in arrival order (signalWithStart may deliver the first symptom
     * before [run] starts). The first symptom opens the episode; everything else follows into [inbox] in the same order.
     */
    private val preOpen = mutableListOf<Inbound>()
    private val inbox = ArrayDeque<Inbound>()
    private var diagnosis: CancellationScope? = null
    /** Diagnosis, revalidation, intent record and dispatch in flight; each reports back as exactly one event. */
    private val pending = mutableListOf<Promise<Unit>>()
    /** Records and notices in flight. Nothing waits for them during the episode (design §8.5); the end does. */
    private val unsettled = mutableListOf<Promise<Unit>>()
    /** Whether this run has already said that a record failed for good (RECORD_LAG, once per run; design §8.5). */
    private var recordLagRaised = false
    /** The manifest this run's episode opened with: what a continued run falls back to (design §7.1). */
    private var openedManifest = ""

    private val local: EpisodeActivities = Workflow.newLocalActivityStub(
        EpisodeActivities::class.java,
        LocalActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build(),
    )

    /**
     * The opening read gets a longer budget: with no table yet, a failed read opens the episode as POLICY_MISSING, which
     * escalates for good, while a failed read later only keeps the last table in force.
     */
    private val opening: EpisodeActivities = Workflow.newLocalActivityStub(
        EpisodeActivities::class.java,
        LocalActivityOptions.newBuilder()
            .setScheduleToCloseTimeout(Duration.ofSeconds(60))
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumInterval(Duration.ofSeconds(10)).build())
            .build(),
    )

    override fun run(start: EpisodeStart): EpisodeResult {
        // Symptoms the previous run could not take come first, before anything this run has received.
        preOpen.addAll(0, start.pending.map(::joining))
        Workflow.await { preOpen.any { it.line != null } }
        val first = preOpen.first { it.line != null }
        preOpen.remove(first)
        val read = readPolicy(opening)
        val line = first.line!!
        // The episode opens on this line, so it takes the line's own manifest when the watcher sent one.
        val manifest = line.manifestJson?.takeUnless { it.isBlank() } ?: start.manifestJson
        openedManifest = manifest
        apply(startEpisode(EpisodeEvent.Detected(instanceId(), manifest, line.toSymptom()), read, now()))
        // No yield since `state` was set, so nothing has reached the inbox ahead of these.
        inbox.addAll(preOpen)
        preOpen.clear()

        val late = mutableListOf<SymptomSignal>()
        while (true) {
            Workflow.await { inbox.isNotEmpty() || (current().phase.terminal && settled()) }
            val next = inbox.removeFirstOrNull() ?: break
            val phase = current().phase
            // After the end the core only records what arrives (IGNORED); a symptom among it starts a new episode.
            if (phase.terminal && next.line != null) late += next.line
            val read = if (next.decision && phase != Phase.ESCALATED && !phase.terminal) readPolicy(local) else null
            // The read may have yielded to an Update, so the state is taken afresh.
            apply(transition(current(), next.event, read, now()))
        }
        val end = current()
        // A line the ended episode already had is a redelivery, not a new occurrence.
        val fresh = late.filter { it.eventId !in end.seenEventIds }.distinctBy { it.eventId }
        if (fresh.isNotEmpty()) {
            // The new episode opens on the first fresh line, so it takes that line's manifest when the watcher sent one;
            // otherwise the one this episode opened with (which may be its first line's, not the start argument's).
            val manifest = fresh.first().manifestJson?.takeUnless { it.isBlank() } ?: openedManifest
            Workflow.continueAsNew(EpisodeStart(manifest, start.schemaVersion, fresh))
        }
        return EpisodeResult(end.phase.name, end.escalation?.reason?.name)
    }

    override fun symptom(signal: SymptomSignal) = arrive(joining(signal))

    override fun evidence(signal: EvidenceSignal) = arrive(Inbound(EpisodeEvent.EvidenceArrived(signal.toEvidence()), decision = false))

    override fun agentOff() = arrive(Inbound(EpisodeEvent.AgentOff, decision = false))

    override fun validateDecide(request: DecideRequest) = request.validate()
    override fun decide(request: DecideRequest): String = update(request.toEvent())

    override fun validateConfirm(request: ConfirmRequest) = request.validate()
    override fun confirm(request: ConfirmRequest): String = update(request.toEvent())

    override fun validateTakeover(request: TakeoverRequest) = request.validate()
    override fun takeover(request: TakeoverRequest): String = update(EpisodeEvent.TakenOver(Approver(request.operatorId)))

    override fun validateClose(request: CloseRequest) = request.validate()
    override fun close(request: CloseRequest): String = update(EpisodeEvent.Closed(Approver(request.operatorId), request.outcome))

    override fun view(): EpisodeView = state?.let { viewOf(it) } ?: EpisodeView(instanceId = instanceId(), phase = "OPENING")

    /** An Update applies its event now and answers with the core's reply (design §7.2: refusals are values). */
    private fun update(event: EpisodeEvent): String {
        Workflow.await { state != null }
        val step = transition(current(), event, null, now())
        apply(step)
        return (step.reply ?: Reply.ACCEPTED).name
    }

    private fun joining(signal: SymptomSignal) = Inbound(EpisodeEvent.SymptomJoined(signal.toSymptom()), decision = false, line = signal)

    private fun arrive(inbound: Inbound) {
        if (state == null) preOpen += inbound else inbox.addLast(inbound)
    }

    private fun current(): EpisodeState = checkNotNull(state) { "the episode is not open" }

    private fun settled(): Boolean = pending.all { it.isCompleted } && unsettled.all { it.isCompleted }

    private fun apply(step: Step) {
        state = step.state
        step.commands.forEach(::execute)
    }

    private fun execute(command: Command) {
        when (command) {
            is Command.Diagnose -> {
                val narrator = narrator(command.budget)
                val request = RawJson(command.request.toJson().toString())
                val scope = Workflow.newCancellationScope(Runnable {
                    report(
                        Async.function { narrator.diagnose(request) },
                        failed = { EpisodeEvent.DiagnosisFailed(expired(it), it.message) },
                    ) { answer -> EpisodeEvent.DiagnosisReturned(checkNotNull(answer) { "narrator answered null" }.text) }
                })
                diagnosis = scope
                scope.run()
            }
            Command.CancelDiagnosis -> diagnosis?.cancel()
            is Command.Revalidate -> report(
                Async.function { activities(command.timeoutMs).revalidate(command.candidate.toJson().toString()) },
                failed = { EpisodeEvent.Revalidated(TriState.UNKNOWN, command.token) },
            ) { result -> EpisodeEvent.Revalidated(triState(result), command.token) }
            is Command.SetTimer -> {
                // Timers are never cancelled: one that outlives its phase is expected, and the core ignores it by its token.
                val delay = maxOf(1L, command.at.toEpochMilli() - Workflow.currentTimeMillis())
                Workflow.newTimer(Duration.ofMillis(delay)).thenApply {
                    inbox.addLast(Inbound(EpisodeEvent.DeadlineExpired(command.which, command.token), decision = true))
                }
            }
            is Command.Notify -> notice(noticeJson(command.notice))
            is Command.Record -> {
                // Records run concurrently and may land out of seq order; the store keys them by (instance, seq).
                val id = instanceId()
                val seq = command.seq
                val kind = command.entry.kind.name
                val payload = command.entry.payload.toString()
                keep(
                    unsettled,
                    Async.procedure { activities(30_000).record(id, seq, kind, payload) }.handle { _, failure ->
                        // A record that failed for good: say so once per run (design §8.5). Temporal history still has it.
                        // The detail names the application's cause, not the activity wrapper (which names the worker host).
                        // The flag is set only once the notice is under way, so a throw here cannot swallow the one notice.
                        if (failure != null && !recordLagRaised) {
                            val cause = ((failure as? ActivityFailure)?.cause as? TemporalFailure)?.originalMessage ?: failure.message
                            notice(noticeJson(Notice(NoticeKind.RECORD_LAG, current().phase, detail = "record $seq $kind failed: $cause")))
                            recordLagRaised = true
                        }
                    },
                )
            }
            is Command.RecordIntent -> {
                val id = instanceId()
                report(
                    Async.procedure { activities(command.timeoutMs).recordIntent(id, command.seq, intentJson(command.intent)) },
                    failed = { EpisodeEvent.RecordFailed(command.seq, it.message) },
                ) { EpisodeEvent.Recorded(command.seq) }
            }
            is Command.Dispatch -> {
                val id = instanceId()
                report(
                    Async.function { activities(command.timeoutMs).dispatch(id, intentJson(command.intent), command.approverKind) },
                    // The application's cause (its type and message: why picasso refused), not the activity wrapper's text.
                    failed = { EpisodeEvent.DispatchReturned(DispatchResult.Uncertain(((it as? ActivityFailure)?.cause as? TemporalFailure)?.message ?: it.message), command.token) },
                ) { outcome -> EpisodeEvent.DispatchReturned(checkNotNull(outcome) { "dispatch answered null" }.toResult(), command.token) }
            }
        }
    }

    /** A notice is never awaited and its failure is never reported further: the channel is what failed (design §8.5). */
    private fun notice(json: String) {
        val id = instanceId()
        keep(unsettled, Async.procedure { activities(30_000).sendNotice(id, json) }.handle { _, _ -> })
    }

    /**
     * Turns an activity's outcome into exactly one event for the main loop: [done] for a value, [failed] for a failure —
     * and [failed] too when [done] throws (a null answer, say). An exception escaping a `handle` callback would be
     * swallowed by the SDK and the event lost, leaving the episode waiting for ever.
     */
    private fun <T> report(promise: Promise<T>, failed: (RuntimeException) -> EpisodeEvent, done: (T?) -> EpisodeEvent) {
        keep(
            pending,
            promise.handle { value, failure ->
                val event = try {
                    if (failure == null) done(value) else failed(failure)
                } catch (e: RuntimeException) {
                    failed(e)
                }
                inbox.addLast(Inbound(event, decision = true))
            },
        )
    }

    private fun keep(list: MutableList<Promise<Unit>>, promise: Promise<Unit>) {
        list.removeAll { it.isCompleted }
        list += promise
    }

    /** A read that fails, or returns nothing, is unreadable: the core keeps the last valid table (design §8.2). */
    private fun readPolicy(stub: EpisodeActivities): PolicyRead = try {
        stub.readPolicy()?.toRead() ?: PolicyRead.Unreadable("readPolicy returned nothing")
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

    /** Only the whole budget running out is DIAGNOSIS_EXPIRED; a heartbeat or StartToClose timeout is a failure. */
    private fun expired(failure: RuntimeException): Boolean =
        ((failure as? ActivityFailure)?.cause as? TimeoutFailure)?.timeoutType == TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE

    /**
     * The episode instance (design §7.1): the workflow id and the run that opened the episode, read from the started
     * event. A reset run continues the same instance — it replays its base run's events, narrator's answers naming the
     * base instance among them, under a new run id, which would diverge at the first diagnosis. Continue-as-new starts a
     * new run with a new original run id, so a new instance; the chain's first run id would not do, as every continued
     * run shares it. It is not always the current run's id, so the current run id is never derived from it.
     */
    private fun instanceId(): String = Workflow.getInfo().let { "${it.workflowId}/${it.originalExecutionRunId.ifEmpty { it.runId }}" }

    private fun now(): Instant = Instant.ofEpochMilli(Workflow.currentTimeMillis())
}
