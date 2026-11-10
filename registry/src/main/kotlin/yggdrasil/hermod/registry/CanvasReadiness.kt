// registry/src/main/kotlin/hermod/registry/CanvasReadiness.kt
package yggdrasil.hermod.registry

import yggdrasil.hermod.core.BlockContract
import yggdrasil.hermod.core.Reversibility
import yggdrasil.hermod.core.SideEffect

/** One canvas-readiness finding. `code` is a stable C1–C5 id the UI renders; distinct in shape from
 *  [ContractValidator]'s plain-string ValidationResult (analogous in role, not the same type). */
data class ReadinessDiagnostic(val code: String, val message: String)

/**
 * Canvas-readiness completeness check: does this contract carry the presentation metadata the operator
 * canvas (v0.4) needs? Distinct from [ContractValidator] (runtime safety). A contract is canvas-ready
 * iff [check] returns empty. Used as palette admission control (spec §2.1).
 */
object CanvasReadiness {
    fun check(c: BlockContract): List<ReadinessDiagnostic> {
        val d = mutableListOf<ReadinessDiagnostic>()
        if (c.displayName.isBlank()) d += ReadinessDiagnostic("C1", "displayName is blank")
        if (c.description.isBlank()) d += ReadinessDiagnostic("C2", "description is blank")
        c.params.filter { it.label.isBlank() }.forEach {
            d += ReadinessDiagnostic("C3", "param '${it.name}' has no label")
        }
        (c.inputs + c.outputs).filter { it.label.isBlank() }.forEach {
            d += ReadinessDiagnostic("C4", "port '${it.name}' has no label")
        }
        c.params.filter { it.widget == "select" && it.enumValues.isEmpty() }.forEach {
            d += ReadinessDiagnostic("C5", "param '${it.name}' uses widget=select but has no enumValues")
        }
        return d
    }

    fun isReady(c: BlockContract): Boolean = check(c).isEmpty()

    /** Risk badge derived from sideEffects + reversibility (no new field; spec §4.1). */
    fun risk(c: BlockContract): String = when {
        SideEffect.ACTUATION in c.sideEffects || c.compensation.reversibility == Reversibility.IRREVERSIBLE -> "red"
        c.compensation.reversibility == Reversibility.MITIGATABLE ||
            c.sideEffects.any { it == SideEffect.EXTERNAL_CALL || it == SideEffect.MESSAGE_SEND } -> "amber"
        else -> "green"
    }
}
