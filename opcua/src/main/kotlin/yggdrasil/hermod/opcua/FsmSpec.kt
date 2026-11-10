package yggdrasil.hermod.opcua

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

/** One FSM state: logical id + the integer code the equipment's stateNode reports. */
data class FsmState(val id: String, val code: Int)

/** The governed action of a hermod-driven transition (the workflow to run). */
data class FsmAction(val workflow: String)

/** One transition. `command` is nullable (reactive/PLC transitions). `action` present iff driver=hermod. */
data class FsmTransition(
    val id: String,
    val from: String,
    val to: String,
    val command: String? = null,
    val driver: String,
    val action: FsmAction? = null,
)

/**
 * A Git-canonical FSM spec (`model/fsm/<name>.yaml`). Loader only — structural validation lives in
 * [FsmValidator] (no-throw, mirrors [ModelValidator]); cross-artifact checks (stateNode ∈ site model,
 * action.workflow ∈ governed ot-* set) live in the CI conformance gate. See the design spec 2026-07-02.
 */
class FsmSpec(
    val name: String,
    val unit: String,
    val stateNode: String,
    val states: List<FsmState>,
    val transitions: List<FsmTransition>,
    val version: String = "",
) {
    companion object {
        private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
        private data class Dto(
            val name: String,
            val unit: String = "",
            val stateNode: String,
            val states: List<FsmState> = emptyList(),
            val transitions: List<FsmTransition> = emptyList(),
            val version: String = "",
        )
        fun parse(yaml: String): FsmSpec =
            mapper.readValue(yaml, Dto::class.java).let { FsmSpec(it.name, it.unit, it.stateNode, it.states, it.transitions, it.version) }
        fun fromFile(f: File): FsmSpec = parse(f.readText())

        /** Resolve the FSM spec FILE by its IN-FILE (unit, version) — NOT by filename. Scans <modelDir>/fsm for
         *  yaml files, parses each, matches unit+version. Ambiguous (more than one) throws (fail-closed); none → null. Pure
         *  :opcua (FsmSpec parse + java.io.File), no :registry — reused by the CLI's resolveFsmFile + the auto-correct
         *  poller. See design 2026-07-03. */
        fun resolve(modelDir: File, unit: String, version: String): File? {
            val fsmDir = File(modelDir, "fsm")
            val matches = (fsmDir.listFiles { f -> f.isFile && f.name.endsWith(".yaml") } ?: emptyArray()).filter { f ->
                val s = try { fromFile(f) } catch (e: Exception) { return@filter false }
                s.unit == unit && s.version == version
            }
            if (matches.size > 1) error("ambiguous FSM spec: ${matches.size} files declare unit='$unit' version='$version'")
            return matches.singleOrNull()
        }
    }
}
