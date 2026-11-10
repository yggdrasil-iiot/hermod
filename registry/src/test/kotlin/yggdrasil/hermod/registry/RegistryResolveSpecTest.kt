package yggdrasil.hermod.registry

import yggdrasil.hermod.core.*
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests for Registry.resolveSpec (Latest/Caret -> highest concrete version) and
 * BlockIndex.versionsOf. Uses the same RegistryDbSupport Testcontainer harness as RegistryTest.
 */
class RegistryResolveSpecTest {

    private lateinit var index: BlockIndex
    private lateinit var registry: Registry

    // Builtin contracts for exact/caret-on-builtin tests
    private val builtinBx = contract("b.x", "1.0.0")
    private val builtinBup = contract("b.up", "1.2.0")
    private val builtins = mapOf(
        "b.x#1.0.0"  to builtinBx,
        "b.up#1.2.0" to builtinBup,
    )

    @BeforeTest fun setup() {
        RegistryDbSupport.reset()
        index = BlockIndex(RegistryDbSupport::connection)
        registry = Registry(
            index = index,
            store = BlockStore(kotlin.io.path.createTempDirectory("hermod-store-spec").toFile()),
            builtins = builtins,
            handlerLoadCheck = { _, _ -> }, // no jar on disk — resolveSpec only reads the index
        )
    }

    // -------------------------------------------------------------------------
    // Case 1: latest picks highest version across inserted rows
    // -------------------------------------------------------------------------
    @Test fun `resolveSpec latest picks highest version`() {
        insertPlugin("plg.a", "1.0.0")
        insertPlugin("plg.a", "1.2.0")
        insertPlugin("plg.a", "2.0.0")
        insertPlugin("plg.a", "1.10.0")

        val res = registry.resolveSpec("plg.a", "latest")
        assertIs<Resolution.Plugin>(res)
        assertEquals("2.0.0", res.contract.version)
    }

    // -------------------------------------------------------------------------
    // Case 2: ^1.0.0 within same major, picks highest compatible
    // -------------------------------------------------------------------------
    @Test fun `resolveSpec caret picks highest within same major`() {
        insertPlugin("plg.b", "1.0.0")
        insertPlugin("plg.b", "1.2.0")
        insertPlugin("plg.b", "2.0.0")

        val res = registry.resolveSpec("plg.b", "^1.0.0")
        assertIs<Resolution.Plugin>(res)
        assertEquals("1.2.0", res.contract.version)
    }

    // -------------------------------------------------------------------------
    // Case 3: exact spec hits builtins-first
    // -------------------------------------------------------------------------
    @Test fun `resolveSpec exact hits builtin before index`() {
        val res = registry.resolveSpec("b.x", "1.0.0")
        assertIs<Resolution.Builtin>(res)
        assertEquals("b.x", res.contract.id)
    }

    @Test fun `resolveSpec exact builtin with unknown version returns null`() {
        assertNull(registry.resolveSpec("b.x", "9.9.9"))
    }

    // -------------------------------------------------------------------------
    // Case 4: no match / malformed spec returns null
    // -------------------------------------------------------------------------
    @Test fun `resolveSpec unknown id with latest returns null`() {
        assertNull(registry.resolveSpec("plg.none", "latest"))
    }

    @Test fun `resolveSpec unknown id with caret returns null`() {
        assertNull(registry.resolveSpec("plg.none", "^1.0.0"))
    }

    @Test fun `resolveSpec malformed spec returns null`() {
        assertNull(registry.resolveSpec("plg.none", "garbage"))
    }

    // -------------------------------------------------------------------------
    // Case 5: caret on builtin single version
    // -------------------------------------------------------------------------
    @Test fun `resolveSpec caret on builtin single version returns Builtin`() {
        val res = registry.resolveSpec("b.up", "^1.0.0")
        assertIs<Resolution.Builtin>(res)
        assertEquals("1.2.0", res.contract.version)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Insert a plugin row directly into the index — no jar required for resolveSpec. */
    private fun insertPlugin(id: String, version: String) {
        index.insert(BlockIndex.Row(
            id          = id,
            version     = version,
            manifestJson = ManifestLoader.toJson(contract(id, version)),
            jarPath     = "/jars/$version.jar",
            sha256      = "sha$version",
        ))
    }

    /** Minimal valid BlockContract (transform / REVERSIBLE+STATIC / NONE idempotency). */
    private fun contract(id: String, version: String): BlockContract = BlockContract(
        id               = id,
        version          = version,
        category         = BlockCategory.transform,
        forwardHandler   = "yggdrasil.hermod.test.StubBlock",
        idempotency      = IdempotencySpec(IdempotencyStrategy.NONE),
        compensation     = CompensationSpec(
            reversibility = Reversibility.REVERSIBLE,
            kind          = CompensationKind.STATIC,
            handler       = "yggdrasil.hermod.test.StubBlock#compensate",
        ),
        retry            = RetrySpec(3, 100, 1000),
    )
}
