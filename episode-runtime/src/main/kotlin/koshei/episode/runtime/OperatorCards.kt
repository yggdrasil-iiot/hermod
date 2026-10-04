package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode
import koshei.episode.ClaimKind
import koshei.episode.DiagnosisEscalation
import koshei.episode.DiagnosisResponse
import koshei.episode.ResponseParse
import koshei.episode.parseDiagnosisResponse

/** The proposal as the candidate names it (§9.5: from kind and ref, never by taking the id apart). */
data class CardProposal(val candidateId: String, val kind: String, val ref: Map<String, String?>)

/** One guidance line of the narrator's card, in its order (named apart from `:episode`'s CardLine). */
data class GuidanceLine(val label: String, val text: String)

data class CardClaim(val text: String, val foundIn: List<String>?)

/**
 * What a decision must carry back (§9.5 결정 칸): which Update ("decide" in AWAITING_APPROVAL, "confirm" of the
 * precondition in UNKNOWN_PRECONDITION), the proposal it answers, the candidates it saw, and until when. An OUTCOME
 * confirmation in AWAITING_EVIDENCE is sent from the view (proposalId, candidateId) and has no decision here.
 */
data class CardDecision(val update: String, val proposalId: String, val candidatesVersion: String, val deadlineMillis: Long?)

/**
 * The operator card (design §9.5). The narrator fields come from the live view (records may lag, §8.5); the facts —
 * the symptoms that arrived — from the records. Order is §9.5's until §19 D is decided: unknowns first. The
 * narrator's `cause` is recorded only and never reaches the card.
 */
data class OperatorCard(
    val unknowns: List<JsonNode>,
    val symptoms: List<JsonNode>,
    val proposal: CardProposal?,
    val rationale: String?,
    /** The diagnosis gave no rationale: the screen says "이유 없음" rather than leaving a gap (§9.5). */
    val rationaleMissing: Boolean,
    val guidance: List<GuidanceLine>,
    /** Documents that were in the evidence bundle — not facts (R13). */
    val citations: List<JsonNode>,
    val uncitedSentences: List<String>,
    val unverifiedCitations: List<CardClaim>,
    /** CITATION claims with no text: "확인 못 한 인용 n개(본문 없음)". */
    val unlocatedCitations: Int,
    val unverifiedNumbers: List<CardClaim>,
    /** NUMBER claims with no text: "확인 못 한 숫자 n개(위치 모름)" (§9.5). */
    val unlocatedNumbers: Int,
    /** UNKNOWN_PRECONDITION only: the proposition the person confirms, verbatim (§8.3); null rather than invented. */
    val precondition: String?,
    /** APPROVE_REMEDY precondition: the symptoms that joined after the attempt's snapshot (§8.3). */
    val joinedAfterSnapshot: List<JsonNode>,
    val decision: CardDecision?,
    val identityAssurance: String = "SELF_ASSERTED",
    /** The diagnosis could not be read as contract 0.6: the narrator fields are empty, the facts remain. */
    val responseUnreadable: Boolean = false,
    /**
     * The narrator fields (or [responseUnreadable], or [diagnosisRefused]) come from the last DIAGNOSIS_RESULT record, not
     * the live view: an escalating verdict closes its attempt in the same step, so an ESCALATED view has no live answer.
     */
    val diagnosisFromRecord: Boolean = false,
    /**
     * The recorded answer was refused by the judge for who it is (another contract, episode or attempt — DIAGNOSIS_FAILED)
     * or for the candidates it saw (CANDIDATES_VERSION_MISMATCH): it is not shown as narration, and this names the reason.
     */
    val diagnosisRefused: String? = null,
    /** Fewer symptom records have landed than the live view counts: the facts on this card are behind (records lag, §8.5). */
    val factsBehind: Boolean = false,
    /** With [joinedAfterSnapshot]: the live count of symptoms joined after the snapshot; more than the lines shown means records lag. */
    val joinedAfterSnapshotCount: Int? = null,
    /** With [diagnosisRefused]: the judge's detail from the record — a contract §4 breach or whose answer it was. */
    val diagnosisRefusedDetail: String? = null,
    /** The attempt whose answer the narrator fields show (live or recorded); null when none is shown. */
    val diagnosisAttempt: Int? = null,
)

/**
 * What the card shows of a narrator answer: none (withheld, or no answer yet), unreadable, refused by the judge, or a
 * contract 0.6 response.
 */
private sealed interface Narration {
    data object None : Narration
    data object Unreadable : Narration
    data class Refused(val reason: String) : Narration
    data class Read(val response: DiagnosisResponse) : Narration
}

/**
 * Judge reasons under which a recorded answer is not the narrator speaking about this episode's offer: a readable
 * DIAGNOSIS_FAILED is an identity mismatch (contract, episode, attempt) or a contract breach, and a candidates-version
 * mismatch answered another candidate list.
 */
private val REFUSED_ANSWERS = setOf(DiagnosisEscalation.DIAGNOSIS_FAILED.name, DiagnosisEscalation.CANDIDATES_VERSION_MISMATCH.name)

private fun narrationOf(answer: JsonNode?, judged: String? = null): Narration =
    when (val parse = answer?.let(::parseDiagnosisResponse)) {
        is ResponseParse.Valid -> if (judged != null && judged in REFUSED_ANSWERS) Narration.Refused(judged) else Narration.Read(parse.response)
        is ResponseParse.Invalid, null -> Narration.Unreadable
    }

fun operatorCard(view: EpisodeView, events: List<StoredEvent>): OperatorCard {
    val records = events.sortedBy { it.seq }.mapNotNull { e -> readOrNull(e.payloadJson)?.let { e to it } }
    // §9.5, §19 D: an episode withheld for a person shows the facts and the ESCALATE task only.
    val withheld = view.escalationReason == "WITHHELD_HUMAN_FIRST"
    val proposal = if (withheld) null else view.candidateJson?.let(::readOrNull)?.let(::proposalOf)
    val live = view.diagnosisJson
    val recorded = if (withheld || live != null || view.phase != "ESCALATED") null else escalatingAnswer(view, records)
    val narration = when {
        withheld -> Narration.None
        live != null -> narrationOf(readOrNull(live))
        recorded != null -> narrationOf(recorded.get("response"), recorded.get("reason")?.takeIf { it.isTextual }?.textValue())
        else -> Narration.None
    }
    val response = (narration as? Narration.Read)?.response
    val diagnosisAttempt = when {
        response == null -> null
        recorded != null -> recorded.path("attempt").takeIf { it.isIntegralNumber }?.intValue()
        else -> view.attempt
    }
    val claims = response?.unverifiedClaims.orEmpty()
    fun claimsOf(kind: ClaimKind) = claims.filter { it.kind == kind }

    val precondition = if (view.phase == "UNKNOWN_PRECONDITION") proposal?.let(::propositionOf) else null
    val remedyPrecondition = precondition != null && proposal?.kind == "APPROVE_REMEDY"
    val joinedAfterSnapshot = if (remedyPrecondition) {
        val requested = records.firstOrNull { (e, p) -> e.kind == "DIAGNOSIS_REQUESTED" && view.attempt != null && p.path("attempt").asInt() == view.attempt }
        if (requested == null) emptyList() else records
            .filter { (e, _) -> e.kind == "SYMPTOM_JOINED" && e.seq > requested.first.seq }
            .mapNotNull { (_, p) -> lineOf(p) }
    } else emptyList()
    val symptoms = records.filter { (e, _) -> e.kind == "OPENED" || e.kind == "SYMPTOM_JOINED" }.mapNotNull { (_, p) -> lineOf(p) }

    val proposalId = view.proposalId
    val candidatesVersion = view.candidatesVersion
    val decision = when {
        proposal == null || proposalId == null || candidatesVersion == null -> null
        view.phase == "AWAITING_APPROVAL" -> CardDecision("decide", proposalId, candidatesVersion, view.stateDeadlineMillis)
        view.phase == "UNKNOWN_PRECONDITION" && precondition != null -> CardDecision("confirm", proposalId, candidatesVersion, view.stateDeadlineMillis)
        else -> null
    }

    return OperatorCard(
        unknowns = readOrNull(view.unknownsJson)?.takeIf { it.isArray }?.toList().orEmpty(),
        symptoms = symptoms,
        proposal = proposal,
        rationale = response?.rationale,
        rationaleMissing = response != null && response.rationale == null,
        guidance = response?.card.orEmpty().map { GuidanceLine(it.label, it.text) },
        citations = response?.citations.orEmpty(),
        uncitedSentences = response?.uncitedSentences.orEmpty(),
        unverifiedCitations = claimsOf(ClaimKind.CITATION).mapNotNull { c -> c.text?.let { CardClaim(it, c.foundIn) } },
        unlocatedCitations = claimsOf(ClaimKind.CITATION).count { it.text == null },
        unverifiedNumbers = claimsOf(ClaimKind.NUMBER).mapNotNull { c -> c.text?.let { CardClaim(it, c.foundIn) } },
        unlocatedNumbers = claimsOf(ClaimKind.NUMBER).count { it.text == null },
        precondition = precondition,
        joinedAfterSnapshot = joinedAfterSnapshot,
        decision = decision,
        responseUnreadable = narration == Narration.Unreadable,
        diagnosisFromRecord = recorded != null,
        diagnosisRefused = (narration as? Narration.Refused)?.reason,
        factsBehind = symptoms.size < view.symptoms,
        joinedAfterSnapshotCount = if (remedyPrecondition) view.joinedAfterSnapshot else null,
        diagnosisRefusedDetail = if (narration is Narration.Refused) recorded?.get("detail")?.takeIf { it.isTextual }?.textValue() else null,
        diagnosisAttempt = diagnosisAttempt,
    )
}

/**
 * The DIAGNOSIS_RESULT record of the answer that escalated this episode, or null. Many escalations write no new result
 * (a failed or expired diagnosis, a takeover, ATTEMPTS_EXHAUSTED, APPROVAL_EXPIRED, REPEATED_REMEDY …), and the last
 * result is then an earlier answer — often a superseded proposal — that must not be shown as the reason. So the last
 * result counts only when it escalated (verdict ESCALATE), for the reason the episode escalated with (the judge's names
 * map one to one onto the escalation reasons, `Diagnosing.kt`), and answers the last attempt requested.
 */
private fun escalatingAnswer(view: EpisodeView, records: List<Pair<StoredEvent, JsonNode>>): JsonNode? {
    val last = records.lastOrNull { (e, _) -> e.kind == "DIAGNOSIS_RESULT" }?.second ?: return null
    val lastAttempt = records.filter { (e, _) -> e.kind == "DIAGNOSIS_REQUESTED" }.maxOfOrNull { (_, p) -> p.path("attempt").asInt() }
    return last.takeIf {
        it.path("verdict").asText() == "ESCALATE" &&
            view.escalationReason != null && it.path("reason").asText() == view.escalationReason &&
            lastAttempt != null && it.path("attempt").asInt() == lastAttempt
    }
}

/** A record's `line`, only when it is the object a symptom line is. */
private fun lineOf(payload: JsonNode): JsonNode? = payload.get("line")?.takeIf { it.isObject }

/** A card never fails on what it reads: text that is not JSON (or has a duplicated key) is simply not there. */
private fun readOrNull(text: String): JsonNode? = try {
    strictJson.readTree(text)
} catch (e: Exception) {
    null
}

private fun proposalOf(c: JsonNode): CardProposal? {
    val id = c.get("candidateId")?.takeIf { it.isTextual }?.textValue() ?: return null
    val kind = c.get("kind")?.takeIf { it.isTextual }?.textValue() ?: return null
    val ref = c.get("ref")?.takeIf { it.isObject }?.properties()?.associate { (k, v) -> k to if (v.isNull) null else v.asText() }.orEmpty()
    return CardProposal(id, kind, ref)
}

/**
 * The proposition a person checks for this candidate (design §8.3), its ref values filled in. Null — never a guess — when
 * the kind has none or a value it names is missing: a person must not confirm a proposition nobody wrote (§8.3).
 */
private fun propositionOf(p: CardProposal): String? {
    fun v(key: String): String? = p.ref[key]?.takeIf { it.isNotBlank() }
    return when (p.kind) {
        "APPROVE_REMEDY" -> {
            val robotId = v("robotId") ?: return null
            val jobOrderId = v("jobOrderId") ?: return null
            val searchId = v("searchId") ?: return null
            "($robotId, $jobOrderId)에 searchId $searchId 뒤로 더 새 탐색 줄이 없다"
        }
        "CHOOSE_SOURCE" -> {
            val jobOrderId = v("jobOrderId") ?: return null
            val material = v("material") ?: return null
            val alternative = v("alternative") ?: return null
            "주문 $jobOrderId 의 자재 $material 이 아직 결품이고 대체 위치 $alternative 가 아직 쓸 수 있다"
        }
        "OPERATOR_DECISION" -> {
            val executionId = v("executionId") ?: return null
            val unitId = v("unitId") ?: return null
            "실행 $executionId 의 단위 $unitId 가 아직 운영자 보류이고 판단이 나지 않았다"
        }
        else -> null
    }
}
