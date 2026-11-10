package yggdrasil.hermod.opcua

/** No-throw validation result; mirrors registry ContractValidator (ok = errors.isEmpty()). */
// NOTE: intentionally mirrors yggdrasil.hermod.registry.ValidationResult (no :registry dep in :opcua main); keep the two in sync.
data class ValidationResult(val errors: List<String>, val warnings: List<String> = emptyList()) {
    val ok: Boolean get() = errors.isEmpty()
}

object ModelValidator {
    // R1 supports string (s=) and numeric (i=) identifier types only; GUID (g=) and opaque (b=) are R4.
    private val NODE_ID = Regex("""^ns=\d+;[si]=.+$""")
    private const val WRITABLE_SETPOINT_TYPE = "Double"   // R1 OpcUaApplyPort.typeToVariant only handles Double

    fun validateModel(m: SiteModel): ValidationResult {
        val e = mutableListOf<String>()
        for ((key, n) in m.setpoints()) {
            if (!NODE_ID.matches(n.nodeId)) e += "node '$key': unsupported or malformed nodeId '${n.nodeId}' (R1 requires ns=<int>;[s|i]=...)"
            if (n.type != WRITABLE_SETPOINT_TYPE)
                e += "node '$key': setpoint type '${n.type}' not writable in R1 (only '$WRITABLE_SETPOINT_TYPE'; typed write-path is R4)"
            n.euRange?.let { if (it.low >= it.high) e += "node '$key': euRange low ${it.low} must be < high ${it.high}" }
        }
        val cmd = m.activate.command; val done = m.activate.doneNode
        if (!NODE_ID.matches(cmd.nodeId)) e += "activate.command: unsupported or malformed nodeId '${cmd.nodeId}' (R1 requires ns=<int>;[s|i]=...)"
        if (cmd.type != "Method") e += "activate.command.type must be 'Method' (got '${cmd.type}')"
        if (!NODE_ID.matches(done.nodeId)) e += "activate.doneNode: unsupported or malformed nodeId '${done.nodeId}' (R1 requires ns=<int>;[s|i]=...)"
        if (done.type != "Boolean") e += "activate.doneNode.type must be 'Boolean' (got '${done.type}')"
        m.activate.doneClear?.let { dc ->
            if (yggdrasil.hermod.sdk.DoneClearMode.fromToken(dc) == null)
                e += "activate.doneClear '$dc' unknown (expected on-release | explicit-reset | master-clears)"
        }
        return ValidationResult(e)
    }

    fun validatePolicy(p: CommandPolicy): ValidationResult {
        val e = mutableListOf<String>()
        val ids = p.rules().map { it.id }
        ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { e += "policy: duplicate rule id '$it'" }
        // Default-token validity (deny|allow) is enforced fail-closed at CommandPolicy.parse() (Task 1.1),
        // so by the time we hold a CommandPolicy the default is already valid — no re-check here.
        return ValidationResult(e)
    }

    /** Full validation incl. the cross-reference WARNING (policy rule -> unknown model node). */
    fun validate(m: SiteModel, p: CommandPolicy): ValidationResult {
        val mv = validateModel(m); val pv = validatePolicy(p)
        val warnings = mutableListOf<String>()
        val known = m.setpoints().keys
        p.rules().filter { it.node !in known }.forEach { warnings += "policy rule '${it.id}': references node '${it.node}' not in the site model" }
        return ValidationResult(mv.errors + pv.errors, mv.warnings + pv.warnings + warnings)
    }
}
