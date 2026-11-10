package yggdrasil.hermod.dispatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Verifies that the two OPC-UA blocks are properly registered as builtins across all three sites:
 *   (a) [BuiltinBlocks.byId] (handler instances),
 *   (b) [DispatchAssembly.builtinContracts] (manifest loading),
 *   (c) implicitly: [yggdrasil.hermod.registry.Registry.BuiltinIds] (resolution guard).
 */
class OpcUaBuiltinRegistrationTest {

    @Test fun `opcua write is in BuiltinBlocks byId`() {
        val block = BuiltinBlocks.byId["opcua.write"]
        assertNotNull(block, "opcua.write must be in BuiltinBlocks.byId")
        assertEquals("opcua.write", block.id)
    }

    @Test fun `opcua call is in BuiltinBlocks byId`() {
        val block = BuiltinBlocks.byId["opcua.call"]
        assertNotNull(block, "opcua.call must be in BuiltinBlocks.byId")
        assertEquals("opcua.call", block.id)
    }

    @Test fun `opcua write manifest loads and is in builtinContracts`() {
        val contract = DispatchAssembly.builtinContracts["opcua.write#1.0.0"]
        assertNotNull(contract, "opcua.write#1.0.0 must be in DispatchAssembly.builtinContracts")
        assertEquals("opcua.write", contract.id)
        assertEquals("1.0.0", contract.version)
    }

    @Test fun `opcua call manifest loads and is in builtinContracts`() {
        val contract = DispatchAssembly.builtinContracts["opcua.call#1.0.0"]
        assertNotNull(contract, "opcua.call#1.0.0 must be in DispatchAssembly.builtinContracts")
        assertEquals("opcua.call", contract.id)
        assertEquals("1.0.0", contract.version)
    }
}
