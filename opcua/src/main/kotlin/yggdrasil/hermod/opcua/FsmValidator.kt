package yggdrasil.hermod.opcua

/**
 * Fail-closed structural validation of an [FsmSpec] (no-throw; reuses [ValidationResult] from
 * ModelValidator). Cross-artifact checks (stateNode ∈ site model; action.workflow resolvable) are the
 * conformance gate's job (it holds the site model + workflow set). See the design spec 2026-07-02 §7.
 */
object FsmValidator {
    private val DRIVERS = setOf("hermod", "field")

    fun validate(fsm: FsmSpec): ValidationResult {
        val e = mutableListOf<String>()
        if (fsm.stateNode.isBlank()) e += "stateNode must be non-blank"

        // states: non-empty, unique id, unique code
        if (fsm.states.isEmpty()) e += "at least one state is required"
        fsm.states.groupingBy { it.id }.eachCount().filter { it.value > 1 }.keys.forEach { e += "duplicate state id '$it'" }
        fsm.states.groupingBy { it.code }.eachCount().filter { it.value > 1 }.keys.forEach { e += "duplicate state code '$it'" }
        val ids = fsm.states.map { it.id }.toSet()

        // transitions: non-empty, unique+non-blank id, valid from/to/driver/action, unique (from,command)
        if (fsm.transitions.isEmpty()) e += "at least one transition is required"
        fsm.transitions.groupingBy { it.id }.eachCount().filter { it.value > 1 }.keys.forEach { e += "duplicate transition id '$it'" }
        val seenKey = mutableSetOf<Pair<String, String?>>()
        for (t in fsm.transitions) {
            if (t.id.isBlank()) e += "a transition has a blank id"
            if (t.from !in ids) e += "transition '${t.id}': from-state '${t.from}' is not a declared state"
            if (t.to !in ids)   e += "transition '${t.id}': to-state '${t.to}' is not a declared state"
            if (t.driver !in DRIVERS) e += "transition '${t.id}': driver '${t.driver}' must be one of $DRIVERS"
            if (t.driver == "hermod" && t.action?.workflow.isNullOrBlank())
                e += "transition '${t.id}': hermod-driven transition must declare action.workflow"
            if (t.driver == "field" && t.action != null)
                e += "transition '${t.id}': field-driven transition must NOT declare an action"
            val key = t.from to t.command   // null participates as an ordinary value
            if (!seenKey.add(key)) e += "duplicate (from='${t.from}', command='${t.command}') transition"
        }
        return ValidationResult(e)
    }
}
