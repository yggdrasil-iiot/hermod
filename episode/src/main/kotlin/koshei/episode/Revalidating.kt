package koshei.episode

import java.time.Instant

internal fun Out.onRevalidating(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    when {
        event is EpisodeEvent.Revalidated && event.token == state.token -> onPrecondition(event.result, "REVALIDATE", policy, now)
        event is EpisodeEvent.Revalidated -> ignore(event, "stale revalidation")
        else -> ignore(event, "revalidating")
    }
}

/**
 * UNKNOWN(PRECONDITION) (design §5.1): execution stays blocked; a periodic recheck, a person's confirmation of the
 * proposition (§8.3), or `unknownMs` ends it.
 */
internal fun Out.onUnknownPrecondition(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    val attempt = state.attempt
    val candidate = attempt?.candidate
        ?: return escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, "internal: no proposed candidate"))
    when {
        event is EpisodeEvent.Revalidated && event.token == state.token -> onPrecondition(event.result, "REVALIDATE", policy, now)
        event is EpisodeEvent.Revalidated -> ignore(event, "stale revalidation")
        // The same remedy can be proposed again by a later attempt: a card from the earlier one must not decide this one.
        event is EpisodeEvent.ConfirmedPrecondition && event.candidateId == candidate.candidateId && event.proposalId != attempt.proposalId -> {
            reply = Reply.REFUSED_STALE
            ignore(event, "stale confirmation: ${event.proposalId}")
        }
        event is EpisodeEvent.ConfirmedPrecondition && event.candidateId == candidate.candidateId -> {
            reply = Reply.ACCEPTED
            record(RecordKind.CONFIRMATION) {
                put("candidateId", candidate.candidateId)
                put("proposalId", event.proposalId)
                put("holds", event.holds)
                put("by", event.by.id)
                put("assurance", event.by.assurance.name)
                put("proposition", event.proposition)
            }
            onPrecondition(if (event.holds) TriState.TRUE else TriState.FALSE, "PERSON", policy, now)
        }
        event is EpisodeEvent.DeadlineExpired && event.which == Timer.RECHECK -> {
            record(RecordKind.RECHECK) { put("candidateId", candidate.candidateId) }
            commands += Command.Revalidate(candidate, policy.deadlines.revalidateMs, state.token)
        }
        event is EpisodeEvent.DeadlineExpired && event.which == Timer.STATE ->
            escalate(now, Escalation(EscalationReason.UNKNOWN_UNRESOLVED, "precondition of ${candidate.candidateId}"))
        else -> ignore(event, "precondition unknown")
    }
}

/**
 * One precondition verdict (design §5.2 REVALIDATING rows, §5.1 UNKNOWN(PRECONDITION) row): TRUE records the dispatch
 * intent — a person's TRUE does not revalidate again; FALSE re-diagnoses if a symptom joined after this attempt's
 * snapshot, otherwise the proposal is superseded; UNKNOWN waits.
 */
private fun Out.onPrecondition(result: TriState, source: String, policy: PolicyTable, now: Instant) {
    record(RecordKind.REVALIDATION) {
        put("result", result.name)
        put("source", source)   // REVALIDATE (the activity) or PERSON (a confirmation)
    }
    when (result) {
        TriState.TRUE -> proceedToDispatch(policy, now)
        TriState.FALSE -> {
            val attempt = state.attempt
            if (attempt != null && state.symptoms.size > attempt.symptomCount)
                rediagnose(RediagnoseReason.PRECONDITION_BROKEN, policy, now, detail = "a symptom joined after the diagnosis")
            else {
                closeAttempt(ClosedAs.SUPERSEDED, now)
                enter(Phase.SUPERSEDED, now)
            }
        }
        TriState.UNKNOWN ->
            if (state.phase == Phase.UNKNOWN_PRECONDITION) recheckTimer(policy, now)
            else {
                enter(Phase.UNKNOWN_PRECONDITION, now)
                stateTimer(now, policy.deadlines.unknownMs)
                recheckTimer(policy, now)
                notify(NoticeKind.CONFIRM_PRECONDITION, detail = state.attempt?.candidate?.candidateId)
            }
    }
}

private fun Out.recheckTimer(policy: PolicyTable, now: Instant) {
    val next = now.plusMillis(policy.deadlines.unknownRecheckMs)
    val at = state.stateDeadline?.let { minOf(next, it) } ?: next
    commands += Command.SetTimer(Timer.RECHECK, at, state.token)
}

/**
 * The gate before the execution intent, and again before `Dispatch` (design §5.1, §6). The approval (or, for a person
 * task, the proposal) must still be valid — both the window fixed when it was given and the window of the table in
 * force; a POLICY approval must still be one the table in force would give — the table may have turned auto-approval
 * off or been rejected meanwhile (R6, §8.2), and then a person approves instead; anything unknown right now — symptoms
 * that joined after the diagnosis, the episode's own unknowns — blocks an execution-class candidate (§6, the second line
 * of defence after the projection). Returns true when the attempt may go on; otherwise the episode has already moved
 * (a new diagnosis, back to a person, or ESCALATED).
 */
internal fun Out.passesDispatchGate(policy: PolicyTable, now: Instant): Boolean {
    val attempt = state.attempt
    val candidate = attempt?.candidate
    val basis = attempt?.approval?.at ?: attempt?.proposedAt
    if (attempt == null || candidate == null || basis == null) {
        escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, "internal: no proposed candidate"))
        return false
    }
    // Valid only if both the window fixed when the approval was given and the window of the table in force now allow it.
    val fixedUntil = attempt.validUntil
    if (!approvalStillValid(basis, now, policy) || (fixedUntil != null && !now.isBefore(fixedUntil))) {
        val until = listOfNotNull(basis.plusMillis(policy.deadlines.approvalValidityMs), fixedUntil).min()
        rediagnose(RediagnoseReason.APPROVAL_LAPSED, policy, now, detail = "valid until $until")
        return false
    }
    val clean = (attempt.verdict as? DiagnosisVerdict.Proposed)?.clean ?: false
    if (attempt.approval?.by == ApprovalBy.POLICY && !autoApproves(candidate, clean, policy)) {
        record(RecordKind.APPROVAL) {
            put("candidateId", candidate.candidateId)
            put("result", ApprovalRecordResult.REVOKED.name)
            put("by", ApprovalBy.POLICY.name)
            put("policyVersion", policy.version)
        }
        state = state.copy(attempt = attempt.copy(approval = null, validUntil = null))
        awaitApproval(candidate, policy, now)
        return false
    }
    // Fail-safe: if the unknowns cannot even be computed, treat something as unknown.
    val somethingUnknown = try { state.currentUnknowns().isNotEmpty() } catch (e: Exception) { true }
    if (candidate.executionClass && somethingUnknown) {
        escalate(now, Escalation(EscalationReason.UNKNOWN_BLOCKS_EXECUTION, "unknown after the diagnosis: ${candidate.candidateId}"))
        return false
    }
    // The table in force now may have a lower threshold than the one that proposed this attempt (design §5.2).
    val dispatched = state.dispatchCounts[candidate.candidateId] ?: 0
    if (dispatched >= policy.repeatedRemedyThreshold) {
        escalate(now, Escalation(EscalationReason.REPEATED_REMEDY, "${candidate.candidateId} dispatched $dispatched times"))
        return false
    }
    return true
}

/** REVALIDATING / UNKNOWN(PRECONDITION) → DISPATCH_PENDING: records the execution intent; `Dispatch` follows only its `Recorded`. */
private fun Out.proceedToDispatch(policy: PolicyTable, now: Instant) {
    if (!passesDispatchGate(policy, now)) return
    val attempt = state.attempt ?: return
    val candidate = attempt.candidate ?: return
    enter(Phase.DISPATCH_PENDING, now)
    val seq = nextSeq()
    val intent = DispatchIntent(candidate, "${state.instanceId}:${attempt.number}:${candidate.kind.name}", attempt.approval)
    state = state.copy(attempt = attempt.copy(intentSeq = seq, intent = intent))
    commands += Command.RecordIntent(seq, intent, policy.deadlines.recordMs)
}
