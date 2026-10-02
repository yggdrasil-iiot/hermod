package koshei.episode.runtime

import java.nio.file.Path

/** The two line kinds of a picasso export (design §12). [name] is the `SymptomSignal.kind`. */
enum class ExportKind(val file: BundleFile) {
    INCIDENT(BundleFile("incidents.jsonl", "incidents", "incidentId")),
    SEARCH(BundleFile("remedy-searches.jsonl", "remedySearches", "searchId")),
}

/**
 * A picasso export as symptoms — the one place that says how a line becomes a `symptom` signal, shared by the dev CLI
 * and the watcher so both send the same `eventId` for the same line (design §7.2 구현(B3c), §12).
 */
object ExportSymptoms {
    /** picasso `LedgerExport.SCHEMA_VERSION` this reader was written against (picasso `8ab5400`). */
    val SCHEMA_VERSIONS = setOf("5")

    /** Incidents first, then searches: the order picasso writes them, and the order the watcher sends them. */
    fun read(dir: Path): BundleRead =
        Bundles.read(dir, "runId", SCHEMA_VERSIONS, ExportKind.entries.map { it.file }, uniqueIds = true)

    /** `search:<runId>:<searchId>`, `incident:<runId>:<digest>`. Null for an incident without a digest: never guessed. */
    fun eventId(kind: ExportKind, runId: String, line: BundleLine): String? = when (kind) {
        ExportKind.SEARCH -> "search:$runId:${line.id}"
        ExportKind.INCIDENT -> line.node.get("digest")?.takeIf { it.isTextual }?.textValue()?.takeIf { it.isNotBlank() }
            ?.let { "incident:$runId:$it" }
    }

    /** The `symptom` signal for [line]: the line and the manifest exactly as written (design §7.1 구현(B3b)). */
    fun symptom(kind: ExportKind, export: BundleRead.Ready, line: BundleLine, observedAtMillis: Long): SymptomSignal? =
        eventId(kind, export.runId, line)?.let { SymptomSignal(it, kind.name, line.text, observedAtMillis, export.manifestJson) }
}
