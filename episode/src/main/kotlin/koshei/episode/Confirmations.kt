package koshei.episode

import java.time.Instant

/**
 * A person's confirmation of one item of the episode's `unknowns` (design §7.2, §9.1), in any phase but ESCALATED.
 * The candidate's own OUTCOME item is not confirmed here: the outcome check ([EpisodeEvent.ConfirmedOutcome]) decides
 * the attempt and clears it together — clearing it here would leave UNKNOWN(OUTCOME) waiting with nothing unknown.
 */
internal fun Out.confirmUnknown(event: EpisodeEvent.ConfirmedUnknown, now: Instant) {
    if ("candidateId" in event.subject) return ignore(event, "the candidate's own outcome is confirmed through its outcome")
    val current = try { state.currentUnknowns() } catch (e: Exception) { emptyList() }
    if (current.none { it.subject == event.subject && it.what == event.what }) return ignore(event, "not unknown now")
    reply = Reply.ACCEPTED
    state = state.copy(
        confirmations = state.confirmations + UnknownConfirmation(LinkedHashMap(event.subject), event.what, now, state.symptoms.size),
        episodeUnknowns = state.episodeUnknowns.filterNot { it.subject == event.subject && it.what == event.what },
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
