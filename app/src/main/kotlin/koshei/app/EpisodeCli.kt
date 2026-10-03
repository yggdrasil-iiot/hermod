package koshei.app

import io.temporal.client.WorkflowClient
import io.temporal.serviceclient.WorkflowServiceStubs
import koshei.episode.runtime.AgentOffBroadcast
import koshei.episode.runtime.BundleRead
import koshei.episode.runtime.EpisodeStart
import koshei.episode.runtime.EpisodeWorkflow
import koshei.episode.runtime.ExportKind
import koshei.episode.runtime.ExportSymptoms
import koshei.episode.runtime.SymptomSignal
import koshei.episode.runtime.TemporalEpisodeSignals
import koshei.runtime.DataConverterSupport
import java.nio.file.Path

/** What `episode open` sends: signalWithStart of [signal] on workflow [workflowId], starting it with [start] if absent. */
data class OpenSymptom(val workflowId: String, val start: EpisodeStart, val signal: SymptomSignal)

/**
 * The dev CLI's way into the episode loop (design §7.2: "symptom · agentOff are the watcher's or the CLI's signal").
 * It is not the watcher (plan C): it opens one episode from one line of one picasso export, picked by hand.
 *
 *   episode open --export <dir> (--search <searchId> | --incident <incidentId>) [--key <correlationKey>]
 *   episode agent-off <workflowId>
 *
 * Whatever cannot be read is refused with [IllegalArgumentException], never guessed (design §12). The export is read
 * through [ExportSymptoms], as the watcher reads it, so a line the CLI sends is a line the watcher would send, with the
 * same `eventId`. So the manifest needs `schemaVersion "5"` and `counts`; lines past `counts` are not read; and one
 * line that cannot be read in either jsonl file blocks the open — a broken incident line blocks a `--search` too. JSON
 * is read as strictly as the core reads it (duplicate keys refused). What cannot be read is refused, not skipped.
 */
object EpisodeCli {
    /** Workflow ids are `ep:<key>`. The control plane puts the id in one path segment, so no `/`, `;`, `%` or `?`. */
    private val KEY = Regex("[A-Za-z0-9._:-]+")
    private const val PREFIX = "ep:"

    data class OpenArgs(val export: Path, val kind: ExportKind, val id: String, val key: String?)

    fun parseOpen(args: Array<String>): OpenArgs {
        val flags = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val flag = args[i]
            require(flag in setOf("--export", "--search", "--incident", "--key")) { "episode open: unknown argument '$flag'" }
            val value = args.getOrNull(i + 1)
            require(value != null && !value.startsWith("--")) { "episode open: $flag requires a value" }
            require(flags.put(flag, value) == null) { "episode open: $flag given twice" }
            i += 2
        }
        val export = requireNotNull(flags["--export"]) { "episode open requires --export <dir>" }
        val search = flags["--search"]
        val incident = flags["--incident"]
        require((search == null) != (incident == null)) { "episode open requires exactly one of --search <searchId> or --incident <incidentId>" }
        val key = flags["--key"]
        if (key != null) require(KEY.matches(key)) { "--key '$key' must match ${KEY.pattern}" }
        return if (search != null) OpenArgs(Path.of(export), ExportKind.SEARCH, search, key)
        else OpenArgs(Path.of(export), ExportKind.INCIDENT, incident!!, key)
    }

    fun symptom(open: OpenArgs): OpenSymptom {
        val export = when (val r = ExportSymptoms.read(open.export)) {
            is BundleRead.Ready -> r
            is BundleRead.NotReady -> throw IllegalArgumentException("${r.why} — not ready yet; read again once the export is complete")
            is BundleRead.Broken -> throw IllegalArgumentException(r.why)
        }
        val line = export.lines.getValue(open.kind.file.name).singleOrNull { it.id == open.id }
            ?: throw IllegalArgumentException("no ${open.kind.file.idField} '${open.id}' in ${open.kind.file.name}")
        val signal = ExportSymptoms.symptom(open.kind, export, line, System.currentTimeMillis())
            ?: throw IllegalArgumentException("${open.id} has no digest — refusing to guess its eventId")
        val key = open.key ?: "${export.runId}:${open.id}".also {
            require(KEY.matches(it)) { "the default key '$it' (<runId>:<id>) does not match ${KEY.pattern}; pass --key <correlationKey>" }
        }
        return OpenSymptom(workflowId = PREFIX + key, start = EpisodeStart(export.manifestJson), signal = signal)
    }

    /** [workflowId] if it is an episode's (`ep:` and a key of [KEY]'s charset); otherwise [IllegalArgumentException]. */
    fun checkWorkflowId(workflowId: String): String {
        require(workflowId.startsWith(PREFIX) && KEY.matches(workflowId.removePrefix(PREFIX))) {
            "'$workflowId' is not an episode workflow id (ep:<key>, key ${KEY.pattern})"
        }
        return workflowId
    }

    /** What `episode agent-off` was asked: one episode (its workflow id, checked) or every open one. */
    sealed interface AgentOffTarget {
        data class One(val workflowId: String) : AgentOffTarget
        data object All : AgentOffTarget
    }

    /** [args] after `episode agent-off`: exactly one `<workflowId>` or `--all`. */
    fun agentOffTarget(args: List<String>): AgentOffTarget {
        require(args.size == 1) { "episode agent-off takes exactly one of <workflowId> or --all" }
        return if (args[0] == "--all") AgentOffTarget.All else AgentOffTarget.One(checkWorkflowId(args[0]))
    }

    /**
     * signalWithStart on the episode queue, as the watcher will (design §7.1). Typed, so renaming the workflow or
     * signal method breaks this at compile time.
     */
    fun open(s: OpenSymptom): String = withClient { client ->
        TemporalEpisodeSignals(client).open(s.workflowId, s.start, s.signal)
    }

    fun agentOff(workflowId: String) = withClient { client ->
        client.newWorkflowStub(EpisodeWorkflow::class.java, checkWorkflowId(workflowId)).agentOff()
    }

    /** `agentOff` to every open episode (design §10.4); the broadcast's report. */
    fun agentOffAll(): AgentOffBroadcast.Report = withClient { client -> AgentOffBroadcast(client).send() }

    /** A client made as the worker makes one (local 7233, koshei's converter), shut down after [block]. */
    private fun <T> withClient(block: (WorkflowClient) -> T): T {
        val service = WorkflowServiceStubs.newLocalServiceStubs()
        try {
            return block(WorkflowClient.newInstance(service, DataConverterSupport.clientOptions()))
        } finally {
            service.shutdown()
        }
    }
}
