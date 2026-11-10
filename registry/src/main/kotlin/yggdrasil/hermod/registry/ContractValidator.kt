package yggdrasil.hermod.registry

import yggdrasil.hermod.core.*

// NOTE: yggdrasil.hermod.opcua.ValidationResult intentionally mirrors this shape; keep the two in sync.
data class ValidationResult(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

object ContractValidator {
    fun validate(c: BlockContract): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Rule 1: requiresState ⊆ stateBinding
        val bound = c.stateBinding.map { it.key }.toSet()
        c.compensation.requiresState.filterNot { it in bound }.forEach {
            errors += "compensation.requiresState key '$it' not declared in stateBinding"
        }
        // Rule 2: idempotency NONE -> warning (intended NONE like actuate still warns, allowed)
        if (c.idempotency.strategy == IdempotencyStrategy.NONE) {
            warnings += "idempotency.strategy is NONE for '${c.id}' — re-execution is not deduped (intended only for IRREVERSIBLE/maxAttempts=1)"
        }
        // Rule 3: REVERSIBLE/MITIGATABLE with actual compensation (kind != NONE) need a handler.
        // kind=NONE (e.g. db.read/transform.map — nothing to undo) needs none; IRREVERSIBLE is a gate.
        val needsHandler = c.compensation.reversibility != Reversibility.IRREVERSIBLE &&
            c.compensation.kind != CompensationKind.NONE
        if (needsHandler && c.compensation.handler.isNullOrBlank())
            errors += "compensation.handler required for ${c.compensation.reversibility}/${c.compensation.kind} block '${c.id}'"
        return ValidationResult(errors, warnings)
    }
}

object WorkflowValidator {
    /**
     * Rule 4: every step references an existing pinned (id, version) in the registry — now the
     * hybrid union (built-ins ∪ published plugins) via [Registry.contains].
     */
    fun validate(def: WorkflowDef, reg: Registry): List<String> =
        def.steps.mapNotNull { s ->
            if (!reg.contains(s.blockId, s.pinnedVersion))
                "workflow '${def.name}' step '${s.blockId}' v${s.pinnedVersion} not found in registry"
            else null
        }
}
