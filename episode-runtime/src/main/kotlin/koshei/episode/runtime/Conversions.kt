package koshei.episode.runtime

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import koshei.episode.Approver
import koshei.episode.DiagnosisVerdict
import koshei.episode.DispatchIntent
import koshei.episode.DispatchResult
import koshei.episode.EpisodeEvent
import koshei.episode.EpisodeState
import koshei.episode.Evidence
import koshei.episode.Notice
import koshei.episode.PolicyRead
import koshei.episode.RejectReason
import koshei.episode.Symptom
import koshei.episode.SymptomKind
import koshei.episode.TriState
import koshei.episode.UnknownWhat
import java.time.Instant

/** Strict like the core's own mapper: a duplicated key is an error, never silently the last value. */
internal val strictJson: ObjectMapper = ObjectMapper()
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

/**
 * A symptom signal as the core's [Symptom]. A line that is not a JSON object, or an unknown kind, becomes an empty
 * SEARCH line: the core then refuses it with a record (opening: escalates, joining: ignored) — the shell does not judge.
 */
internal fun SymptomSignal.toSymptom(): Symptom {
    val known = SymptomKind.entries.firstOrNull { it.name == kind }
    val node = if (known == null) null else try {
        strictJson.readTree(lineJson) as? ObjectNode
    } catch (e: Exception) {
        null
    }
    return Symptom(eventId, known ?: SymptomKind.SEARCH, node ?: strictJson.createObjectNode(), Instant.ofEpochMilli(observedAtMillis))
}

internal fun EvidenceSignal.toEvidence(): Evidence = Evidence(
    jobResponseId, executionId, physicalState, reachedEvidence, completedUnits, inDoubtUnits, unverifiedUnits,
    operatorRequired, Instant.ofEpochMilli(receivedAtMillis), picassoInstanceId = picassoInstanceId, connection = connection,
)

/** design §8.2: a table that does not even parse is read like a rejected one (the core keeps the last valid table). */
internal fun PolicyReadResult.toRead(): PolicyRead = when (kind) {
    "MISSING" -> PolicyRead.Missing
    "FOUND" -> json?.let {
        try {
            PolicyRead.Found(strictJson.readTree(it))
        } catch (e: Exception) {
            PolicyRead.Unreadable("not JSON: ${e.message}")
        }
    } ?: PolicyRead.Unreadable("FOUND without a table")
    "UNREADABLE" -> PolicyRead.Unreadable(reason ?: "unreadable")
    else -> PolicyRead.Unreadable("unknown read kind $kind")
}

internal fun DecideRequest.validate() {
    require(operatorId.isNotBlank()) { "operatorId is required" }
    require(proposalId.isNotBlank() && sawCandidatesVersion.isNotBlank()) { "proposalId and sawCandidatesVersion are required" }
    require(reason == null || RejectReason.entries.any { it.name == reason }) { "unknown reject reason $reason" }
}

internal fun DecideRequest.toEvent(): EpisodeEvent.ApprovalDecided = EpisodeEvent.ApprovalDecided(
    proposalId, sawCandidatesVersion, approve, Approver(operatorId),
    reason?.let { r -> RejectReason.entries.firstOrNull { it.name == r } }, note,
)

internal fun ConfirmRequest.validate() {
    require(operatorId.isNotBlank()) { "operatorId is required" }
    when (kind) {
        "PRECONDITION", "OUTCOME" -> require(candidateId.isNotBlank() && proposalId.isNotBlank()) { "$kind needs candidateId and proposalId" }
        "UNKNOWN" -> {
            require(subject.isNotEmpty()) { "UNKNOWN needs a subject" }
            require(UnknownWhat.entries.any { it.name == what }) { "unknown what $what" }
        }
        else -> throw IllegalArgumentException("unknown confirm kind $kind")
    }
}

internal fun ConfirmRequest.toEvent(): EpisodeEvent = when (kind) {
    "PRECONDITION" -> EpisodeEvent.ConfirmedPrecondition(candidateId, proposalId, holds, Approver(operatorId), proposition)
    "OUTCOME" -> EpisodeEvent.ConfirmedOutcome(candidateId, proposalId, holds, Approver(operatorId))
    else -> EpisodeEvent.ConfirmedUnknown(LinkedHashMap(subject), UnknownWhat.valueOf(what), Approver(operatorId), note)
}

internal fun TakeoverRequest.validate() = require(operatorId.isNotBlank()) { "operatorId is required" }

internal fun CloseRequest.validate() {
    require(operatorId.isNotBlank()) { "operatorId is required" }
    require(outcome.isNotBlank()) { "outcome is required" }
}

/** A dispatch outcome the core understands; anything else is "did it go out? unknown" (design §11). */
internal fun DispatchOutcome.toResult(): DispatchResult = when {
    kind == "ANSWER" && answerJson != null -> DispatchResult.Answer(answerJson)
    kind == "PERSON_TASK" -> DispatchResult.PersonTaskIssued
    else -> DispatchResult.Uncertain("unrecognised dispatch outcome $kind")
}

internal fun triState(value: String?): TriState = TriState.entries.firstOrNull { it.name == value } ?: TriState.UNKNOWN

/** The execution intent as the record and the dispatch activity see it. */
internal fun intentJson(intent: DispatchIntent): String = strictJson.createObjectNode().apply {
    set<ObjectNode>("candidate", intent.candidate.toJson())
    put("idempotencyKey", intent.idempotencyKey)
    val approval = intent.approval
    if (approval == null) putNull("approval") else putObject("approval").apply {
        put("by", approval.by.name)
        put("approverId", approval.approver?.id)
        put("assurance", approval.approver?.assurance?.name)
        put("at", approval.at.toString())
    }
}.toString()

internal fun noticeJson(notice: Notice): String = strictJson.createObjectNode().apply {
    put("kind", notice.kind.name)
    put("phase", notice.phase.name)
    put("reason", notice.reason?.name)
    put("detail", notice.detail)
}.toString()

/** The query view of a state (design §7.3); never changes the state. */
internal fun viewOf(state: EpisodeState): EpisodeView {
    val attempt = state.attempt
    val unknowns = try {
        state.currentUnknowns().map { it.toJson() }
    } catch (e: Exception) {
        emptyList()
    }
    return EpisodeView(
        instanceId = state.instanceId,
        phase = state.phase.name,
        escalationReason = state.escalation?.reason?.name,
        escalationDetail = state.escalation?.detail,
        attempt = attempt?.number,
        proposalId = attempt?.proposalId,
        candidateId = attempt?.candidate?.candidateId,
        candidatesVersion = attempt?.request?.candidatesVersion,
        stateDeadlineMillis = state.stateDeadline?.toEpochMilli(),
        episodeDeadlineMillis = state.episodeDeadline?.toEpochMilli(),
        historyJson = strictJson.createArrayNode().apply { state.history.forEach { add(it.toJson()) } }.toString(),
        unknownsJson = strictJson.createArrayNode().apply { unknowns.forEach { add(it) } }.toString(),
        symptoms = state.symptoms.size,
        candidateJson = attempt?.candidate?.toJson()?.toString(),
        candidatesJson = attempt?.request?.candidates?.let { cs -> strictJson.createArrayNode().apply { cs.forEach { add(it.toJson()) } }.toString() },
        // An escalating verdict closes the attempt in the same step, so its answer is read from the DIAGNOSIS_RESULT record.
        diagnosisJson = (attempt?.verdict as? DiagnosisVerdict.Proposed)?.response?.raw?.toString(),
        joinedAfterSnapshot = attempt?.let { state.symptoms.size - it.symptomCount },
    )
}
