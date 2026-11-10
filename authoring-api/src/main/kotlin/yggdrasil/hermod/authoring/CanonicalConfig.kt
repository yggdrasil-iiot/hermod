package yggdrasil.hermod.authoring

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.security.MessageDigest

/**
 * Points at the ①-published canonical (HERMOD_RECIPE_SETPOINTS → …/registry/recipe/<ref>/<ver>/recipe-setpoints.yaml).
 * Reads the canonical as RAW BYTES once (bootDigest = sha256(bytes)), parses the bean from the same bytes, and reads
 * the sibling manifest.json. No git. When unset, everything is null → the controller fail-closes 409.
 */
class CanonicalConfig(val file: File?) {
    val rawBytes: ByteArray? = file?.takeIf { it.isFile }?.readBytes()
    val bootDigest: String? = rawBytes?.let { sha256hex(it) }
    val manifest: RecipeManifest? = file?.parentFile?.resolve("manifest.json")
        ?.takeIf { it.isFile }?.let { runCatching { mapper.readValue(it, RecipeManifest::class.java) }.getOrNull() }

    fun yamlText(): String = rawBytes?.toString(Charsets.UTF_8)
        ?: error("no canonical bytes — HERMOD_RECIPE_SETPOINTS must point at a published registry canonical")

    companion object {
        private val mapper = jacksonObjectMapper()
        fun fromEnv(): CanonicalConfig = CanonicalConfig(System.getenv("HERMOD_RECIPE_SETPOINTS")?.let(::File))
        fun sha256hex(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
