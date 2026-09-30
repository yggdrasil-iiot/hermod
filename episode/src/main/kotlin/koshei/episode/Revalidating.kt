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
    val candidate = state.attempt?.candidate
        ?: return escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, "internal: no proposed candidate"))
    when {
        event is EpisodeEvent.Revalidated && event.token == state.token -> onPrecondition(event.result, "REVALIDATE", policy, now)
        event is EpisodeEvent.Revalidated -> ignore(event, "stale revalidation")
        event is EpisodeEvent.ConfirmedPrecondition && event.candidateId == candidate.candidateId -> {
            reply = Reply.ACCEPTED
            record(RecordKind.CONFIRMATION) {
                put("candidateId", candidate.candidateId)
                put("holds", event.holds)
                put("by", event.by.id)
                put("assurance", event.by.assurance.name)
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
 * The last gate before DISPATCH_PENDING. The approval (or, for a person task, the proposal) must still be valid
 * (design §5.1); a POLICY approval must still be one the table in force would give — the table may have turned
 * auto-approval off or been rejected meanwhile (R6, §8.2), and then a person approves instead; anything unknown right
 * now — including symptoms that joined after the diagnosis — blocks an execution-class candidate (§6, the second line
 * of defence after the projection). Then the execution intent is recorded; `Dispatch` follows only its `Recorded`
 * (plan B2b-2b, which repeats these checks there).
 */
private fun Out.proceedToDispatch(policy: PolicyTable, now: Instant) {
    val attempt = state.attempt
    val candidate = attempt?.candidate
    val basis = attempt?.approval?.at ?: attempt?.proposedAt
    if (attempt == null || candidate == null || basis == null)
        return escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, "internal: no proposed candidate"))
    if (!approvalStillValid(basis, now, policy))
        return rediagnose(RediagnoseReason.APPROVAL_LAPSED, policy, now, detail = "valid until ${basis.plusMillis(policy.deadlines.approvalValidityMs)}")
    val clean = (attempt.verdict as? DiagnosisVerdict.Proposed)?.clean ?: false
    if (attempt.approval?.by == ApprovalBy.POLICY && !autoApproves(candidate, clean, policy)) {
        record(RecordKind.APPROVAL) {
            put("candidateId", candidate.candidateId)
            put("by", ApprovalBy.POLICY.name)
            put("revoked", true)
        }
        state = state.copy(attempt = attempt.copy(approval = null))
        return awaitApproval(candidate, policy, now)
    }
    // Fail-safe: if the unknowns cannot even be computed, treat something as unknown.
    val somethingUnknown = try { state.currentUnknowns().isNotEmpty() } catch (e: Exception) { true }
    if (candidate.executionClass && somethingUnknown)
        return escalate(now, Escalation(EscalationReason.UNKNOWN_BLOCKS_EXECUTION, "unknown after the diagnosis: ${candidate.candidateId}"))
    enter(Phase.DISPATCH_PENDING, now)
    val seq = nextSeq()
    val intent = DispatchIntent(candidate, "${state.instanceId}:${attempt.number}:${candidate.kind.name}", attempt.approval)
    state = state.copy(attempt = attempt.copy(intentSeq = seq))
    commands += Command.RecordIntent(seq, intent, policy.deadlines.recordMs)
}
