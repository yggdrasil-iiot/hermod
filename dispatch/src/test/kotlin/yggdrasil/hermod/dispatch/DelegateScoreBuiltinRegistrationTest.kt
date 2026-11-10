package yggdrasil.hermod.dispatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** delegate.score must be registered across BuiltinBlocks + DispatchAssembly.builtinContracts (+ Registry.BuiltinIds). */
class DelegateScoreBuiltinRegistrationTest {
    @Test fun `delegate score is in BuiltinBlocks byId`() {
        val block = BuiltinBlocks.byId["delegate.score"]
        assertNotNull(block, "delegate.score must be in BuiltinBlocks.byId")
        assertEquals("delegate.score", block.id)
    }
    @Test fun `delegate score manifest loads and is in builtinContracts`() {
        val contract = DispatchAssembly.builtinContracts["delegate.score#1.0.0"]
        assertNotNull(contract, "delegate.score#1.0.0 must be in DispatchAssembly.builtinContracts")
        assertEquals("delegate.score", contract.id)
        assertEquals("1.0.0", contract.version)
    }
}
