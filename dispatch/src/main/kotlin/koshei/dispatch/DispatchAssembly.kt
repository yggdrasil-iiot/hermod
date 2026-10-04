package koshei.dispatch

import koshei.core.BlockContract
import koshei.registry.BlockIndex
import koshei.registry.BlockStore
import koshei.registry.ContractValidator
import koshei.registry.ManifestLoader
import koshei.registry.Registry

/**
 * Engine-neutral registry assembly shared by every engine runtime (`:runtime` Temporal, `:conductor-runtime`).
 *
 * Holds the builtin-contract loading (from the `:blocks` manifests on the classpath) plus a real
 * PluginLoader-based handlerLoadCheck. Lives in `:dispatch` so both engines build the SAME registry without
 * either dragging in the other's SDK. `RuntimeAssembly` delegates here (keeping only its Temporal-specific
 * IR-lowering + test-block augmentation).
 */
object DispatchAssembly {
    private val MANIFEST_IDS = listOf("db.read", "transform.map", "db.upsert", "notify.email", "actuate", "merge", "opcua.write", "opcua.call", "delegate.score")

    /** The 9 built-in contracts, loaded from the blocks/opcua/delegation-module manifests on the classpath. */
    val builtinContracts: Map<String, BlockContract> by lazy {
        MANIFEST_IDS.associate { id ->
            val yaml = javaClass.getResourceAsStream("/manifests/$id.yaml")
                ?: error("manifest not found on classpath: /manifests/$id.yaml")
            val c = ManifestLoader.load(yaml.bufferedReader().readText())
            val v = ContractValidator.validate(c)
            check(v.ok) { "manifest $id invalid: ${v.errors}" }
            "${c.id}#${c.version}" to c
        }
    }

    /**
     * Construct a [Registry] over the given index/store with the static builtins (plus optional [extraBuiltins]
     * for test fixtures) and a PluginLoader-based handlerLoadCheck. The check is [PluginLoader.probe]: it proves
     * the forwardHandler is a public concrete [koshei.sdk.Block] with a public no-arg ctor WITHOUT running any
     * plugin code — publish is reachable from an unauthenticated API, so the candidate jar must not execute
     * during the check (design §14).
     */
    fun buildRegistry(
        index: BlockIndex,
        store: BlockStore,
        extraBuiltins: Map<String, BlockContract> = emptyMap(),
    ): Registry {
        val loader = PluginLoader()
        return Registry(
            index = index,
            store = store,
            builtins = builtinContracts + extraBuiltins,
            handlerLoadCheck = { jar, fqcn -> loader.probe(jar.absolutePath, fqcn) },
        )
    }
}
