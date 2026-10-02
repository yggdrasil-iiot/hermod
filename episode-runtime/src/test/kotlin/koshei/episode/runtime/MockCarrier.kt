package koshei.episode.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The Mock host (plan C, design §16): carries picasso JobResponses to koshei in the dev shape `koshei-carry-0`, written
 * the picasso way (lines first, manifest last, each by rename). Tests mix in what a real host will send: reports before
 * the answer, reports of other executions, and the same `jobResponseId` twice.
 */
internal class MockCarrier(val dir: Path, val carryRunId: String = "carry-1") {
    private val mapper = ObjectMapper()
    private val lines = mutableListOf<String>()

    /** One JobResponse. A null list or flag is written as JSON null ("not reported"). */
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
    ): MockCarrier = raw(mapper.writeValueAsString(linkedMapOf(
        "jobResponseId" to jobResponseId, "jobOrderId" to jobOrderId, "executionId" to executionId, "version" to 1,
        "physicalState" to physicalState, "requiredEvidence" to "E1", "reachedEvidence" to reachedEvidence,
        "completedUnits" to completedUnits, "unverifiedUnits" to unverifiedUnits, "incompleteUnits" to emptyMap<String, String>(),
        "operatorRequired" to operatorRequired, "inDoubtUnits" to inDoubtUnits, "blockedBy" to emptyList<String>(),
    )))

    /** A line as given. */
    fun raw(line: String): MockCarrier { lines += line; return this }

    /** Writes everything so far: the lines, then the manifest. */
    fun snapshot(): MockCarrier {
        write("job-responses.jsonl", lines.joinToString("") { "$it\n" })
        write("manifest.json", """{"schemaVersion":"koshei-carry-0","carryRunId":"$carryRunId","writtenAt":"x","counts":{"jobResponses":${lines.size}}}""")
        return this
    }

    private fun write(name: String, text: String) {
        Files.createDirectories(dir)
        val tmp = dir.resolve("$name.tmp")
        Files.writeString(tmp, text)
        Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
