package yggdrasil.hermod.opcua

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

data class Decision(val allowed: Boolean, val ruleId: String?)

class CommandPolicy(private val rules: List<Rule>, private val defaultDeny: Boolean) {
    data class Rule(val id: String, val node: String, val allow: Boolean)
    /** First-match; no match -> the policy's default (R1 ships `deny`). Authorization by node only; value range is the model euRange. */
    fun evaluate(node: String): Decision {
        val r = rules.firstOrNull { it.node == node } ?: return Decision(!defaultDeny, null)
        return Decision(r.allow, r.id)
    }
    /** Read accessor for validation: returns the list of rules. */
    // Exposed as a function (not a property) to avoid shadowing the private `rules` field.
    fun rules(): List<Rule> = rules
    /** Read accessor for validation: true when the policy default is deny. */
    val denyByDefault: Boolean get() = defaultDeny

    companion object {
        private val mapper = ObjectMapper().registerKotlinModule()
        private data class Dto(val default: String = "deny", val rules: List<Rule> = emptyList())
        fun default(): CommandPolicy = System.getenv("HERMOD_OPCUA_POLICY")
            ?.let { parse(File(it).readText()) } ?: fromClasspath()
        fun fromClasspath(): CommandPolicy =
            (CommandPolicy::class.java.getResourceAsStream("/model/command-policy.json")
                ?: error("model/command-policy.json not on classpath")).bufferedReader().use { parse(it.readText()) }
        fun parse(json: String): CommandPolicy =
            mapper.readValue(json, Dto::class.java).let {
                require(it.default == "deny" || it.default == "allow") {
                    "command-policy default must be 'deny' or 'allow', got '${it.default}'"
                }
                CommandPolicy(it.rules, it.default == "deny")
            }
    }
}
