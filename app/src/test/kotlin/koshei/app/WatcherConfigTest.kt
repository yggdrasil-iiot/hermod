package koshei.app

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WatcherConfigTest {
    private val sep = File.pathSeparator
    private val on = "KOSHEI_PICASSO" to "mock"

    @Test fun `exports, carry, interval and policy come from the environment`() {
        val c = WatcherConfig.fromEnv(mapOf(
            on, "KOSHEI_WATCH_EXPORTS" to "a${sep}b", "KOSHEI_WATCH_CARRY" to "c", "KOSHEI_WATCH_INTERVAL_MS" to "500", "KOSHEI_EPISODE_POLICY" to "p.yaml",
        ))
        assertEquals(listOf(Path.of("a"), Path.of("b")), c.exports)
        assertEquals(Path.of("c"), c.carry)
        assertEquals(500, c.intervalMs)
        assertEquals(Path.of("p.yaml"), c.policyPath)
    }

    @Test fun `defaults`() {
        val c = WatcherConfig.fromEnv(mapOf(on, "KOSHEI_WATCH_EXPORTS" to "a"))
        assertNull(c.carry)
        assertEquals(2000, c.intervalMs)
        assertEquals(Path.of("model/episode-policy/active.yaml"), c.policyPath)
    }

    @Test fun `the real window is episode mode too - the watcher starts with KOSHEI_PICASSO=picasso`() =
        assertEquals(listOf(Path.of("a")), WatcherConfig.fromEnv(mapOf("KOSHEI_PICASSO" to "picasso", "KOSHEI_WATCH_EXPORTS" to "a")).exports)

    @Test fun `what is wrong stops the start`() {
        listOf(
            mapOf(on),
            mapOf(on, "KOSHEI_WATCH_EXPORTS" to ""),
            mapOf(on, "KOSHEI_WATCH_EXPORTS" to "a$sep"),
            mapOf(on, "KOSHEI_WATCH_EXPORTS" to "a", "KOSHEI_WATCH_INTERVAL_MS" to "50"),
            mapOf(on, "KOSHEI_WATCH_EXPORTS" to "a", "KOSHEI_WATCH_INTERVAL_MS" to "x"),
            mapOf(on, "KOSHEI_WATCH_EXPORTS" to "a", "KOSHEI_WATCH_CARRY" to " "),
        ).forEach { assertFailsWith<IllegalArgumentException>(it.toString()) { WatcherConfig.fromEnv(it) } }
    }

    @Test fun `outside episode mode the watcher does not start - no worker would run what it opens`() {
        assertFailsWith<IllegalArgumentException> { WatcherConfig.fromEnv(mapOf("KOSHEI_WATCH_EXPORTS" to "a")) }
        assertFailsWith<IllegalArgumentException> { WatcherConfig.fromEnv(mapOf("KOSHEI_PICASSO" to "off", "KOSHEI_WATCH_EXPORTS" to "a")) }
        assertFailsWith<IllegalArgumentException> { WatcherConfig.fromEnv(mapOf("KOSHEI_PICASSO" to "real", "KOSHEI_WATCH_EXPORTS" to "a")) }
    }
}
