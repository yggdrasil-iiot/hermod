package yggdrasil.hermod.compiler.conductor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import yggdrasil.hermod.compiler.IrNode
import yggdrasil.hermod.compiler.IrSource
import yggdrasil.hermod.compiler.WorkflowIR
import yggdrasil.hermod.core.BlockContract
import yggdrasil.hermod.core.CompensationKind
import yggdrasil.hermod.core.Reversibility

/**
 * Engine backend #2: lowers the engine-neutral [WorkflowIR] to a Conductor workflow-definition JSON.
 * Pure data (Jackson only) — no Conductor/Temporal runtime dependency, so it lives in :compiler.
 * Proves engine lock-in is relaxed: same IR -> a second engine's native format. Server NOT required;
 * [validate] hard-checks the output (round-trip into the vendored typed model + structural fidelity).
 */
object ConductorBackend {
    private val mapper = ObjectMapper().registerKotlinModule()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)

    fun emit(ir: WorkflowIR): String {
        val tasks = buildLayeredTasks(ir.nodes)
        val compensable = ir.nodes.any(::isCompensable)
        val wf = ConductorWorkflowDef(
            name = ir.name, version = 1, tasks = tasks,
            failureWorkflow = if (compensable) "${ir.name}-compensation" else null,
        )
        val json = mapper.writeValueAsString(wf)
        validate(json)
        return json
    }

    /** Longest-path level per node. ir.nodes is topo-ordered, so each upstream level is known first. */
    private fun levelize(nodes: List<IrNode>): Map<String, Int> {
        val level = HashMap<String, Int>(nodes.size)
        for (n in nodes) {
            val ups = n.inputs.mapNotNull { (it.source as? IrSource.NodeOutput)?.nodeId }
            level[n.nodeId] = if (ups.isEmpty()) 0 else 1 + ups.maxOf { level.getValue(it) }
        }
        return level
    }

    /** Group by level (declaration order preserved within a level); >1 node -> FORK_JOIN + JOIN barrier. */
    private fun buildLayeredTasks(nodes: List<IrNode>): List<ConductorTask> {
        val level = levelize(nodes)
        val byLevel = LinkedHashMap<Int, MutableList<IrNode>>()
        for (n in nodes) byLevel.getOrPut(level.getValue(n.nodeId)) { mutableListOf() }.add(n)
        val out = ArrayList<ConductorTask>()
        for (lvl in byLevel.keys.sorted()) {
            val group = byLevel.getValue(lvl)
            if (group.size == 1) {
                out += taskFor(group[0])
            } else {
                out += ConductorTask(
                    name = "fork", taskReferenceName = "fork_L$lvl", type = "FORK_JOIN",
                    inputParameters = emptyMap(),
                    forkTasks = group.map { listOf(taskFor(it)) },
                )
                out += ConductorTask(
                    name = "join", taskReferenceName = "join_L$lvl", type = "JOIN",
                    inputParameters = emptyMap(),
                    joinOn = group.map { it.nodeId },
                )
            }
        }
        return out
    }

    /** Single source of truth: a block becomes a Conductor WAIT task (no worker) when it gates a human
     *  or is IRREVERSIBLE. Shared by [taskFor] (task type) and [emitTaskDefs] (24h timeout) so they never drift. */
    private fun needsWait(c: BlockContract) = c.human.requireApprovalBefore ||
        c.compensation.reversibility == Reversibility.IRREVERSIBLE

    private fun taskFor(n: IrNode): ConductorTask {
        val needsWait = needsWait(n.contract)
        val wired = n.inputs.associate { w ->
            w.inputName to when (val s = w.source) {
                is IrSource.NodeOutput -> "\${${s.nodeId}.output.${s.outputName}}"
                IrSource.WorkflowInput -> "\${workflow.input.${w.inputName}}"
            }
        }
        val collisions = wired.keys intersect n.params.keys
        require(collisions.isEmpty()) {
            "node '${n.nodeId}' (${n.contract.id}): operator params $collisions shadow input-wire names — rename the param(s)"
        }
        val outputName = n.contract.outputs.firstOrNull()?.name ?: ""
        return ConductorTask(
            name = n.contract.id,
            taskReferenceName = n.nodeId,
            type = if (needsWait) "WAIT" else "SIMPLE",
            // `_failAtBlockId` forwards the workflow's start-input fault-injection key into every task so a
            // test can fail a specific block mid-flight (ForwardWorker reads it). Harmless in production: when
            // the start input omits the key, Conductor resolves the reference to null and the worker no-ops it.
            inputParameters = wired + n.params + mapOf(
                "_pinnedVersion" to n.contract.version,
                "_outputName" to outputName,
                "_nodeId" to n.nodeId,
                "_failAtBlockId" to "\${workflow.input._failAtBlockId}",
                "_slowMs" to "\${workflow.input._slowMs}",
                "_slowAtBlockId" to "\${workflow.input._slowAtBlockId}",
            ),
        )
    }

    private fun isCompensable(n: IrNode) =
        n.contract.compensation.reversibility != Reversibility.IRREVERSIBLE &&
            n.contract.compensation.kind != CompensationKind.NONE

    fun emitCompensation(ir: WorkflowIR): String {
        val comp = ir.nodes.filter(::isCompensable).reversed().mapIndexed { i, n ->
            ConductorTask(
                name = "compensate",
                taskReferenceName = "c$i",
                type = "SIMPLE",
                inputParameters = mapOf(
                    "_blockId" to n.contract.id,
                    "_nodeId" to n.nodeId,
                    "_pinnedVersion" to n.contract.version,
                    "_failedWorkflowId" to "\${workflow.input.workflowId}",
                ),
            )
        }
        val wf = ConductorWorkflowDef(name = "${ir.name}-compensation", version = 1, tasks = comp, failureWorkflow = null)
        return mapper.writeValueAsString(wf)
    }

    fun emitTaskDefs(ir: WorkflowIR): String {
        // WAIT-type tasks (human gate / IRREVERSIBLE) do not have a worker — their Conductor timeout
        // is the window for a human to approve or reject. Use 86400s (24h) so the gate is not
        // falsely timed-out during tests or low-traffic periods. The block's own timeoutMs is a
        // worker-processing SLA and must NOT be applied to WAIT tasks. (needsWait shared with taskFor.)
        // floor 2s so responseTimeoutSeconds (= timeoutSeconds-1) stays strictly in (0, timeoutSeconds)
        fun timeoutSec(c: BlockContract): Long = if (needsWait(c)) 86400L else (c.timeoutMs / 1000).coerceAtLeast(2)

        // Group by blockId (Conductor task type is version-agnostic). v0.2d I-3: with a real DAG the same
        // blockId can appear on multiple nodes; FAIL LOUD if they disagree on the taskdef-shaping dims
        // (retryCount, timeoutSeconds, needsWait) instead of silently last-wins picking one.
        val forward = ir.nodes.groupBy { it.contract.id }.map { (id, nodes) ->
            val dims = nodes.map { Triple(it.contract.retry.maxAttempts, timeoutSec(it.contract), needsWait(it.contract)) }.toSet()
            require(dims.size == 1) {
                "taskdef conflict for blockId '$id': nodes ${nodes.map { it.nodeId }} disagree on (retryCount,timeoutSeconds,needsWait)=$dims"
            }
            val c = nodes.first().contract
            ConductorTaskDef(name = id, retryCount = c.retry.maxAttempts, timeoutSeconds = timeoutSec(c))
        }
        val compensate = ConductorTaskDef(name = "compensate", retryCount = 0, timeoutSeconds = 30)
        val all = (forward + compensate).sortedBy { it.name }   // deterministic
        return mapper.writeValueAsString(all)
    }

    /** A deterministic bundle the deployer registers: main workflow, compensation workflow, taskdefs. */
    data class ConductorBundle(val workflow: String, val compensation: String, val taskDefs: String)

    fun emitBundle(ir: WorkflowIR): ConductorBundle =
        ConductorBundle(emit(ir), emitCompensation(ir), emitTaskDefs(ir))

    fun validateCompensation(ir: WorkflowIR, compJson: String) {
        val cwf: ConductorWorkflowDef = mapper.readValue(compJson)
        val expected = ir.nodes.filter(::isCompensable).reversed().map { it.contract.id }
        require(cwf.tasks.map { it.inputParameters["_blockId"] } == expected) {
            "compensation tasks not in strict reverse of compensable forward steps"
        }
        require(cwf.tasks.all {
            it.inputParameters.containsKey("_pinnedVersion") &&
                it.inputParameters.containsKey("_blockId") &&
                it.inputParameters.containsKey("_nodeId") &&
                it.inputParameters.containsKey("_failedWorkflowId")
        }) {
            "compensate task missing _blockId/_nodeId/_pinnedVersion/_failedWorkflowId"
        }
    }

    fun validate(json: String) {
        val cwf: ConductorWorkflowDef = mapper.readValue(json)
        require(cwf.tasks.isNotEmpty()) { "conductor workflow has no tasks" }
        val flat = flatten(cwf.tasks)
        require(flat.map { it.taskReferenceName }.toSet().size == flat.size) {
            "duplicate taskReferenceName (refs must be unique across fork branches)"
        }
        require(flat.filter { it.type == "SIMPLE" || it.type == "WAIT" }
            .all { it.inputParameters.containsKey("_pinnedVersion") }) {
            "conductor SIMPLE/WAIT task missing _pinnedVersion (version pin not preserved)"
        }
    }

    /** Depth-first flatten: every task plus the tasks nested in its fork branches. */
    private fun flatten(tasks: List<ConductorTask>): List<ConductorTask> =
        tasks.flatMap { t -> listOf(t) + flatten(t.forkTasks?.flatten() ?: emptyList()) }
}
