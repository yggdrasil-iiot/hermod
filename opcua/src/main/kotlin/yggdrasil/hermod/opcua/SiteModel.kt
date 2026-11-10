package yggdrasil.hermod.opcua

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

data class EuRange(val low: Double, val high: Double)
data class NodeDef(val nodeId: String, val type: String, val euRange: EuRange? = null)
data class ActivateDef(val command: NodeDef, val doneNode: NodeDef, val doneClear: String? = null)

class SiteModel(
    val endpoint: String,
    private val nodes: Map<String, NodeDef>,
    val activate: ActivateDef,
) {
    fun node(key: String): NodeDef =
        nodes[key] ?: throw IllegalArgumentException("unknown model node: $key")

    /** All setpoint nodes (everything under `nodes:`), for the sim to pre-register the address space. */
    fun setpoints(): Map<String, NodeDef> = nodes

    companion object {
        private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
        private data class Dto(val endpoint: String, val nodes: Map<String, NodeDef>, val activate: ActivateDef)

        /** Default load: env `HERMOD_OPCUA_MODEL` path if set, else the classpath resource. */
        fun default(): SiteModel = System.getenv("HERMOD_OPCUA_MODEL")
            ?.let { fromFile(File(it)) } ?: fromClasspath()

        fun fromClasspath(): SiteModel =
            (SiteModel::class.java.getResourceAsStream("/model/ot-site.yaml")
                ?: error("model/ot-site.yaml not on classpath")).bufferedReader().use { parse(it.readText()) }

        fun fromFile(f: File): SiteModel = parse(f.readText())

        fun parse(yaml: String): SiteModel =
            mapper.readValue(yaml, Dto::class.java).let { SiteModel(it.endpoint, it.nodes, it.activate) }
    }
}
