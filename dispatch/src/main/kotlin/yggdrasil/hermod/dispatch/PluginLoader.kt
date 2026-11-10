package yggdrasil.hermod.dispatch

import yggdrasil.hermod.sdk.Block
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader

/**
 * Loads a plugin [Block] from a jar in an isolated child classloader. Parent = the loader that has
 * `:sdk` (this class's loader), so `yggdrasil.hermod.sdk.Block` is the SAME Class in host and plugin (parent-first
 * delegation gives type identity — the `as Block` cast and `isAssignableFrom` work across the boundary).
 *
 * [probe] is the publish-time check; [load] is the execution path.
 */
class PluginLoader(private val parent: ClassLoader = PluginLoader::class.java.classLoader) {
    /**
     * Publish-time check that runs NO plugin code: resolves [fqcn] from the jar WITHOUT initialization
     * (`initialize=false`, so no static initializer), then checks by reflection only (never instantiated) that
     * [load] could instantiate it — a public, concrete [Block], DEFINED BY THE JAR ITSELF (not resolved via
     * parent-first delegation to a class already on the host classpath), with a public no-arg constructor —
     * and closes the loader so the candidate jar is released. A jar published through an unauthenticated
     * surface must not execute during the check (design §14), and naming a host-classpath class must not let
     * it satisfy the check either. A malformed class file (a `LinkageError`, e.g. from garbage uploaded bytes)
     * is mapped to a clean [IllegalArgumentException] instead of escaping publish as a raw error. Trade-off: a
     * class whose static initializer or constructor THROWS now passes publish and fails at its first execution
     * instead (a failed activity), because detecting that would mean running the code.
     */
    fun probe(jarPath: String, fqcn: String) {
        URLClassLoader(arrayOf(File(jarPath).toURI().toURL()), parent).use { loader ->
            val cls = try {
                Class.forName(fqcn, false, loader)
            } catch (e: LinkageError) {
                throw IllegalArgumentException("$fqcn cannot be loaded from the jar: $e", e)
            }
            require(cls.classLoader === loader) { "$fqcn must be defined by the plugin jar" }
            require(Block::class.java.isAssignableFrom(cls)) { "$fqcn is not a yggdrasil.hermod.sdk.Block" }
            require(Modifier.isPublic(cls.modifiers) && !cls.isInterface && !Modifier.isAbstract(cls.modifiers)) {
                "$fqcn must be a public concrete class"
            }
            val ctor = try {
                cls.getDeclaredConstructor()
            } catch (e: NoSuchMethodException) {
                null
            } catch (e: LinkageError) {
                throw IllegalArgumentException("$fqcn cannot be linked: $e", e)
            }
            require(ctor != null && Modifier.isPublic(ctor.modifiers)) { "$fqcn needs a public no-arg constructor" }
        }
    }

    fun load(jarPath: String, fqcn: String): Block {
        // The loader is intentionally NOT closed: on success it must outlive the returned Block (which
        // lazily resolves further plugin classes through it), and HandlerRegistry caches that Block for
        // the process lifetime. On the failure path the loader is unreferenced and GC-eligible (a minor
        // per-failed-attempt allocation — see HandlerRegistry I1). v0.2 keeps loaders process-lived.
        val loader = URLClassLoader(arrayOf(File(jarPath).toURI().toURL()), parent)  // parent-first
        val cls = Class.forName(fqcn, true, loader)
        // Chunk-5 gate greps this line to prove isolation (classloader of the loaded plugin class).
        println("[PluginLoader] loaded $fqcn via ${cls.classLoader}")
        require(Block::class.java.isAssignableFrom(cls)) { "$fqcn is not a yggdrasil.hermod.sdk.Block" }
        return cls.getDeclaredConstructor().newInstance() as Block   // no-arg ctor required
    }
}
