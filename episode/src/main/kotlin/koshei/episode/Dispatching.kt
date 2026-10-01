package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/** The approval window's wire schema this module reads (picasso `ApprovalWire.SCHEMA_VERSION`). */
const val PICASSO_APPROVAL_SCHEMA = "2"

/**
 * Every refusal value of the approval window's schema "2" (picasso `ApprovalRefusal`, `docs/orchestration.md` §7.3, read
 * at picasso main 584c6cd). A value outside this set is not understood and goes to a person (design §8.4).
 */
val PICASSO_REFUSALS_V2: Set<String> = setOf(
    "WITHHELD", "NO_PROPOSAL", "PROPOSAL_CHANGED", "NOT_DECLARED", "REVOKED", "EXPIRED", "ROBOT_OUT_OF_SCOPE",
    "SKILL_OUT_OF_SCOPE", "VALUE_NOT_DECLARED", "OBJECT_NOT_OBSERVED", "DECLARED_CONTRADICTS_OBSERVED",
    "CAPABILITY_UNKNOWN", "VALUES_NOT_ACCEPTED", "REFUSED_BY_GATE", "REMEDY_NOT_APPLIED",
)

/** Refusals that fold "my earlier attempt may already have landed" (design §11): UNKNOWN(OUTCOME), never a retry. */
private val MAY_HAVE_LANDED = setOf("NO_PROPOSAL", "REMEDY_NOT_APPLIED")

/** What a dispatch result means for the episode (design §5.2 DISPATCHED rows). */
internal sealed interface DispatchJudgement {
    /** The word the audit record uses for this judgement (design §13); fixed, not a class name. */
    val label: String

    /** picasso approved; [delivered] is its `steps`, what actually went out (picasso §7.3). */
    class Accepted(val executionId: String, delivered: JsonNode) : DispatchJudgement {
        override val label: String get() = "ACCEPTED"
        val delivered: JsonNode = delivered.deepCopy()
    }

    data object PersonTask : DispatchJudgement {
        override val label: String get() = "PERSON_TASK"
    }

    data class MayHaveLanded(val refusal: String) : DispatchJudgement {
        override val label: String get() = "MAY_HAVE_LANDED"
    }

    data class Uncertain(val detail: String?) : DispatchJudgement {
        override val label: String get() = "UNCERTAIN"
    }

    data class Refused(val refusal: String) : DispatchJudgement {
        override val label: String get() = "REFUSED"
    }

    data class AnswerUnknown(val detail: String) : DispatchJudgement {
        override val label: String get() = "ANSWER_UNKNOWN"
    }
}

/** A picasso answer only for APPROVE_REMEDY, an issued task only for the person-task kinds; anything else is not understood. */
internal fun judgeDispatch(kind: CandidateKind, result: DispatchResult): DispatchJudgement = when (result) {
    is DispatchResult.Uncertain -> DispatchJudgement.Uncertain(result.message)
    DispatchResult.PersonTaskIssued ->
        if (kind == CandidateKind.CHOOSE_SOURCE || kind == CandidateKind.OPERATOR_DECISION) DispatchJudgement.PersonTask
        else DispatchJudgement.AnswerUnknown("a person-task answer for $kind")
    is DispatchResult.Answer ->
        if (kind == CandidateKind.APPROVE_REMEDY) judgePicassoAnswer(result.json)
        else DispatchJudgement.AnswerUnknown("a picasso answer for $kind")
}

/**
 * Reads the approval window's answer (design §8.4): `schemaVersion` first, then `outcome`; branches on the `refusal`
 * value only, never on the prose `reason`. Read with the module's strict mapper, so a duplicated key is not understood.
 */
internal fun judgePicassoAnswer(json: String): DispatchJudgement {
    val node = try {
        mapper.readTree(json)
    } catch (e: Exception) {
        return DispatchJudgement.AnswerUnknown("unreadable: ${e.message}")
    }
    if (node == null || !node.isObject) return DispatchJudgement.AnswerUnknown("not a JSON object")
    val schema = node.text("schemaVersion")
    if (schema != PICASSO_APPROVAL_SCHEMA) return DispatchJudgement.AnswerUnknown("schemaVersion $schema")
    return when (val outcome = node.text("outcome")) {
        "APPROVED" -> {
            val executionId = node.text("executionId")
            val steps = node.get("steps")
            if (executionId.isNullOrBlank() || steps == null || !steps.isArray) DispatchJudgement.AnswerUnknown("APPROVED without executionId or steps")
            else DispatchJudgement.Accepted(executionId, steps)
        }
        "REFUSED" -> {
            val refusal = node.text("refusal")
            when {
                refusal == null || refusal !in PICASSO_REFUSALS_V2 -> DispatchJudgement.AnswerUnknown("refusal $refusal")
                refusal in MAY_HAVE_LANDED -> DispatchJudgement.MayHaveLanded(refusal)
                else -> DispatchJudgement.Refused(refusal)
            }
        }
        else -> DispatchJudgement.AnswerUnknown("outcome $outcome")
    }
}

/** What koshei tells the approval window (picasso §7.1): AGENT for a POLICY approval, PERSON for a person's; none for a task. */
internal fun approverKindOf(intent: DispatchIntent): String? = when {
    intent.candidate.kind != CandidateKind.APPROVE_REMEDY -> null
    intent.approval?.by == ApprovalBy.POLICY -> "AGENT"
    else -> "PERSON"
}

/**
 * DISPATCH_PENDING (design §5.2): only this attempt's intent `Recorded` may dispatch; any other record result escalates
 * without dispatching. A JobResponse handled here came before this attempt's `Recorded` and never counts (§12).
 */
internal fun Out.onDispatchPending(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    val expected = state.attempt?.intentSeq
        ?: return escalate(now, Escalation(EscalationReason.RECORD_FAILED, "internal: no intent in DISPATCH_PENDING"))
    when (event) {
        is EpisodeEvent.Recorded ->
            if (event.seq == expected) dispatch(policy, now)
            else escalate(now, Escalation(EscalationReason.RECORD_FAILED, "Recorded(${event.seq}), expected $expected"))
        is EpisodeEvent.RecordFailed -> escalate(
            now,
            Escalation(EscalationReason.RECORD_FAILED, if (event.seq == expected) event.message else "RecordFailed(${event.seq}), expected $expected"),
        )
        is EpisodeEvent.EvidenceArrived -> {
            val e = event.evidence
            if (e.jobResponseId in state.seenJobResponseIds) recordEvidence(e, counted = false, why = "duplicate jobResponseId")
            else {
                // Seen now, so a redelivered copy after the answer cannot count either.
                state = state.copy(seenJobResponseIds = state.seenJobResponseIds + e.jobResponseId)
                recordEvidence(e, counted = false, why = "before this attempt's Recorded")
            }
        }
        else -> ignore(event, "dispatch pending")
    }
}

/** `Recorded` → `Dispatch`, after the same gate as before the intent (design §6); counts the dispatch (REPEATED_REMEDY). */
private fun Out.dispatch(policy: PolicyTable, now: Instant) {
    if (!passesDispatchGate(policy, now)) return
    val attempt = state.attempt
    val intent = attempt?.intent
        ?: return escalate(now, Escalation(EscalationReason.RECORD_FAILED, "internal: no intent to dispatch"))
    val id = intent.candidate.candidateId
    val count = (state.dispatchCounts[id] ?: 0) + 1
    val approverKind = approverKindOf(intent)
    // From here on whether it went out is not known until the answer says so; an episode that ends first must not read
    // as "never dispatched" in its history. The answer overwrites this.
    state = state.copy(
        dispatchCounts = state.dispatchCounts + (id to count),
        attempt = attempt.copy(dispatch = DispatchSummary("UNCERTAIN", intent.idempotencyKey, approverKind, null)),
    )
    enter(Phase.DISPATCHED, now)
    record(RecordKind.DISPATCH_SENT) {
        put("candidateId", id)
        put("idempotencyKey", intent.idempotencyKey)
        put("approverKind", approverKind)
        put("count", count)
    }
    commands += Command.Dispatch(intent, approverKind, policy.deadlines.dispatchMs, state.token)
}

/** DISPATCHED (design §5.2): the answer decides; a JobResponse that comes first is kept for the next phase. */
internal fun Out.onDispatched(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    val attempt = state.attempt
    val intent = attempt?.intent
        ?: return escalate(now, Escalation(EscalationReason.DISPATCH_ANSWER_UNKNOWN, "internal: no intent in DISPATCHED"))
    when {
        event is EpisodeEvent.DispatchReturned && event.token != state.token -> ignore(event, "stale dispatch result")
        event is EpisodeEvent.DispatchReturned -> onDispatchResult(attempt, intent, event.result, policy, now)
        event is EpisodeEvent.EvidenceArrived -> bufferEvidence(event.evidence)
        else -> ignore(event, "dispatched")
    }
}

private fun Out.onDispatchResult(attempt: Attempt, intent: DispatchIntent, result: DispatchResult, policy: PolicyTable, now: Instant) {
    val judgement = judgeDispatch(intent.candidate.kind, result)
    // picasso counts execution ids per process, so one can come again after a restart: then nothing may link to it.
    val reused = judgement is DispatchJudgement.Accepted && judgement.executionId in state.usedExecutionIds
    record(RecordKind.DISPATCH_RESULT) {
        put("judgement", judgement.label)
        if (result is DispatchResult.Answer) put("answer", result.json)
        if (result is DispatchResult.Uncertain) put("detail", result.message)
        if (reused) put("executionIdReused", true)
    }
    fun summary(value: String, delivered: JsonNode? = null) = DispatchSummary(value, intent.idempotencyKey, approverKindOf(intent), delivered)
    when (judgement) {
        is DispatchJudgement.Accepted -> {
            state = state.copy(
                attempt = attempt.copy(executionId = if (reused) null else judgement.executionId, dispatch = summary("ACCEPTED", judgement.delivered)),
                usedExecutionIds = state.usedExecutionIds + judgement.executionId,
            )
            enterAwaitingEvidence(policy, now)
        }
        DispatchJudgement.PersonTask -> {
            state = state.copy(attempt = attempt.copy(dispatch = summary("PERSON_TASK")))
            enterAwaitingEvidence(policy, now)
        }
        is DispatchJudgement.MayHaveLanded -> {
            state = state.copy(attempt = attempt.copy(dispatch = summary(judgement.refusal), authorUnknown = true))
            enterUnknownOutcome(policy, now, outcomeUnknown = true, detail = judgement.refusal)
        }
        is DispatchJudgement.Uncertain -> {
            state = state.copy(attempt = attempt.copy(dispatch = summary("UNCERTAIN"), authorUnknown = true))
            enterUnknownOutcome(policy, now, outcomeUnknown = true, detail = judgement.detail)
        }
        is DispatchJudgement.Refused -> {
            state = state.copy(attempt = attempt.copy(dispatch = summary(judgement.refusal)))
            escalate(now, Escalation(EscalationReason.DISPATCH_REFUSED, judgement.refusal))
        }
        is DispatchJudgement.AnswerUnknown -> {
            state = state.copy(attempt = attempt.copy(dispatch = summary("ANSWER_UNKNOWN")))
            escalate(now, Escalation(EscalationReason.DISPATCH_ANSWER_UNKNOWN, judgement.detail))
        }
    }
}
