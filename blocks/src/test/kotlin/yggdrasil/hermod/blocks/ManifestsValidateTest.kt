package yggdrasil.hermod.blocks

import yggdrasil.hermod.registry.ContractValidator
import yggdrasil.hermod.registry.ManifestLoader
import kotlin.test.*

/**
 * Round-trip guard: every shipped manifest resource loads via the registry's ManifestLoader
 * and validates with ZERO errors (warnings allowed — e.g. NONE-idempotency on actuate/transform.map).
 * Pins that shipped manifests are publishable and the version map is internally consistent.
 */
class ManifestsValidateTest {
    private val manifestIds = listOf("db.read", "transform.map", "db.upsert", "notify.email", "actuate", "merge")

    private val expectedVersions = mapOf(
        "db.read" to "1.0.0",
        "transform.map" to "1.0.0",
        "db.upsert" to "1.2.0",
        "notify.email" to "1.0.0",
        "actuate" to "1.0.0",
        "merge" to "1.0.0",
    )

    private fun loadResource(id: String): String =
        requireNotNull(this::class.java.getResourceAsStream("/manifests/$id.yaml")) {
            "manifest resource /manifests/$id.yaml not found"
        }.bufferedReader().use { it.readText() }

    @Test fun `all shipped manifests load and validate with zero errors`() {
        for (id in manifestIds) {
            val contract = ManifestLoader.load(loadResource(id))
            assertEquals(id, contract.id, "manifest id mismatch")
            assertEquals(expectedVersions[id], contract.version, "manifest version must match the authoritative map for $id")
            val result = ContractValidator.validate(contract)
            assertTrue(result.errors.isEmpty(), "manifest '$id' must have zero validation errors: ${result.errors}")
        }
    }
}
