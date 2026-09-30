package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/** How an attempt ended (design §9.4 `closedAs`). */
enum class ClosedAs { REDIAGNOSE, RESOLVED, SUPERSEDED, UNKNOWN, ESCALATED }

enum class ApprovalResult { APPROVED, REJECTED, EXPIRED }

data class DiagnosisSummary(val outcome: DiagnosisOutcome, val candidateId: String?, val picked: String?)

/** [by] is null for EXPIRED; [reason] only for REJECTED. */
data class ApprovalSummary(val result: ApprovalResult, val by: ApprovalBy?, val reason: RejectReason?, val at: Instant)

/** Filled by plan B2b-2b. */
data class DispatchSummary(val result: String, val idempotencyKey: String, val approverKind: String?, val delivered: Boolean?)

/** Filled by plan B2b-2b. */
data class EvidenceSummary(val grade: String, val outcome: String)

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
            if (dispatch.delivered == null) putNull("delivered") else put("delivered", dispatch.delivered)
        }
        if (evidence == null) putNull("evidence") else putObject("evidence").apply {
            put("grade", evidence.grade)
            put("outcome", evidence.outcome)
        }
        put("closedAs", closedAs.name)
        put("at", at.toString())
    }
}
