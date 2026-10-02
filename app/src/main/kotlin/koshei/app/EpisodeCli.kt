package koshei.app

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.serviceclient.WorkflowServiceStubs
import koshei.episode.runtime.EPISODE_TASK_QUEUE
import koshei.episode.runtime.EpisodeStart
import koshei.episode.runtime.EpisodeWorkflow
import koshei.episode.runtime.SymptomSignal
import koshei.runtime.DataConverterSupport
import java.nio.file.Files
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
 * Whatever cannot be read is refused with [IllegalArgumentException], never guessed (design §12).
 */
object EpisodeCli {
    /** Workflow ids are `ep:<key>`. The control plane puts the id in one path segment, so no `/`, `;`, `%` or `?`. */
    private val KEY = Regex("[A-Za-z0-9._:-]+")
    private const val PREFIX = "ep:"
    private val json = jacksonObjectMapper()

    enum class Kind(val file: String, val idField: String) {
        SEARCH("remedy-searches.jsonl", "searchId"),
        INCIDENT("incidents.jsonl", "incidentId"),
    }

    data class OpenArgs(val export: Path, val kind: Kind, val id: String, val key: String?)

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
        return if (search != null) OpenArgs(Path.of(export), Kind.SEARCH, search, key)
        else OpenArgs(Path.of(export), Kind.INCIDENT, incident!!, key)
    }

    fun symptom(open: OpenArgs): OpenSymptom {
        val manifestFile = open.export.resolve("manifest.json")
        require(Files.isRegularFile(manifestFile)) { "no manifest.json in ${open.export} — an export is read only once its manifest is there" }
        val manifestJson = Files.readString(manifestFile)
        val runId = parse(manifestJson, "manifest.json").get("runId")?.takeIf { it.isTextual }?.textValue()
        require(!runId.isNullOrBlank()) { "manifest.json in ${open.export} has no runId — refusing to guess one" }

        val linesFile = open.export.resolve(open.kind.file)
        require(Files.isRegularFile(linesFile)) { "no ${open.kind.file} in ${open.export}" }
        val matches = Files.readAllLines(linesFile).withIndex().filter { it.value.isNotBlank() }.mapNotNull { (n, line) ->
            val node = parse(line, "${open.kind.file} line ${n + 1}")
            if (node.text(open.kind.idField) == open.id) line to node else null
        }
        require(matches.isNotEmpty()) { "no ${open.kind.idField} '${open.id}' in ${open.kind.file}" }
        require(matches.size == 1) { "${matches.size} lines carry ${open.kind.idField} '${open.id}' in ${open.kind.file} — refusing to pick one" }
        val (line, node) = matches.single()

        val eventId = when (open.kind) {
            Kind.SEARCH -> "search:$runId:${open.id}"
            Kind.INCIDENT -> {
                val digest = node.text("digest")
                require(!digest.isNullOrBlank()) { "${open.id} has no digest — refusing to guess its eventId" }
                "incident:$runId:$digest"
            }
        }
        val key = open.key ?: "$runId:${open.id}".also {
            require(KEY.matches(it)) { "the default key '$it' (<runId>:<id>) does not match ${KEY.pattern}; pass --key <correlationKey>" }
        }
        return OpenSymptom(
            workflowId = PREFIX + key,
            start = EpisodeStart(manifestJson),
            signal = SymptomSignal(eventId, open.kind.name, line, System.currentTimeMillis(), manifestJson),
        )
    }

    /** [workflowId] if it is an episode's (`ep:` and a key of [KEY]'s charset); otherwise [IllegalArgumentException]. */
    fun checkWorkflowId(workflowId: String): String {
        require(workflowId.startsWith(PREFIX) && KEY.matches(workflowId.removePrefix(PREFIX))) {
            "'$workflowId' is not an episode workflow id (ep:<key>, key ${KEY.pattern})"
        }
        return workflowId
    }

    /** signalWithStart on the episode queue, as the watcher will (design §7.1). */
    fun open(s: OpenSymptom) = withClient { client ->
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(EPISODE_TASK_QUEUE)
            .setWorkflowId(s.workflowId)
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .build()
        val stub = client.newWorkflowStub(EpisodeWorkflow::class.java, options)
        WorkflowStub.fromTyped(stub).signalWithStart("symptom", arrayOf<Any>(s.signal), arrayOf<Any>(s.start))
    }

    fun agentOff(workflowId: String) = withClient { client ->
        client.newWorkflowStub(EpisodeWorkflow::class.java, checkWorkflowId(workflowId)).agentOff()
    }

    /** A client made as the worker makes one (local 7233, koshei's converter), shut down after [block]. */
    private fun <T> withClient(block: (WorkflowClient) -> T): T {
        val service = WorkflowServiceStubs.newLocalServiceStubs()
        try {
            return block(WorkflowClient.newInstance(service, DataConverterSupport.clientOptions()))
        } finally {
            service.shutdown()
        }
    }

    private fun parse(text: String, what: String): JsonNode {
        val node = try { json.readTree(text) } catch (e: JsonProcessingException) { null }
        require(node != null && node.isObject) { "$what is not a JSON object" }
        return node
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.textValue()
}
