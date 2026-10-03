package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/** The approval window's wire schema this module reads (picasso `ApprovalWire.SCHEMA_VERSION`, ADR 48, main 3d6530a). */
const val PICASSO_APPROVAL_SCHEMA = "4"

/**
 * Every refusal value of the approval window's schema "4" (picasso `ApprovalRefusal`, `docs/orchestration.md` §7.3, read
 * at picasso main 3d6530a): schema 2's fifteen and CONSUMED (ADR 46). A value outside this set is not understood and
 * goes to a person (design §8.4).
 */
val PICASSO_REFUSALS: Set<String> = setOf(
    "WITHHELD", "NO_PROPOSAL", "CONSUMED", "PROPOSAL_CHANGED", "NOT_DECLARED", "REVOKED", "EXPIRED", "ROBOT_OUT_OF_SCOPE",
    "SKILL_OUT_OF_SCOPE", "VALUE_NOT_DECLARED", "OBJECT_NOT_OBSERVED", "DECLARED_CONTRADICTS_OBSERVED",
    "CAPABILITY_UNKNOWN", "VALUES_NOT_ACCEPTED", "REFUSED_BY_GATE", "REMEDY_NOT_APPLIED",
)

/**
 * Refusals that fold "my earlier attempt may already have landed" (design §11): UNKNOWN(OUTCOME), never a retry.
 * CONSUMED is read the same way: its record names who consumed the proposal, but koshei does not narrow on it (§19 B).
 */
private val MAY_HAVE_LANDED = setOf("NO_PROPOSAL", "REMEDY_NOT_APPLIED", "CONSUMED")

/** What a dispatch result means for the episode (design §5.2 DISPATCHED rows). */
internal sealed interface DispatchJudgement {
    /** The word the audit record uses for this judgement (design §13); fixed, not a class name. */
    val label: String

    /**
     * picasso approved. [instanceId] and [executionId] together name this attempt across picasso restarts (ADR 48);
     * [units] are the `unitId` of every step, in order — what a JobResponse calls them; [delivered] is `steps`, what
     * actually went out (picasso §7.3).
     */
    class Accepted(val instanceId: String, val executionId: String, units: List<String>, delivered: JsonNode) : DispatchJudgement {
        override val label: String get() = "ACCEPTED"
        val units: List<String> = units.toList()
        val delivered: JsonNode = delivered.deepCopy()
    }

    data object PersonTask : DispatchJudgement {
        override val label: String get() = "PERSON_TASK"
    }

    /** [consumedExecutionId]: for CONSUMED, the execution its consumption record names — recorded, not linked (§19 B). */
    data class MayHaveLanded(val refusal: String, val consumedExecutionId: String? = null) : DispatchJudgement {
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
 * Reads the approval window's answer (design §8.4): `schemaVersion` first, then `instanceId` (every schema-4 answer
 * carries it), then `outcome`; branches on the `refusal` value only, never on the prose `reason`. Read with the module's
 * strict mapper, so a duplicated key is not understood.
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
    val instanceId = node.text("instanceId")?.takeIf { it.isNotBlank() }
        ?: return DispatchJudgement.AnswerUnknown("no instanceId")
    return when (val outcome = node.text("outcome")) {
        "APPROVED" -> approved(node, instanceId)
        "REFUSED" -> {
            val refusal = node.text("refusal")
            when {
                refusal == null || refusal !in PICASSO_REFUSALS -> DispatchJudgement.AnswerUnknown("refusal $refusal")
                refusal in MAY_HAVE_LANDED -> DispatchJudgement.MayHaveLanded(
                    refusal,
                    if (refusal == "CONSUMED") node.get("consumed")?.text("executionId")?.takeIf { it.isNotBlank() } else null,
                )
                else -> DispatchJudgement.Refused(refusal)
            }
        }
        else -> DispatchJudgement.AnswerUnknown("outcome $outcome")
    }
}

/**
 * An APPROVED answer: an execution and at least one step, each naming its own unit (ADR 48). picasso approves only
 * a non-empty remedy (§7.1 `sawSkillTypes` is never empty), so no steps means an answer koshei does not understand —
 * and an attempt with no units could never be judged DONE.
 */
private fun approved(node: JsonNode, instanceId: String): DispatchJudgement {
    val executionId = node.text("executionId")?.takeIf { it.isNotBlank() }
    val steps = node.get("steps")?.takeIf { it.isArray && it.size() > 0 }
    if (executionId == null || steps == null) return DispatchJudgement.AnswerUnknown("APPROVED without executionId or steps")
    val units = steps.map { it.text("unitId")?.takeIf { u -> u.isNotBlank() } }
    if (units.any { it == null } || units.toSet().size != units.size)
        return DispatchJudgement.AnswerUnknown("APPROVED steps without a unitId each, or with one twice")
    return DispatchJudgement.Accepted(instanceId, executionId, units.filterNotNull(), steps)
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
            if (e.reportKey in state.seenReports) recordEvidence(e, counted = false, why = "duplicate jobResponseId")
            else {
                // Seen now, so a redelivered copy after the answer cannot count either.
                state = state.copy(seenReports = state.seenReports + e.reportKey)
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
    // One execution is (instanceId, executionId) (ADR 48): unique per attempt, also across picasso restarts. Should picasso
    // give the same pair twice, nothing may link to it (fail closed) — a report of the first attempt could resolve this one.
    val execution = (judgement as? DispatchJudgement.Accepted)?.let { "${it.instanceId}/${it.executionId}" }
    val reused = execution != null && execution in state.usedExecutions
    record(RecordKind.DISPATCH_RESULT) {
        put("judgement", judgement.label)
        if (result is DispatchResult.Answer) put("answer", result.json)
        if (result is DispatchResult.Uncertain) put("detail", result.message)
        if (judgement is DispatchJudgement.MayHaveLanded && judgement.consumedExecutionId != null)
            put("consumedExecutionId", judgement.consumedExecutionId)
        if (reused) put("executionIdReused", true)
    }
    fun summary(value: String, delivered: JsonNode? = null) = DispatchSummary(value, intent.idempotencyKey, approverKindOf(intent), delivered)
    when (judgement) {
        is DispatchJudgement.Accepted -> {
            state = state.copy(
                attempt = attempt.copy(
                    executionId = if (reused) null else judgement.executionId,
                    picassoInstanceId = if (reused) null else judgement.instanceId,
                    approvedUnits = judgement.units,
                    dispatch = summary("ACCEPTED", judgement.delivered),
                ),
                usedExecutions = state.usedExecutions + execution!!,
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
