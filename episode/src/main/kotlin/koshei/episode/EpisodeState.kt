package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * A person's confirmation of one unknown (design §9.1). It covers the lines that had arrived when it was given — the
 * first [coversSymptoms] of `EpisodeState.symptoms` — and none that arrive later, whatever their `observedAt` says: the
 * watcher's clock and the workflow's are never compared (§12). [at] is kept for the record.
 */
@ConsistentCopyVisibility
data class UnknownConfirmation internal constructor(val subject: Map<String, String?>, val what: UnknownWhat, val at: Instant, val coversSymptoms: Int) {
    internal companion object {
        /** The only way to make one: the subject is copied, so the caller's map cannot change a confirmation later. */
        fun of(subject: Map<String, String?>, what: UnknownWhat, at: Instant, coversSymptoms: Int): UnknownConfirmation =
            UnknownConfirmation(LinkedHashMap(subject), what, at, coversSymptoms)
    }
}

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
    /** The intent DISPATCH_PENDING recorded (under [intentSeq]). */
    val intent: DispatchIntent? = null,
    /** picasso's `executionId` from an APPROVED answer: with [picassoInstanceId], what links a JobResponse to this attempt (design §12). */
    val executionId: String? = null,
    /** picasso's `instanceId` from that answer: `executionId` is counted per instance, so only the pair names the execution (ADR 48). */
    val picassoInstanceId: String? = null,
    /** The `unitId` of every step that answer sent (ADR 48): a linked JobResponse is DONE when it completed all of them (§12). */
    val approvedUnits: List<String> = emptyList(),
    val dispatch: DispatchSummary? = null,
    val evidence: EvidenceSummary? = null,
    /** NO_PROPOSAL, REMEDY_NOT_APPLIED, CONSUMED or an uncertain dispatch: a later DONE is "done, by whom unknown" (design §11). */
    val authorUnknown: Boolean = false,
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
    /** The episode's own unknowns (design §9.1, §9.4), e.g. OUTCOME after NO_PROPOSAL; they reach the next diagnosis request. */
    val episodeUnknowns: List<Unknown> = emptyList(),
    /** JobResponses that arrived in DISPATCHED, before the answer; applied on entering AWAITING_EVIDENCE / UNKNOWN(OUTCOME). */
    val bufferedEvidence: List<Evidence> = emptyList(),
    /** Every JobResponse seen in this episode, by [Evidence.reportKey]: a redelivered one never counts twice (design §6). */
    val seenReports: Set<String> = emptySet(),
    /**
     * Every execution an APPROVED answer gave in this episode, as `<instanceId>/<executionId>`. The pair is unique per
     * attempt (ADR 48), so a repeat is picasso's fault, not a restart: an attempt whose execution was used before links no
     * JobResponse (a stale one could resolve it).
     */
    val usedExecutions: Set<String> = emptySet(),
    /** A person's confirmations of unknowns (design §7.2, §9.1). */
    val confirmations: List<UnknownConfirmation> = emptyList(),
) {
    /** Every symptom so far as a snapshot (design §9.0). Throws on a malformed manifest; callers escalate. */
    internal fun snapshot(): Snapshot = Snapshot(
        mapper.readTree(manifestJson) as? ObjectNode ?: throw IllegalArgumentException("manifest is not a JSON object"),
        symptoms.filter { it.kind == SymptomKind.INCIDENT }.map { it.line },
        symptoms.filter { it.kind == SymptomKind.SEARCH }.map { it.line },
    )

    /**
     * Unknowns right now (design §9.1): those each incident line derives, minus what a person confirmed after that line
     * had arrived, plus the episode's own. Without confirmations this equals `deriveUnknowns(snapshot()) +
     * episodeUnknowns`, in the same order. Throws on a malformed manifest; callers fail safe.
     */
    fun currentUnknowns(): List<Unknown> {
        // snapshot() is built once, so the manifest and every line are validated as for a diagnosis request. Its incidents
        // are the INCIDENT symptoms in order: zip them with those symptoms' positions in `symptoms`.
        val incidents = snapshot().incidents
        val positions = symptoms.indices.filter { symptoms[it].kind == SymptomKind.INCIDENT }
        val derived = incidents.zip(positions).flatMap { (line, position) ->
            deriveUnknownsOf(line).filterNot { u -> confirmations.any { u.matches(it.subject, it.what) && position < it.coversSymptoms } }
        }
        return derived + episodeUnknowns
    }
}
