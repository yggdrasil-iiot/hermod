package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode

/**
 * picasso's approval window as the activities use it (design §8.3, §8.4): [MockPicasso] today, the real client
 * (`POST /approvals`) later.
 */
interface ApprovalWindow {
    /** Re-reads the source for [candidateJson]: `TRUE`, `FALSE` or `UNKNOWN`. */
    fun revalidate(candidateJson: String): String

    /** Asks for approval of the intent's candidate; the answer text exactly as the window sent it (schema 2). */
    fun approve(intent: JsonNode, approverKind: String?): String
}
