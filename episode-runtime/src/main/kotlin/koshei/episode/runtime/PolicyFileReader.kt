package koshei.episode.runtime

import java.nio.file.Files
import java.nio.file.Path

/**
 * The plan-B policy source (design §8.2): one YAML file, read afresh on every call. No file is MISSING (the table was
 * taken down on purpose); a file that cannot be read or is not strict YAML is UNREADABLE (the core keeps the last valid
 * table); anything else is FOUND as JSON text, and the core decides whether it is a valid table.
 */
class PolicyFileReader(private val path: Path) {
    fun read(): PolicyReadResult {
        if (!Files.exists(path)) return PolicyReadResult("MISSING")
        val text = try {
            Files.readString(path)
        } catch (e: Exception) {
            return PolicyReadResult("UNREADABLE", reason = "$path: cannot read: ${e.message}")
        }
        return try {
            PolicyReadResult("FOUND", json = StrictYaml.toJson(text))
        } catch (e: IllegalArgumentException) {
            PolicyReadResult("UNREADABLE", reason = "$path: ${e.message}")
        }
    }
}
