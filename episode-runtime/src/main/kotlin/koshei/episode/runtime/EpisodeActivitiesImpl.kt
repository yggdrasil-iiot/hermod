package koshei.episode.runtime

import io.temporal.failure.ApplicationFailure
import org.slf4j.LoggerFactory

/**
 * The koshei-side activities of plan B (design §8.2–§8.5): the policy file, the episode tables, and picasso's
 * approval window (Mock picasso for now). They read, write and carry; every judgement stays in the pure core.
 *
 * A failure no retry can mend — a different record on a taken seq ([RecordConflict]) or a value the database cannot
 * hold ([RecordUnstorable]) — leaves as a non-retryable [ApplicationFailure] typed with the exception's name.
 */
class EpisodeActivitiesImpl(
    private val policy: PolicyFileReader,
    private val store: EpisodeStore,
    private val picasso: ApprovalWindow,
) : EpisodeActivities {

    override fun readPolicy(): PolicyReadResult = policy.read()

    override fun revalidate(candidateJson: String): String = picasso.revalidate(candidateJson)

    override fun record(instanceId: String, seq: Long, kind: String, payloadJson: String) {
        append(instanceId, seq, kind, payloadJson)
    }

    override fun recordIntent(instanceId: String, seq: Long, intentJson: String) {
        append(instanceId, seq, INTENT_KIND, intentJson)
    }

    /**
     * Idempotent on the intent's key (design §8.4, §11): a stored outcome is returned without calling anyone. A person
     * task is issued once. A remedy whose answer was lost before it was stored meets picasso's consumption on retry,
     * which the core reads as UNKNOWN(OUTCOME). The intent is parsed before anything is stored, as
     * [EpisodeStore.issueTask] requires.
     */
    override fun dispatch(instanceId: String, intentJson: String, approverKind: String?): DispatchOutcome {
        val intent = try {
            strictJson.readTree(intentJson)
        } catch (e: Exception) {
            throw ApplicationFailure.newNonRetryableFailure("intent is not JSON: ${e.message}", "BadIntent")
        }
        val key = intent?.path("idempotencyKey")?.asText().orEmpty()
        if (key.isBlank()) throw ApplicationFailure.newNonRetryableFailure("intent without idempotencyKey", "BadIntent")
        store.outcome(key)?.let { return it }
        return nonRetryableOnBadValue {
            when (val kind = intent.path("candidate").path("kind").asText()) {
                "APPROVE_REMEDY" -> store.saveOutcome(instanceId, key, DispatchOutcome("ANSWER", picasso.approve(intent, approverKind)))
                "CHOOSE_SOURCE", "OPERATOR_DECISION" -> store.issueTask(instanceId, key, intentJson).also {
                    // Not the intent: it carries the approver's id.
                    if (it.kind == "PERSON_TASK") log.info("episode person task {} {}", instanceId, key)
                }
                else -> throw ApplicationFailure.newNonRetryableFailure("no dispatch target for $kind", "NoDispatchTarget")
            }
        }
    }

    /**
     * Logged before it is stored, so the notice reaches the log even when the database is down. At least once: a retry
     * logs it again, and a crash after the commit stores it twice.
     */
    override fun sendNotice(instanceId: String, noticeJson: String) {
        log.info("episode notice {} {}", instanceId, noticeJson)
        nonRetryableOnBadValue { store.notice(instanceId, noticeJson) }
    }

    private fun append(instanceId: String, seq: Long, kind: String, payloadJson: String) {
        try {
            nonRetryableOnBadValue { store.append(instanceId, seq, kind, payloadJson) }
        } catch (e: RecordConflict) {
            throw ApplicationFailure.newNonRetryableFailure(e.message, "RecordConflict")
        }
    }

    private inline fun <T> nonRetryableOnBadValue(block: () -> T): T = try {
        block()
    } catch (e: RecordUnstorable) {
        throw ApplicationFailure.newNonRetryableFailure(e.message, "RecordUnstorable")
    }

    companion object {
        /** The intent record's kind in episode_event (the core's RecordKind has no value for it: it is a command). */
        const val INTENT_KIND = "DISPATCH_INTENT"
        private val log = LoggerFactory.getLogger(EpisodeActivitiesImpl::class.java)
    }
}
