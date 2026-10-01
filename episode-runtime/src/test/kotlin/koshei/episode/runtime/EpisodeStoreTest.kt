package koshei.episode.runtime

import com.fasterxml.jackson.core.JsonProcessingException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
        store.notice("ep:b/r1", """{"kind":"RECORD_LAG"}""")
        store.notice("ep:a/r1", """{"kind":"ESCALATED"}""")
        assertEquals(listOf("APPROVAL_NEEDED", "ESCALATED"), store.notices("ep:a/r1").map { it.substringAfter("\"kind\": \"").substringBefore('"') })
    }

    @Test fun `a dispatch outcome is kept verbatim and the first writer wins`() {
        assertNull(store.outcome("k1"))
        val answer = """{"schemaVersion":"2",  "outcome":"APPROVED"}"""   // odd spacing must survive
        assertEquals(DispatchOutcome("ANSWER", answer), store.saveOutcome("ep:a/r1", "k1", DispatchOutcome("ANSWER", answer)))
        assertEquals(DispatchOutcome("ANSWER", answer), store.saveOutcome("ep:a/r1", "k1", DispatchOutcome("ANSWER", "{}")))
        assertEquals(DispatchOutcome("ANSWER", answer), store.outcome("k1"))
        store.saveOutcome("ep:a/r1", "k2", DispatchOutcome("ANSWER", """{"outcome":"REJECTED"}"""))
        assertNotEquals(store.outcome("k1"), store.outcome("k2"), "each key has its own outcome")
    }

    @Test fun `an outcome kind outside the two, or an answer without its text, is refused by the table`() {
        assertThrows<SQLException> { store.saveOutcome("ep:a/r1", "k1", DispatchOutcome("APPROVED", "{}")) }
        assertThrows<SQLException> { store.saveOutcome("ep:a/r1", "k2", DispatchOutcome("ANSWER", null)) }
    }

    @Test fun `a person task is issued once - the task and its notice together`() {
        val intent = """{"idempotencyKey":"ep:a/r1:1:CHOOSE_SOURCE","candidate":{"kind":"CHOOSE_SOURCE"}}"""
        assertEquals(DispatchOutcome("PERSON_TASK"), store.issueTask("ep:a/r1", "ep:a/r1:1:CHOOSE_SOURCE", intent))
        assertEquals(DispatchOutcome("PERSON_TASK"), store.issueTask("ep:a/r1", "ep:a/r1:1:CHOOSE_SOURCE", intent))
        assertEquals(1, store.notices("ep:a/r1").size, "one notice for one task")
        assertTrue("PERSON_TASK" in store.notices("ep:a/r1").single() && "CHOOSE_SOURCE" in store.notices("ep:a/r1").single())
        assertEquals(DispatchOutcome("PERSON_TASK"), store.outcome("ep:a/r1:1:CHOOSE_SOURCE"))
    }

    @Test fun `a payload jsonb cannot hold is unstorable - a retry would never succeed`() {
        assertThrows<RecordUnstorable> { store.append("ep:a/r1", 1, "OPENED", nul) }
        assertThrows<RecordUnstorable> { store.append("ep:a/r1", 2, "OPENED", """{"a":"\ud800"}""") }   // a lone surrogate
        assertThrows<RecordUnstorable> { store.notice("ep:a/r1", nul) }
        assertEquals(emptyList(), store.events("ep:a/r1"))
        assertEquals(emptyList(), store.notices("ep:a/r1"))
    }

    @Test fun `a person task whose notice the database refuses leaves neither row`() {
        // The task row goes in first; the notice INSERT then fails in the database, inside the same transaction.
        assertThrows<RecordUnstorable> { store.issueTask("ep:a/r1", "ep:a/r1:1:CHOOSE_SOURCE", nul) }
        assertNull(store.outcome("ep:a/r1:1:CHOOSE_SOURCE"), "the task row rolled back with the notice")
        assertEquals(emptyList(), store.notices("ep:a/r1"))
    }

    @Test fun `a failing rollback does not hide why the task failed`() {
        val held = HeldConnection(failRollback = true)
        try {
            val e = assertThrows<RecordUnstorable> { EpisodeStore { held.proxy }.issueTask("ep:a/r1", "k", nul) }
            assertEquals(listOf("rollback failed: connection gone"), e.cause!!.suppressed.map { it.message })
        } finally {
            held.real.close()
        }
    }

    @Test fun `a failed rollback never commits half a person task`() {
        // A fault on the Kotlin side leaves the transaction open with the task row in it; re-enabling autocommit would
        // commit that row without its notice, and every retry would then find the task and tell no one.
        val held = HeldConnection(failRollback = true)
        try {
            assertThrows<JsonProcessingException> { EpisodeStore { held.proxy }.issueTask("ep:a/r1", "k", "not json") }
            assertNull(store.outcome("k"), "the task row is not committed")   // read on another connection
            assertEquals(emptyList(), store.notices("ep:a/r1"))
        } finally {
            held.real.close()
        }
    }

    @Test fun `when rollback and the autocommit restore both fail, the caller still sees why the task failed`() {
        val held = HeldConnection(failRollback = true, failRestore = true)
        try {
            val e = assertThrows<JsonProcessingException> { EpisodeStore { held.proxy }.issueTask("ep:a/r1", "k", "not json") }
            assertEquals(listOf("rollback failed: connection gone"), e.suppressed.map { it.message })
        } finally {
            held.real.close()
        }
    }

    @Test fun `a person task leaves its connection in autocommit`() {
        val held = HeldConnection(failRollback = false)
        try {
            EpisodeStore { held.proxy }.issueTask("ep:a/r1", "k", """{"x":1}""")
            assertTrue(held.real.autoCommit)
        } finally {
            held.real.close()
        }
    }

    @Test fun `the schema script is safe to run concurrently on a fresh database`() {
        repeat(5) { round ->
            val schema = "episode_fresh_$round"   // a schema of its own, so the shared tables stay as they are
            EpisodeDb.connection().use { it.createStatement().execute("DROP SCHEMA IF EXISTS $schema CASCADE; CREATE SCHEMA $schema") }
            val fresh = EpisodeStore { EpisodeDb.connection().also { c -> c.createStatement().use { it.execute("SET search_path TO $schema") } } }
            val pool = Executors.newFixedThreadPool(4)
            try {
                val barrier = CyclicBarrier(4)
                val failures = (1..4)
                    .map { pool.submit<String?> { barrier.await(); runCatching { fresh.ensureSchema() }.exceptionOrNull()?.message } }
                    .mapNotNull { it.get(60, TimeUnit.SECONDS) }
                assertEquals(emptyList(), failures, "round $round")
                assertEquals(emptyList(), fresh.events("ep:a/r1"), "the tables exist in the fresh schema")
            } finally {
                pool.shutdown()
                EpisodeDb.connection().use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    /** A real connection behind a proxy: closing is held back so its state can be read afterwards; rollback may fail. */
    private class HeldConnection(failRollback: Boolean, failRestore: Boolean = false) {
        val real: Connection = EpisodeDb.connection()
        val proxy: Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, m, args ->
            when {
                m.name == "close" -> null
                m.name == "rollback" && args.isNullOrEmpty() && failRollback -> throw SQLException("rollback failed: connection gone")
                m.name == "setAutoCommit" && args!![0] == true && failRestore -> throw SQLException("autocommit failed: connection gone")
                else -> try { m.invoke(real, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
            }
        } as Connection
    }

    private companion object {
        /** A NUL character, escaped the way Jackson writes it: valid JSON that jsonb cannot hold. */
        const val nul = """{"a":"\u0000"}"""
    }
}
