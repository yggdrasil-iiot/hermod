package yggdrasil.hermod.opcua

import yggdrasil.hermod.registry.ContractValidator
import yggdrasil.hermod.registry.ManifestLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * In-module manifest validation: catches an invalid sideEffects token, bad field, or validator
 * rule at `:opcua:test` time rather than at runtime registry-assembly.
 *
 * Mirrors [yggdrasil.hermod.blocks.ManifestsValidateTest] but covers the two OPC-UA manifests.
 * Uses `:registry` (test scope only; `:opcua` main stays 0-registry).
 */
class ManifestsValidateTest {

    private val manifestIds = listOf("opcua.write", "opcua.call")

    private val expectedVersions = mapOf(
        "opcua.write" to "1.0.0",
        "opcua.call"  to "1.0.0",
    )

    private fun loadResource(id: String): String =
        requireNotNull(this::class.java.getResourceAsStream("/manifests/$id.yaml")) {
            "manifest resource /manifests/$id.yaml not found"
        }.bufferedReader().use { it.readText() }

    @Test fun `opcua manifests load and validate with zero errors`() {
        for (id in manifestIds) {
            val contract = ManifestLoader.load(loadResource(id))
            assertEquals(id, contract.id, "manifest id mismatch")
            assertEquals(expectedVersions[id], contract.version, "manifest version must match for $id")
            val result = ContractValidator.validate(contract)
            assertTrue(result.errors.isEmpty(), "manifest '$id' must have zero validation errors: ${result.errors}")
        }
    }
}
