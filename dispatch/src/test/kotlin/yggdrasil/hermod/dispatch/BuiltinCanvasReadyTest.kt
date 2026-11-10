package yggdrasil.hermod.dispatch
import yggdrasil.hermod.registry.CanvasReadiness
import kotlin.test.Test
import kotlin.test.assertTrue

class BuiltinCanvasReadyTest {
    @Test fun `all builtin contracts are canvas-ready`() {
        DispatchAssembly.builtinContracts.forEach { (key, c) ->
            assertTrue(CanvasReadiness.isReady(c), "$key not canvas-ready: ${CanvasReadiness.check(c)}")
        }
    }
}
