package yggdrasil.hermod.runtime

import yggdrasil.hermod.dispatch.BuiltinBlocks
import yggdrasil.hermod.dispatch.HandlerRegistry
import yggdrasil.hermod.registry.BlockIndex
import yggdrasil.hermod.registry.BlockStore
import yggdrasil.hermod.registry.Registry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Dynamic HandlerRegistry: builtins resolve to their static BuiltinBlocks instance (DB-free); a
 * published plugin (id,version) resolves via Registry + isolated PluginLoader, and is cached
 * (second get == same instance, so the jar is classloaded exactly once).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HandlerRegistryTest {
    private val pg = PostgreSQLContainer("postgres:16")
    private lateinit var registry: Registry
    private lateinit var storeRoot: File

    private fun connection(): Connection = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)

    @BeforeAll fun up() {
        pg.start()
        BlockIndex(::connection).ensureSchema()
        storeRoot = File.createTempFile("hermod-hr-store", "").let { it.delete(); it.mkdirs(); it }
        // Real Registry: builtins from RuntimeAssembly's loaded manifests; handlerLoadCheck via PluginLoader.
        registry = RuntimeAssembly.buildRegistry(BlockIndex(::connection), BlockStore(storeRoot))
        // Publish the fixture plugin so a (id,version) -> Resolution.Plugin path exists.
        val jar = FixtureBlockJar.build(withManifest = true)
        val result = registry.publish(jar)
        assertTrue(result.ok, "fixture publish failed: ${result.errors}")
    }
    @AfterAll fun down() { pg.stop() }

    @Test fun `builtin resolves to its static BuiltinBlocks instance`() {
        val hr = HandlerRegistry(registry)
        val block = hr.get("db.upsert", "1.2.0")
        assertSame(BuiltinBlocks.byId["db.upsert"], block, "builtin must be the static instance")
    }

    @Test fun `opcua write resolves to its static BuiltinBlocks instance`() {
        val hr = HandlerRegistry(registry)
        val block = hr.get("opcua.write", "1.0.0")
        assertEquals("opcua.write", block.id)
        assertSame(BuiltinBlocks.byId["opcua.write"], block, "opcua.write must be the static builtin instance")
    }

    @Test fun `opcua call resolves to its static BuiltinBlocks instance`() {
        val hr = HandlerRegistry(registry)
        val block = hr.get("opcua.call", "1.0.0")
        assertEquals("opcua.call", block.id)
        assertSame(BuiltinBlocks.byId["opcua.call"], block, "opcua.call must be the static builtin instance")
    }

    @Test fun `delegate score resolves to its static BuiltinBlocks instance`() {
        val hr = HandlerRegistry(registry)
        val block = hr.get("delegate.score", "1.0.0")
        assertEquals("delegate.score", block.id)
        assertSame(BuiltinBlocks.byId["delegate.score"], block, "delegate.score must be the static builtin instance")
    }

    @Test fun `plugin resolves via PluginLoader and is cached`() {
        val hr = HandlerRegistry(registry)
        val first = hr.get(FixtureBlockJar.ID, FixtureBlockJar.VERSION)
        val second = hr.get(FixtureBlockJar.ID, FixtureBlockJar.VERSION)
        assertEquals(FixtureBlockJar.ID, first.id)
        assertSame(first, second, "plugin Block must be cached (loaded once)")
    }
}
