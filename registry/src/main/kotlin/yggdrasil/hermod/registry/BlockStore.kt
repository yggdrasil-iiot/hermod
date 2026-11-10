package yggdrasil.hermod.registry

import java.io.File
import java.security.MessageDigest

/** Content-addressed jar directory: a plugin jar lands at `<root>/<id>/<version>/<sha256>.jar`. Idempotent. */
class BlockStore(private val root: File = File(System.getenv("HERMOD_PLUGIN_DIR") ?: "registry-store")) {
    data class Stored(val path: String, val sha256: String)

    fun put(id: String, version: String, jar: File): Stored {
        // Defensive path-traversal guard (review M3): id/version come from an untrusted manifest and
        // are interpolated into a filesystem path. Reject separators / parent refs at the store boundary.
        require(id.none { it == '/' || it == '\\' } && ".." !in id) { "illegal block id for path: '$id'" }
        require(version.none { it == '/' || it == '\\' } && ".." !in version) { "illegal version for path: '$version'" }
        val sha = sha256(jar)
        val dir = File(root, "$id/$version").apply { mkdirs() }
        val dest = File(dir, "$sha.jar")
        if (!dest.exists()) jar.copyTo(dest)   // content-addressed -> idempotent
        return Stored(dest.absolutePath, sha)
    }

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
}
