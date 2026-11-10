package yggdrasil.hermod.app

import io.temporal.client.WorkflowClient
import io.temporal.serviceclient.WorkflowServiceStubs
import yggdrasil.hermod.runtime.DataConverterSupport
import yggdrasil.hermod.runtime.TemporalEnginePort
import yggdrasil.hermod.runtime.WorkflowInput

/**
 * CLI driver for the demo workflow, going through the EnginePort seam (§7.2) rather than raw Temporal
 * stubs, so the same client boundary the rest of the platform uses is exercised end-to-end.
 *
 * Usage:
 *   start  <workflowId> [failAtBlockId|-] [slowMs] [workflowName] [slowAtBlockId|-]  -> start a run
 *                                                      (async). "-" = no injected failure / no slow target.
 *                                                      workflowName selects which bound contract list to
 *                                                      drive (default "demo"); slowAtBlockId restricts
 *                                                      slowMs to one blockId (null = workflow-wide).
 *   approve <workflowId>                            -> send the human-gate approval signal
 *   reject  <workflowId> <reason>                   -> decline at the human gate (-> compensation)
 *   result  <workflowId>                            -> block for result, prints completed=…
 *   status  <workflowId>                            -> print the execution status name
 */
fun main(args: Array<String>) {
    val service = WorkflowServiceStubs.newLocalServiceStubs()
    val client = WorkflowClient.newInstance(service, DataConverterSupport.clientOptions())
    val engine = TemporalEnginePort(client, TASK_QUEUE)

    val cmd = args.getOrNull(0) ?: error("cmd required (start|approve|reject|result|status)")
    val id = args.getOrNull(1) ?: error("workflowId required")
    when (cmd) {
        "start" -> {
            val failAt = args.getOrNull(2)?.takeIf { it != "-" && it.isNotBlank() }
            val slowMs = args.getOrNull(3)?.toLong() ?: 0
            // Optional 4th arg selects the bound workflow (keyed BoundWorkflow). Default keeps the v0.1
            // crash-recovery script (`start <id> - <slowMs>`) driving the builtin "demo" unchanged.
            val workflowName = args.getOrNull(4)?.takeIf { it.isNotBlank() } ?: "demo"
            // Optional 5th arg targets slowMs at one blockId (v0.3b concurrency gate). "-"/absent = workflow-wide.
            val slowAt = args.getOrNull(5)?.takeIf { it != "-" && it.isNotBlank() }
            engine.start(id, WorkflowInput(failAtBlockId = failAt, slowMs = slowMs, workflowName = workflowName, slowAtBlockId = slowAt))
            println("started $id (workflow=$workflowName)")
        }
        "approve" -> { engine.signalApproval(id); println("approved $id") }
        "reject" -> {
            val reason = args.getOrNull(2) ?: error("reject requires <reason>")
            engine.signalReject(id, reason)
            println("rejected $id: $reason")
        }
        "result" -> {
            val out = engine.awaitResult(id)
            println("completed=${out.completed} compensated=${out.compensatedInReverseOrder}")
        }
        "status" -> println("status=${engine.queryStatus(id)}")
        else -> error("unknown cmd: $cmd")
    }
}
