package yggdrasil.hermod.compiler

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import yggdrasil.hermod.core.BlockContract

/** Engine-neutral normalized workflow. DAG-shaped (nodes + input wires); v0.2b fills LINEAR chains only. */
data class WorkflowIR(
    val name: String,
    val nodes: List<IrNode>,          // topologically ordered (linear: input order)
)

/** One compiled step: its resolved pinned contract (carries id/version/io/idempotency/compensation/
 *  retry/human/sideEffects/timeout) + operator params + computed input wiring. */
data class IrNode(
    val nodeId: String,               // index-based "s0".."sN" — determinism + Conductor taskReferenceName uniqueness
    val contract: BlockContract,
    val params: Map<String, String>,
    val inputs: List<IrInputWire>,    // empty for 0-input nodes
)

data class IrInputWire(val inputName: String, val type: String, val source: IrSource)

// Explicit polymorphism discriminator (NOT conditional): the two variants must be distinguishable in
// the canonical serialization, else WorkflowInput ({}) and a field-less NodeOutput could collide.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = IrSource.WorkflowInput::class, name = "workflowInput"),
    JsonSubTypes.Type(value = IrSource.NodeOutput::class, name = "nodeOutput"),
)
sealed interface IrSource {
    /** First node (no upstream output): fed by the workflow's external input. */
    data object WorkflowInput : IrSource
    /** Wired to a prior node's output (by position; name may differ). */
    data class NodeOutput(val nodeId: String, val outputName: String) : IrSource
}

/** Thrown by WorkflowCompiler.compile on resolve/type errors — aggregates ALL diagnostics. */
class CompileException(val diagnostics: List<String>) :
    RuntimeException("workflow compile failed:\n" + diagnostics.joinToString("\n") { "  - $it" })
