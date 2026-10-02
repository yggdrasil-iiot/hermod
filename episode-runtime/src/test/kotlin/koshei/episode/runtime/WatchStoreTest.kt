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
        events.append("ep:a/r1", 1, "OPENED", "{}")
        events.append("ep:a/r1", 7, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1", "APPROVE_REMEDY"))
        events.append("ep:b/r2", 5, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1", "APPROVE_REMEDY"))
        events.append("ep:c/r3", 5, EpisodeActivitiesImpl.INTENT_KIND, intent("SEQ-3", "APPROVE_REMEDY"))
        events.append("ep:d/r4", 5, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1", "CHOOSE_SOURCE"))   // a person task: no executionId
        events.append("ep:a/r1", 9, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1", "APPROVE_REMEDY"))   // a second attempt
        assertEquals(listOf("ep:a/r1", "ep:b/r2"), watch.instancesForOrder("PATROL-1"))
        assertEquals(emptyList(), watch.instancesForOrder("NOPE"))
    }
}
