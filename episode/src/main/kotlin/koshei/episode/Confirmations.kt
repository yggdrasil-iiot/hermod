package koshei.episode

import java.time.Instant

/**
 * A person's confirmation of one item of the episode's `unknowns` (design §7.2, §9.1), in any phase but ESCALATED. In
 * ESCALATED the answer is `REFUSED_SUBJECT_NOT_UNKNOWN` by reuse (the transition table sends it past here): there is
 * no new Reply value for it.
 *
 * Three kinds of item are not confirmed here, and are refused as "not unknown" with the item kept:
 * - the candidate's own OUTCOME item (subject `candidateId`): the outcome check ([EpisodeEvent.ConfirmedOutcome]) decides
 *   the attempt and clears it together — clearing it here would leave UNKNOWN(OUTCOME) waiting with nothing unknown;
 * - an OUTCOME item on the current attempt's own `executionId` while the attempt awaits its outcome (AWAITING_EVIDENCE,
 *   UNKNOWN(OUTCOME)): that doubt is the outcome check's to settle, for the same reason. Once the attempt is over, the
 *   item is an ordinary one and a person may confirm it;
 * - a subject with no value at all: it names nothing, so it cannot name what was checked on site.
 */
internal fun Out.confirmUnknown(event: EpisodeEvent.ConfirmedUnknown, now: Instant) {
    if ("candidateId" in event.subject) return ignore(event, "the candidate's own outcome is confirmed through its outcome")
    if (event.subject.values.all { it == null }) return ignore(event, "anonymous subject")
    val current = try { state.currentUnknowns() } catch (e: Exception) { emptyList() }
    if (current.none { it.matches(event.subject, event.what) }) return ignore(event, "not unknown now")
    val executionId = state.attempt?.executionId
    if (event.what == UnknownWhat.OUTCOME && executionId != null && event.subject["executionId"] == executionId &&
        (state.phase == Phase.AWAITING_EVIDENCE || state.phase == Phase.UNKNOWN_OUTCOME)
    ) return ignore(event, "the current execution's outcome is decided by the outcome check")
    reply = Reply.ACCEPTED
    state = state.copy(
        confirmations = state.confirmations + UnknownConfirmation.of(event.subject, event.what, now, state.symptoms.size),
        episodeUnknowns = state.episodeUnknowns.filterNot { it.matches(event.subject, event.what) },
    )
    record(RecordKind.CONFIRMATION) {
        put("kind", "UNKNOWN")
        putObject("subject").apply { event.subject.forEach { (k, v) -> put(k, v) } }
        put("what", event.what.name)
        put("by", event.by.id)
        put("assurance", event.by.assurance.name)
        put("note", event.note)
    }
}
