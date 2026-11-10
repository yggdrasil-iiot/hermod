package yggdrasil.hermod.runtime

import yggdrasil.hermod.compiler.WorkflowIR
import yggdrasil.hermod.core.BlockContract

/**
 * Engine-specific backend (Temporal). Lowers the engine-neutral [WorkflowIR] into the ordered
 * [BlockContract] list that the existing contract-driven [SagaWorkflowImpl] consumes via
 * [BoundWorkflow]. Kept trivial on purpose: the saga is already contract-driven, so "lowering" to
 * Temporal == projecting the IR's per-node contracts in order. Lives in :runtime (NOT :compiler) so
 * the compiler stays engine-neutral.
 */
object TemporalBackend {
    fun lower(ir: WorkflowIR): List<BlockContract> = ir.nodes.map { it.contract }
}
