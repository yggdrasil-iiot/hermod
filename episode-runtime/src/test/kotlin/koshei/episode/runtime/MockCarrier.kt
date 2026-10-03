package koshei.episode.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import koshei.episode.PICASSO_ONLINE
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The Mock host (plan C, design §16): carries picasso JobResponses to koshei as ResultExport schema 1 (picasso ADR 48),
 * the file `job-responses.jsonl`. [snapshot] writes it whole by rename, as picasso's reference driver does; [append]
 * adds text as it is, as an appending host caught mid-write would. Tests mix in what a real host will send: reports
 * before the answer, reports of other executions and instances, and the same report twice.
 */
internal class MockCarrier(val dir: Path, val instanceId: String = MockPicasso.INSTANCE) {
    private val mapper = ObjectMapper()
    private val lines = mutableListOf<String>()

    /** One JobResponse with every field picasso writes, in its order. A null list or flag is written as JSON null. */
    fun report(
        jobResponseId: String,
        jobOrderId: String?,
        executionId: String?,
        completedUnits: List<String>? = emptyList(),
        inDoubtUnits: List<String>? = emptyList(),
        unverifiedUnits: List<String>? = emptyList(),
        operatorRequired: Boolean? = false,
        physicalState: String? = "PHYSICALLY_DONE",
        reachedEvidence: String? = "E1",
        connection: String? = PICASSO_ONLINE,
        instanceId: String = this.instanceId,
    ): MockCarrier = raw(mapper.writeValueAsString(linkedMapOf(
        "schemaVersion" to "1", "contractSemver" to "0.9.0", "instanceId" to instanceId,
        "jobResponseId" to jobResponseId, "jobOrderId" to jobOrderId, "executionId" to executionId, "version" to 1,
        "physicalState" to physicalState, "requiredEvidence" to "E1", "reachedEvidence" to reachedEvidence,
        "completedUnits" to completedUnits, "unverifiedUnits" to unverifiedUnits, "inDoubtUnits" to inDoubtUnits,
        "incompleteUnits" to emptyMap<String, String>(), "operatorRequired" to operatorRequired,
        "residualHold" to linkedMapOf("kind" to "HOLD_KIND_EMPTY", "objectRef" to "", "reason" to ""), "autoResolvesInDoubt" to true, "results" to emptyMap<String, String>(),
        "blockedBy" to emptyList<String>(), "connection" to connection,
    )))

    /** A line as given. */
    fun raw(line: String): MockCarrier { lines += line; return this }

    /** Writes every line so far, whole, by rename — picasso `BundleWriter.carry`. */
    fun snapshot(): MockCarrier {
        Files.createDirectories(dir)
        val tmp = dir.resolve("${ResultExportFile.FILE}.tmp")
        Files.writeString(tmp, lines.joinToString("") { "$it\n" })
        Files.move(tmp, dir.resolve(ResultExportFile.FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return this
    }

    /** Appends [text] to the file exactly as given — no newline is added. Does not touch the lines [snapshot] writes. */
    fun append(text: String): MockCarrier {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(ResultExportFile.FILE), text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        return this
    }
}
