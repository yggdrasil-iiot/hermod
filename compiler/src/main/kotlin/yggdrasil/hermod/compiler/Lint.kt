package yggdrasil.hermod.compiler

import yggdrasil.hermod.core.BlockCategory
import yggdrasil.hermod.core.CompensationKind
import yggdrasil.hermod.core.IdempotencyStrategy
import yggdrasil.hermod.core.Reversibility
import yggdrasil.hermod.core.SideEffect

enum class LintSeverity { ERROR, WARNING }
data class LintDiagnostic(val severity: LintSeverity, val rule: String, val message: String)

/** Workflow-level (cross-step) lint over the compiled IR. Per-contract checks are ContractValidator's
 *  job; this is the layer above. Pure — returns all diagnostics; the caller decides error vs warning IO. */
object WorkflowLinter {
    fun lint(ir: WorkflowIR): List<LintDiagnostic> {
        val out = mutableListOf<LintDiagnostic>()

        // E1 irreversible-ordering: a compensable step after an IRREVERSIBLE step can't be made consistent.
        val firstIrrevIdx = ir.nodes.indexOfFirst { it.contract.compensation.reversibility == Reversibility.IRREVERSIBLE }
        if (firstIrrevIdx >= 0) {
            ir.nodes.drop(firstIrrevIdx + 1).forEach { later ->
                val compensable = later.contract.compensation.reversibility != Reversibility.IRREVERSIBLE &&
                    later.contract.compensation.kind != CompensationKind.NONE
                if (compensable) out += LintDiagnostic(LintSeverity.ERROR, "irreversible-ordering",
                    "compensable step '${later.contract.id}' (${later.nodeId}) runs after IRREVERSIBLE step '${ir.nodes[firstIrrevIdx].contract.id}' — a later failure cannot undo the irreversible effect")
            }
        }

        // W1 idempotency-none: only for side-effecting, non-IRREVERSIBLE NONE (real at-least-once hazard).
        ir.nodes.forEach { n ->
            val hasEffect = n.contract.sideEffects.any { it != SideEffect.NONE }
            val irreversible = n.contract.compensation.reversibility == Reversibility.IRREVERSIBLE
            if (n.contract.idempotency.strategy == IdempotencyStrategy.NONE && hasEffect && !irreversible)
                out += LintDiagnostic(LintSeverity.WARNING, "idempotency-none",
                    "side-effecting step '${n.contract.id}' (${n.nodeId}) has idempotency=NONE — not deduped on retry/replay (at-least-once risk)")
        }

        // W2 no-sink.
        if (ir.nodes.none { it.contract.category == BlockCategory.sink })
            out += LintDiagnostic(LintSeverity.WARNING, "no-sink",
                "workflow '${ir.name}' has no sink step — may have no durable output")

        return out
    }
}
