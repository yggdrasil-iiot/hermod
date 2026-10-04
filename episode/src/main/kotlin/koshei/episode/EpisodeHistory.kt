package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/** How an attempt ended (design §9.4 `closedAs`). */
enum class ClosedAs { REDIAGNOSE, RESOLVED, SUPERSEDED, UNKNOWN, ESCALATED }

enum class ApprovalResult { APPROVED, REJECTED, EXPIRED }

data class DiagnosisSummary(val outcome: DiagnosisOutcome, val candidateId: String?, val picked: String?)

/** [by] is null for EXPIRED; [reason] only for REJECTED. */
data class ApprovalSummary(val result: ApprovalResult, val by: ApprovalBy?, val reason: RejectReason?, val at: Instant)

/**
 * [result]: `ACCEPTED` · `PERSON_TASK` · a picasso refusal value · `UNCERTAIN` · `ANSWER_UNKNOWN` (design §9.4).
 * [approverKind] is what koshei sent the approval window. [delivered] is what picasso says actually went out — its
 * answer's `steps` — and null when nothing was approved; kept as a private copy, each read a fresh copy.
 */
class DispatchSummary(val result: String, val idempotencyKey: String, val approverKind: String?, delivered: JsonNode?) {
    private val deliveredCopy: JsonNode? = delivered?.deepCopy()
    val delivered: JsonNode? get() = deliveredCopy?.deepCopy()

    override fun equals(other: Any?): Boolean =
        this === other || (other is DispatchSummary && result == other.result && idempotencyKey == other.idempotencyKey &&
            approverKind == other.approverKind && deliveredCopy == other.deliveredCopy)

    override fun hashCode(): Int = listOf(result, idempotencyKey, approverKind, deliveredCopy).hashCode()

    override fun toString(): String = "DispatchSummary($result, $idempotencyKey)"
}

/**
 * [grade] E0..E2 as picasso reported it, E3 for a person's on-site check; null when the JobResponse did not say — it is
 * never filled in (design §12). [outcome] `DONE` · `NOT_DONE` · `UNKNOWN`.
 */
data class EvidenceSummary(val grade: String?, val outcome: String)

/**
 * One closed attempt, as the diagnosis request's `history` carries it (contract 0.6 §3.4, design §9.4). All eight keys
 * are always written, in this order; a stage that was never reached is `null`, never omitted (contract §2).
 */
data class HistoryEntry(
    val attempt: Int,
    val candidatesVersion: String,
    val diagnosis: DiagnosisSummary?,
    val approval: ApprovalSummary?,
    val dispatch: DispatchSummary?,
    val evidence: EvidenceSummary?,
    val closedAs: ClosedAs,
    val at: Instant,
) {
    fun toJson(): ObjectNode = mapper.createObjectNode().apply {
        put("attempt", attempt)
        put("candidatesVersion", candidatesVersion)
        if (diagnosis == null) putNull("diagnosis") else putObject("diagnosis").apply {
            put("outcome", diagnosis.outcome.name)
            put("candidateId", diagnosis.candidateId)
            put("picked", diagnosis.picked)
        }
        if (approval == null) putNull("approval") else putObject("approval").apply {
            put("result", approval.result.name)
            put("by", approval.by?.name)
            put("reason", approval.reason?.name)
            put("at", approval.at.toString())
        }
        if (dispatch == null) putNull("dispatch") else putObject("dispatch").apply {
            put("result", dispatch.result)
            put("idempotencyKey", dispatch.idempotencyKey)
            put("approverKind", dispatch.approverKind)
            set<JsonNode>("delivered", dispatch.delivered)
        }
        if (evidence == null) putNull("evidence") else putObject("evidence").apply {
            put("grade", evidence.grade)
            put("outcome", evidence.outcome)
        }
        put("closedAs", closedAs.name)
        put("at", at.toString())
    }
}
