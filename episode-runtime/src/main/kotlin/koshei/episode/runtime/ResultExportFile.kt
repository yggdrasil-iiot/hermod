package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * picasso's result notices as a host carries them (picasso ADR 48, `docs/orchestration.md` §8, main 3d6530a): the file
 * `job-responses.jsonl`, one JobResponse per line in the order picasso gave them, every line with its own
 * `schemaVersion` ("1") and `instanceId`. There is no manifest. A host may write the file whole by rename (picasso's
 * reference driver, `BundleWriter.carry`) or append to it, so a last piece without its newline is not a line yet.
 *
 * At least once: the same report may come again, in this file or later. The id of a line is `<instanceId>/<jobResponseId>`
 * — both are counted per picasso instance — and the core drops repeats by it (design §6, §12).
 *
 * What it cannot read stops the file there (design §12): a line that is not one strict JSON object, starts with a BOM,
 * has another schemaVersion, or names no instanceId or jobResponseId. The lines before it are read.
 */
object ResultExportFile {
    const val FILE = "job-responses.jsonl"
    val SCHEMA_VERSIONS = setOf("1")

    sealed interface Read {
        /** [lines] in order; [broken] says why reading stopped at the line after them, or is null when it did not. */
        data class Ready(val lines: List<BundleLine>, val broken: String?) : Read {
            /**
             * The cursor's run (design §12): the instance of the first line. A file replaced whole by another instance's
             * starts a new cursor from its first line; the same instance's file changed under the cursor is STUCK.
             */
            val runId: String? get() = lines.firstOrNull()?.node?.get("instanceId")?.textValue()
        }

        /** Not there yet, or not readable this moment: read again at the next poll. */
        data class NotReady(val why: String) : Read

        /** Cannot be read as it is: say so and wait — never skip, never guess. */
        data class Broken(val why: String) : Read
    }

    sealed interface Line {
        /** A report and the order whose episodes it goes to. */
        data class Routed(val jobOrderId: String, val signal: EvidenceSignal) : Line
        /** Read, but it names no order: there is nowhere to carry it. */
        data class Unrouted(val jobResponseId: String) : Line
        /** A field has a shape this reader does not know: stop, never guess. */
        data class Broken(val why: String) : Line
    }

    fun read(dir: Path, readBytes: (Path) -> ByteArray = Files::readAllBytes): Read {
        val path = dir.resolve(FILE)
        val size = try { Files.size(path) } catch (e: NoSuchFileException) {
            return Read.NotReady("no $FILE in $dir")
        } catch (e: IOException) {
            return Read.NotReady("$FILE in $dir: ${e.javaClass.simpleName}")
        }
        // No size in the reason: it would make a new reason (and a new log row) every time the file grows.
        if (size > Bundles.MAX_FILE_BYTES) return Read.Broken("$FILE in $dir is over ${Bundles.MAX_FILE_BYTES} bytes")
        val bytes = try {
            readBytes(path)
        } catch (e: IOException) {
            return Read.NotReady("$FILE in $dir could not be read (vanished or denied)")
        }
        // A last piece without its newline is still being written — it may even end partway through a character, so
        // cut at the last newline byte first and decode only the complete lines.
        val end = bytes.lastIndexOf(NEWLINE) + 1
        val complete = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, end))
                .toString()
        } catch (e: CharacterCodingException) {
            return Read.Broken("$FILE in $dir is not UTF-8")   // a complete line will not become readable by waiting
        }
        val lines = ArrayList<BundleLine>()
        for (raw in complete.split('\n').dropLast(1)) {
            val line = raw.removeSuffix("\r")
            if (Bundles.isBlankLine(line)) continue
            val where = "$FILE in $dir line ${lines.size + 1}"
            if (line.startsWith(Char(0xFEFF))) return Read.Ready(lines, "$where starts with a UTF-8 BOM")
            val (parsed, notObject) = Bundles.parseObject(line)
            val node = parsed ?: return Read.Ready(lines, "$where $notObject")
            val schema = node.get("schemaVersion")?.takeIf { it.isTextual }?.textValue()
            if (schema !in SCHEMA_VERSIONS) return Read.Ready(lines, "$where: schemaVersion ${node.get("schemaVersion")}, this reader knows $SCHEMA_VERSIONS")
            val instance = nonBlank(node, "instanceId") ?: return Read.Ready(lines, "$where has no instanceId")
            val id = nonBlank(node, "jobResponseId") ?: return Read.Ready(lines, "$where has no jobResponseId")
            lines += BundleLine(lines.size + 1, "$instance/$id", line, node)
        }
        return Read.Ready(lines, null)
    }

    /** A line [read] returned, as the `evidence` signal it becomes; [receivedAtMillis] is when the watcher read it. */
    fun of(node: JsonNode, receivedAtMillis: Long): Line {
        val id = nonBlank(node, "jobResponseId") ?: return Line.Broken("no jobResponseId")
        // Each field reader returns null for absent or JSON null ("not reported") and throws [WrongShape] otherwise.
        fun text(f: String): String? = node.get(f).let { v ->
            when {
                v == null || v.isNull -> null
                v.isTextual -> v.textValue()
                else -> throw WrongShape("$f is not text")
            }
        }
        fun units(f: String): List<String>? = node.get(f).let { v ->
            when {
                v == null || v.isNull -> null
                v.isArray && v.all { it.isTextual } -> v.map { it.textValue() }
                else -> throw WrongShape("$f is not a list of text")
            }
        }
        fun flag(f: String): Boolean? = node.get(f).let { v ->
            when {
                v == null || v.isNull -> null
                v.isBoolean -> v.booleanValue()
                else -> throw WrongShape("$f is not true or false")
            }
        }
        val signal = try {
            EvidenceSignal(
                jobResponseId = id,
                executionId = text("executionId"),
                physicalState = text("physicalState"),
                reachedEvidence = text("reachedEvidence"),
                completedUnits = units("completedUnits"),
                inDoubtUnits = units("inDoubtUnits"),
                unverifiedUnits = units("unverifiedUnits"),
                operatorRequired = flag("operatorRequired"),
                receivedAtMillis = receivedAtMillis,
                picassoInstanceId = text("instanceId"),
                connection = text("connection"),
            )
        } catch (e: WrongShape) {
            return Line.Broken("$id: ${e.message}")
        }
        val order = nonBlank(node, "jobOrderId") ?: return Line.Unrouted(id)
        return Line.Routed(order, signal)
    }

    private const val NEWLINE: Byte = 0x0A

    private fun nonBlank(node: JsonNode, field: String): String? =
        node.get(field)?.takeIf { it.isTextual }?.textValue()?.takeIf { it.isNotBlank() }

    /** A field of a shape this reader does not know. */
    private class WrongShape(why: String) : Exception(why, null, false, false)
}
