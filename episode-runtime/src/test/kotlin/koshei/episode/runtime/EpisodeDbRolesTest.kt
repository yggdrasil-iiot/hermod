package koshei.episode.runtime

import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeDbRolesTest {
    private val script: String = Files.readString(Path.of(System.getProperty("koshei.repoRoot"), "scripts", "episode-db-roles.sql"))

    /** The script with psql's `:'rt_password'` bound, as `psql -v rt_password=…` would. */
    private fun grants(password: String = "rt-test") = script.replace(":'rt_password'", "'$password'")

    @BeforeEach fun up() {
        EpisodeDb.reset()                  // the schema, applied by the owner
        EpisodeDb.execAsOwner(grants())
        EpisodeDb.execAsOwner(grants())    // twice: the script is idempotent
    }

    private fun runtime(): Connection = EpisodeDb.connectionAs("koshei_episode_rt", "rt-test")

    private fun asRuntime(sql: String) = runtime().use { c -> c.createStatement().use { it.execute(sql) } }

    /** One boolean, as the owner. */
    private fun ownerSays(sql: String): Boolean =
        EpisodeDb.connection().use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getBoolean(1) } } }

    @Test fun `the runtime role appends and reads every episode table`() {
        asRuntime("INSERT INTO episode_event (episode_instance_id, seq, kind, payload) VALUES ('i/r', 1, 'OPENED', '{}')")
        asRuntime("INSERT INTO episode_notice (episode_instance_id, notice) VALUES ('i/r', '{}')")
        asRuntime("INSERT INTO episode_dispatch (idempotency_key, episode_instance_id, outcome_kind, answer) VALUES ('k', 'i/r', 'ANSWER', 'a')")
        asRuntime("INSERT INTO episode_watch_cursor (source, run_id, position, last_id) VALUES ('s', 'r', 1, 'x')")
        asRuntime("INSERT INTO episode_watch_log (source, kind, detail) VALUES ('s', 'BROKEN', '{}')")
        asRuntime("SELECT count(*) FROM episode_event")
    }

    @Test fun `the runtime role can neither change nor remove a row, nor empty a table`() {
        asRuntime("INSERT INTO episode_event (episode_instance_id, seq, kind, payload) VALUES ('i/r', 1, 'OPENED', '{}')")
        for (table in listOf("episode_event", "episode_notice", "episode_dispatch", "episode_watch_cursor", "episode_watch_log")) {
            for (sql in listOf("UPDATE $table SET at = now()", "DELETE FROM $table", "TRUNCATE $table")) {
                val e = assertFailsWith<SQLException>(sql) { asRuntime(sql) }
                assertEquals("42501", e.sqlState, "$sql: ${e.message}")   // insufficient_privilege
            }
        }
    }

    @Test fun `the real stores, as the runtime role, run every statement they have`() {
        val episodes = EpisodeStore(::runtime)
        val intent = """{"candidate":{"kind":"APPROVE_REMEDY","ref":{"jobOrderId":"JO-1"}}}"""
        assertTrue(episodes.append("wf/r", 1, "OPENED", "{}"))
        assertFalse(episodes.append("wf/r", 1, "OPENED", "{}"), "the retry reads the row back")
        assertTrue(episodes.append("wf/r", 2, "DISPATCH_INTENT", intent))
        episodes.notice("wf/r", """{"kind":"NOTE"}""")
        assertEquals(DispatchOutcome("ANSWER", "a"), episodes.saveOutcome("wf/r", "k1", DispatchOutcome("ANSWER", "a")))
        assertEquals(DispatchOutcome("ANSWER", "a"), episodes.saveOutcome("wf/r", "k1", DispatchOutcome("ANSWER", "b")), "the first writer wins")
        assertEquals(DispatchOutcome("PERSON_TASK"), episodes.issueTask("wf/r", "k2", """{"x":1}"""))
        assertEquals(DispatchOutcome("PERSON_TASK"), episodes.issueTask("wf/r", "k2", """{"x":1}"""), "issued once")
        assertNull(episodes.outcome("none"))
        assertEquals(listOf(1L, 2L), episodes.events("wf/r").map { it.seq })
        assertEquals(listOf("wf/r"), episodes.instances(10).map { it.instanceId })
        assertEquals(2, episodes.noticeRows("wf/r").size)
        assertEquals(2, episodes.noticesAfter(0, 10).size)
        assertEquals(2, episodes.notices("wf/r").size)

        val watch = WatchStore(::runtime)
        assertNull(watch.cursor("s", "run"))
        watch.advance("s", "run", 1, "a")
        watch.advance("s", "run", 1, "a")   // the same place again reads the row back
        assertEquals(WatchCursor(1, "a"), watch.cursor("s", "run"))
        watch.log("s", "BROKEN", """{"line":"x"}""")
        watch.log("s", "BROKEN", """{"line":"\u0000"}""")   // jsonb refuses NUL: the fallback row
        assertEquals(listOf("""{"unstorable": true}""", """{"line": "x"}"""), watch.logs(10).map { it.detailJson })
        assertEquals(listOf("wf/r"), watch.instancesForOrder("JO-1"))
    }

    @Test fun `the worker's schema step, as the runtime role, checks the owner's schema instead of applying it`() {
        EpisodeStore(::runtime).ensureSchema()
    }

    @Test fun `a runtime role that may create in the schema still only checks - the tables stay the owner's`() {
        EpisodeDb.execAsOwner("GRANT CREATE ON SCHEMA public TO koshei_episode_rt")   // as on a database where PUBLIC still has it
        try {
            EpisodeStore(::runtime).ensureSchema()
            assertFalse(ownerSays("SELECT bool_or(pg_get_userbyid(relowner) = 'koshei_episode_rt') FROM pg_class WHERE relname LIKE 'episode%'"))
        } finally {
            EpisodeDb.execAsOwner("REVOKE CREATE ON SCHEMA public FROM koshei_episode_rt")
        }
    }

    @Test fun `as the runtime role, a missing table is a clear failure - never a half schema`() {
        EpisodeDb.execAsOwner("ALTER TABLE episode_watch_log RENAME TO episode_watch_log_away")
        try {
            val e = assertFailsWith<IllegalStateException> { EpisodeStore(::runtime).ensureSchema() }
            assertTrue("episode_watch_log" in e.message.orEmpty() && "owner" in e.message.orEmpty(), e.message)
        } finally {
            EpisodeDb.execAsOwner("ALTER TABLE episode_watch_log_away RENAME TO episode_watch_log")
        }
    }

    @Test fun `as the owner the schema step still applies the script`() {
        val store = EpisodeDb.reset()
        EpisodeDb.execAsOwner("DROP INDEX episode_notice_instance")
        store.ensureSchema()
        assertTrue(ownerSays("SELECT to_regclass('episode_notice_instance') IS NOT NULL"), "the owner's schema step ran the DDL again")
    }

    @Test fun `the roles script refuses a runtime role that may create in the schema`() {
        EpisodeDb.execAsOwner("GRANT CREATE ON SCHEMA public TO koshei_episode_rt")
        try {
            val e = assertFailsWith<SQLException> { EpisodeDb.execAsOwner(grants()) }
            assertTrue("CREATE" in e.message.orEmpty() && "PUBLIC" in e.message.orEmpty(), e.message)
        } finally {
            EpisodeDb.execAsOwner("REVOKE CREATE ON SCHEMA public FROM koshei_episode_rt")
        }
    }

    @Test fun `the roles script takes back what an existing role was given beyond its grants`() {
        EpisodeDb.execAsOwner("ALTER ROLE koshei_episode_rt NOLOGIN CREATEDB CREATEROLE")
        EpisodeDb.execAsOwner("GRANT UPDATE ON SEQUENCE episode_notice_id_seq TO koshei_episode_rt")
        try {
            EpisodeDb.execAsOwner(grants())
            assertTrue(ownerSays("SELECT rolcanlogin AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolsuper FROM pg_roles WHERE rolname = 'koshei_episode_rt'"))
            assertFalse(ownerSays("SELECT has_sequence_privilege('koshei_episode_rt', 'episode_notice_id_seq', 'UPDATE')"))
        } finally {   // never leave the role unable to log in for the next test
            EpisodeDb.execAsOwner("ALTER ROLE koshei_episode_rt LOGIN NOCREATEDB NOCREATEROLE")
            EpisodeDb.execAsOwner("REVOKE UPDATE ON SEQUENCE episode_notice_id_seq FROM koshei_episode_rt")
        }
    }

    // Drift guards: the schema, the runtime role's check and the roles script name the same objects.

    private val schemaSql: String = checkNotNull(javaClass.getResourceAsStream("/episode-schema.sql")).bufferedReader().use { it.readText() }

    @Test fun `the runtime role's check names every table and index the schema script creates`() {
        // ^ in multiline mode: statements only, not the comments that mention CREATE TABLE IF NOT EXISTS
        val created = Regex("""^CREATE (TABLE|INDEX) IF NOT EXISTS (\w+)""", RegexOption.MULTILINE).findAll(schemaSql).map { it.groupValues[2] }.toList()
        assertEquals(created.toSet(), EpisodeStore.SCHEMA_OBJECTS.toSet())
        assertEquals(EpisodeStore.SCHEMA_OBJECTS.size, EpisodeStore.SCHEMA_OBJECTS.toSet().size, "no name twice")
    }

    @Test fun `the roles script grants on exactly the schema's five tables`() {
        val tables = Regex("""^CREATE TABLE IF NOT EXISTS (\w+)""", RegexOption.MULTILINE).findAll(schemaSql).map { it.groupValues[1] }.toSet()
        assertEquals(5, tables.size)
        for (statement in listOf("""REVOKE ALL ON (?!SEQUENCE)([\w, ]+) FROM koshei_episode_rt""", """GRANT SELECT, INSERT ON ([\w, ]+) TO koshei_episode_rt""")) {
            val named = checkNotNull(Regex(statement).find(script)) { "the roles script has no $statement" }.groupValues[1]
            assertEquals(tables, named.split(",").map { it.trim() }.toSet(), statement)
        }
    }
}
