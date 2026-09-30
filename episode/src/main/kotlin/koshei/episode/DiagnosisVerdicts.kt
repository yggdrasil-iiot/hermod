package koshei.episode

/**
 * Why a diagnosis sends the episode to a person: the subset of design §5.3 that the DIAGNOSING rows of §5.2 produce from a
 * response. DIAGNOSIS_EXPIRED is not here (it comes from the activity failing, not from a response); B2b-2 maps these
 * onto the episode's single escalation-reason type.
 */
enum class DiagnosisEscalation {
    ESCALATE_RECOMMENDED, NO_GROUNDS, UNCITED, OUT_OF_CANDIDATES, CANDIDATES_VERSION_MISMATCH, UNKNOWN_BLOCKS_EXECUTION, DIAGNOSIS_FAILED,
}

sealed interface DiagnosisVerdict {
    /** An offered candidate the transition function may propose; [clean] feeds `requireClean` (design §9.5). */
    data class Proposed(val candidate: Candidate, val clean: Boolean) : DiagnosisVerdict

    /**
     * [picked] is what narrator pointed at: the text or id outside the list for OUT_OF_CANDIDATES (null when there was no
     * recommendation marker), or the blocked id for UNKNOWN_BLOCKS_EXECUTION. The transition never branches on it. [detail]
     * explains a DIAGNOSIS_FAILED.
     */
    data class Escalate(val reason: DiagnosisEscalation, val picked: String? = null, val detail: String? = null) : DiagnosisVerdict
}

/**
 * Checks a diagnosis against the request that was sent (design §5.2, DIAGNOSING rows, in order). The response never
 * decides alone: koshei re-checks that the id was offered (both judges agree on OUT_OF_CANDIDATES) and that no
 * execution-class candidate slipped past the unknowns (a projection defect, reported as UNKNOWN_BLOCKS_EXECUTION).
 */
fun judgeDiagnosis(request: DiagnosisRequest, parse: ResponseParse): DiagnosisVerdict {
    val response = when (parse) {
        is ResponseParse.Invalid -> return DiagnosisVerdict.Escalate(DiagnosisEscalation.DIAGNOSIS_FAILED, detail = parse.errors.joinToString("; "))
        is ResponseParse.Valid -> parse.response
    }
    if (response.contractVersion != DIAGNOSIS_CONTRACT_VERSION || response.episodeId != request.episodeId || response.attempt != request.attempt)
        return DiagnosisVerdict.Escalate(
            DiagnosisEscalation.DIAGNOSIS_FAILED,
            detail = "response is for contract ${response.contractVersion} / ${response.episodeId} attempt ${response.attempt}, " +
                "request was $DIAGNOSIS_CONTRACT_VERSION / ${request.episodeId} attempt ${request.attempt}",
        )
    if (response.sawCandidatesVersion != request.candidatesVersion)
        return DiagnosisVerdict.Escalate(DiagnosisEscalation.CANDIDATES_VERSION_MISMATCH)

    return when (response.outcome) {
        DiagnosisOutcome.NO_GROUNDS -> DiagnosisVerdict.Escalate(DiagnosisEscalation.NO_GROUNDS)
        DiagnosisOutcome.UNCITED -> DiagnosisVerdict.Escalate(DiagnosisEscalation.UNCITED)
        DiagnosisOutcome.OUT_OF_CANDIDATES -> DiagnosisVerdict.Escalate(DiagnosisEscalation.OUT_OF_CANDIDATES, picked = response.picked)
        DiagnosisOutcome.RECOMMENDED -> {
            val id = response.candidateId!!
            val candidate = request.candidates.firstOrNull { it.candidateId == id }
            when {
                id == Candidate.ESCALATE_ID -> DiagnosisVerdict.Escalate(DiagnosisEscalation.ESCALATE_RECOMMENDED)
                candidate == null -> DiagnosisVerdict.Escalate(DiagnosisEscalation.OUT_OF_CANDIDATES, picked = id)
                candidate.executionClass && request.unknowns.isNotEmpty() ->
                    DiagnosisVerdict.Escalate(DiagnosisEscalation.UNKNOWN_BLOCKS_EXECUTION, picked = id)
                else -> DiagnosisVerdict.Proposed(candidate, response.clean)
            }
        }
    }
}
