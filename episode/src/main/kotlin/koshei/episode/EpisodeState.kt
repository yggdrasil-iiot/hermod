package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/** The attempt in progress (design §9.4). Closed attempts live in [EpisodeState.history]. */
@ConsistentCopyVisibility
data class Attempt internal constructor(
    val number: Int,
    val request: DiagnosisRequest,
    /** How many symptoms this attempt's snapshot saw; any later one "joined after the diagnosis" (design §5.2). */
    val symptomCount: Int,
    val verdict: DiagnosisVerdict? = null,
    val proposedAt: Instant? = null,
    val approval: Approval? = null,
    /** The seq of this attempt's execution-intent record, once DISPATCH_PENDING was entered. */
    val intentSeq: Long? = null,
    /**
     * Until when the approval (or, for a person task, the proposal) may be acted on — fixed when REVALIDATING was entered
     * from the table in force then (design §5.1). Null before that. A later table can only shorten it.
     */
    val validUntil: Instant? = null,
) {
    /** design §7.2: instance id + attempt number. */
    val proposalId: String get() = "${request.episodeId}#$number"

    val candidate: Candidate? get() = (verdict as? DiagnosisVerdict.Proposed)?.candidate
}

/**
 * An episode between two events (design §5). Only [startEpisode] and [transition] make one. Every phase entry takes a
 * new [token]; STATE and RECHECK timers carry it, so a timer from an earlier phase is recognisably stale.
 *
 * The state holds [DiagnosisVerdict] values (inside [Attempt]), which are never serialized — see `DiagnosisVerdicts.kt`.
 * Runtime persistence (plan B3) uses its own shape and re-judges from the raw response.
 */
@ConsistentCopyVisibility
data class EpisodeState internal constructor(
    val instanceId: String,
    val startedAt: Instant,
    /** Null only when the policy was off at the start and the episode escalated before any deadline existed. */
    val episodeDeadline: Instant?,
    val phase: Phase,
    val enteredAt: Instant,
    val token: Long,
    /** The current phase's own deadline (its STATE timer), if it has one. */
    val stateDeadline: Instant?,
    val escalation: Escalation?,
    val manifestJson: String,
    /** Opening symptom first, then joined ones in arrival order (design §9.0). */
    val symptoms: List<Symptom>,
    val seenEventIds: Set<String>,
    val attempt: Attempt?,
    val history: List<HistoryEntry>,
    /** Dispatches per candidateId in this episode (design §5.2 REPEATED_REMEDY). Plan B2b-2b counts them. */
    val dispatchCounts: Map<String, Int>,
    /** The table in force — which is also the last valid one. In ESCALATED, only the source of the retention time. */
    val policy: PolicyTable?,
    val autoApproveSuspended: Boolean,
    val lastRejected: List<String>,
    val recordSeq: Long,
) {
    /** Every symptom so far as a snapshot (design §9.0). Throws on a malformed manifest; callers escalate. */
    internal fun snapshot(): Snapshot = Snapshot(
        mapper.readTree(manifestJson) as? ObjectNode ?: throw IllegalArgumentException("manifest is not a JSON object"),
        symptoms.filter { it.kind == SymptomKind.INCIDENT }.map { it.line },
        symptoms.filter { it.kind == SymptomKind.SEARCH }.map { it.line },
    )

    /** Unknowns right now (design §9.1), from every symptom so far. Plan B2b-2b adds episode-level ones and confirmations. */
    internal fun currentUnknowns(): List<Unknown> = deriveUnknowns(snapshot())
}
