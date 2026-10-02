package koshei.episode.runtime

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** One jsonl file of a bundle: its [name], its count's key in the manifest's `counts`, and the field that names a line. */
data class BundleFile(val name: String, val countKey: String, val idField: String)

/** One line as read: [position] is 1-based among the file's non-blank lines; [text] is the line exactly as written. */
class BundleLine(val position: Int, val id: String, val text: String, val node: JsonNode)

sealed interface BundleRead {
    /** [lines] per file name, bounded by the manifest's counts. [manifestJson] is the manifest exactly as written. */
    data class Ready(val runId: String, val manifestJson: String, val lines: Map<String, List<BundleLine>>) : BundleRead

    /** Not there yet, or being rewritten: read again at the next poll. */
    data class NotReady(val why: String) : BundleRead

    /** Cannot be read as it is: stop and say so — never skip, never guess (design §12). */
    data class Broken(val why: String) : BundleRead
}

/**
 * Reads a bundle written the picasso way (picasso `BundleWriter`): jsonl files first, `manifest.json` last, each by
 * rename. A live host rewrites the files whole as lines are added, so a file may be newer than the manifest: only the
 * first `counts.<key>` lines are taken, and the manifest is read again at the end — if it changed, the bundle is read
 * again later. JSON is read as strictly as the core reads it (duplicate keys refused, BOM refused, objects only).
 */
object Bundles {
    const val MANIFEST = "manifest.json"
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** The module's strict mapper, and no trailing tokens: one line is one JSON object and nothing after it. */
    private val reader = strictJson.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun read(
        dir: Path,
        runIdField: String,
        schemaVersions: Set<String>,
        files: List<BundleFile>,
        uniqueIds: Boolean,
        readText: (Path) -> String = Files::readString,
    ): BundleRead = try {
        readBundle(dir, runIdField, schemaVersions, files, uniqueIds, readText)
    } catch (e: CharacterCodingException) {
        // Bytes that are not UTF-8 will not become readable by waiting: broken, not "not ready".
        BundleRead.Broken("$dir: a file is not UTF-8 (${e.javaClass.simpleName})")
    }

    private fun readBundle(
        dir: Path,
        runIdField: String,
        schemaVersions: Set<String>,
        files: List<BundleFile>,
        uniqueIds: Boolean,
        readText: (Path) -> String,
    ): BundleRead {
        val manifestFile = dir.resolve(MANIFEST)
        val manifestJson = text(manifestFile, readText) ?: return BundleRead.NotReady("no $MANIFEST in $dir")
        if (manifestJson.startsWith(Char(0xFEFF))) return BundleRead.Broken("$MANIFEST in $dir starts with a UTF-8 BOM")
        val manifest = parse(manifestJson) ?: return BundleRead.Broken("$MANIFEST in $dir is not one strict JSON object")
        val schema = manifest.textOf("schemaVersion")
        if (schema !in schemaVersions) return BundleRead.Broken("$MANIFEST in $dir: schemaVersion $schema, this reader knows $schemaVersions")
        val runId = manifest.textOf(runIdField) ?: return BundleRead.Broken("$MANIFEST in $dir has no $runIdField")
        val counts = manifest.get("counts")?.takeIf { it.isObject } ?: return BundleRead.Broken("$MANIFEST in $dir has no counts")

        val lines = LinkedHashMap<String, List<BundleLine>>()
        for (file in files) {
            val count = counts.get(file.countKey)?.takeIf { it.isInt && it.intValue() >= 0 }?.intValue()
                ?: return BundleRead.Broken("$MANIFEST in $dir: counts.${file.countKey} is not a count")
            val path = dir.resolve(file.name)
            val fileText = when {
                count == 0 && !Files.exists(path) -> ""
                else -> {
                    val size = try { Files.size(path) } catch (e: NoSuchFileException) {
                        return BundleRead.Broken("${file.name} in $dir: the manifest counts $count lines, there is no file")
                    } catch (e: IOException) {
                        return BundleRead.NotReady("${file.name} in $dir: ${e.javaClass.simpleName}")   // e.g. denied during the writer's rename
                    }
                    if (size > MAX_FILE_BYTES) return BundleRead.Broken("${file.name} in $dir: $size bytes, over $MAX_FILE_BYTES")
                    text(path, readText) ?: return BundleRead.NotReady("${file.name} in $dir vanished while reading")
                }
            }
            val raw = fileText.lines().filter { it.isNotBlank() }
            if (raw.size < count) return BundleRead.NotReady("${file.name} in $dir has ${raw.size} lines, the manifest counts $count")
            val read = ArrayList<BundleLine>(count)
            val seen = HashSet<String>()
            for ((i, line) in raw.take(count).withIndex()) {
                val where = "${file.name} in $dir line ${i + 1}"
                if (line.startsWith(Char(0xFEFF))) return BundleRead.Broken("$where starts with a UTF-8 BOM")
                val node = parse(line) ?: return BundleRead.Broken("$where is not one strict JSON object")
                val id = node.textOf(file.idField) ?: return BundleRead.Broken("$where has no ${file.idField}")
                if (uniqueIds && !seen.add(id)) return BundleRead.Broken("$where repeats ${file.idField} '$id'")
                read += BundleLine(i + 1, id, line, node)
            }
            lines[file.name] = read
        }
        if (text(manifestFile, readText) != manifestJson) return BundleRead.NotReady("$MANIFEST in $dir changed while reading")
        return BundleRead.Ready(runId, manifestJson, lines)
    }

    /** The file's text; null when it is absent or momentarily unreadable (read again later). Bad UTF-8 is thrown on. */
    private fun text(path: Path, readText: (Path) -> String): String? = try {
        readText(path)
    } catch (e: CharacterCodingException) {
        throw e
    } catch (e: IOException) {
        null
    }

    private fun parse(text: String): JsonNode? {
        val node = try { reader.readTree(text) } catch (e: JsonProcessingException) { return null }
        return node?.takeIf { it.isObject }
    }

    private fun JsonNode.textOf(field: String): String? = get(field)?.takeIf { it.isTextual }?.textValue()?.takeIf { it.isNotBlank() }
}
