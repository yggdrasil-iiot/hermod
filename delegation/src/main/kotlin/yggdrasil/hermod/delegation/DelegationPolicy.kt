package yggdrasil.hermod.delegation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

data class DelegationDecision(val allowed: Boolean, val ruleId: String?)

class DelegationPolicy(private val endpoints: List<Endpoint>, private val defaultDeny: Boolean) {
    data class Endpoint(
        val id: String,
        val url: String = "",
        val metric: String = "",
        val threshold: Double = 1.0,
        val allow: Boolean = false,
    )

    private fun find(id: String): Endpoint? = endpoints.firstOrNull { it.id == id }

    /** First-match by id; no match -> policy default (ships `deny`). Authorization by endpoint id only. */
    fun evaluate(id: String): DelegationDecision {
        val e = find(id) ?: return DelegationDecision(!defaultDeny, null)
        return DelegationDecision(e.allow, e.id)
    }
    fun thresholdFor(id: String): Double = find(id)?.threshold ?: 1.0
    fun urlFor(id: String): String = find(id)?.url ?: ""
    fun metricFor(id: String): String = find(id)?.metric ?: ""

    fun endpoints(): List<Endpoint> = endpoints
    val denyByDefault: Boolean get() = defaultDeny

    companion object {
        private val mapper = ObjectMapper().registerKotlinModule()
        private data class Dto(val default: String = "deny", val endpoints: List<Endpoint> = emptyList())
        fun default(): DelegationPolicy = System.getenv("HERMOD_DELEGATION_POLICY")
            ?.let { parse(File(it).readText()) } ?: fromClasspath()
        fun fromClasspath(): DelegationPolicy =
            (DelegationPolicy::class.java.getResourceAsStream("/model/delegation-policy.json")
                ?: error("model/delegation-policy.json not on classpath")).bufferedReader().use { parse(it.readText()) }
        fun parse(json: String): DelegationPolicy =
            mapper.readValue(json, Dto::class.java).let {
                require(it.default == "deny" || it.default == "allow") {
                    "delegation-policy default must be 'deny' or 'allow', got '${it.default}'"
                }
                DelegationPolicy(it.endpoints, it.default == "deny")
            }
    }
}
