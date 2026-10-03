package koshei.app

import koshei.blocks.Db
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class EpisodeDbLoginTest {
    @Test fun `the episode login falls back to the shared one, then to Db's defaults`() {
        assertEquals("rt" to "p1", EpisodeDbLogin.fromEnv(mapOf("KOSHEI_EPISODE_DB_USER" to "rt", "KOSHEI_EPISODE_DB_PASS" to "p1", "KOSHEI_DB_USER" to "u", "KOSHEI_DB_PASS" to "p")).let { it.user to it.pass })
        assertEquals(Db.user to Db.pass, EpisodeDbLogin.fromEnv(mapOf("KOSHEI_DB_USER" to "u", "KOSHEI_DB_PASS" to "p")).let { it.user to it.pass })
        assertEquals(Db.user to Db.pass, EpisodeDbLogin.fromEnv(emptyMap()).let { it.user to it.pass }, "exactly the rest of the worker's login")
    }

    @Test fun `a blank episode user is no episode user`() {
        assertEquals(Db.user to Db.pass, EpisodeDbLogin.fromEnv(mapOf("KOSHEI_EPISODE_DB_USER" to " ")).let { it.user to it.pass })
    }

    @Test fun `a user without its password is refused - never mixed with the shared password`() {
        assertFailsWith<IllegalArgumentException> { EpisodeDbLogin.fromEnv(mapOf("KOSHEI_EPISODE_DB_USER" to "rt", "KOSHEI_DB_PASS" to "p")) }
    }

    @Test fun `a password without its user is refused - never used with the shared user`() {
        assertFailsWith<IllegalArgumentException> { EpisodeDbLogin.fromEnv(mapOf("KOSHEI_EPISODE_DB_PASS" to "p1")) }
        assertFailsWith<IllegalArgumentException> { EpisodeDbLogin.fromEnv(mapOf("KOSHEI_EPISODE_DB_USER" to "", "KOSHEI_EPISODE_DB_PASS" to "p1")) }
    }

    @Test fun `the login prints its user, never its password`() {
        val shown = EpisodeDbLogin("rt", "s3cret").toString()
        assertEquals("EpisodeDbLogin(user=rt, pass=***)", shown)
        assertFalse("s3cret" in shown)
    }
}
