package yggdrasil.hermod.delegation

import yggdrasil.hermod.registry.ContractValidator
import yggdrasil.hermod.registry.ManifestLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestsValidateTest {
    @Test fun `delegate score manifest loads and validates with zero errors`() {
        val yaml = requireNotNull(this::class.java.getResourceAsStream("/manifests/delegate.score.yaml"))
            .bufferedReader().use { it.readText() }
        val contract = ManifestLoader.load(yaml)
        assertEquals("delegate.score", contract.id)
        assertEquals("1.0.0", contract.version)
        val result = ContractValidator.validate(contract)
        assertTrue(result.errors.isEmpty(), "manifest must have zero errors: ${result.errors}")
    }
}
