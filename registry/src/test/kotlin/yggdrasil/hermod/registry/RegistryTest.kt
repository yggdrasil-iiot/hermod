package yggdrasil.hermod.registry

import yggdrasil.hermod.core.*
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryTest {
    private lateinit var storeRoot: File
    private lateinit var registry: Registry
    private val handlerCheckCalls = mutableListOf<Pair<File, String>>()

    // Synthetic builtin map (NOT loaded from :blocks). The contract is a plausible db.upsert#1.2.0.
    private val builtinContract = BlockContract(
        id = "db.upsert", version = "1.2.0", category = BlockCategory.sink, forwardHandler = "yggdrasil.hermod.blocks.DbUpsertBlock",
        idempotency = IdempotencySpec(IdempotencyStrategy.UPSERT, "row:id"),
        compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.STATIC, "yggdrasil.hermod.blocks.DbUpsertBlock#compensate", listOf("priorValues")),
        stateBinding = listOf(StateBindingSpec("priorValues")),
        retry = RetrySpec(5, 200, 10_000), sideEffects = listOf(SideEffect.DB_WRITE),
    )
    private val builtins = mapOf("db.upsert#1.2.0" to builtinContract)

    @BeforeTest fun setup() {
        RegistryDbSupport.reset()
        storeRoot = File.createTempFile("hermod-store", "").let { it.delete(); it.mkdirs(); it }
        handlerCheckCalls.clear()
        registry = Registry(
            index = BlockIndex(RegistryDbSupport::connection),
            store = BlockStore(storeRoot),
            builtins = builtins,
            handlerLoadCheck = { jar, fqcn ->
                handlerCheckCalls += jar to fqcn
                // Synthetic check: the handler "class" must appear as a (slash) path inside the jar.
                val entryPath = fqcn.replace('.', '/') + ".class"
                java.util.jar.JarFile(jar).use { jf ->
                    if (jf.getJarEntry(entryPath) == null)
                        throw IllegalStateException("handler class $fqcn not in jar")
                }
            },
        )
    }

    /** A valid plugin manifest YAML for an example block, parameterised by id/version/handler. */
    private fun manifestYaml(
        id: String = "io.example.greet",
        version: String = "1.0.0",
        handler: String = "io.example.GreetBlock",
    ) = """
        id: $id
        version: $version
        category: transform
        forward: { handler: "$handler" }
        idempotency: { strategy: NATURAL }
        compensation: { reversibility: REVERSIBLE, kind: NONE }
        retry: { maxAttempts: 3, backoff: { initialMs: 100, maxMs: 1000 } }
        sideEffects: [NONE]
    """.trimIndent()

    /**
     * Build a tiny plugin jar in-test (java.util.jar): a `manifests/<name>.yaml` entry, and
     * optionally a stub `.class` entry for the handler so the synthetic handlerLoadCheck passes.
     */
    private fun buildJar(
        manifest: String,
        handlerClass: String? = null,
        name: String = "plugin",
    ): File {
        val jar = File(storeRoot, "$name-${System.nanoTime()}.jar")
        JarOutputStream(jar.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry("manifests/block.yaml"))
            jos.write(manifest.toByteArray())
            jos.closeEntry()
            if (handlerClass != null) {
                jos.putNextEntry(JarEntry(handlerClass.replace('.', '/') + ".class"))
                jos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())) // stub bytes
                jos.closeEntry()
            }
        }
        return jar
    }

    @Test fun `publish a valid plugin jar then resolve returns Plugin and index has the row`() {
        val jar = buildJar(manifestYaml(), handlerClass = "io.example.GreetBlock")
        val result = registry.publish(jar)
        assertTrue(result.ok, "expected publish ok, got ${result.errors}")

        val res = registry.resolve("io.example.greet", "1.0.0")
        assertTrue(res is Resolution.Plugin)
        assertEquals("io.example.greet", res.contract.id)
        assertEquals(1, BlockIndex(RegistryDbSupport::connection).list().size)
        // handlerLoadCheck was consulted with the contract's forwardHandler
        assertEquals("io.example.GreetBlock", handlerCheckCalls.single().second)
    }

    @Test fun `publish invalid manifest is rejected with validation errors`() {
        // REVERSIBLE/STATIC with no handler -> ContractValidator rule 3 error.
        val badManifest = """
            id: io.example.bad
            version: 1.0.0
            category: sink
            forward: { handler: "io.example.BadBlock" }
            idempotency: { strategy: UPSERT, keyExpression: "row:id" }
            compensation: { reversibility: REVERSIBLE, kind: STATIC }
            retry: { maxAttempts: 3, backoff: { initialMs: 100, maxMs: 1000 } }
        """.trimIndent()
        val jar = buildJar(badManifest, handlerClass = "io.example.BadBlock")
        val result = registry.publish(jar)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("handler") }, result.errors.toString())
    }

    @Test fun `publish duplicate id and version is rejected`() {
        val jar1 = buildJar(manifestYaml(), handlerClass = "io.example.GreetBlock")
        assertTrue(registry.publish(jar1).ok)
        val jar2 = buildJar(manifestYaml(), handlerClass = "io.example.GreetBlock")
        val result = registry.publish(jar2)
        assertFalse(result.ok, "duplicate (id,version) must be rejected")
    }

    @Test fun `publish jar whose handler class is absent is rejected`() {
        val jar = buildJar(manifestYaml(), handlerClass = null) // no class entry
        val result = registry.publish(jar)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("handler") }, result.errors.toString())
    }

    @Test fun `publish a jar with no manifest is rejected`() {
        val jar = File(storeRoot, "empty.jar")
        JarOutputStream(jar.outputStream()).use { /* no entries */ }
        val result = registry.publish(jar)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("manifest") }, result.errors.toString())
    }

    @Test fun `resolve a builtin never published returns Builtin`() {
        val res = registry.resolve("db.upsert", "1.2.0")
        assertTrue(res is Resolution.Builtin)
        assertEquals("db.upsert", res.contract.id)
    }

    @Test fun `resolve unknown returns null`() {
        assertNull(registry.resolve("io.example.x", "9.9.9"))
    }

    @Test fun `contains is true for builtin and false for unknown`() {
        assertTrue(registry.contains("db.upsert", "1.2.0"))
        assertFalse(registry.contains("io.example.x", "9.9.9"))
    }

    @Test fun `publishing a built-in id is rejected as reserved`() {
        // A jar claiming a reserved built-in id ("db.upsert") must be rejected.
        val jar = buildJar(manifestYaml(id = "db.upsert", version = "2.0.0", handler = "io.example.GreetBlock"), handlerClass = "io.example.GreetBlock")
        val result = registry.publish(jar)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("reserved") }, result.errors.toString())
    }

    @Test fun `rule4 WorkflowValidator accepts builtins and rejects unknown steps via union`() {
        val def = WorkflowDef("d", listOf(WorkflowStep("db.upsert", "1.2.0"), WorkflowStep("ghost", "9.9.9")))
        val errs = WorkflowValidator.validate(def, registry)
        assertTrue(errs.any { it.contains("ghost") && it.contains("9.9.9") }, errs.toString())
        assertFalse(errs.any { it.contains("db.upsert") }, "builtin step must pass: $errs")
    }

    @Test fun `publish(jar, contract) stores the supplied contract not the jar manifest`() {
        val jar = buildJar(manifestYaml(), handlerClass = "io.example.GreetBlock")  // jar manifest has NO labels
        // Authored contract carries a param label; build it via the loader so it round-trips identically to storage.
        val authored = BlockContract(
            id = "io.example.greet", version = "1.0.0", category = BlockCategory.transform,
            displayName = "인사", description = "인사 블록",
            params = listOf(ParamSpec("who", "string", required = true, label = "대상")),
            forwardHandler = "io.example.GreetBlock",
            idempotency = IdempotencySpec(IdempotencyStrategy.NATURAL),
            compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.NONE),
            retry = RetrySpec(3, 100, 1000),
        )
        val r = registry.publish(jar, authored)
        assertTrue(r.ok, r.errors.toString())
        val resolved = registry.resolve("io.example.greet", "1.0.0")
        assertTrue(resolved is Resolution.Plugin)
        // stored manifest_json is the AUTHORED contract (label present) — proves UI owns the contract,
        // and that Chunk 1's ManifestLoader round-trip survived the store→fromJson path.
        assertEquals("대상", (resolved as Resolution.Plugin).contract.params.first().label)
    }

    @Test fun `publish(jar) still works and delegates (back-compat)`() {
        val jar = buildJar(manifestYaml(), handlerClass = "io.example.GreetBlock")
        assertTrue(registry.publish(jar).ok)
    }

    @Test fun `list unions builtins and published plugins`() {
        val jar = buildJar(manifestYaml(), handlerClass = "io.example.GreetBlock")
        assertTrue(registry.publish(jar).ok)
        val all = registry.list()
        assertTrue(all.any { it.id == "db.upsert" }, "builtin missing from list")
        assertTrue(all.any { it.id == "io.example.greet" }, "plugin missing from list")
        assertEquals(2, all.size)
    }

    @Test fun `listWithFlags marks builtins not-deprecated and reflects the index flag`() {
        val jar = buildJar(manifestYaml(), handlerClass = "io.example.GreetBlock")
        assertTrue(registry.publish(jar).ok)
        BlockIndex(RegistryDbSupport::connection).deprecate("io.example.greet", "1.0.0")
        val flags = registry.listWithFlags().associate { it.first.id to it.second }
        assertEquals(false, flags["db.upsert"])          // builtin → never deprecated
        assertEquals(true, flags["io.example.greet"])    // plugin row flag honored
    }
}
