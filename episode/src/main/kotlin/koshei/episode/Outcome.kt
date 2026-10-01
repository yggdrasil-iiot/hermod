package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/** AWAITING_EVIDENCE: a JobResponse or a person's check decides; `evidenceMs` ends it (design §5.1, §5.2). */
internal fun Out.enterAwaitingEvidence(policy: PolicyTable, now: Instant) {
    enter(Phase.AWAITING_EVIDENCE, now)
    stateTimer(now, policy.deadlines.evidenceMs)
    replayBuffered(policy, now)
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
    if (e.jobResponseId in state.seenJobResponseIds) return recordEvidence(e, counted = false, why = "duplicate jobResponseId")
    state = state.copy(bufferedEvidence = state.bufferedEvidence + e, seenJobResponseIds = state.seenJobResponseIds + e.jobResponseId)
    recordEvidence(e, counted = false, why = "kept until the answer")
}

/** Re-applies what DISPATCHED kept — an internal step, not a new signal, so the jobResponseId dedup does not apply (§6). */
private fun Out.replayBuffered(policy: PolicyTable, now: Instant) {
    val buffered = state.bufferedEvidence
    if (buffered.isEmpty()) return
    state = state.copy(bufferedEvidence = emptyList())
    for (e in buffered) {
        if (state.phase == Phase.AWAITING_EVIDENCE || state.phase == Phase.UNKNOWN_OUTCOME) takeEvidence(e, replayed = true, policy, now)
        else recordEvidence(e, counted = false, why = "kept, but the episode left for ${state.phase}")
    }
}

/** Task 4 replaces this placeholder with the design §12 judgement. */
internal fun Out.takeEvidence(e: Evidence, replayed: Boolean, policy: PolicyTable, now: Instant) =
    recordEvidence(e, counted = false, why = "not judged yet")

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
    put("receivedAt", e.receivedAt.toString())
}

private fun ObjectNode.putStrings(key: String, values: List<String>?) {
    if (values == null) putNull(key) else putArray(key).apply { values.forEach { add(it) } }
}

/** Task 4 replaces this placeholder: JobResponses, a person's check, and the two deadlines. */
internal fun Out.onOutcome(event: EpisodeEvent, policy: PolicyTable, now: Instant) = ignore(event, "awaiting the outcome")
