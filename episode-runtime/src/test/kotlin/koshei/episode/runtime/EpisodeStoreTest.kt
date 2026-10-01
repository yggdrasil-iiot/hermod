package koshei.episode.runtime

import com.fasterxml.jackson.core.JsonProcessingException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeStoreTest {
    private lateinit var store: EpisodeStore

    @BeforeEach fun up() { store = EpisodeDb.reset() }

    @Test fun `records come back in seq order whatever order they landed in`() {
        assertTrue(store.append("ep:a/r1", 2, "TRANSITION", """{"to":"DIAGNOSING"}"""))
        assertTrue(store.append("ep:a/r1", 1, "OPENED", """{"eventId":"search:s-1"}"""))
        assertTrue(store.append("ep:b/r1", 1, "OPENED", """{}"""))
        assertEquals(listOf(1L to "OPENED", 2L to "TRANSITION"), store.events("ep:a/r1").map { it.seq to it.kind })
    }

    @Test fun `the same record again is a quiet no-op - an activity retry`() {
        assertTrue(store.append("ep:a/r1", 1, "OPENED", """{"a":1,"b":2}"""))
        assertFalse(store.append("ep:a/r1", 1, "OPENED", """{"b":2,"a":1}"""))   // the same JSON value
        assertEquals(1, store.events("ep:a/r1").size)
    }

    @Test fun `a different record on a taken seq is a conflict, never overwritten`() {
        store.append("ep:a/r1", 1, "OPENED", """{"a":1}""")
        assertThrows<RecordConflict> { store.append("ep:a/r1", 1, "OPENED", """{"a":2}""") }
        assertThrows<RecordConflict> { store.append("ep:a/r1", 1, "TRANSITION", """{"a":1}""") }
        assertEquals("""{"a": 1}""", store.events("ep:a/r1").single().payloadJson)
    }

    @Test fun `notices are kept in arrival order per episode`() {
        store.notice("ep:a/r1", """{"kind":"APPROVAL_NEEDED"}""")
        store.notice("ep:a/r1", """{"kind":"ESCALATED"}""")
        assertEquals(listOf("APPROVAL_NEEDED", "ESCALATED"), store.notices("ep:a/r1").map { it.substringAfter("\"kind\": \"").substringBefore('"') })
    }

    @Test fun `a dispatch outcome is kept verbatim and the first writer wins`() {
        assertNull(store.outcome("k1"))
        val answer = """{"schemaVersion":"2",  "outcome":"APPROVED"}"""   // odd spacing must survive
        assertEquals(DispatchOutcome("ANSWER", answer), store.saveOutcome("ep:a/r1", "k1", DispatchOutcome("ANSWER", answer)))
        assertEquals(DispatchOutcome("ANSWER", answer), store.saveOutcome("ep:a/r1", "k1", DispatchOutcome("ANSWER", "{}")))
        assertEquals(DispatchOutcome("ANSWER", answer), store.outcome("k1"))
    }

    @Test fun `a person task is issued once - the task and its notice together`() {
        val intent = """{"idempotencyKey":"ep:a/r1:1:CHOOSE_SOURCE","candidate":{"kind":"CHOOSE_SOURCE"}}"""
        assertEquals(DispatchOutcome("PERSON_TASK"), store.issueTask("ep:a/r1", "ep:a/r1:1:CHOOSE_SOURCE", intent))
        assertEquals(DispatchOutcome("PERSON_TASK"), store.issueTask("ep:a/r1", "ep:a/r1:1:CHOOSE_SOURCE", intent))
        assertEquals(1, store.notices("ep:a/r1").size, "one notice for one task")
        assertTrue("PERSON_TASK" in store.notices("ep:a/r1").single() && "CHOOSE_SOURCE" in store.notices("ep:a/r1").single())
        assertEquals(DispatchOutcome("PERSON_TASK"), store.outcome("ep:a/r1:1:CHOOSE_SOURCE"))
    }

    @Test fun `a person task whose notice cannot be written leaves neither row`() {
        // The task row goes in first; the intent is not JSON, so building the notice fails inside the transaction.
        assertThrows<JsonProcessingException> { store.issueTask("ep:a/r1", "ep:a/r1:1:CHOOSE_SOURCE", "not json") }
        assertNull(store.outcome("ep:a/r1:1:CHOOSE_SOURCE"), "the task row rolled back with the notice")
        assertEquals(emptyList(), store.notices("ep:a/r1"))
    }
}
