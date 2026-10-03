package koshei.app

import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeDbLoginTest {
    @Test fun `the episode login falls back to the shared one, then to Db's defaults`() {
        assertEquals("rt" to "p1", EpisodeDbLogin.fromEnv(mapOf("KOSHEI_EPISODE_DB_USER" to "rt", "KOSHEI_EPISODE_DB_PASS" to "p1", "KOSHEI_DB_USER" to "u", "KOSHEI_DB_PASS" to "p")).let { it.user to it.pass })
        assertEquals(koshei.blocks.Db.user to koshei.blocks.Db.pass, EpisodeDbLogin.fromEnv(mapOf("KOSHEI_DB_USER" to "u", "KOSHEI_DB_PASS" to "p")).let { it.user to it.pass })
        assertEquals(koshei.blocks.Db.user to koshei.blocks.Db.pass, EpisodeDbLogin.fromEnv(emptyMap()).let { it.user to it.pass }, "exactly the rest of the worker's login")
    }

    @Test fun `a user without its password is refused - never mixed with the shared password`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { EpisodeDbLogin.fromEnv(mapOf("KOSHEI_EPISODE_DB_USER" to "rt", "KOSHEI_DB_PASS" to "p")) }
    }
}
