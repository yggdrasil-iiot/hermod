package koshei.episode.runtime

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * The plan-B policy source (design §8.2): one YAML file, read afresh on every call. No file is MISSING (the table was
 * taken down on purpose); a file that cannot be read, is larger than [MAX_BYTES], or is not strict YAML is UNREADABLE
 * (the core keeps the last valid table); anything else is FOUND as JSON text, and the core decides whether it is a
 * valid table.
 */
class PolicyFileReader(private val path: Path) {
    fun read(): PolicyReadResult {
        val text = try {
            val size = Files.size(path)
            if (size > MAX_BYTES) return PolicyReadResult("UNREADABLE", reason = "$path: $size bytes, over the limit of $MAX_BYTES")
            Files.readString(path)
        } catch (e: NoSuchFileException) {
            return PolicyReadResult("MISSING")
        } catch (e: IOException) {
            // A file-system exception's message is often the path alone; say it once.
            val detail = e.message?.takeUnless { it == path.toString() }?.let { ": $it" } ?: ""
            return PolicyReadResult("UNREADABLE", reason = "$path: cannot read: ${e.javaClass.simpleName}$detail")
        }
        return try {
            PolicyReadResult("FOUND", json = StrictYaml.toJson(text))
        } catch (e: IllegalArgumentException) {
            PolicyReadResult("UNREADABLE", reason = "$path: ${e.message}")
        }
    }

    companion object {
        const val MAX_BYTES = 1024L * 1024
    }
}
