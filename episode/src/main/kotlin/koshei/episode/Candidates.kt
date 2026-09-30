package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode

enum class CandidateKind { APPROVE_REMEDY, CHOOSE_SOURCE, OPERATOR_DECISION, ESCALATE }

/**
 * One option the diagnosis may point at (design §9.1, contract 0.6 §3.1). The id is derived from CONTENT, never from
 * list position, so the same action on the same target keeps its id across re-diagnoses. picasso's searchId /
 * incidentId repeat across runs of the same seed, so they are never id material — only traced in [ref].
 */
class Candidate private constructor(
    val kind: CandidateKind,
    val ref: Map<String, String>?,
    idParts: List<String>,
    val sawSkillTypes: List<String>?,
) {
    val candidateId: String =
        if (kind == CandidateKind.ESCALATE) ESCALATE_ID else (listOf(kind.name) + idParts.map(::encodePart)).joinToString(":")

    /** Execution class (design §9.1): moves the field, so it is withheld while anything is unknown. */
    val executionClass: Boolean
        get() = when (kind) {
            CandidateKind.APPROVE_REMEDY, CandidateKind.CHOOSE_SOURCE -> true
            CandidateKind.OPERATOR_DECISION -> ref?.get("decision") == REWORK
            CandidateKind.ESCALATE -> false
        }

    fun toJson(): ObjectNode = mapper.createObjectNode().apply {
        put("candidateId", candidateId)
        put("kind", kind.name)
        if (ref == null) putNull("ref") else putObject("ref").apply { ref.forEach { (k, v) -> put(k, v) } }
        if (kind == CandidateKind.APPROVE_REMEDY) putArray("sawSkillTypes").apply { sawSkillTypes.orEmpty().forEach { add(it) } }
    }

    companion object {
        const val ESCALATE_ID = "ESCALATE"
        const val CONFIRM_DONE = "CONFIRM_DONE"
        const val REWORK = "REWORK"

        val ESCALATE = Candidate(CandidateKind.ESCALATE, null, emptyList(), null)

        fun approveRemedy(robotId: String, jobOrderId: String, skills: List<String>, searchId: String) = Candidate(
            CandidateKind.APPROVE_REMEDY,
            linkedMapOf("robotId" to robotId, "jobOrderId" to jobOrderId, "searchId" to searchId),
            listOf(robotId, jobOrderId, skills.joinToString("+")),
            skills,
        )

        fun chooseSource(jobOrderId: String, material: String, source: String, searchId: String) = Candidate(
            CandidateKind.CHOOSE_SOURCE,
            linkedMapOf("jobOrderId" to jobOrderId, "material" to material, "source" to source, "searchId" to searchId),
            listOf(jobOrderId, material, source),
            null,
        )

        fun operatorDecision(executionId: String, unitId: String, decision: String) = Candidate(
            CandidateKind.OPERATOR_DECISION,
            linkedMapOf("executionId" to executionId, "unitId" to unitId, "decision" to decision),
            listOf(executionId, unitId, decision),
            null,
        )

        /** `%` first, then `:`, so the encoding stays injective. */
        private fun encodePart(s: String): String = s.replace("%", "%25").replace(":", "%3A")
    }
}

/**
 * Pure projection of picasso lines into candidates (design §9.1). ESCALATE is always present; with any unknown
 * (from the snapshot or passed by the caller) every execution-class candidate is withheld. Result ordered by id;
 * an id seen twice keeps its first occurrence.
 */
fun projectCandidates(snapshot: Snapshot, unknowns: List<Unknown>): List<Candidate> {
    val byId = LinkedHashMap<String, Candidate>()
    fun add(c: Candidate) { byId.putIfAbsent(c.candidateId, c) }

    add(Candidate.ESCALATE)
    for (s in snapshot.searches) {
        val searchId = requireNotNull(s.text("searchId"))
        when (s.text("outcome")) {
            "FOUND" -> {
                // Every step must name its skill: picasso approves only if its steps ⊆ sawSkillTypes, so a partial list
                // would be a candidate that can never be approved as seen. No complete list, no candidate.
                val steps = s.path("steps")
                val skills = steps.mapNotNull { it.text("skillType") }
                if (skills.isNotEmpty() && skills.size == steps.size())
                    add(Candidate.approveRemedy(s.req("robotId"), s.req("jobOrderId"), skills, searchId))
            }
            "SOURCE_MISSING" -> {
                // alternatives null = the cell was not asked; [] = the cell answered "no place". Neither offers a source.
                val alts = s.get("alternatives")
                if (alts != null && alts.isArray) alts.filter { it.isTextual }.forEach {
                    add(Candidate.chooseSource(s.req("jobOrderId"), s.req("material"), it.textValue(), searchId))
                }
            }
            else -> Unit
        }
    }
    for (i in snapshot.incidents) {
        val unresolved = i.get("unresolved")?.let { it.isBoolean && it.booleanValue() } ?: false
        val undecided = i.get("resolution")?.isNull ?: true
        if (unresolved && undecided) {
            add(Candidate.operatorDecision(i.req("executionId"), i.req("unitId"), Candidate.CONFIRM_DONE))
            add(Candidate.operatorDecision(i.req("executionId"), i.req("unitId"), Candidate.REWORK))
        }
    }
    val blocked = unknowns.isNotEmpty()
    return byId.values.filterNot { blocked && it.executionClass }.sortedBy { it.candidateId }
}

private fun ObjectNode.req(key: String): String = requireNotNull(text(key)) { "picasso line without $key: ${get("searchId") ?: get("incidentId")}" }
