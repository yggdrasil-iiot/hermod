package koshei.episode

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class DeadlinesTest {
    private val p = Policies.valid()   // episodeMs 3_600_000, escalatedRetentionMs 86_400_000, approvalValidityMs 600_000
    private val start = Instant.parse("2026-09-30T00:00:00Z")

    @Test fun `the episode deadline is start plus episodeMs`() {
        assertEquals(Instant.parse("2026-09-30T01:00:00Z"), episodeDeadline(start, p))
    }

    @Test fun `a wait ends at its own budget when that comes first`() {
        val entered = start.plusSeconds(60)
        assertEquals(entered.plusMillis(300_000), waitUntil(entered, 300_000, episodeDeadline(start, p)))
    }

    @Test fun `a wait never outlives the episode`() {
        val entered = start.plusSeconds(3_500)
        assertEquals(episodeDeadline(start, p), waitUntil(entered, 300_000, episodeDeadline(start, p)))
    }

    @Test fun `ESCALATED counts its retention from entry, past the episode deadline`() {
        val entered = episodeDeadline(start, p)
        assertEquals(entered.plusMillis(86_400_000), escalatedUntil(entered, p))
    }

    @Test fun `ESCALATED without any valid policy falls back to 24 hours`() {
        assertEquals(start.plusMillis(FALLBACK_ESCALATED_RETENTION_MS), escalatedUntil(start, null))
        assertEquals(86_400_000L, FALLBACK_ESCALATED_RETENTION_MS)
    }

    @Test fun `an approval is valid until, and not at, basis plus approvalValidityMs`() {
        assertEquals(true, approvalStillValid(start, start.plusMillis(599_999), p))
        assertEquals(false, approvalStillValid(start, start.plusMillis(600_000), p))
    }
}
