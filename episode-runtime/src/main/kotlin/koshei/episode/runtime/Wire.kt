package koshei.episode.runtime

/** The episode workflow's own queue (design §4.2). */
const val EPISODE_TASK_QUEUE = "koshei-episode-tq"

/** narrator's queue (narrator contract 0.6 §2). Its Python worker — or, in mock mode, koshei's — polls it. */
const val NARRATOR_TASK_QUEUE = "narrator-tq"

// Everything below crosses a Temporal payload boundary: Kotlin data classes whose fields all have defaults (so any
// Jackson converter can build them), plain strings and numbers, JSON as text. The core's own types never cross.

/**
 * signalWithStart's start input (design §7.1). [manifestJson] is the picasso export manifest the symptoms come from.
 * [pending] are symptoms that reached the previous run after its episode had ended: the run continued as new with them,
 * and the first one opens this run's episode.
 */
data class EpisodeStart(val manifestJson: String = "", val schemaVersion: Int = 1, val pending: List<SymptomSignal> = emptyList())

/**
 * `symptom` signal: one picasso export line (design §12). [kind] is `INCIDENT` or `SEARCH`. [manifestJson] is the
 * manifest of the export the line came from; the watcher sends it with every signal (plan C). Only the symptom that
 * opens an episode uses it — a joined symptom's manifest is not read (design §7.1).
 */
data class SymptomSignal(
    val eventId: String = "",
    val kind: String = "",
    val lineJson: String = "",
    val observedAtMillis: Long = 0,
    val manifestJson: String? = null,
)

/**
 * `evidence` signal: a JobResponse the host carried (design §12). A null list or flag means "not reported".
 * [picassoInstanceId] and [connection] are picasso's `instanceId` and `connection` (ResultExport schema 1, ADR 48).
 */
data class EvidenceSignal(
    val jobResponseId: String = "",
    val executionId: String? = null,
    val physicalState: String? = null,
    val reachedEvidence: String? = null,
    val completedUnits: List<String>? = null,
    val inDoubtUnits: List<String>? = null,
    val unverifiedUnits: List<String>? = null,
    val operatorRequired: Boolean? = null,
    val receivedAtMillis: Long = 0,
    val picassoInstanceId: String? = null,
    val connection: String? = null,
)

/** `decide` Update (design §7.2). [reason] is a `RejectReason` name; [note] stays in the audit record. */
data class DecideRequest(
    val proposalId: String = "",
    val sawCandidatesVersion: String = "",
    val approve: Boolean = false,
    val operatorId: String = "",
    val reason: String? = null,
    val note: String? = null,
)

/**
 * `confirm` Update (design §7.2). [kind]: `PRECONDITION` and `OUTCOME` name the proposal ([candidateId], [proposalId])
 * and say whether it [holds] (for OUTCOME: whether it was done); `UNKNOWN` names an unknown ([subject], [what]).
 */
data class ConfirmRequest(
    val kind: String = "",
    val candidateId: String = "",
    val proposalId: String = "",
    val holds: Boolean = false,
    val subject: Map<String, String?> = emptyMap(),
    val what: String = "",
    val operatorId: String = "",
    val note: String? = null,
    /** PRECONDITION only: the proposition the card showed (§7.2). The control plane fills it from its own card, never from the client. */
    val proposition: String? = null,
)

data class TakeoverRequest(val operatorId: String = "")

data class CloseRequest(val operatorId: String = "", val outcome: String = "")

/** What `readPolicy` found (design §8.2): `FOUND` with the table as JSON text, `UNREADABLE` with why, or `MISSING`. */
data class PolicyReadResult(val kind: String = "MISSING", val json: String? = null, val reason: String? = null)

/** What `dispatch` came back with: `ANSWER` with picasso's answer as raw text, or `PERSON_TASK`. */
data class DispatchOutcome(val kind: String = "", val answerJson: String? = null)

/** `view` query (design §7.3). Times are epoch milliseconds; history and unknowns are JSON text. */
data class EpisodeView(
    val instanceId: String = "",
    val phase: String = "",
    val escalationReason: String? = null,
    val escalationDetail: String? = null,
    val attempt: Int? = null,
    val proposalId: String? = null,
    val candidateId: String? = null,
    val candidatesVersion: String? = null,
    val stateDeadlineMillis: Long? = null,
    val episodeDeadlineMillis: Long? = null,
    val historyJson: String = "[]",
    val unknownsJson: String = "[]",
    val symptoms: Int = 0,
    /** The attempt's candidate as `Candidate.toJson()`: the card draws the proposal from kind and ref (§9.5). */
    val candidateJson: String? = null,
    /** The candidates the attempt's diagnosis request offered, each as `Candidate.toJson()` (§7.3). */
    val candidatesJson: String? = null,
    /**
     * The attempt's narrator answer as received, once it was proposed (§7.3 current recommendation). Records may lag; this
     * does not. An answer that escalated is never here: its attempt closed in the same step, so it is read from the record.
     */
    val diagnosisJson: String? = null,
    /**
     * How many symptoms joined after the attempt's snapshot (design §8.3), counted live: records may lag, this does not.
     * Null when there is no attempt. The lines themselves are read from the records.
     */
    val joinedAfterSnapshot: Int? = null,
)

/** What the workflow returns when the episode ends. */
data class EpisodeResult(val phase: String = "", val escalationReason: String? = null)
