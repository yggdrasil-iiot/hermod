package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/** picasso's `ConnectionState` name for a robot it sees online (picasso `JobResponse.connection`). Anything else blocks DONE. */
const val PICASSO_ONLINE = "CONNECTION_STATE_ONLINE"

/** ① of design §12: a JobResponse belongs to this attempt only through the execution its approval named — instance and id. */
private fun Attempt.links(e: Evidence): Boolean =
    executionId != null && picassoInstanceId != null && e.executionId == executionId && e.picassoInstanceId == picassoInstanceId

/** AWAITING_EVIDENCE: a JobResponse or a person's check decides; `evidenceMs` ends it (design §5.1, §5.2). */
internal fun Out.enterAwaitingEvidence(policy: PolicyTable, now: Instant) {
    enter(Phase.AWAITING_EVIDENCE, now)
    stateTimer(now, policy.deadlines.evidenceMs)
    replayBuffered(policy, now)
    // A remedy no JobResponse can complete (its execution was given twice, design §12) ends only by a person or the
    // deadline: say so. A person task needs no notice, the dispatch activity issued the task. After the replay, so a kept
    // doubtful report that already moved the episode to UNKNOWN(OUTCOME) does not notify twice.
    val attempt = state.attempt
    val candidate = attempt?.candidate
    if (state.phase == Phase.AWAITING_EVIDENCE && candidate?.kind == CandidateKind.APPROVE_REMEDY && attempt.executionId == null)
        notify(NoticeKind.CONFIRM_OUTCOME, detail = candidate.candidateId)
}

/**
 * UNKNOWN(OUTCOME) (design §5.1): execution stays blocked; a JobResponse, a person's check or `unknownMs` ends it — no
 * periodic recheck, picasso has no surface to ask (§11). [outcomeUnknown] adds the episode-level OUTCOME unknown for the
 * candidate (§9.4), which blocks execution candidates in the next diagnosis until a person knows the outcome.
 */
internal fun Out.enterUnknownOutcome(policy: PolicyTable, now: Instant, outcomeUnknown: Boolean, detail: String?) {
    val candidate = state.attempt?.candidate
    if (outcomeUnknown && candidate != null) state = state.copy(
        episodeUnknowns = state.episodeUnknowns +
            Unknown(mapOf("candidateId" to candidate.candidateId), UnknownWhat.OUTCOME, now.toString(), "picasso"),
    )
    enter(Phase.UNKNOWN_OUTCOME, now, detail = detail)
    stateTimer(now, policy.deadlines.unknownMs)
    notify(NoticeKind.CONFIRM_OUTCOME, detail = candidate?.candidateId)
    replayBuffered(policy, now)
}

/** DISPATCHED keeps JobResponses that come before the answer (they leave the same pump as the acceptance, design §5.2). */
internal fun Out.bufferEvidence(e: Evidence) {
    if (e.reportKey in state.seenReports) return recordEvidence(e, counted = false, why = "duplicate jobResponseId")
    state = state.copy(bufferedEvidence = state.bufferedEvidence + e, seenReports = state.seenReports + e.reportKey)
    recordEvidence(e, counted = false, why = "kept until the answer")
}

/**
 * Re-applies what DISPATCHED kept — an internal step, not a new signal, so the report dedup does not apply (§6).
 * The reports arrived together, so a doubt in any linked one holds back every resolution of the batch: a clean report must
 * not resolve the attempt just because it happened to be replayed before the doubtful one (design §12 ②). A kept batch is
 * therefore stricter than a stream: if any linked kept report is in doubt, no kept report resolves — and since those
 * reports are already marked seen, only a fresh report can resolve afterwards.
 */
private fun Out.replayBuffered(policy: PolicyTable, now: Instant) {
    val buffered = state.bufferedEvidence
    if (buffered.isEmpty()) return
    val attempt = state.attempt
    val doubtInBatch = attempt != null && buffered.any { attempt.links(it) && it.inDoubt() }
    state = state.copy(bufferedEvidence = emptyList())
    for (e in buffered) {
        if (state.phase == Phase.AWAITING_EVIDENCE || state.phase == Phase.UNKNOWN_OUTCOME)
            takeEvidence(e, replayed = true, policy, now, allowResolve = !doubtInBatch)
        else recordEvidence(e, counted = false, why = "kept, but the episode left for ${state.phase}")
    }
}

/** ② of design §12: in doubt, unverified, a person needed, or simply not reported. */
private fun Evidence.inDoubt(): Boolean {
    val doubt = inDoubtUnits
    val unverified = unverifiedUnits
    return doubt == null || unverified == null || operatorRequired != false || doubt.isNotEmpty() || unverified.isNotEmpty()
}

/**
 * One JobResponse, in the design §12 order: ① record only (duplicate, not this attempt's execution) → ② UNKNOWN (anything
 * in doubt or not reported) → ③ DONE (every approved unit completed, and picasso sees the robot online). A report that
 * says DONE and UNKNOWN at once is UNKNOWN. JobResponses never say NOT_DONE (④ — only a person does). Anything else is
 * recorded (⑤): units still to complete, or a connection other than ONLINE — `CONNECTION_STATE_UNSPECIFIED` (picasso
 * has not seen the robot yet) included: what is not known does not end the attempt. [allowResolve] is false while
 * replaying a batch that holds a doubtful report: ③ then records instead of resolving.
 * ③ reads one report's `completedUnits` as the execution's whole progress so far, as picasso writes it (the units
 * completed, not the ones completed since the last report); a reporter that sent only increments would never reach DONE.
 */
internal fun Out.takeEvidence(e: Evidence, replayed: Boolean, policy: PolicyTable, now: Instant, allowResolve: Boolean = true) {
    val attempt = state.attempt ?: return recordEvidence(e, counted = false, why = "no attempt in progress")
    if (!replayed) {
        if (e.reportKey in state.seenReports) return recordEvidence(e, counted = false, why = "duplicate jobResponseId")
        state = state.copy(seenReports = state.seenReports + e.reportKey)
    }
    // ① Linked only through the execution picasso returned for this attempt's approval. Person tasks have none (§8.4).
    if (!attempt.links(e)) return recordEvidence(e, counted = false, why = "not linked to this attempt's execution")
    // ② In doubt, unverified, a person needed, or simply not reported: UNKNOWN — never DONE, never folded into NOT_DONE.
    if (e.inDoubt()) {
        recordEvidence(e, counted = true, why = "in doubt")
        val units = (e.inDoubtUnits.orEmpty() + e.unverifiedUnits.orEmpty()).distinct()
        val subjects: List<Map<String, String?>> =
            if (units.isEmpty()) listOf(mapOf("executionId" to e.executionId))
            else units.map { mapOf("executionId" to e.executionId, "unitId" to it) }
        // Once per (subject, what): a repeated report does not grow the next request.
        val fresh = subjects.filterNot { s -> state.episodeUnknowns.any { it.subject == s && it.what == UnknownWhat.OUTCOME } }
        state = state.copy(
            attempt = attempt.copy(evidence = EvidenceSummary(e.reachedEvidence, "UNKNOWN")),
            episodeUnknowns = state.episodeUnknowns + fresh.map { Unknown(it, UnknownWhat.OUTCOME, e.receivedAt.toString(), "picasso") },
        )
        if (state.phase == Phase.AWAITING_EVIDENCE)
            enterUnknownOutcome(policy, now, outcomeUnknown = false, detail = "JobResponse ${e.jobResponseId} in doubt")
        return
    }
    // ③ DONE: every unit the approval sent completed, while picasso sees the robot online. An attempt with no units cannot
    // be judged (the answer reader refuses such an APPROVED; this guards containsAll's vacuous true).
    val completed = e.completedUnits
    if (attempt.approvedUnits.isEmpty() || completed == null || !completed.containsAll(attempt.approvedUnits))
        return recordEvidence(e, counted = false, why = "not every approved unit completed yet")
    if (e.connection != PICASSO_ONLINE)
        return recordEvidence(e, counted = false, why = "connection ${e.connection}: not DONE unless picasso sees the robot online")
    if (!allowResolve) return recordEvidence(e, counted = false, why = "not resolved: a kept report is in doubt")
    recordEvidence(e, counted = true, why = "every approved unit completed")
    resolve(EvidenceSummary(e.reachedEvidence, "DONE"), now)
}

/**
 * RESOLVED (design §5.2): the attempt closes with its evidence; after NO_PROPOSAL, REMEDY_NOT_APPLIED, CONSUMED or an
 * uncertain dispatch the record says "done; by whom unknown" (§11).
 */
private fun Out.resolve(evidence: EvidenceSummary, now: Instant) {
    val attempt = state.attempt ?: return
    attempt.candidate?.let { forgetOutcomeUnknown(it) }
    state = state.copy(attempt = attempt.copy(evidence = evidence))
    closeAttempt(ClosedAs.RESOLVED, now)
    enter(Phase.RESOLVED, now, detail = if (attempt.authorUnknown) "done; by whom unknown" else "done")
}

/** The candidate's own OUTCOME unknown (§9.4) goes once a person or a report knows the outcome. Unit-level ones stay. */
private fun Out.forgetOutcomeUnknown(candidate: Candidate) {
    val subject = mapOf("candidateId" to candidate.candidateId)
    state = state.copy(episodeUnknowns = state.episodeUnknowns.filterNot { it.what == UnknownWhat.OUTCOME && it.subject == subject })
}

/** The audit record of a JobResponse (design §13), whether or not it counted. */
internal fun Out.recordEvidence(e: Evidence, counted: Boolean, why: String) = record(RecordKind.EVIDENCE) {
    putEvidence(e)
    put("counted", counted)
    put("why", why)
}

/** Every field of a JobResponse, as delivered — also used when one arrives where it cannot count (ESCALATED, §13). */
internal fun ObjectNode.putEvidence(e: Evidence) {
    put("jobResponseId", e.jobResponseId)
    put("executionId", e.executionId)
    put("physicalState", e.physicalState)
    put("reachedEvidence", e.reachedEvidence)
    putStrings("completedUnits", e.completedUnits)
    putStrings("inDoubtUnits", e.inDoubtUnits)
    putStrings("unverifiedUnits", e.unverifiedUnits)
    put("operatorRequired", e.operatorRequired)
    put("picassoInstanceId", e.picassoInstanceId)
    put("connection", e.connection)
    put("receivedAt", e.receivedAt.toString())
}

private fun ObjectNode.putStrings(key: String, values: List<String>?) {
    if (values == null) putNull(key) else putArray(key).apply { values.forEach { add(it) } }
}

/**
 * AWAITING_EVIDENCE and UNKNOWN(OUTCOME) (design §5.1, §5.2, §7.2, §12). A person's on-site check of the executed target
 * is evidence grade E3: DONE resolves, NOT_DONE re-diagnoses. A check from another attempt's card is refused as stale.
 */
internal fun Out.onOutcome(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    val attempt = state.attempt
    val candidate = attempt?.candidate
        ?: return escalate(now, Escalation(EscalationReason.DISPATCH_ANSWER_UNKNOWN, "internal: no proposed candidate"))
    when {
        event is EpisodeEvent.EvidenceArrived -> takeEvidence(event.evidence, replayed = false, policy, now)
        event is EpisodeEvent.ConfirmedOutcome && event.candidateId == candidate.candidateId && event.proposalId != attempt.proposalId -> {
            reply = Reply.REFUSED_STALE
            ignore(event, "stale confirmation: ${event.proposalId}")
        }
        event is EpisodeEvent.ConfirmedOutcome && event.candidateId == candidate.candidateId -> {
            reply = Reply.ACCEPTED
            record(RecordKind.OUTCOME_CONFIRMED) {
                put("candidateId", candidate.candidateId)
                put("proposalId", event.proposalId)
                put("done", event.done)
                put("by", event.by.id)
                put("assurance", event.by.assurance.name)
            }
            val evidence = EvidenceSummary("E3", if (event.done) "DONE" else "NOT_DONE")
            if (event.done) resolve(evidence, now)
            else {
                state = state.copy(attempt = attempt.copy(evidence = evidence))
                forgetOutcomeUnknown(candidate)
                rediagnose(RediagnoseReason.NOT_DONE, policy, now, detail = "by ${event.by.id}")
            }
        }
        event is EpisodeEvent.DeadlineExpired && event.which == Timer.STATE ->
            if (state.phase == Phase.AWAITING_EVIDENCE) escalate(now, Escalation(EscalationReason.EVIDENCE_EXPIRED, candidate.candidateId))
            else escalate(now, Escalation(EscalationReason.UNKNOWN_UNRESOLVED, "outcome of ${candidate.candidateId}"), closedAs = ClosedAs.UNKNOWN)
        else -> ignore(event, "awaiting the outcome")
    }
}
