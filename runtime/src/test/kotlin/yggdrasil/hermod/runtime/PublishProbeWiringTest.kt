package yggdrasil.hermod.runtime

import yggdrasil.hermod.dispatch.DispatchAssembly
import yggdrasil.hermod.registry.BlockIndex
import yggdrasil.hermod.registry.BlockStore
import yggdrasil.hermod.registry.ManifestLoader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The registry every engine builds (DispatchAssembly) must check a publish candidate WITHOUT running its code.
 * No DB: Registry.publish consults handlerLoadCheck before the index, and the index here throws — the test
 * only cares that the plugin's static initializer and constructor never ran on the way.
 */
class PublishProbeWiringTest {
    @Test fun `publish-time handler check runs no plugin code`() {
        val clinitKey = "hermod.wiring.clinit.${System.nanoTime()}"
        val ctorKey = "hermod.wiring.ctor.${System.nanoTime()}"
        val jar = FixtureBlockJar.build(
            withManifest = false,
            staticInit = "System.setProperty(\"$clinitKey\", \"ran\");",
            ctorBody = "System.setProperty(\"$ctorKey\", \"ran\");",
        )
        val storeRoot = File.createTempFile("hermod-probe-store", "").let { it.delete(); it.mkdirs(); it }
        try {
            val registry = DispatchAssembly.buildRegistry(
                BlockIndex { error("no database in this test") },
                BlockStore(storeRoot),
            )
            val contract = ManifestLoader.fromYaml(FixtureBlockJar.manifestYaml)

            val ex = runCatching { registry.publish(jar, contract) }.exceptionOrNull()
            assertEquals("no database in this test", ex?.message, "publish must pass the handler check and reach the index")

            assertNull(System.getProperty(clinitKey), "publish must not run the plugin's static initializer")
            assertNull(System.getProperty(ctorKey), "publish must not construct the plugin")
        } finally {
            storeRoot.deleteRecursively()
        }
    }
}
