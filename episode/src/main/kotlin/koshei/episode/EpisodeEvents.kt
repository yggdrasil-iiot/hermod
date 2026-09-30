package koshei.episode

import java.time.Instant

/** What the runtime feeds into [transition] (design §6). Plan B2b-2b adds the dispatch and evidence events. */
sealed interface EpisodeEvent {
    /** The first symptom, delivered by signalWithStart. [instanceId] = workflow id + runId (design §7.1). */
    data class Detected(val instanceId: String, val manifestJson: String, val symptom: Symptom) : EpisodeEvent

    data class SymptomJoined(val symptom: Symptom) : EpisodeEvent

    /** The `diagnose` activity result as raw JSON text, so duplicate keys are caught ([parseDiagnosisResponse]). */
    data class DiagnosisReturned(val json: String) : EpisodeEvent

    /** The activity failed after Temporal's retries; [expired] = its ScheduleToClose ran out. */
    data class DiagnosisFailed(val expired: Boolean, val message: String?) : EpisodeEvent

    /** `decide` Update (design §7.2). [reason] is required for a rejection by the control plane; [note] is free text for the audit record only. */
    data class ApprovalDecided(
        val proposalId: String,
        val sawCandidatesVersion: String,
        val approve: Boolean,
        val by: Approver,
        val reason: RejectReason?,
        val note: String?,
    ) : EpisodeEvent

    /**
     * `revalidate` result; an activity failure arrives as UNKNOWN. [token] is the one [Command.Revalidate] carried, so a
     * late result for an earlier phase is recognisably stale.
     */
    data class Revalidated(val result: TriState, val token: Long) : EpisodeEvent

    /** `confirm` Update on UNKNOWN(PRECONDITION): the person checked the proposition for [candidateId] (design §8.3). */
    data class ConfirmedPrecondition(val candidateId: String, val holds: Boolean, val by: Approver) : EpisodeEvent

    data class DeadlineExpired(val which: Timer, val token: Long) : EpisodeEvent

    /** `takeover` Update. */
    data class TakenOver(val by: Approver) : EpisodeEvent

    /** `agentOff` broadcast signal (design §10.4). */
    data object AgentOff : EpisodeEvent

    /** `close` Update, accepted only in ESCALATED. */
    data class Closed(val by: Approver, val outcome: String) : EpisodeEvent
}

/** What [transition] asks the runtime to do, in order (design §6). Results come back as events. */
sealed interface Command {
    data class Diagnose(val request: DiagnosisRequest, val budget: DiagnosisBudget) : Command

    data object CancelDiagnosis : Command

    /** The runtime returns [token] in [EpisodeEvent.Revalidated]. */
    data class Revalidate(val candidate: Candidate, val timeoutMs: Long, val token: Long) : Command

    data class SetTimer(val which: Timer, val at: Instant, val token: Long) : Command

    data class Notify(val notice: Notice) : Command

    /** Append-only record; the runtime does not wait for it (design §8.5). */
    data class Record(val seq: Long, val entry: RecordEntry) : Command

    /** The execution-intent record of DISPATCH_PENDING; its outcome comes back as `Recorded(seq)` (plan B2b-2b). */
    data class RecordIntent(val seq: Long, val intent: DispatchIntent, val timeoutMs: Long) : Command
}
