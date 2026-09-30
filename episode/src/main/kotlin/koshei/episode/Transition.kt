package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import java.time.Instant

/** One transition's result: the next state, the commands to run in order, and the reply if the event was an Update. */
data class Step(val state: EpisodeState, val commands: List<Command>, val reply: Reply? = null)

/** EPISODE timers are set once and never go stale. */
const val EPISODE_TOKEN: Long = -1

/**
 * Opens an episode on its first symptom (design §5.2 start rows, §7.1). [read] is what `readPolicy` found at the start.
 * The policy off, missing or expired → ESCALATED at once, with a retention timer; otherwise CORRELATING for the merge
 * window, with the whole-episode timer.
 */
fun startEpisode(event: EpisodeEvent.Detected, read: PolicyRead, now: Instant): Step {
    val out = Out(
        EpisodeState(
            instanceId = event.instanceId, startedAt = now, episodeDeadline = null, phase = Phase.CORRELATING,
            enteredAt = now, token = 0, stateDeadline = null, escalation = null, manifestJson = event.manifestJson,
            symptoms = listOf(event.symptom), seenEventIds = setOf(event.symptom.eventId), attempt = null,
            history = emptyList(), dispatchCounts = emptyMap(), policy = null, autoApproveSuspended = false,
            lastRejected = emptyList(), recordSeq = 0,
        ),
    )
    val resolution = resolvePolicy(read, null, now)
    // The audit record keeps what arrived, verbatim (design §13): the manifest text as a tree, the line as it was.
    out.record(RecordKind.OPENED) {
        put("instanceId", event.instanceId)
        put("eventId", event.symptom.eventId)
        put("kind", event.symptom.kind.name)
        set<JsonNode>("manifest", rawJson(event.manifestJson))
        set<JsonNode>("line", event.symptom.line)
        put("policyVersion", (resolution as? PolicyResolution.Active)?.policy?.version)
    }
    out.applyPolicy(resolution, announce = false)
    when (resolution) {
        is PolicyResolution.Off -> out.escalate(now, Escalation(resolution.reason.escalation()))
        is PolicyResolution.Active -> {
            // A line or manifest that can never become a snapshot fails now, not after the merge window.
            val problem = event.symptom.problem()?.let { "opening symptom: $it" }
                ?: try { out.state.snapshot(); null } catch (e: Exception) { "snapshot: ${e.message}" }
            if (problem != null) out.escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, problem))
            else {
                val deadline = episodeDeadline(now, resolution.policy)
                out.state = out.state.copy(episodeDeadline = deadline)
                out.commands += Command.SetTimer(Timer.EPISODE, deadline, EPISODE_TOKEN)
                out.enter(Phase.CORRELATING, now)
                out.stateTimer(now, resolution.policy.deadlines.correlatingMs)
            }
        }
    }
    // Announced once the phase is settled, so the notice names the phase the episode is actually in.
    out.announceRejected(resolution.rejectedErrors)
    return out.step()
}

/**
 * The pure transition function (design §5.2, §6). [read] is a fresh `readPolicy` result when the runtime read the
 * policy for this decision, or null to keep the table in force. It never throws on an event that does not apply: the
 * event is recorded and, for an Update, refused with a value.
 */
fun transition(state: EpisodeState, event: EpisodeEvent, read: PolicyRead?, now: Instant): Step {
    val out = Out(state, opening = false)
    if (state.phase.terminal) {
        out.ignore(event, "episode is ${state.phase}")
        out.reply = if (event.isUpdate()) Reply.REFUSED_CLOSED else null
        return out.step()
    }
    // What the event met, before any global row below changes it: `close` needs an episode that was already with a person.
    val wasEscalated = state.phase == Phase.ESCALATED
    // A diagnosis result or failure IS the end of the activity: nothing is left to cancel when a global row escalates on it.
    val cancel = event !is EpisodeEvent.DiagnosisReturned && event !is EpisodeEvent.DiagnosisFailed
    if (read != null && !wasEscalated) {
        val resolution = resolvePolicy(read, state.policy, now)
        out.applyPolicy(resolution)
        if (resolution is PolicyResolution.Off) out.escalate(now, Escalation(resolution.reason.escalation()), cancelDiagnosis = cancel)
    }
    // Without a fresh read the table in force still expires (design §8.2): no decision on an expired table.
    val expiresAt = out.state.policy?.expiresAt
    if (read == null && out.state.phase != Phase.ESCALATED && expiresAt != null && !now.isBefore(expiresAt))
        out.escalate(now, Escalation(EscalationReason.POLICY_EXPIRED), cancelDiagnosis = cancel)
    // The episode deadline holds by time too, not only by its timer: a late event must not be acted on past it.
    val deadline = out.state.episodeDeadline
    if (out.state.phase != Phase.ESCALATED && deadline != null && !now.isBefore(deadline))
        out.escalate(now, Escalation(EscalationReason.EPISODE_EXPIRED), cancelDiagnosis = cancel)
    when {
        event is EpisodeEvent.SymptomJoined -> out.join(event)
        event is EpisodeEvent.Detected -> out.ignore(event, "already open")
        event is EpisodeEvent.Closed && !wasEscalated -> out.ignore(event, "not escalated")
        // The deadline check above has normally escalated already; an early-delivered EPISODE timer still counts.
        event is EpisodeEvent.DeadlineExpired && event.which == Timer.EPISODE && !wasEscalated ->
            if (out.state.phase != Phase.ESCALATED) out.escalate(now, Escalation(EscalationReason.EPISODE_EXPIRED))
        out.state.phase == Phase.ESCALATED -> out.onEscalated(event, now)
        event is EpisodeEvent.TakenOver -> {
            out.reply = Reply.ACCEPTED
            out.escalate(now, Escalation(EscalationReason.TAKEN_OVER, "by ${event.by.id}"))
        }
        event is EpisodeEvent.AgentOff -> out.escalate(now, Escalation(EscalationReason.AGENT_LAYER_OFF))
        event is EpisodeEvent.DeadlineExpired && event.token != out.state.token -> out.ignore(event, "stale timer")
        else -> {
            val policy = out.state.policy
            if (policy == null) out.escalate(now, Escalation(EscalationReason.POLICY_MISSING, "no table in force"))
            else out.onPhase(event, policy, now)
        }
    }
    return out.step()
}

private fun Out.onPhase(event: EpisodeEvent, policy: PolicyTable, now: Instant) = when (state.phase) {
    Phase.CORRELATING -> onCorrelating(event, policy, now)
    Phase.DIAGNOSING -> onDiagnosing(event, policy, now)
    Phase.AWAITING_APPROVAL -> onAwaitingApproval(event, policy, now)
    Phase.REVALIDATING -> onRevalidating(event, policy, now)
    Phase.UNKNOWN_PRECONDITION -> onUnknownPrecondition(event, policy, now)
    // Plan B2b-2b: DISPATCH_PENDING, DISPATCHED, AWAITING_EVIDENCE, UNKNOWN_OUTCOME.
    else -> ignore(event, "not handled in ${state.phase} yet")
}

/** ESCALATED (design §5.1): the agent takes no action; a person closes it, or its retention does. */
private fun Out.onEscalated(event: EpisodeEvent, now: Instant) {
    when {
        event is EpisodeEvent.Closed -> {
            reply = Reply.ACCEPTED
            enter(Phase.CLOSED, now, state.escalation, detail = "by ${event.by.id}: ${event.outcome}")
        }
        event is EpisodeEvent.DeadlineExpired && event.which == Timer.RETENTION && event.token == state.token ->
            enter(Phase.CLOSED, now, state.escalation, detail = "UNATTENDED")
        event is EpisodeEvent.TakenOver -> {
            reply = Reply.ACCEPTED
            ignore(event, "already with a person")
        }
        else -> ignore(event, "escalated: the agent takes no action")
    }
}

private fun Out.join(event: EpisodeEvent.SymptomJoined) {
    val symptom = event.symptom
    if (symptom.eventId in state.seenEventIds) return ignore(event, "duplicate eventId ${symptom.eventId}")
    symptom.problem()?.let { return ignore(event, it) }
    state = state.copy(symptoms = state.symptoms + symptom, seenEventIds = state.seenEventIds + symptom.eventId)
    record(RecordKind.SYMPTOM_JOINED) {
        put("eventId", symptom.eventId)
        put("kind", symptom.kind.name)
        put("observedAt", symptom.observedAt.toString())
        set<JsonNode>("line", symptom.line)
    }
}

/**
 * Accumulates one transition. [opening] is true only inside [startEpisode], so the first TRANSITION record says it
 * came from nowhere.
 *
 * The helpers are order-sensitive: each reads and rewrites [state] at call time, so `escalate` looks at the phase the
 * episode is in when it is called (whether a diagnosis is still in flight, which phase the notice names). Call it before
 * entering another phase, never after.
 */
internal class Out(var state: EpisodeState, private var opening: Boolean = true) {
    val commands = mutableListOf<Command>()
    var reply: Reply? = null

    fun nextSeq(): Long = (state.recordSeq + 1).also { state = state.copy(recordSeq = it) }

    fun record(kind: RecordKind, payload: ObjectNode.() -> Unit = {}) {
        commands += Command.Record(nextSeq(), RecordEntry(kind, mapper.createObjectNode().apply(payload)))
    }

    fun notify(kind: NoticeKind, reason: EscalationReason? = null, detail: String? = null) {
        commands += Command.Notify(Notice(kind, state.phase, reason, detail))
    }

    /**
     * Enters [phase]: a new token (older timers go stale), no state deadline until [stateTimer] sets one. [escalation]
     * is kept on the state (ESCALATED, and CLOSED after it); [detail] overrides its detail in the record.
     */
    fun enter(phase: Phase, now: Instant, escalation: Escalation? = null, detail: String? = null) {
        val from = if (opening) null else state.phase
        opening = false
        state = state.copy(phase = phase, enteredAt = now, token = state.token + 1, stateDeadline = null, escalation = escalation)
        record(RecordKind.TRANSITION) {
            put("from", from?.name)
            put("to", phase.name)
            put("reason", escalation?.reason?.name)
            put("detail", detail ?: escalation?.detail)
        }
    }

    /** The current phase's own deadline: `min(now + budget, episode deadline)` (design §5.1). */
    fun stateTimer(now: Instant, budgetMs: Long) {
        val until = state.episodeDeadline?.let { waitUntil(now, budgetMs, it) } ?: now.plusMillis(budgetMs)
        state = state.copy(stateDeadline = until)
        commands += Command.SetTimer(Timer.STATE, until, state.token)
    }

    fun step(): Step = Step(state, commands.toList(), reply)
}

internal fun Out.ignore(event: EpisodeEvent, why: String) {
    record(RecordKind.IGNORED) {
        put("event", event::class.simpleName)
        put("phase", state.phase.name)
        put("why", why)
        // A late diagnosis is still the model's answer: keep it verbatim (design §13).
        if (event is EpisodeEvent.DiagnosisReturned) put("json", event.json)
        // Who sent an Update that did not apply (the PoC has no authentication, design §7.2).
        event.approver()?.let {
            put("by", it.id)
            put("assurance", it.assurance.name)
        }
    }
    if (reply == null) reply = event.refusal()
}

/**
 * ESCALATED (design §5.1): cancels a diagnosis in flight unless the escalation IS the diagnosis result, closes the
 * attempt into the history, and starts the retention clock from now (not from the episode deadline).
 */
internal fun Out.escalate(now: Instant, escalation: Escalation, approval: ApprovalSummary? = null, cancelDiagnosis: Boolean = true) {
    if (cancelDiagnosis && state.phase == Phase.DIAGNOSING) commands += Command.CancelDiagnosis
    closeAttempt(ClosedAs.ESCALATED, now, approval)
    enter(Phase.ESCALATED, now, escalation)
    commands += Command.SetTimer(Timer.RETENTION, escalatedUntil(now, state.policy), state.token)
    notify(NoticeKind.ESCALATED, escalation.reason, escalation.detail)
}

/** Moves the attempt in progress into the history (design §9.4). [approval] overrides what the attempt itself knows. */
internal fun Out.closeAttempt(closedAs: ClosedAs, now: Instant, approval: ApprovalSummary? = null) {
    val a = state.attempt ?: return
    val approvalSummary = approval ?: a.approval?.let { ApprovalSummary(ApprovalResult.APPROVED, it.by, null, it.at) }
    val entry = HistoryEntry(a.number, a.request.candidatesVersion, a.diagnosisSummary(), approvalSummary, null, null, closedAs, now)
    state = state.copy(attempt = null, history = state.history + entry)
    record(RecordKind.ATTEMPT_CLOSED) { setAll<ObjectNode>(entry.toJson()) }
}

/** Takes the resolved table into the state. [announce] = false defers the POLICY_REJECTED notice to [announceRejected]. */
internal fun Out.applyPolicy(resolution: PolicyResolution, announce: Boolean = true) {
    // A read that brings another table version than the one in state is an audit event. The very first table is not: there
    // is nothing it replaces, and the OPENED record names it.
    val adopted = resolution.adopt
    val before = state.policy
    if (adopted != null && before != null && adopted.version != before.version) {
        record(RecordKind.POLICY_ADOPTED) {
            put("version", adopted.version)
            put("previousVersion", before.version)
        }
    }
    state = when (resolution) {
        is PolicyResolution.Active -> state.copy(policy = resolution.policy, autoApproveSuspended = resolution.autoApproveSuspended)
        is PolicyResolution.Off -> state.copy(policy = resolution.policy ?: state.policy)
    }
    if (announce) announceRejected(resolution.rejectedErrors)
}

/** Records and announces a rejected policy read once per distinct error list (design §8.2 POLICY_REJECTED). */
internal fun Out.announceRejected(errors: List<String>) {
    if (errors.isNotEmpty() && errors != state.lastRejected) {
        record(RecordKind.POLICY_REJECTED) { putArray("errors").apply { errors.forEach { add(it) } } }
        notify(NoticeKind.POLICY_REJECTED, detail = errors.joinToString("; "))
    }
    state = state.copy(lastRejected = errors)
}

private fun Attempt.diagnosisSummary(): DiagnosisSummary? = when (val v = verdict) {
    null -> null
    is DiagnosisVerdict.Proposed -> DiagnosisSummary(DiagnosisOutcome.RECOMMENDED, v.candidate.candidateId, null)
    is DiagnosisVerdict.Escalate -> v.response?.let { DiagnosisSummary(it.outcome, it.candidateId, it.picked) }
}

private fun PolicyOffReason.escalation(): EscalationReason = when (this) {
    PolicyOffReason.POLICY_MISSING -> EscalationReason.POLICY_MISSING
    PolicyOffReason.POLICY_EXPIRED -> EscalationReason.POLICY_EXPIRED
    PolicyOffReason.AGENT_LAYER_OFF -> EscalationReason.AGENT_LAYER_OFF
}

private fun EpisodeEvent.approver(): Approver? = when (this) {
    is EpisodeEvent.ApprovalDecided -> by
    is EpisodeEvent.ConfirmedPrecondition -> by
    is EpisodeEvent.TakenOver -> by
    is EpisodeEvent.Closed -> by
    else -> null
}

/** [json] as a tree for an audit record; text that does not parse is kept as a string instead — never lost, never a crash. */
private fun rawJson(json: String): JsonNode = try {
    mapper.readTree(json) ?: TextNode.valueOf(json)
} catch (e: Exception) {
    TextNode.valueOf(json)
}

private fun EpisodeEvent.isUpdate(): Boolean =
    this is EpisodeEvent.ApprovalDecided || this is EpisodeEvent.ConfirmedPrecondition ||
        this is EpisodeEvent.TakenOver || this is EpisodeEvent.Closed

/** The value an Update gets when its event does not apply here (design §7.2). Signals get none. */
private fun EpisodeEvent.refusal(): Reply? = when (this) {
    is EpisodeEvent.ApprovalDecided -> Reply.REFUSED_NOT_AWAITING
    is EpisodeEvent.ConfirmedPrecondition -> Reply.REFUSED_SUBJECT_NOT_UNKNOWN
    is EpisodeEvent.Closed -> Reply.REFUSED_NOT_ESCALATED
    else -> null
}
