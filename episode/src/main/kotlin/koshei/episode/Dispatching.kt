package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/** The approval window's wire schema this module reads (picasso `ApprovalWire.SCHEMA_VERSION`). */
const val PICASSO_APPROVAL_SCHEMA = "2"

/**
 * Every refusal value of the approval window's schema "2" (picasso `ApprovalRefusal`, `docs/orchestration.md` §7.3, read
 * at picasso main 584c6cd). A value outside this set is not understood and goes to a person (design §8.4).
 */
val PICASSO_REFUSALS_V2: Set<String> = setOf(
    "WITHHELD", "NO_PROPOSAL", "PROPOSAL_CHANGED", "NOT_DECLARED", "REVOKED", "EXPIRED", "ROBOT_OUT_OF_SCOPE",
    "SKILL_OUT_OF_SCOPE", "VALUE_NOT_DECLARED", "OBJECT_NOT_OBSERVED", "DECLARED_CONTRADICTS_OBSERVED",
    "CAPABILITY_UNKNOWN", "VALUES_NOT_ACCEPTED", "REFUSED_BY_GATE", "REMEDY_NOT_APPLIED",
)

/** Refusals that fold "my earlier attempt may already have landed" (design §11): UNKNOWN(OUTCOME), never a retry. */
private val MAY_HAVE_LANDED = setOf("NO_PROPOSAL", "REMEDY_NOT_APPLIED")

/** What a dispatch result means for the episode (design §5.2 DISPATCHED rows). */
internal sealed interface DispatchJudgement {
    /** picasso approved; [delivered] is its `steps`, what actually went out (picasso §7.3). */
    class Accepted(val executionId: String, delivered: JsonNode) : DispatchJudgement {
        val delivered: JsonNode = delivered.deepCopy()
    }

    data object PersonTask : DispatchJudgement

    data class MayHaveLanded(val refusal: String) : DispatchJudgement

    data class Uncertain(val detail: String?) : DispatchJudgement

    data class Refused(val refusal: String) : DispatchJudgement

    data class AnswerUnknown(val detail: String) : DispatchJudgement
}

/** A picasso answer only for APPROVE_REMEDY, an issued task only for the person-task kinds; anything else is not understood. */
internal fun judgeDispatch(kind: CandidateKind, result: DispatchResult): DispatchJudgement = when (result) {
    is DispatchResult.Uncertain -> DispatchJudgement.Uncertain(result.message)
    DispatchResult.PersonTaskIssued ->
        if (kind == CandidateKind.CHOOSE_SOURCE || kind == CandidateKind.OPERATOR_DECISION) DispatchJudgement.PersonTask
        else DispatchJudgement.AnswerUnknown("a person-task answer for $kind")
    is DispatchResult.Answer ->
        if (kind == CandidateKind.APPROVE_REMEDY) judgePicassoAnswer(result.json)
        else DispatchJudgement.AnswerUnknown("a picasso answer for $kind")
}

/**
 * Reads the approval window's answer (design §8.4): `schemaVersion` first, then `outcome`; branches on the `refusal`
 * value only, never on the prose `reason`. Read with the module's strict mapper, so a duplicated key is not understood.
 */
internal fun judgePicassoAnswer(json: String): DispatchJudgement {
    val node = try {
        mapper.readTree(json)
    } catch (e: Exception) {
        return DispatchJudgement.AnswerUnknown("unreadable: ${e.message}")
    }
    if (node == null || !node.isObject) return DispatchJudgement.AnswerUnknown("not a JSON object")
    val schema = node.text("schemaVersion")
    if (schema != PICASSO_APPROVAL_SCHEMA) return DispatchJudgement.AnswerUnknown("schemaVersion $schema")
    return when (val outcome = node.text("outcome")) {
        "APPROVED" -> {
            val executionId = node.text("executionId")
            val steps = node.get("steps")
            if (executionId.isNullOrBlank() || steps == null || !steps.isArray) DispatchJudgement.AnswerUnknown("APPROVED without executionId or steps")
            else DispatchJudgement.Accepted(executionId, steps)
        }
        "REFUSED" -> {
            val refusal = node.text("refusal")
            when {
                refusal == null || refusal !in PICASSO_REFUSALS_V2 -> DispatchJudgement.AnswerUnknown("refusal $refusal")
                refusal in MAY_HAVE_LANDED -> DispatchJudgement.MayHaveLanded(refusal)
                else -> DispatchJudgement.Refused(refusal)
            }
        }
        else -> DispatchJudgement.AnswerUnknown("outcome $outcome")
    }
}
