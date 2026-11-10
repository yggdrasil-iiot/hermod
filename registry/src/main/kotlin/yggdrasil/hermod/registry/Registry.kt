package yggdrasil.hermod.registry

import yggdrasil.hermod.core.BlockContract
import yggdrasil.hermod.core.SemVer
import yggdrasil.hermod.core.VersionSpec
import java.io.File

sealed interface Resolution {
    data class Builtin(val contract: BlockContract) : Resolution
    data class Plugin(val contract: BlockContract, val jarPath: String) : Resolution
}

data class PublishResult(val ok: Boolean, val errors: List<String> = emptyList())

/**
 * Hybrid registry façade. Resolution unions injected built-ins (resolved first, never in the DB)
 * with published plugins in the Postgres [BlockIndex]; jars live in [BlockStore].
 *
 * `builtins` ("id#version" -> contract) and `handlerLoadCheck` are injected so `:registry` stays
 * free of `:blocks` and the isolation-classloader (both supplied by `:runtime` in Chunk 3).
 */
class Registry(
    private val index: BlockIndex,
    private val store: BlockStore,
    private val builtins: Map<String, BlockContract>,                 // "id#version" -> contract (injected from runtime)
    private val handlerLoadCheck: (jar: File, fqcn: String) -> Unit,  // injected; throws if the handler is not a loadable public concrete Block with a public no-arg ctor
) {
    fun publish(jar: File): PublishResult {
        val manifest = readManifestFromJar(jar)                       // §4.3.a: read /manifests/*.yaml in jar
            ?: return PublishResult(false, listOf("no manifest in jar"))
        val contract = try { ManifestLoader.fromYaml(manifest) }
            catch (e: Exception) { return PublishResult(false, listOf("manifest parse: ${e.message}")) }
        return publish(jar, contract)
    }

    /** Publish using a caller-supplied contract (the authoring UI owns the manifest; the jar supplies only
     *  the handler). All gating lives here so publish(jar) and publish(jar, contract) share one path (spec §5.2). */
    fun publish(jar: File, contract: BlockContract): PublishResult {
        val v = ContractValidator.validate(contract)
        if (!v.ok) return PublishResult(false, v.errors)
        if (builtins.containsKey("${contract.id}#${contract.version}") || BuiltinIds.contains(contract.id))
            return PublishResult(false, listOf("id '${contract.id}' is reserved (built-in)"))
        try { handlerLoadCheck(jar, contract.forwardHandler) } catch (e: Exception) {
            return PublishResult(false, listOf("handler '${contract.forwardHandler}' load failed: ${e.message}"))
        }
        // Reject duplicates BEFORE writing the jar (review I1): a differing-content re-publish of an
        // existing (id,version) would otherwise leave an orphan jar on disk when index.insert fails.
        // This also gives a clean message instead of conflating duplicate with a real DB error (I2).
        if (index.find(contract.id, contract.version) != null)
            return PublishResult(false, listOf("${contract.id}#${contract.version} already published (versions are immutable)"))
        val stored = store.put(contract.id, contract.version, jar)
        return try {
            index.insert(BlockIndex.Row(contract.id, contract.version, ManifestLoader.toJson(contract), stored.path, stored.sha256))
            PublishResult(true)
        } catch (e: Exception) { PublishResult(false, listOf("registry insert failed: ${e.message}")) }
    }

    fun resolve(id: String, version: String): Resolution? {
        builtins["$id#$version"]?.let { return Resolution.Builtin(it) }
        return index.find(id, version)?.let { Resolution.Plugin(ManifestLoader.fromJson(it.manifestJson), it.jarPath) }
    }

    fun contains(id: String, version: String) = resolve(id, version) != null

    fun versionsOf(id: String): List<String> {
        val builtinVers = builtins.keys.filter { it.startsWith("$id#") }.map { it.substringAfter("#") }
        return (builtinVers + index.versionsOf(id)).distinct()
    }

    fun resolveSpec(id: String, spec: String): Resolution? {
        val vs = VersionSpec.parseOrNull(spec) ?: return null
        if (vs is VersionSpec.Exact) return resolve(id, vs.v.toString())
        val best = versionsOf(id).mapNotNull { SemVer.parseOrNull(it) }.filter { vs.matches(it) }.maxOrNull()
            ?: return null
        return resolve(id, best.toString())
    }

    fun list(): List<BlockContract> =
        builtins.values + index.list().map { ManifestLoader.fromJson(it.manifestJson) }

    /** Like [list] but pairs each contract with its deprecated flag. Builtins are never deprecated.
     *  Used only by the authoring palette projection — resolution paths stay untouched (spec §5.3). */
    fun listWithFlags(): List<Pair<BlockContract, Boolean>> =
        builtins.values.map { it to false } +
            index.list().map { ManifestLoader.fromJson(it.manifestJson) to it.deprecated }

    object BuiltinIds { fun contains(id: String) = id in setOf("db.read", "transform.map", "db.upsert", "notify.email", "actuate", "merge", "opcua.write", "opcua.call", "delegate.score") }

    private fun readManifestFromJar(jar: File): String? = java.util.jar.JarFile(jar).use { jf ->
        val entry = jf.entries().asSequence().firstOrNull {
            it.name.startsWith("manifests/") && it.name.endsWith(".yaml")
        } ?: return null
        jf.getInputStream(entry).bufferedReader().readText()
    }
}
