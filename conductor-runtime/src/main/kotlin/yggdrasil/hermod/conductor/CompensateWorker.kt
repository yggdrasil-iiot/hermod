package yggdrasil.hermod.conductor

import com.netflix.conductor.client.worker.Worker
import com.netflix.conductor.common.metadata.tasks.Task
import com.netflix.conductor.common.metadata.tasks.TaskResult
import yggdrasil.hermod.dispatch.HandlerRegistry
import yggdrasil.hermod.registry.Registry
import yggdrasil.hermod.sdk.Block
import yggdrasil.hermod.sdk.CompensationContext

/**
 * Conductor task worker for the shared "compensate" task type.
 * Reads the ledger, calls handler.compensate(boundState, ctx), marks the row compensated.
 * Always returns COMPLETED (idempotent — if no ledger row, there's nothing to undo).
 * [resolveHandler] resolves (id, version) -> Block; defaults to the real [HandlerRegistry] (tests inject a stub).
 */
class CompensateWorker(
    private val registry: Registry,
    private val ledger: CompLedger,
    private val resolveHandler: (String, String) -> Block = HandlerRegistry(registry)::get,
) : Worker {

    override fun getTaskDefName(): String = "compensate"

    override fun execute(task: Task): TaskResult {
        val inputData = task.inputData
        val failedWorkflowId = inputData["_failedWorkflowId"] as? String ?: ""
        val nodeId = inputData["_nodeId"] as? String ?: ""
        val blockId = inputData["_blockId"] as? String ?: ""   // retained: resolves the handler
        val version = inputData["_pinnedVersion"] as? String ?: ""

        task.ensureStatusForResult()
        val result = TaskResult(task)

        println("[conductor] compensate $blockId")

        val row = ledger.readForCompensation(failedWorkflowId, nodeId)
        if (row != null) {
            val faultOn = System.getenv("HERMOD_FAULT_INJECT") != null
            try {
                if (faultOn && ledger.isCompensateFaultArmed(blockId)) error("compensate fault injected for $blockId")
                val block: Block = resolveHandler(blockId, version)
                block.compensate(row.boundState, CompensationContext(runId = failedWorkflowId))
                ledger.recordResult(failedWorkflowId, nodeId, "COMPENSATED", System.currentTimeMillis())
            } catch (e: Exception) {
                println("[conductor] compensate FAILED $blockId: ${e.message}")
                ledger.recordResult(failedWorkflowId, nodeId, "FAILED", System.currentTimeMillis())
            }
        }
        result.status = TaskResult.Status.COMPLETED   // ALWAYS — best-effort, failureWorkflow continues
        return result
    }
}
