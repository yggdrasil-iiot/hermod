package yggdrasil.hermod.conductor

import com.netflix.conductor.client.worker.Worker
import com.netflix.conductor.common.metadata.tasks.Task
import com.netflix.conductor.common.metadata.tasks.TaskResult
import yggdrasil.hermod.core.CompensationKind
import yggdrasil.hermod.core.Reversibility
import yggdrasil.hermod.dispatch.HandlerRegistry
import yggdrasil.hermod.registry.Registry
import yggdrasil.hermod.registry.Resolution
import yggdrasil.hermod.sdk.Block
import yggdrasil.hermod.sdk.BlockInput
import yggdrasil.hermod.sdk.PermanentBlockFailure
import yggdrasil.hermod.sdk.Record

/**
 * `TaskResult(task)` switches on `task.getStatus().ordinal()` and NPEs if the status is null.
 * Real Conductor-polled tasks always carry a status; test-built [Task]s may not. Shared by both workers.
 */
internal fun Task.ensureStatusForResult() {
    if (status == null) status = Task.Status.IN_PROGRESS
}

/**
 * Conductor task worker for one forward block type.
 * [blockId] = task def name (Conductor task type).
 * [resolveHandler] resolves the (id, version) -> Block; defaults to the real version-keyed [HandlerRegistry].
 * Tests inject a stub resolver (HandlerRegistry resolves builtins to the fixed [yggdrasil.hermod.dispatch.BuiltinBlocks]
 * set, so a stub Block cannot be injected through the registry — the resolver function is the seam).
 */
class ForwardWorker(
    private val blockId: String,
    private val registry: Registry,
    private val ledger: CompLedger,
    private val resolveHandler: (String, String) -> Block = HandlerRegistry(registry)::get,
) : Worker {

    override fun getTaskDefName(): String = blockId

    override fun execute(task: Task): TaskResult {
        val inputData = task.inputData
        val version = inputData["_pinnedVersion"] as? String ?: ""
        val outputName = inputData["_outputName"] as? String ?: ""
        // emit always injects _nodeId (= the IR nodeId), so the referenceTaskName arm is a test-only
        // convenience for hand-built Tasks; production never relies on the conductor-client Task API.
        val nodeId = inputData["_nodeId"] as? String ?: task.referenceTaskName ?: ""
        // branch-targeted slow injection (test-only): honor slowMs only when unset or this block is targeted.
        val slowAt = inputData["_slowAtBlockId"] as? String
        val slowMs: Long = (inputData["_slowMs"] as? String)?.toIntOrNull()
            ?.takeIf { slowAt == null || slowAt == blockId }?.toLong() ?: 0L

        // Multi-input (join) nodes carry one inputData entry per contract input name (e.g. left/right);
        // single-input nodes carry their rows under the single "rows" key (back-compat invariant).
        val contract = when (val r = registry.resolve(blockId, version)) {
            is Resolution.Builtin -> r.contract
            is Resolution.Plugin  -> r.contract
            null                  -> null
        }
        val inputNames = contract?.inputs?.map { it.name } ?: emptyList()
        val isMulti = inputNames.size > 1

        val rows: List<Record> = if (isMulti) emptyList() else asRows(inputData["rows"])
        val namedInputs: Map<String, List<Record>> =
            if (isMulti) inputNames.associateWith { asRows(inputData[it]) } else emptyMap()
        val failAtBlockId = inputData["_failAtBlockId"] as? String

        val block: Block = resolveHandler(blockId, version)
        task.ensureStatusForResult()
        val result = TaskResult(task)

        return try {
            println("[conductor] forward $blockId")
            val out = block.forward(BlockInput(rows = rows, namedInputs = namedInputs, failAtBlockId = failAtBlockId, slowMs = slowMs, runId = task.workflowInstanceId))

            // Append to ledger if compensable (before reporting completion for crash safety)
            if (isCompensable(blockId, version)) {
                ledger.append(task.workflowInstanceId, nodeId, blockId, version, out.boundState)
            }

            // Publish output under the wired output name
            if (outputName.isNotEmpty()) {
                result.addOutputData(outputName, out.rows)
            }
            result.addOutputData("boundState", out.boundState)
            result.status = TaskResult.Status.COMPLETED
            result
        } catch (e: PermanentBlockFailure) {
            result.status = TaskResult.Status.FAILED_WITH_TERMINAL_ERROR
            result.reasonForIncompletion = e.message ?: "PermanentBlockFailure"
            result
        }
    }

    private fun isCompensable(blockId: String, version: String): Boolean {
        val contract = when (val r = registry.resolve(blockId, version)) {
            is Resolution.Builtin -> r.contract
            is Resolution.Plugin  -> r.contract
            null                  -> return false
        }
        return contract.compensation.reversibility != Reversibility.IRREVERSIBLE &&
            contract.compensation.kind != CompensationKind.NONE
    }

    companion object {
        /** Coerce the raw JSON-decoded rows (List<Map<String,Any?>>) to List<Record> (Map<String,String?>). */
        @Suppress("UNCHECKED_CAST")
        fun asRows(raw: Any?): List<Record> = when (raw) {
            null -> emptyList()
            is List<*> -> raw.filterIsInstance<Map<*, *>>().map { m ->
                m.entries.associate { (k, v) -> k.toString() to v?.toString() }
            }
            else -> emptyList()
        }
    }
}
