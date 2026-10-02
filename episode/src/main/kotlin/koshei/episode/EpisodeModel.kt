package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * Where an episode is (design §5.1). PROPOSED is momentary — the policy decision happens inside the same step — so it
 * is never a resting phase; its passage is recorded ([RecordKind.PROPOSED]). The two UNKNOWN kinds are separate phases.
 */
enum class Phase {
    CORRELATING, DIAGNOSING, AWAITING_APPROVAL, REVALIDATING, UNKNOWN_PRECONDITION,
    DISPATCH_PENDING, DISPATCHED, AWAITING_EVIDENCE, UNKNOWN_OUTCOME,
    ESCALATED, RESOLVED, SUPERSEDED, CLOSED;

    val terminal: Boolean get() = this == RESOLVED || this == SUPERSEDED || this == CLOSED
}

/** Why an episode went to a person (design §5.3, the values that lead to ESCALATED). */
enum class EscalationReason {
    APPROVAL_EXPIRED, EVIDENCE_EXPIRED, EPISODE_EXPIRED, DIAGNOSIS_EXPIRED, DIAGNOSIS_FAILED,
    NO_GROUNDS, UNCITED, ESCALATE_RECOMMENDED, OUT_OF_CANDIDATES, CANDIDATES_VERSION_MISMATCH,
    UNKNOWN_BLOCKS_EXECUTION, UNKNOWN_UNRESOLVED, ATTEMPTS_EXHAUSTED, REPEATED_REMEDY,
    SNAPSHOT_TOO_LARGE, CANDIDATES_TOO_MANY, AGENT_LAYER_OFF, POLICY_MISSING, POLICY_EXPIRED,
    TAKEN_OVER, DISPATCH_REFUSED, DISPATCH_ANSWER_UNKNOWN, CHILD_OVERDUE, RECORD_FAILED, WITHHELD_HUMAN_FIRST,
}

data class Escalation(val reason: EscalationReason, val detail: String? = null)

/** Why an attempt ended in a new diagnosis instead of a person (design §5.1, §5.2). Recorded, not an escalation. */
enum class RediagnoseReason { APPROVAL_REJECTED, APPROVAL_LAPSED, PRECONDITION_BROKEN, NOT_DONE }

/**
 * Why a person rejected a proposal (design §5.3 `APPROVAL_REJECTED(reason)`, contract 0.6 §3.4 "koshei 거절 열거값").
 * Only this value reaches the diagnosis history — narrator copies it into the model's context — while the operator's
 * free text stays in the audit record.
 */
enum class RejectReason { WRONG_TARGET, WRONG_ACTION, PRECONDITION_NOT_MET, NOT_NOW, OTHER }

/** How far an approver's identity is proven. The PoC has no authentication (design §7.2), and the record says so. */
enum class IdentityAssurance { SELF_ASSERTED }

data class Approver(val id: String, val assurance: IdentityAssurance = IdentityAssurance.SELF_ASSERTED)

/** Who approved (design §9.4 `approval.by`). Person-task kinds have no approval at all. */
enum class ApprovalBy { PERSON, POLICY }

/** [approver] is null for POLICY. */
data class Approval(val by: ApprovalBy, val approver: Approver?, val at: Instant)

enum class SymptomKind { INCIDENT, SEARCH }

/**
 * One picasso export line the watcher carried in (design §12): an incident line (keyed by `digest`) or a remedy-search
 * line (keyed by `searchId`). [eventId] is the watcher's dedup key. The line is kept verbatim; each read is a fresh copy.
 */
class Symptom(val eventId: String, val kind: SymptomKind, line: ObjectNode, val observedAt: Instant) {
    private val lineCopy: ObjectNode = line.deepCopy()
    val line: ObjectNode get() = lineCopy.deepCopy()

    /** Why this line cannot go into a snapshot (the contract's request-reading rules, see [Snapshot]), or null. */
    internal fun problem(): String? = when (kind) {
        SymptomKind.INCIDENT -> if (lineCopy.text("digest").isNullOrBlank()) "incident line without digest" else null
        SymptomKind.SEARCH -> if (lineCopy.text("searchId").isNullOrBlank()) "search line without searchId" else null
    }

    /** A value: same event, kind, line and time (a restored state must compare equal to the one it came from). */
    override fun equals(other: Any?): Boolean {
        if (other !is Symptom) return false
        return eventId == other.eventId && kind == other.kind && lineCopy == other.lineCopy && observedAt.compareTo(other.observedAt) == 0
    }

    override fun hashCode(): Int = listOf(eventId, kind, lineCopy, observedAt).hashCode()

    override fun toString(): String = "Symptom($eventId, $kind)"
}

/** A three-valued observation (design §6): never folded into true/false. */
enum class TriState { TRUE, FALSE, UNKNOWN }

/**
 * Timers the runtime sets from [Command.SetTimer]. STATE and RECHECK belong to one phase and carry its token, so a timer
 * left over from an earlier phase is ignored. EPISODE is set once. RETENTION belongs to ESCALATED.
 */
enum class Timer { STATE, RECHECK, EPISODE, RETENTION }

/** What an Update handler returns (design §7.2): refusals are values, recorded, never exceptions. */
enum class Reply {
    ACCEPTED, REFUSED_NOT_AWAITING, REFUSED_STALE, REFUSED_SUBJECT_NOT_UNKNOWN, REFUSED_NOT_ESCALATED, REFUSED_CLOSED,
    /** "The precondition holds" came without the proposition it confirms (§8.3): nobody confirms a proposition nobody wrote. */
    REFUSED_NO_PROPOSITION,
}

/** RECORD_LAG is raised by the runtime when a record keeps failing (design §8.5); the core never emits it. */
enum class NoticeKind { APPROVAL_NEEDED, CONFIRM_PRECONDITION, ESCALATED, POLICY_REJECTED, CONFIRM_OUTCOME, RECORD_LAG }

/** What goes to the operator channel (design §8.5). The card itself (§9.5) is drawn by the runtime from the state. */
data class Notice(val kind: NoticeKind, val phase: Phase, val reason: EscalationReason? = null, val detail: String? = null)

enum class RecordKind {
    OPENED, TRANSITION, SYMPTOM_JOINED, DIAGNOSIS_REQUESTED, DIAGNOSIS_RESULT, PROPOSED, APPROVAL, REDIAGNOSE,
    REVALIDATION, RECHECK, CONFIRMATION, POLICY_REJECTED, IGNORED, POLICY_ADOPTED, ATTEMPT_CLOSED,
    DISPATCH_SENT, DISPATCH_RESULT, EVIDENCE, OUTCOME_CONFIRMED,
}

/** One append-only record (design §13). The payload is a private copy; each read is a fresh copy. */
class RecordEntry(val kind: RecordKind, payload: ObjectNode) {
    private val payloadCopy: ObjectNode = payload.deepCopy()
    val payload: ObjectNode get() = payloadCopy.deepCopy()
    override fun toString(): String = "RecordEntry($kind, $payloadCopy)"
}

/**
 * What the DISPATCH_PENDING intent record says will be dispatched (design §5.2, §11). [targetUnit] is the unit whose
 * presence in a JobResponse's `completedUnits` counts as DONE (design §12). It is null for every kind today: picasso has
 * not said how a JobResponse names the target's unit (design §19), so no JobResponse can complete an attempt yet.
 */
@ConsistentCopyVisibility
data class DispatchIntent internal constructor(
    val candidate: Candidate,
    val idempotencyKey: String,
    val approval: Approval?,
    val targetUnit: String? = null,
)
