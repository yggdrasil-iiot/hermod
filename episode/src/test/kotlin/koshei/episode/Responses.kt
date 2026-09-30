package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Builds a contract-0.6 response for a given request, every key present. Tests edit the result to break one rule at a
 * time. The default is a clean RECOMMENDED answer pointing at the request's first non-ESCALATE candidate.
 */
internal object Responses {
    fun forRequest(
        request: DiagnosisRequest,
        outcome: String = "RECOMMENDED",
        candidateId: String? = request.candidates.first { it.kind != CandidateKind.ESCALATE }.candidateId,
        picked: String? = null,
        edit: ObjectNode.() -> Unit = {},
    ): ObjectNode = mapper.createObjectNode().apply {
        put("contractVersion", DIAGNOSIS_CONTRACT_VERSION)
        put("episodeId", request.episodeId)
        put("attempt", request.attempt)
        put("outcome", outcome)
        put("candidateId", if (outcome == "RECOMMENDED") candidateId else null)
        put("picked", picked)
        put("sawCandidatesVersion", request.candidatesVersion)
        put("rationale", "재고는 있고 경로만 막혔다 [출처: 결품 대응 절차, 3.2]")
        putArray("card").addObject().put("label", "절차").put("text", "대체 위치에서 집는다")
        put("cause", "AMR 이송 지연")
        putArray("citations").addObject().put("title", "결품 대응 절차").put("section", "3.2").put("verified", true)
        putArray("unverifiedClaims")
        putArray("uncitedSentences")
        putObject("versions").apply {
            put("modelId", "claude-sonnet-5"); putNull("promptVersion"); putNull("corpusVersion")
            putNull("searchFingerprint"); put("narratorCommit", "0000000")
        }
        put("elapsedSeconds", 12.5)
        edit()
    }

    /** Request 01 of the contract fixtures: APPROVE_REMEDY + ESCALATE, no unknowns. */
    fun request01(): DiagnosisRequest =
        diagnosisRequest("ep:fixture-01-recommended", 1, PicassoRun1.snapshot(listOf("incident-1"), listOf("search-1")))

    /** Request 04 of the contract fixtures: LINK_BROKEN, ESCALATE + CONFIRM_DONE only. */
    fun request04(): DiagnosisRequest =
        diagnosisRequest("ep:fixture-04-unknown", 1, PicassoRun1.snapshot(incidentIds = listOf("incident-6")))
}
