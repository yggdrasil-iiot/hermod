package yggdrasil.hermod.dispatch

import yggdrasil.hermod.registry.Registry
import yggdrasil.hermod.registry.Resolution
import yggdrasil.hermod.sdk.Block

/**
 * Version-keyed handler resolution: `(id, version)` -> Block. Builtins come from the static
 * [BuiltinBlocks] set (DB-free); plugins are resolved lazily via [Registry.resolve] and loaded in an
 * isolated classloader by [PluginLoader]. Results are cached so a plugin jar classloads exactly once.
 */
class HandlerRegistry(private val registry: Registry, private val loader: PluginLoader = PluginLoader()) {
    private val cache = HashMap<String, Block>()

    // Deliberate v0.2 trade-off (code-review I1): only SUCCESSFUL loads are cached — a failing plugin
    // load is not negatively cached, so each Temporal activity retry re-attempts it. Acceptable because
    // publish-time handlerLoadCheck already gates most bad plugins (it cannot catch a throwing static
    // initializer or constructor — those surface here, at first execution), and Temporal's RetryOptions
    // bound the re-attempts; a permanently-broken plugin simply fails its activity. (@Synchronized guards the plain
    // HashMap + the load; builtin lookups are microsecond-cheap so the global lock is fine for v0.2.)
    @Synchronized fun get(id: String, version: String): Block = cache.getOrPut("$id#$version") {
        when (val r = registry.resolve(id, version)) {
            is Resolution.Builtin -> BuiltinBlocks.byId[id] ?: error("builtin handler missing: $id")
            is Resolution.Plugin  -> loader.load(r.jarPath, r.contract.forwardHandler)
            null -> error("no block: $id#$version")
        }
    }
}
