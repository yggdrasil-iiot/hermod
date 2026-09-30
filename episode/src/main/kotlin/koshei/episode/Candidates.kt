package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode

enum class CandidateKind { APPROVE_REMEDY, CHOOSE_SOURCE, OPERATOR_DECISION, ESCALATE }

/** What an operator may decide about an unresolved unit. The enum NAME is the wire value (id material and `ref.decision`). */
enum class OperatorDecision { CONFIRM_DONE, REWORK }

/**
 * One option the diagnosis may point at (design §9.1, contract 0.6 §3.1). The id is derived from CONTENT, never from
 * list position, so the same action on the same target keeps its id across re-diagnoses. picasso's searchId /
 * incidentId repeat across runs of the same seed, so they are never id material — only traced in [ref].
 *
 * Value semantics: two candidates are equal when id, kind, [ref] and [sawSkillTypes] are equal.
 */
class Candidate private constructor(
    val kind: CandidateKind,
    ref: Map<String, String?>?,
    /** Already-encoded id segments (see [encodePart]); joined with `:` after the kind. */
    idParts: List<String>,
    sawSkillTypes: List<String>?,
) {
    val ref: Map<String, String?>? = ref?.let { LinkedHashMap(it) }
    val sawSkillTypes: List<String>? = sawSkillTypes?.toList()

    val candidateId: String =
        if (kind == CandidateKind.ESCALATE) ESCALATE_ID else (listOf(kind.name) + idParts).joinToString(":")

    /**
     * Execution class (design §9.1): moves the field, so it is withheld while anything is unknown. Fail-closed: of the
     * operator decisions only an explicit CONFIRM_DONE is outside the class, so an unexpected value counts as execution.
     */
    val executionClass: Boolean
        get() = when (kind) {
            CandidateKind.APPROVE_REMEDY, CandidateKind.CHOOSE_SOURCE -> true
            CandidateKind.OPERATOR_DECISION -> ref?.get("decision") != OperatorDecision.CONFIRM_DONE.name
            CandidateKind.ESCALATE -> false
        }

    fun toJson(): ObjectNode = mapper.createObjectNode().apply {
        put("candidateId", candidateId)
        put("kind", kind.name)
        if (ref == null) putNull("ref") else putObject("ref").apply { ref.forEach { (k, v) -> put(k, v) } }
        if (kind == CandidateKind.APPROVE_REMEDY) putArray("sawSkillTypes").apply { sawSkillTypes.orEmpty().forEach { add(it) } }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Candidate && candidateId == other.candidateId && kind == other.kind &&
            ref == other.ref && sawSkillTypes == other.sawSkillTypes)

    override fun hashCode(): Int = listOf(candidateId, kind, ref, sawSkillTypes).hashCode()

    override fun toString(): String = "Candidate($candidateId)"

    companion object {
        const val ESCALATE_ID = "ESCALATE"

        val ESCALATE = Candidate(CandidateKind.ESCALATE, null, emptyList(), null)

        fun approveRemedy(robotId: String, jobOrderId: String, skills: List<String>, searchId: String) = Candidate(
            CandidateKind.APPROVE_REMEDY,
            linkedMapOf("robotId" to robotId, "jobOrderId" to jobOrderId, "searchId" to searchId),
            listOf(encodePart(robotId), encodePart(jobOrderId), skills.joinToString("+") { encodePart(it).replace("+", "%2B") }),
            skills,
        )

        /**
         * [missingSource] is picasso's `source` (the slot that ran out); [alternative] is the place to pick instead.
         * Only the alternative is id material; the missing slot is carried for the operator card.
         */
        fun chooseSource(jobOrderId: String, material: String, missingSource: String?, alternative: String, searchId: String) = Candidate(
            CandidateKind.CHOOSE_SOURCE,
            linkedMapOf(
                "jobOrderId" to jobOrderId, "material" to material, "missingSource" to missingSource,
                "alternative" to alternative, "searchId" to searchId,
            ),
            listOf(jobOrderId, material, alternative).map(::encodePart),
            null,
        )

        fun operatorDecision(executionId: String, unitId: String, decision: OperatorDecision) = Candidate(
            CandidateKind.OPERATOR_DECISION,
            linkedMapOf("executionId" to executionId, "unitId" to unitId, "decision" to decision.name),
            listOf(executionId, unitId, decision.name).map(::encodePart),
            null,
        )

        /** `%` first, then `:`, so the encoding stays injective. Skill lists additionally encode `+` (their separator). */
        private fun encodePart(s: String): String = s.replace("%", "%25").replace(":", "%3A")
    }
}

/**
 * Pure projection of picasso lines into candidates (design §9.1). ESCALATE is always present; with any unknown
 * (from the snapshot or passed by the caller) every execution-class candidate is withheld. Result ordered by id;
 * an id seen twice keeps its first occurrence. A line that lacks a field its candidate needs offers no candidate
 * (fail-safe: fewer options, never an exception; ESCALATE remains).
 */
fun projectCandidates(snapshot: Snapshot, unknowns: List<Unknown>): List<Candidate> {
    val byId = LinkedHashMap<String, Candidate>()
    fun add(c: Candidate) { byId.putIfAbsent(c.candidateId, c) }

    add(Candidate.ESCALATE)
    for (s in snapshot.searches) {
        val searchId = requireNotNull(s.text("searchId"))
        val robotId = s.text("robotId")
        val jobOrderId = s.text("jobOrderId")
        when (s.text("outcome")) {
            "FOUND" -> {
                // Every step must name its skill: picasso approves only if its steps ⊆ sawSkillTypes, so a partial list
                // would be a candidate that can never be approved as seen. No complete list, no candidate.
                val steps = s.path("steps")
                val skills = steps.mapNotNull { it.text("skillType") }
                if (robotId != null && jobOrderId != null && skills.isNotEmpty() && skills.size == steps.size())
                    add(Candidate.approveRemedy(robotId, jobOrderId, skills, searchId))
            }
            "SOURCE_MISSING" -> {
                // alternatives null = the cell was not asked; [] = the cell answered "no place". Neither offers a source.
                val material = s.text("material")
                val alts = s.get("alternatives")
                if (jobOrderId != null && material != null && alts != null && alts.isArray) alts.filter { it.isTextual }.forEach {
                    add(Candidate.chooseSource(jobOrderId, material, s.text("source"), it.textValue(), searchId))
                }
            }
            else -> Unit
        }
    }
    for (i in snapshot.incidents) {
        val unresolved = i.get("unresolved")?.let { it.isBoolean && it.booleanValue() } ?: false
        val undecided = i.get("resolution")?.isNull ?: true
        val executionId = i.text("executionId")
        val unitId = i.text("unitId")
        if (unresolved && undecided && executionId != null && unitId != null)
            OperatorDecision.entries.forEach { add(Candidate.operatorDecision(executionId, unitId, it)) }
    }
    val blocked = unknowns.isNotEmpty()
    return byId.values.filterNot { blocked && it.executionClass }.sortedBy { it.candidateId }
}
