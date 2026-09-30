package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/** narrator letters the offered candidates A..Z (narrator `diagnose/context.py` `aliases`); more cannot be offered. */
const val MAX_OFFERED_CANDIDATES = 26

internal fun Out.onCorrelating(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    if (event is EpisodeEvent.DeadlineExpired && event.which == Timer.STATE) enterDiagnosing(policy, now)
    else ignore(event, "correlating")
}

/**
 * Enters DIAGNOSING for the next attempt (design §5.2). On every entry — joined symptoms can change each of them — the
 * attempt count, a WITHHELD search line, the snapshot size and the number of offered candidates are checked; any
 * failing check escalates without calling `diagnose`.
 */
internal fun Out.enterDiagnosing(policy: PolicyTable, now: Instant) {
    val number = (state.history.maxOfOrNull { it.attempt } ?: 0) + 1
    if (number > policy.maxAttemptsPerEpisode)
        return escalate(now, Escalation(EscalationReason.ATTEMPTS_EXHAUSTED, "attempt $number > ${policy.maxAttemptsPerEpisode}"))
    val snapshot = try {
        state.snapshot()
    } catch (e: Exception) {
        return escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, "snapshot: ${e.message}"))
    }
    if (snapshot.searches.any { it.text("outcome") == "WITHHELD" })
        return escalate(now, Escalation(EscalationReason.WITHHELD_HUMAN_FIRST))
    val bytes = try {
        mapper.writeValueAsBytes(snapshot.toJson()).size
    } catch (e: Exception) {
        return escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, "snapshot: ${e.message}"))
    }
    if (bytes > policy.snapshotMaxBytes)
        return escalate(now, Escalation(EscalationReason.SNAPSHOT_TOO_LARGE, "$bytes bytes > ${policy.snapshotMaxBytes}"))
    val request = try {
        diagnosisRequest(state.instanceId, number, snapshot, history = state.history.map { it.toJson() })
    } catch (e: Exception) {
        return escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, "request: ${e.message}"))
    }
    val offered = request.candidates.count { it.kind != CandidateKind.ESCALATE }
    if (offered > MAX_OFFERED_CANDIDATES)
        return escalate(now, Escalation(EscalationReason.CANDIDATES_TOO_MANY, "$offered candidates besides ESCALATE"))
    state = state.copy(attempt = Attempt(number, request, state.symptoms.size))
    enter(Phase.DIAGNOSING, now)
    record(RecordKind.DIAGNOSIS_REQUESTED) {
        put("attempt", number)
        put("candidatesVersion", request.candidatesVersion)
    }
    commands += Command.Diagnose(request, policy.diagnosis)
}

internal fun Out.onDiagnosing(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    val attempt = state.attempt ?: return ignore(event, "no attempt in progress")
    when (event) {
        is EpisodeEvent.DiagnosisReturned -> {
            val verdict = judgeDiagnosis(attempt.request, parseDiagnosisResponse(event.json))
            state = state.copy(attempt = attempt.copy(verdict = verdict))
            record(RecordKind.DIAGNOSIS_RESULT) {
                put("attempt", attempt.number)
                when (verdict) {
                    is DiagnosisVerdict.Proposed -> {
                        put("verdict", "PROPOSED")
                        put("candidateId", verdict.candidate.candidateId)
                        putNull("reason")
                        put("clean", verdict.clean)
                        putNull("detail")
                        set<JsonNode>("response", verdict.response.raw)
                    }
                    is DiagnosisVerdict.Escalate -> {
                        put("verdict", "ESCALATE")
                        put("candidateId", verdict.response?.candidateId)
                        put("reason", verdict.reason.name)
                        putNull("clean")
                        put("detail", verdict.detail)
                        set<JsonNode>("response", verdict.response?.raw ?: verdict.raw)
                        put("rawText", verdict.rawText)
                    }
                }
            }
            when (verdict) {
                is DiagnosisVerdict.Proposed -> propose(verdict, policy, now)
                is DiagnosisVerdict.Escalate -> escalate(
                    now,
                    Escalation(verdict.reason.escalation(), verdict.detail ?: verdict.picked?.let { "picked: $it" }),
                    cancelDiagnosis = false,
                )
            }
        }
        is EpisodeEvent.DiagnosisFailed -> escalate(
            now,
            Escalation(if (event.expired) EscalationReason.DIAGNOSIS_EXPIRED else EscalationReason.DIAGNOSIS_FAILED, event.message),
            cancelDiagnosis = false,
        )
        else -> ignore(event, "diagnosing")
    }
}

/**
 * PROPOSED (design §5.2), decided within the same step: a remedy dispatched too often escalates; person-task kinds
 * have no approval step; the policy auto-approves only a clean APPROVE_REMEDY on its skill allowlist, and never while
 * its auto-approval is suspended; everything else waits for a person.
 */
private fun Out.propose(proposed: DiagnosisVerdict.Proposed, policy: PolicyTable, now: Instant) {
    val candidate = proposed.candidate
    state = state.copy(attempt = state.attempt?.copy(proposedAt = now))
    val dispatched = state.dispatchCounts[candidate.candidateId] ?: 0
    val route = when {
        dispatched >= policy.repeatedRemedyThreshold -> Route.REPEATED_REMEDY
        candidate.kind == CandidateKind.CHOOSE_SOURCE || candidate.kind == CandidateKind.OPERATOR_DECISION -> Route.PERSON_TASK
        autoApproves(candidate, proposed.clean, policy) -> Route.POLICY
        else -> Route.AWAITING_APPROVAL
    }
    record(RecordKind.PROPOSED) {
        put("candidateId", candidate.candidateId)
        put("clean", proposed.clean)
        put("route", route.name)
        put("policyVersion", policy.version)
    }
    when (route) {
        Route.REPEATED_REMEDY -> escalate(
            now,
            Escalation(EscalationReason.REPEATED_REMEDY, "${candidate.candidateId} dispatched $dispatched times"),
            cancelDiagnosis = false,
        )
        Route.PERSON_TASK -> enterRevalidating(null, policy, now)
        Route.POLICY -> enterRevalidating(Approval(ApprovalBy.POLICY, null, now), policy, now)
        Route.AWAITING_APPROVAL -> awaitApproval(candidate, policy, now)
    }
}

/** Where a proposal goes (the PROPOSED record's `route`). */
private enum class Route { REPEATED_REMEDY, PERSON_TASK, POLICY, AWAITING_APPROVAL }

internal fun Out.awaitApproval(candidate: Candidate, policy: PolicyTable, now: Instant) {
    enter(Phase.AWAITING_APPROVAL, now)
    stateTimer(now, policy.deadlines.approvalMs)
    notify(NoticeKind.APPROVAL_NEEDED, detail = candidate.candidateId)
}

/** Whether the policy in force auto-approves [candidate] right now (design §5.2 PROPOSED, §9.5, §10.1). */
internal fun Out.autoApproves(candidate: Candidate, clean: Boolean, policy: PolicyTable): Boolean {
    if (state.autoApproveSuspended || candidate.kind != CandidateKind.APPROVE_REMEDY) return false
    val rule = policy.autoApprove[candidate.kind] ?: return false
    val skills = candidate.sawSkillTypes.orEmpty()
    return rule.allowed && skills.isNotEmpty() && rule.skills.containsAll(skills) && (!rule.requireClean || clean)
}

internal fun Out.onAwaitingApproval(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    val attempt = state.attempt ?: return ignore(event, "no attempt in progress")
    when {
        event is EpisodeEvent.ApprovalDecided -> {
            if (event.proposalId != attempt.proposalId || event.sawCandidatesVersion != attempt.request.candidatesVersion) {
                reply = Reply.REFUSED_STALE
                return ignore(event, "stale: ${event.proposalId} / ${event.sawCandidatesVersion}")
            }
            reply = Reply.ACCEPTED
            if (event.approve) enterRevalidating(Approval(ApprovalBy.PERSON, event.by, now), policy, now, note = event.note)
            else {
                val reason = event.reason ?: RejectReason.OTHER
                // The operator's own words live here, in the audit record, and never in the history narrator reads.
                record(RecordKind.APPROVAL) {
                    put("candidateId", attempt.candidate?.candidateId)
                    put("result", ApprovalResult.REJECTED.name)
                    put("reason", reason.name)
                    put("by", event.by.id)
                    put("assurance", event.by.assurance.name)
                    put("note", event.note)
                    put("policyVersion", policy.version)
                }
                rediagnose(
                    RediagnoseReason.APPROVAL_REJECTED, policy, now,
                    ApprovalSummary(ApprovalResult.REJECTED, ApprovalBy.PERSON, reason, now),
                    detail = listOfNotNull("by ${event.by.id}", event.note).joinToString(": "),
                )
            }
        }
        event is EpisodeEvent.DeadlineExpired && event.which == Timer.STATE -> escalate(
            now,
            Escalation(EscalationReason.APPROVAL_EXPIRED),
            approval = ApprovalSummary(ApprovalResult.EXPIRED, null, null, now),
        )
        else -> ignore(event, "awaiting approval")
    }
}

/** Closes the attempt as REDIAGNOSE and diagnoses again (design §5.1, §5.2). */
internal fun Out.rediagnose(
    reason: RediagnoseReason,
    policy: PolicyTable,
    now: Instant,
    approval: ApprovalSummary? = null,
    detail: String? = null,
) {
    closeAttempt(ClosedAs.REDIAGNOSE, now, approval)
    record(RecordKind.REDIAGNOSE) {
        put("reason", reason.name)
        put("detail", detail)
    }
    enterDiagnosing(policy, now)
}

/**
 * REVALIDATING: re-reads the source before anything is dispatched (design §8.3). [approval] null = person task. The
 * approval's validity is fixed here, from the table in force now ([Attempt.validUntil]): a later table can shorten it but
 * never stretch it. [note] is the approver's free text, for the audit record only.
 */
internal fun Out.enterRevalidating(approval: Approval?, policy: PolicyTable, now: Instant, note: String? = null) {
    val attempt = state.attempt
    val candidate = attempt?.candidate
        ?: return escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, "internal: no proposed candidate"))
    val basis = approval?.at ?: attempt.proposedAt ?: now
    state = state.copy(attempt = attempt.copy(approval = approval, validUntil = basis.plusMillis(policy.deadlines.approvalValidityMs)))
    if (approval != null) record(RecordKind.APPROVAL) {
        put("candidateId", candidate.candidateId)
        put("result", ApprovalResult.APPROVED.name)
        put("by", approval.by.name)
        put("approver", approval.approver?.id)
        put("assurance", approval.approver?.assurance?.name)
        put("note", note)
        put("policyVersion", policy.version)
    }
    enter(Phase.REVALIDATING, now)
    commands += Command.Revalidate(candidate, policy.deadlines.revalidateMs, state.token)
}

private fun DiagnosisEscalation.escalation(): EscalationReason = when (this) {
    DiagnosisEscalation.ESCALATE_RECOMMENDED -> EscalationReason.ESCALATE_RECOMMENDED
    DiagnosisEscalation.NO_GROUNDS -> EscalationReason.NO_GROUNDS
    DiagnosisEscalation.UNCITED -> EscalationReason.UNCITED
    DiagnosisEscalation.OUT_OF_CANDIDATES -> EscalationReason.OUT_OF_CANDIDATES
    DiagnosisEscalation.CANDIDATES_VERSION_MISMATCH -> EscalationReason.CANDIDATES_VERSION_MISMATCH
    DiagnosisEscalation.UNKNOWN_BLOCKS_EXECUTION -> EscalationReason.UNKNOWN_BLOCKS_EXECUTION
    DiagnosisEscalation.DIAGNOSIS_FAILED -> EscalationReason.DIAGNOSIS_FAILED
}
