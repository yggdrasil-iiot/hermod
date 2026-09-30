package koshei.episode

import com.fasterxml.jackson.databind.JsonNode

/**
 * Why a diagnosis sends the episode to a person: the subset of design §5.3 that the DIAGNOSING rows of §5.2 produce from a
 * response. DIAGNOSIS_EXPIRED is not here (it comes from the activity failing, not from a response); B2b-2 maps these
 * onto the episode's single escalation-reason type.
 */
enum class DiagnosisEscalation {
    ESCALATE_RECOMMENDED, NO_GROUNDS, UNCITED, OUT_OF_CANDIDATES, CANDIDATES_VERSION_MISMATCH, UNKNOWN_BLOCKS_EXECUTION, DIAGNOSIS_FAILED,
}

/**
 * The judge's verdict. Only [judgeDiagnosis] (and code in this module) can build one, so holding a [Proposed] means every
 * check passed. Both variants carry the response they were judged from, so the next layer can record versions,
 * rationale, card and citations without pairing the parse and the verdict again.
 */
sealed interface DiagnosisVerdict {
    /** An offered candidate the transition function may propose; [clean] feeds `requireClean` (design §9.5). */
    @ConsistentCopyVisibility
    data class Proposed internal constructor(
        val candidate: Candidate,
        val clean: Boolean,
        val response: DiagnosisResponse,
    ) : DiagnosisVerdict

    /**
     * [response] is null only when the response could not be read (DIAGNOSIS_FAILED from a [ResponseParse.Invalid]); then
     * [raw] is what arrived, a fresh deep copy on each access, and it is null in every other case.
     *
     * [picked] is set only for OUT_OF_CANDIDATES: the text or id outside the list (null when there was no recommendation
     * marker). For UNKNOWN_BLOCKS_EXECUTION the blocked id is `response.candidateId`. The transition never branches on
     * [picked]. [detail] explains a DIAGNOSIS_FAILED, a CANDIDATES_VERSION_MISMATCH, or an UNCITED that koshei found itself.
     */
    @ConsistentCopyVisibility
    data class Escalate internal constructor(
        val reason: DiagnosisEscalation,
        val response: DiagnosisResponse?,
        private val unreadable: JsonNode? = null,
        val picked: String? = null,
        val detail: String? = null,
    ) : DiagnosisVerdict {
        val raw: JsonNode? get() = unreadable?.deepCopy()
    }
}

/**
 * Checks a diagnosis against the request that was sent (design §5.2, DIAGNOSING rows). The response never decides alone.
 * In order, the first check that fires decides:
 *
 * 1. an unreadable response ([ResponseParse.Invalid]) → DIAGNOSIS_FAILED;
 * 2. a response for another contract version, episode or attempt → DIAGNOSIS_FAILED (identity before anything else);
 * 3. a candidates version other than the one sent → CANDIDATES_VERSION_MISMATCH;
 * 4. NO_GROUNDS, UNCITED and OUT_OF_CANDIDATES escalate under their own names;
 * 5. RECOMMENDED without a candidate id (the reader rules this out) → DIAGNOSIS_FAILED, never a crash;
 * 6. RECOMMENDED `ESCALATE` → ESCALATE_RECOMMENDED;
 * 7. RECOMMENDED with no citation whose `verified` is the JSON boolean `true` → UNCITED. koshei re-checks contract §4
 *    rule 3 itself; the string `"true"`, `1` or a missing field is unverified;
 * 8. RECOMMENDED of an id koshei did not offer → OUT_OF_CANDIDATES (both judges agree). Ids match exactly: no trimming,
 *    no case folding;
 * 9. RECOMMENDED of an execution-class candidate while anything is unknown → UNKNOWN_BLOCKS_EXECUTION (a projection
 *    defect: projection never offers that combination);
 *
 * and only then [DiagnosisVerdict.Proposed].
 */
fun judgeDiagnosis(request: DiagnosisRequest, parse: ResponseParse): DiagnosisVerdict {
    val response = when (parse) {
        is ResponseParse.Invalid -> return DiagnosisVerdict.Escalate(
            DiagnosisEscalation.DIAGNOSIS_FAILED, null, unreadable = parse.raw, detail = parse.errors.joinToString("; "),
        )
        is ResponseParse.Valid -> parse.response
    }
    fun escalate(reason: DiagnosisEscalation, picked: String? = null, detail: String? = null) =
        DiagnosisVerdict.Escalate(reason, response, picked = picked, detail = detail)

    if (response.contractVersion != DIAGNOSIS_CONTRACT_VERSION || response.episodeId != request.episodeId || response.attempt != request.attempt)
        return escalate(
            DiagnosisEscalation.DIAGNOSIS_FAILED,
            detail = "response is for contract ${response.contractVersion} / ${response.episodeId} attempt ${response.attempt}, " +
                "request was $DIAGNOSIS_CONTRACT_VERSION / ${request.episodeId} attempt ${request.attempt}",
        )
    if (response.sawCandidatesVersion != request.candidatesVersion)
        return escalate(
            DiagnosisEscalation.CANDIDATES_VERSION_MISMATCH,
            detail = "saw ${response.sawCandidatesVersion}, sent ${request.candidatesVersion}",
        )

    return when (response.outcome) {
        DiagnosisOutcome.NO_GROUNDS -> escalate(DiagnosisEscalation.NO_GROUNDS)
        DiagnosisOutcome.UNCITED -> escalate(DiagnosisEscalation.UNCITED)
        DiagnosisOutcome.OUT_OF_CANDIDATES -> escalate(DiagnosisEscalation.OUT_OF_CANDIDATES, picked = response.picked)
        DiagnosisOutcome.RECOMMENDED -> {
            val id = response.candidateId
                ?: return escalate(DiagnosisEscalation.DIAGNOSIS_FAILED, detail = "RECOMMENDED without a candidate id")
            if (id == Candidate.ESCALATE_ID) return escalate(DiagnosisEscalation.ESCALATE_RECOMMENDED)
            if (response.citations.none { it.get("verified")?.let { v -> v.isBoolean && v.booleanValue() } == true })
                return escalate(DiagnosisEscalation.UNCITED, detail = "RECOMMENDED without a verified citation (contract §4 rule 3)")
            val candidate = request.candidates.firstOrNull { it.candidateId == id }
            when {
                candidate == null -> escalate(DiagnosisEscalation.OUT_OF_CANDIDATES, picked = id)
                candidate.executionClass && request.unknowns.isNotEmpty() -> escalate(DiagnosisEscalation.UNKNOWN_BLOCKS_EXECUTION)
                else -> DiagnosisVerdict.Proposed(candidate, response.clean, response)
            }
        }
    }
}
