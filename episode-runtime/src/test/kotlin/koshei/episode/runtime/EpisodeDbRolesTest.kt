package koshei.episode.runtime

import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun asRuntime(sql: String) = EpisodeDb.connectionAs("koshei_episode_rt", "rt-test").use { c -> c.createStatement().use { it.execute(sql) } }

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

    @Test fun `the worker's schema step, as the runtime role, checks the owner's schema instead of applying it`() {
        EpisodeStore { EpisodeDb.connectionAs("koshei_episode_rt", "rt-test") }.ensureSchema()
    }

    @Test fun `as the runtime role, a missing table is a clear failure - never a half schema`() {
        EpisodeDb.execAsOwner("ALTER TABLE episode_watch_log RENAME TO episode_watch_log_away")
        try {
            val e = assertFailsWith<IllegalStateException> {
                EpisodeStore { EpisodeDb.connectionAs("koshei_episode_rt", "rt-test") }.ensureSchema()
            }
            assertTrue("episode_watch_log" in e.message.orEmpty() && "owner" in e.message.orEmpty(), e.message)
        } finally {
            EpisodeDb.execAsOwner("ALTER TABLE episode_watch_log_away RENAME TO episode_watch_log")
        }
    }

    @Test fun `as the owner the schema step still applies the script`() {
        EpisodeDb.reset().ensureSchema()   // the container's owner has CREATE: the script runs, idempotent as before
    }
}
