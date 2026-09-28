package koshei.runtime

import koshei.dispatch.PluginLoader
import koshei.sdk.Block
import koshei.sdk.BlockInput
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves ISOLATION, not just "a class loads". The fixture Block is compiled at test time from Java
 * source (no Kotlin compiler needed in-test) against the real :sdk classes, jarred, and loaded via
 * PluginLoader. The class is NEVER on the test/parent classpath (it lives only in the temp jar), so
 * if PluginLoader leaked to the system loader the cast and resolution would fail.
 */
class PluginLoaderTest {

    @Test fun `loads a real Block from an isolated jar with type identity`() {
        val jar = FixtureBlockJar.build(withManifest = false)
        val loaded: Block = PluginLoader().load(jar.absolutePath, FixtureBlockJar.FQCN)

        // (a) type identity across the loader boundary: the cast to koshei.sdk.Block succeeded.
        assertEquals(FixtureBlockJar.ID, loaded.id)

        // (b) forward returns the expected output (real execution through the plugin class).
        val out = loaded.forward(BlockInput())
        assertEquals(listOf(mapOf("hello" to "world")), out.rows)

        // (c) isolation: the class came from a child URLClassLoader, NOT the system loader.
        val cl = loaded.javaClass.classLoader
        assertTrue(cl !== ClassLoader.getSystemClassLoader(), "plugin must not load from system loader")
        assertTrue(cl is URLClassLoader, "plugin must load from a URLClassLoader, got ${cl::class.java}")
    }

    @Test fun `rejects a class that is not a Block`() {
        // String is on the parent classpath but is not a koshei.sdk.Block.
        val ex = runCatching { PluginLoader().load("ignored.jar", "java.lang.String") }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException || ex is IllegalStateException, "expected require() failure, got $ex")
    }

    @Test fun `probe accepts a Block without running its static initializer or constructor`() {
        val clinitKey = "koshei.fixture.clinit.${System.nanoTime()}"
        val ctorKey = "koshei.fixture.ctor.${System.nanoTime()}"
        val jar = FixtureBlockJar.build(
            withManifest = false,
            staticInit = "System.setProperty(\"$clinitKey\", \"ran\");",
            ctorBody = "System.setProperty(\"$ctorKey\", \"ran\");",
        )

        PluginLoader().probe(jar.absolutePath, FixtureBlockJar.FQCN)   // must not throw

        assertNull(System.getProperty(clinitKey), "probe must not run the plugin's static initializer")
        assertNull(System.getProperty(ctorKey), "probe must not construct the plugin")
    }

    @Test fun `probe rejects a class that is not a Block`() {
        assertFailsWith<IllegalArgumentException> { PluginLoader().probe("ignored.jar", "java.lang.String") }
    }

    @Test fun `probe rejects an abstract Block the worker could not instantiate`() {
        val jar = FixtureBlockJar.build(withManifest = false, classModifiers = "public abstract")
        assertFailsWith<IllegalArgumentException> { PluginLoader().probe(jar.absolutePath, FixtureBlockJar.FQCN) }
    }

    @Test fun `probe rejects a Block whose no-arg constructor is not public`() {
        val jar = FixtureBlockJar.build(withManifest = false, ctorModifiers = "private")
        assertFailsWith<IllegalArgumentException> { PluginLoader().probe(jar.absolutePath, FixtureBlockJar.FQCN) }
    }

    @Test fun `probe releases the jar so it can be deleted`() {
        // Only actually guards on Windows: POSIX delete() succeeds even with an open file handle, so this
        // assertion is a no-op safety net on Linux/macOS CI, but it is the whole point on this Windows box.
        val jar = FixtureBlockJar.build(withManifest = false)
        PluginLoader().probe(jar.absolutePath, FixtureBlockJar.FQCN)
        assertTrue(jar.delete(), "probe must close its classloader (Windows keeps an open jar locked)")
    }

    @Test fun `probe rejects a jar with a corrupt class file as a clean rejection, not a LinkageError`() {
        val jar = FixtureBlockJar.buildWithGarbageClassBytes()
        assertFailsWith<IllegalArgumentException> { PluginLoader().probe(jar.absolutePath, FixtureBlockJar.FQCN) }
    }

    @Test fun `probe rejects a package-private Block`() {
        val jar = FixtureBlockJar.build(withManifest = false, classModifiers = "")
        assertFailsWith<IllegalArgumentException> { PluginLoader().probe(jar.absolutePath, FixtureBlockJar.FQCN) }
    }

    @Test fun `probe rejects a Block resolved from the host classpath instead of the plugin jar`() {
        // koshei.blocks.TransformMapBlock is a real, public, concrete Block with a public no-arg ctor that
        // already lives on :runtime's own classpath (via :blocks). Parent-first delegation would resolve
        // it without ever touching the candidate jar, so probe must reject it as not-defined-by-the-jar.
        val jar = FixtureBlockJar.build(withManifest = false)
        assertFailsWith<IllegalArgumentException> {
            PluginLoader().probe(jar.absolutePath, "koshei.blocks.TransformMapBlock")
        }
    }

    @Test fun `probe rejects a class defined by the jar that does not implement Block`() {
        // Exercises the isAssignableFrom branch specifically: the class IS defined by the candidate jar
        // (passes the classloader-identity check) and IS public/concrete/no-arg-constructible (passes the
        // modifier and ctor checks) — it just isn't a koshei.sdk.Block.
        val jar = FixtureBlockJar.buildNonBlock()
        val ex = assertFailsWith<IllegalArgumentException> {
            PluginLoader().probe(jar.absolutePath, FixtureBlockJar.NON_BLOCK_FQCN)
        }
        assertTrue(ex.message?.contains("is not a koshei.sdk.Block") == true, "expected the not-a-Block message, got: ${ex.message}")
    }
}
