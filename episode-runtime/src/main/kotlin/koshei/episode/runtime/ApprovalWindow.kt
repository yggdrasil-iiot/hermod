package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode

/**
 * picasso's approval window as the activities use it (design §8.3, §8.4): [MockPicasso] for tests, [HttpApprovalWindow]
 * for `KOSHEI_PICASSO=picasso`.
 *
 * One instance serves every activity of a worker, and activities run on concurrent threads: implementations must be
 * thread-safe.
 */
interface ApprovalWindow {
    /** Re-reads the source for [candidateJson]: `TRUE`, `FALSE` or `UNKNOWN`. */
    fun revalidate(candidateJson: String): String

    /**
     * Asks for approval of the intent's candidate; the answer text exactly as the window sent it (schema 4). [intent] is
     * read-only: the caller still holds it, and implementations must not change it.
     */
    fun approve(intent: JsonNode, approverKind: String?): String
}
