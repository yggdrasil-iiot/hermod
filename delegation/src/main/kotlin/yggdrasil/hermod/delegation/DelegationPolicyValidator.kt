package yggdrasil.hermod.delegation

data class DelegationValidationResult(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

/** Fail-closed policy checks: `default` deny/allow is enforced at parse; this covers per-endpoint rules. */
object DelegationPolicyValidator {
    fun validate(policy: DelegationPolicy): DelegationValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (e in policy.endpoints()) {
            if (!seen.add(e.id)) errors.add("duplicate endpoint id '${e.id}'")
            if (e.threshold < 0.0 || e.threshold > 1.0)
                errors.add("threshold for '${e.id}' must be in [0.0, 1.0], got ${e.threshold}")
            if (e.allow) {
                if (e.url.isBlank()) errors.add("allowed endpoint '${e.id}' must have a non-blank url")
                if (e.metric.isBlank()) errors.add("allowed endpoint '${e.id}' must have a non-blank metric")
            }
        }
        return DelegationValidationResult(errors, warnings)
    }
}
