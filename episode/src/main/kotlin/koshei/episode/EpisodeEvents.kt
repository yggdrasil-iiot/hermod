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
    data class ConfirmedPrecondition(val candidateId: String, val proposalId: String, val holds: Boolean, val by: Approver) : EpisodeEvent

    data class DeadlineExpired(val which: Timer, val token: Long) : EpisodeEvent

    /** `takeover` Update. */
    data class TakenOver(val by: Approver) : EpisodeEvent

    /** `agentOff` broadcast signal (design §10.4). */
    data object AgentOff : EpisodeEvent

    /** `close` Update, accepted only in ESCALATED. */
    data class Closed(val by: Approver, val outcome: String) : EpisodeEvent

    /** The execution-intent record ([Command.RecordIntent]) was stored. */
    data class Recorded(val seq: Long) : EpisodeEvent

    /** The execution-intent record could not be stored, or its activity ran out of time. Nothing was dispatched. */
    data class RecordFailed(val seq: Long, val message: String?) : EpisodeEvent

    /** `dispatch` activity result. [token] is the one [Command.Dispatch] carried, so a late result is recognisably stale. */
    data class DispatchReturned(val result: DispatchResult, val token: Long) : EpisodeEvent

    /** `evidence` signal: a JobResponse the host carried (design §12), deduplicated by its `jobResponseId`. */
    data class EvidenceArrived(val evidence: Evidence) : EpisodeEvent

    /**
     * `confirm` Update on the executed target (AWAITING_EVIDENCE, UNKNOWN(OUTCOME)): the person checked on site whether
     * the action of [proposalId] for [candidateId] was done (design §7.2). Counted as evidence grade E3.
     */
    data class ConfirmedOutcome(val candidateId: String, val proposalId: String, val done: Boolean, val by: Approver) : EpisodeEvent
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

    /** The execution-intent record of DISPATCH_PENDING; its outcome comes back as [EpisodeEvent.Recorded] or [EpisodeEvent.RecordFailed]. */
    data class RecordIntent(val seq: Long, val intent: DispatchIntent, val timeoutMs: Long) : Command

    /**
     * Dispatches the intent (design §8.4): the picasso approval window for APPROVE_REMEDY, sent as [approverKind]
     * (`AGENT` for a POLICY approval, `PERSON` for a person's); an operator task for the person-task kinds
     * ([approverKind] null). The runtime returns [token] in [EpisodeEvent.DispatchReturned]; when its retries run out it
     * returns [DispatchResult.Uncertain] — it never decides to dispatch again.
     */
    data class Dispatch(val intent: DispatchIntent, val approverKind: String?, val timeoutMs: Long, val token: Long) : Command
}

/** What the `dispatch` activity came back with (design §8.4). */
sealed interface DispatchResult {
    /** The picasso approval window's answer as raw JSON text (picasso `ApprovalWire.encode`). */
    data class Answer(val json: String) : DispatchResult

    /** The operator task was issued (CHOOSE_SOURCE, OPERATOR_DECISION). */
    data object PersonTaskIssued : DispatchResult

    /** The activity gave up — retries exhausted, or `dispatchMs` ran out: whether it went out is not known (design §11). */
    data class Uncertain(val message: String?) : DispatchResult
}

/**
 * A picasso result notification (JobResponse) the host carried in (design §12), field by field as delivered. A `null`
 * list or flag means "not reported", which [transition] reads as unknown — never as empty or false.
 */
class Evidence(
    val jobResponseId: String,
    val executionId: String?,
    val physicalState: String?,
    /** The evidence grade picasso reached for the execution (E0..E2). */
    val reachedEvidence: String?,
    completedUnits: List<String>?,
    inDoubtUnits: List<String>?,
    unverifiedUnits: List<String>?,
    val operatorRequired: Boolean?,
    val receivedAt: Instant,
) {
    val completedUnits: List<String>? = completedUnits?.toList()
    val inDoubtUnits: List<String>? = inDoubtUnits?.toList()
    val unverifiedUnits: List<String>? = unverifiedUnits?.toList()

    /** A value: two reports with every field equal are equal (a restored state must compare equal to the one it came from). */
    override fun equals(other: Any?): Boolean {
        if (other !is Evidence) return false
        return jobResponseId == other.jobResponseId && executionId == other.executionId &&
            physicalState == other.physicalState && reachedEvidence == other.reachedEvidence &&
            completedUnits == other.completedUnits && inDoubtUnits == other.inDoubtUnits &&
            unverifiedUnits == other.unverifiedUnits && operatorRequired == other.operatorRequired &&
            receivedAt.compareTo(other.receivedAt) == 0
    }

    override fun hashCode(): Int = listOf(
        jobResponseId, executionId, physicalState, reachedEvidence, completedUnits, inDoubtUnits, unverifiedUnits,
        operatorRequired, receivedAt,
    ).hashCode()

    override fun toString(): String = "Evidence($jobResponseId, $executionId)"
}
