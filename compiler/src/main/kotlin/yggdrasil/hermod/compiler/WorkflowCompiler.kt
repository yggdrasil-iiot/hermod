package yggdrasil.hermod.compiler

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import yggdrasil.hermod.core.BlockContract
import yggdrasil.hermod.core.WorkflowDef
import yggdrasil.hermod.registry.Registry
import yggdrasil.hermod.registry.Resolution

object WorkflowCompiler {
    fun compile(def: WorkflowDef, registry: Registry): WorkflowIR =
        compile(def) { id, spec ->
            when (val r = registry.resolveSpec(id, spec)) {
                is Resolution.Builtin -> r.contract
                is Resolution.Plugin -> r.contract
                null -> null
            }
        }

    fun compile(def: WorkflowDef, resolve: (String, String) -> BlockContract?): WorkflowIR {
        val diags = mutableListOf<String>()
        if (def.steps.isEmpty()) throw CompileException(listOf("workflow '${def.name}' has no steps"))

        // 1. node ids (explicit or s$index) + uniqueness
        val nodeIds = def.steps.mapIndexed { i, s -> s.id ?: "s$i" }
        nodeIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { diags += "duplicate node id '$it'" }
        if (diags.isNotEmpty()) throw CompileException(diags)

        // 2. resolve pinned contracts
        val contracts: List<BlockContract?> = def.steps.map { s ->
            resolve(s.blockId, s.pinnedVersion).also {
                if (it == null) diags += "step '${s.blockId}' v${s.pinnedVersion} not found in registry"
            }
        }
        if (diags.isNotEmpty()) throw CompileException(diags)

        val idToContract: Map<String, BlockContract> = nodeIds.zip(contracts.map { it!! }).toMap()
        val declOrder: Map<String, Int> = nodeIds.withIndex().associate { (i, id) -> id to i }

        // 3. build each node's input wires (explicit wiring, else positional fallback for single-input)
        data class Built(val nodeId: String, val contract: BlockContract, val params: Map<String, String>, val wires: List<IrInputWire>)
        val built = ArrayList<Built>(def.steps.size)
        def.steps.forEachIndexed { i, step ->
            val nodeId = nodeIds[i]
            val contract = contracts[i]!!
            val wires = ArrayList<IrInputWire>(contract.inputs.size)
            for (input in contract.inputs) {
                val wireStr = step.wiring[input.name]
                if (wireStr != null) {
                    val dot = wireStr.lastIndexOf('.')
                    if (dot <= 0) { diags += "node '$nodeId': malformed wire '${input.name}' -> '$wireStr' (expected 'upstreamId.outputName')"; continue }
                    val upId = wireStr.substring(0, dot)
                    val outName = wireStr.substring(dot + 1)
                    val upContract = idToContract[upId]
                    if (upContract == null) { diags += "node '$nodeId': wire '${input.name}' references unknown node '$upId'"; continue }
                    val upOut = upContract.outputs.firstOrNull { it.name == outName }
                    if (upOut == null) { diags += "node '$nodeId': wire '${input.name}' references unknown output '$upId.$outName'"; continue }
                    if (upOut.type != input.type)
                        diags += "type mismatch: $upId.$outName(${upOut.type}) -> ${contract.id}.${input.name}(${input.type})"
                    wires += IrInputWire(input.name, input.type, IrSource.NodeOutput(upId, outName))
                } else if (contract.inputs.size > 1) {
                    diags += "block '${contract.id}' input '${input.name}' is unwired — multi-input nodes must wire every input (no positional fallback)"
                } else {
                    val upstream = built.lastOrNull { it.contract.outputs.isNotEmpty() }
                    if (upstream == null) {
                        wires += IrInputWire(input.name, input.type, IrSource.WorkflowInput)
                    } else {
                        val upOut = upstream.contract.outputs.first()
                        if (upOut.type != input.type)
                            diags += "type mismatch: ${upstream.contract.id}.${upOut.name}(${upOut.type}) -> ${contract.id}.${input.name}(${input.type})"
                        wires += IrInputWire(input.name, input.type, IrSource.NodeOutput(upstream.nodeId, upOut.name))
                    }
                }
            }
            built += Built(nodeId, contract, step.params, wires)
        }
        if (diags.isNotEmpty()) throw CompileException(diags)

        // 4. topological sort (Kahn) with declaration-order tie-break
        val byId = built.associateBy { it.nodeId }
        val deps: Map<String, Set<String>> = built.associate { b ->
            b.nodeId to b.wires.mapNotNull { (it.source as? IrSource.NodeOutput)?.nodeId }.toSet()
        }
        val emitted = LinkedHashSet<String>()
        while (emitted.size < built.size) {
            val next = built.map { it.nodeId }
                .filter { it !in emitted && deps[it]!!.all { d -> d in emitted } }
                .minByOrNull { declOrder[it]!! }
            if (next == null) {
                val remaining = built.map { it.nodeId }.filter { it !in emitted }
                throw CompileException(listOf("cycle detected among nodes: $remaining"))
            }
            emitted += next
        }
        val orderedNodes = emitted.map { id -> byId[id]!!.let { IrNode(it.nodeId, it.contract, it.params, it.wires) } }

        // 5. lint on the topo-ordered IR (unchanged interface)
        val ir = WorkflowIR(def.name, orderedNodes)
        val lint = WorkflowLinter.lint(ir)
        val lintErrors = lint.filter { it.severity == LintSeverity.ERROR }.map { "[${it.rule}] ${it.message}" }
        if (lintErrors.isNotEmpty()) throw CompileException(lintErrors)
        lint.filter { it.severity == LintSeverity.WARNING }
            .forEach { System.err.println("[lint] WARNING ${it.rule}: ${it.message}") }
        return ir
    }

    private val canonicalMapper: ObjectMapper =
        ObjectMapper().registerKotlinModule().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)

    fun canonical(ir: WorkflowIR): String = canonicalMapper.writeValueAsString(ir)
}
