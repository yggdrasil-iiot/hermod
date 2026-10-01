package koshei.episode.runtime

import com.fasterxml.jackson.databind.node.ObjectNode
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import koshei.runtime.RawJson

/**
 * koshei-side activities of the episode workflow (design §8). Names are explicit: they must not collide with the saga's
 * activities on a shared worker, and they are part of the workflow history. Results are nullable because a payload can
 * carry null whatever an implementation declares; the workflow reads a null result as a failure.
 */
@ActivityInterface
interface EpisodeActivities {
    /** design §8.2: the active policy table as JSON text. Run as a local activity. */
    @ActivityMethod(name = "episodeReadPolicy")
    fun readPolicy(): PolicyReadResult?

    /** design §8.3: re-read the source; `TRUE`, `FALSE` or `UNKNOWN`. */
    @ActivityMethod(name = "episodeRevalidate")
    fun revalidate(candidateJson: String): String?

    /** design §8.5, §13: one append-only record, idempotent on (instance, seq). */
    @ActivityMethod(name = "episodeRecord")
    fun record(instanceId: String, seq: Long, kind: String, payloadJson: String)

    /** design §5.2 DISPATCH_PENDING: the execution intent; its success or failure decides whether anything is dispatched. */
    @ActivityMethod(name = "episodeRecordIntent")
    fun recordIntent(instanceId: String, seq: Long, intentJson: String)

    /**
     * design §8.4: the approval window (APPROVE_REMEDY) or an operator task (the person-task kinds).
     *
     * Obligation for the implementation (plan B3b): it MUST be idempotent on the intent's `idempotencyKey`. Temporal
     * retries this activity, and a retry sends again — the approval window must recognise the key, and a person task
     * must not be issued twice.
     */
    @ActivityMethod(name = "episodeDispatch")
    fun dispatch(intentJson: String, approverKind: String?): DispatchOutcome?

    /** design §8.5: the operator channel. A failure never blocks a transition. */
    @ActivityMethod(name = "episodeNotify")
    fun sendNotice(instanceId: String, noticeJson: String)
}

/** narrator's activity (contract 0.6 §2): queue `narrator-tq`, name `diagnose`, request and answer as exact JSON text. */
@ActivityInterface
interface NarratorActivities {
    @ActivityMethod(name = "diagnose")
    fun diagnose(request: RawJson): RawJson?
}

/**
 * A narrator stand-in for P0 (design §8.1): recommends the first offered candidate other than ESCALATE with a clean,
 * cited answer, or answers NO_GROUNDS when only ESCALATE is offered. Every key of contract 0.6 §4 is written.
 */
class MockNarratorActivities : NarratorActivities {
    override fun diagnose(request: RawJson): RawJson {
        val r = strictJson.readTree(request.text)
        val offered = r.get("candidates").map { it.get("candidateId").asText() }.firstOrNull { it != "ESCALATE" }
        val answer: ObjectNode = strictJson.createObjectNode().apply {
            put("contractVersion", r.get("contractVersion").asText())
            put("episodeId", r.get("episodeId").asText())
            put("attempt", r.get("attempt").asInt())
            put("outcome", if (offered != null) "RECOMMENDED" else "NO_GROUNDS")
            put("candidateId", offered)
            putNull("picked")
            put("sawCandidatesVersion", r.get("candidatesVersion").asText())
            put("rationale", "mock: the first offered candidate [mock-sop, 1]")
            putArray("card")
            putNull("cause")
            putArray("citations").addObject().put("title", "mock-sop").put("section", "1").put("verified", true)
            putArray("unverifiedClaims")
            putArray("uncitedSentences")
            putObject("versions").apply {
                put("modelId", "mock")
                putNull("promptVersion")
                putNull("corpusVersion")
                putNull("searchFingerprint")
                put("narratorCommit", "mock")
            }
            put("elapsedSeconds", 0.0)
        }
        return RawJson(answer.toString())
    }
}
