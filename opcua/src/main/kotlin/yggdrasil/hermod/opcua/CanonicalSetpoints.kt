package yggdrasil.hermod.opcua

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

/** One canonical desired setpoint. */
data class CanonicalSetpoint(
    val key: String,
    val nodeId: String,
    val desired: Double,
    val tolerance: Double,
)

/**
 * The Git-canonical DESIRED setpoint values (`model/recipe-setpoints.yaml`). Resolved values are
 * cross-validated against the authoritative [SiteModel] + [CommandPolicy] at load (fail-closed):
 * the node must exist, its declared nodeId must match the model, the model type must be Double,
 * `desired` must be within the model EURange, and the node must be policy-allowed.
 */
class CanonicalSetpoints(
    val endpoint: String,
    private val byKey: Map<String, CanonicalSetpoint>,
) {
    fun byKey(key: String): CanonicalSetpoint? = byKey[key]
    fun keys(): Set<String> = byKey.keys

    companion object {
        private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
        private data class Entry(val nodeId: String, val desired: Double, val tolerance: Double = 1.0)
        private data class Dto(val endpoint: String, val setpoints: Map<String, Entry>)

        /** Load from the given file. */
        fun load(file: File, model: SiteModel, policy: CommandPolicy): CanonicalSetpoints =
            parse(file.readText(), model, policy)

        fun parse(yaml: String, model: SiteModel, policy: CommandPolicy): CanonicalSetpoints {
            val dto = mapper.readValue(yaml, Dto::class.java)
            val errors = mutableListOf<String>()
            val out = LinkedHashMap<String, CanonicalSetpoint>()
            for ((key, e) in dto.setpoints) {
                val nodeDef = try { model.node(key) } catch (ex: IllegalArgumentException) {
                    errors += "node '$key' not in site model (ot-site.yaml)"; continue
                }
                if (nodeDef.nodeId != e.nodeId) errors += "node '$key' nodeId '${e.nodeId}' != site model '${nodeDef.nodeId}'"
                if (nodeDef.type != "Double") errors += "node '$key' type '${nodeDef.type}' is not Double (R1 write path is Double-only)"
                nodeDef.euRange?.let { eu ->
                    if (e.desired < eu.low || e.desired > eu.high)
                        errors += "node '$key' desired ${e.desired} out of EURange [${eu.low}, ${eu.high}]"
                }
                if (!policy.evaluate(key).allowed) errors += "node '$key' denied by command-policy"
                out[key] = CanonicalSetpoint(key, e.nodeId, e.desired, e.tolerance)
            }
            check(errors.isEmpty()) { "invalid recipe-setpoints.yaml: $errors" }
            return CanonicalSetpoints(dto.endpoint, out)
        }
    }
}
