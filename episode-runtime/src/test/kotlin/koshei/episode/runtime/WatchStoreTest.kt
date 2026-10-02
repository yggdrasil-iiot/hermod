package koshei.episode.runtime

import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WatchStoreTest {
    private lateinit var events: EpisodeStore
    private lateinit var watch: WatchStore

    @BeforeEach fun up() {
        events = EpisodeDb.reset()
        watch = WatchStore(EpisodeDb::connection)
    }

    @Test fun `a cursor is per source and run, and moves forward by appending`() {
        assertNull(watch.cursor("dir#incidents.jsonl", "run-1"))
        watch.advance("dir#incidents.jsonl", "run-1", 1, "incident-1")
        watch.advance("dir#incidents.jsonl", "run-1", 2, "incident-2")
        assertEquals(WatchCursor(2, "incident-2"), watch.cursor("dir#incidents.jsonl", "run-1"))
        assertNull(watch.cursor("dir#incidents.jsonl", "run-2"))
        assertNull(watch.cursor("dir#remedy-searches.jsonl", "run-1"))
    }

    @Test fun `advancing to the same place twice is one row, a different id there is refused`() {
        watch.advance("s", "r", 1, "a")
        watch.advance("s", "r", 1, "a")   // a retry after a crash between the signal and the cursor
        assertFailsWith<IllegalStateException> { watch.advance("s", "r", 1, "b") }
        assertEquals(WatchCursor(1, "a"), watch.cursor("s", "r"))
    }

    @Test fun `the watch log keeps what was not carried`() {
        watch.log("s", "STUCK", """{"why":"x"}""")
        assertEquals(listOf("STUCK"), watch.logs(10).map { it.kind })
        assertEquals("x", strictJson.readTree(watch.logs(10).single().detailJson).get("why").textValue())
    }

    @Test fun `an order finds the episode instances whose intent named it`() {
        val intent = { order: String, kind: String -> """{"candidate":{"candidateId":"c","kind":"$kind","ref":{"robotId":"hum-02","jobOrderId":"$order","searchId":"search-1"},"sawSkillTypes":["pick_place"]},"idempotencyKey":"k","approval":null,"targetUnit":null}""" }
        // The first-written instance sorts after the second by id, so an ORDER BY that ignores min(at) would be caught.
        events.append("ep:z/r1", 1, "OPENED", "{}")
        events.append("ep:z/r1", 7, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1", "APPROVE_REMEDY"))
        events.append("ep:b/r2", 5, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1", "APPROVE_REMEDY"))
        events.append("ep:c/r3", 5, EpisodeActivitiesImpl.INTENT_KIND, intent("SEQ-3", "APPROVE_REMEDY"))
        events.append("ep:d/r4", 5, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1", "CHOOSE_SOURCE"))   // a person task: no executionId
        events.append("ep:z/r1", 9, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1", "APPROVE_REMEDY"))   // a second attempt
        assertEquals(listOf("ep:z/r1", "ep:b/r2"), watch.instancesForOrder("PATROL-1"))
        assertEquals(emptyList(), watch.instancesForOrder("NOPE"))
    }

    @Test fun `the cursor never skips lines`() {
        assertFailsWith<IllegalStateException> { watch.advance("s", "r", 2, "b") }   // nothing carried yet: only #1 may come first
        assertNull(watch.cursor("s", "r"))
        watch.advance("s", "r", 1, "a")
        assertFailsWith<IllegalStateException> { watch.advance("s", "r", 3, "c") }
        assertEquals(WatchCursor(1, "a"), watch.cursor("s", "r"))
        watch.advance("s", "r", 2, "b")
        watch.advance("s", "r", 1, "a")   // an older place with the same id stays a no-op
        assertEquals(WatchCursor(2, "b"), watch.cursor("s", "r"))
        assertFailsWith<IllegalStateException> { watch.advance("s", "r", 1, "x") }   // an older place with another id is refused
    }

    @Test fun `a log detail the database cannot hold is replaced, not lost`() {
        watch.log("s", "BROKEN", "{\"line\":\"a\u0000b\"}")      // a raw NUL
        watch.log("s", "BROKEN", """{"line":"a\u0000b"}""")      // an escaped NUL: jsonb refuses it too
        val rows = watch.logs(10)
        assertEquals(2, rows.size)
        rows.forEach {
            assertEquals("BROKEN", it.kind)
            assertEquals("s", it.source)
            assertEquals(true, strictJson.readTree(it.detailJson).get("unstorable").booleanValue())
        }
    }

    @Test fun `logs come newest first and respect the limit`() {
        watch.log("s", "A", "{}")
        watch.log("s", "B", "{}")
        watch.log("s", "C", "{}")
        assertEquals(listOf("C", "B"), watch.logs(2).map { it.kind })
    }
}
