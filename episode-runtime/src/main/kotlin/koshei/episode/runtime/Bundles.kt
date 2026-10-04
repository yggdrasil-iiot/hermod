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
 * again later. JSON is read as strictly as the core reads it (duplicate keys refused, BOM refused, objects only), and
 * an id repeated within a file is broken (result notices, where repeats are expected, are read by [ResultExportFile]).
 *
 * Nothing is called broken while the manifest is moving: a bundle replaced or rewritten under the reader can look
 * broken (a file of the next snapshot, a file gone) and is not. Every verdict "broken" is first checked against a fresh
 * read of the manifest, and a changed manifest turns it into "read again later".
 */
object Bundles {
    const val MANIFEST = "manifest.json"
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024

    /** The module's strict mapper, and no trailing tokens: one line is one JSON object and nothing after it. */
    private val reader = strictJson.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    /** The file at [path] is not UTF-8. Carries the path so the verdict can name the file. */
    private class NotUtf8(val path: Path) : Exception(path.toString(), null, false, false)

    fun read(
        dir: Path,
        runIdField: String,
        schemaVersions: Set<String>,
        files: List<BundleFile>,
        readText: (Path) -> String = Files::readString,
    ): BundleRead {
        val manifestFile = dir.resolve(MANIFEST)
        val manifestSize = try { Files.size(manifestFile) } catch (e: NoSuchFileException) {
            return BundleRead.NotReady("no $MANIFEST in $dir")
        } catch (e: IOException) {
            return BundleRead.NotReady("$MANIFEST in $dir: ${e.javaClass.simpleName}")
        }
        if (manifestSize > MAX_MANIFEST_BYTES) return BundleRead.Broken("$MANIFEST in $dir: $manifestSize bytes, over $MAX_MANIFEST_BYTES")
        val manifestJson = try {
            text(manifestFile, readText)
        } catch (e: NotUtf8) {
            // Bytes that are not UTF-8 will not become readable by waiting: broken, not "not ready".
            return BundleRead.Broken("$MANIFEST in $dir is not UTF-8")
        } ?: return BundleRead.NotReady("no $MANIFEST in $dir")

        /** A verdict "broken" only if the manifest is still the one read; otherwise the bundle moved under us. */
        fun broken(why: String): BundleRead {
            val again = try { text(manifestFile, readText) } catch (e: NotUtf8) { null }
            return if (again != manifestJson) BundleRead.NotReady("$MANIFEST in $dir changed while reading") else BundleRead.Broken(why)
        }

        if (manifestJson.startsWith(Char(0xFEFF))) return BundleRead.Broken("$MANIFEST in $dir starts with a UTF-8 BOM")
        val parsedManifest = parse(manifestJson)
        val manifest = parsedManifest.node ?: return BundleRead.Broken("$MANIFEST in $dir ${parsedManifest.notObject}")
        val schema = manifest.textOf("schemaVersion")
        if (schema !in schemaVersions) return BundleRead.Broken("$MANIFEST in $dir: schemaVersion $schema, this reader knows $schemaVersions")
        val runId = manifest.textOf(runIdField) ?: return BundleRead.Broken("$MANIFEST in $dir has no $runIdField")
        val counts = manifest.get("counts")?.takeIf { it.isObject } ?: return BundleRead.Broken("$MANIFEST in $dir has no counts")

        val lines = LinkedHashMap<String, List<BundleLine>>()
        for (file in files) {
            val count = counts.get(file.countKey)?.takeIf { it.isInt && it.intValue() >= 0 }?.intValue()
                ?: return BundleRead.Broken("$MANIFEST in $dir: counts.${file.countKey} is not a count")
            val path = dir.resolve(file.name)
            val fileText = if (count == 0) "" else {   // nothing counted: the file is not read at all
                val size = try { Files.size(path) } catch (e: NoSuchFileException) {
                    return broken("${file.name} in $dir: the manifest counts $count lines, there is no file")
                } catch (e: IOException) {
                    return BundleRead.NotReady("${file.name} in $dir: ${e.javaClass.simpleName}")   // e.g. denied during the writer's rename
                }
                if (size > MAX_FILE_BYTES) return broken("${file.name} in $dir: $size bytes, over $MAX_FILE_BYTES")
                try {
                    text(path, readText)
                } catch (e: NotUtf8) {
                    return broken("${file.name} in $dir is not UTF-8")
                } ?: return BundleRead.NotReady("${file.name} in $dir could not be read (vanished or denied)")
            }
            val raw = fileText.lines().filter { !isBlankLine(it) }
            if (raw.size < count) return BundleRead.NotReady("${file.name} in $dir has ${raw.size} lines, the manifest counts $count")
            val read = ArrayList<BundleLine>(count)
            val seen = HashSet<String>()
            for ((i, line) in raw.take(count).withIndex()) {
                val where = "${file.name} in $dir line ${i + 1}"
                if (line.startsWith(Char(0xFEFF))) return broken("$where starts with a UTF-8 BOM")
                val parsed = parse(line)
                val node = parsed.node ?: return broken("$where ${parsed.notObject}")
                val id = node.textOf(file.idField) ?: return broken("$where has no ${file.idField}")
                if (!seen.add(id)) return broken("$where repeats ${file.idField} '$id'")
                read += BundleLine(i + 1, id, line, node)
            }
            lines[file.name] = read
        }
        val last = try { text(manifestFile, readText) } catch (e: NotUtf8) { null }
        if (last != manifestJson) return BundleRead.NotReady("$MANIFEST in $dir changed while reading")
        return BundleRead.Ready(runId, manifestJson, lines)
    }

    /** Blank is empty or JSON whitespace only. Other Unicode spaces (U+00A0 …) are a line, and a line that is not JSON. */
    internal fun isBlankLine(line: String): Boolean = line.all { it == ' ' || it == '\t' || it == '\r' }

    /** The file's text; null when it is absent or momentarily unreadable (read again later). Bad UTF-8 is thrown as [NotUtf8]. */
    private fun text(path: Path, readText: (Path) -> String): String? = try {
        readText(path)
    } catch (e: CharacterCodingException) {
        throw NotUtf8(path)
    } catch (e: IOException) {
        null
    }

    /** [node] is the object read, or null; then [notObject] says why, with Jackson's cause when parsing threw. */
    private class Parsed(val node: JsonNode?, cause: String?) {
        val notObject = "is not one strict JSON object" + (cause?.let { ": $it" } ?: "")
    }

    private fun parse(text: String): Parsed {
        val node = try { reader.readTree(text) } catch (e: JsonProcessingException) { return Parsed(null, e.originalMessage) }
        return Parsed(node?.takeIf { it.isObject }, null)
    }

    /**
     * [text] as one strict JSON object (the same reader as the bundle lines): the object, or null and why not — with
     * Jackson's cause when parsing threw, as a bundle line's verdict carries it (design §12).
     */
    internal fun parseObject(text: String): Pair<JsonNode?, String> = parse(text).let { it.node to it.notObject }

    private fun JsonNode.textOf(field: String): String? = get(field)?.takeIf { it.isTextual }?.textValue()?.takeIf { it.isNotBlank() }
}
