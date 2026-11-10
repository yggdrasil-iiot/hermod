package yggdrasil.hermod.conductor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.netflix.conductor.client.http.ConductorClient
import yggdrasil.hermod.blocks.Db
import yggdrasil.hermod.compiler.WorkflowCompiler
import yggdrasil.hermod.compiler.conductor.ConductorBackend
import yggdrasil.hermod.core.WorkflowDef
import yggdrasil.hermod.core.WorkflowStep
import yggdrasil.hermod.dispatch.DispatchAssembly
import yggdrasil.hermod.registry.BlockIndex
import yggdrasil.hermod.registry.BlockStore
import java.io.File
import kotlin.system.exitProcess

/**
 * CLI driver for Conductor execution (gate entrypoint).
 *
 * Commands:
 *   deploy  <wf>           compile workflow yaml -> emit bundle -> register defs (main + compensation + taskdefs)
 *   start   <wf> [k=v ...] start the (already-deployed) workflow, print `workflowId=<id>`.
 *                          Extra key=value pairs are merged into the start input.
 *                          `failAt=<blockId>` is shorthand for `_failAtBlockId=<blockId>` (fault injection).
 *   result  <workflowId>   print `status=<STATUS>`, and on COMPLETED also `rows=<...>`; `compStatus=<...>` when a
 *                          `<name>-compensation` run exists
 *   approve <workflowId>   complete the blocking WAIT task
 *   reject  <workflowId>   fail the blocking WAIT task (-> compensation)
 *   retry   <workflowId>   re-run the whole workflow from the start, test-hooks stripped (v0.6d, whole-run retry)
 *   abort   <workflowId>   terminate the run + dispatch its compensation failureWorkflow (v0.6d)
 *
 * Workflow yaml is loaded from `app/src/main/resources/workflows/<wf>.yaml` (repo-root relative) unless `<wf>`
 * is a path to an existing file. The registry is built via the neutral [DispatchAssembly] (no Temporal SDK).
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) ctlUsage()
    val serverUrl = System.getenv("CONDUCTOR_SERVER_URL") ?: DEFAULT_CONDUCTOR_URL
    val client = ConductorClient.builder().basePath(serverUrl).build()

    when (val cmd = args[0]) {
        "deploy" -> {
            val wf = args.getOrNull(1) ?: ctlUsage()
            val bundle = compileBundle(wf)
            ConductorDeployer(client).deploy(bundle)
            println("[conductor] deployed $wf (main + compensation + taskdefs)")
        }
        "start" -> {
            val wf = args.getOrNull(1) ?: ctlUsage()
            val def = loadWorkflow(wf)
            // Optional extra args: key=value pairs, e.g. `failAt=notify.email` injects `_failAtBlockId` into start input.
            val extras: Map<String, Any?> = args.drop(2).associate { arg ->
                val eq = arg.indexOf('=')
                if (eq < 0) arg to true
                else {
                    val k = arg.substring(0, eq)
                    val v = arg.substring(eq + 1)
                    // Map well-known shorthand keys to their wire names.
                    when (k) {
                        "failAt" -> "_failAtBlockId" to v
                        "slow"   -> "_slowMs" to v
                        "slowAt" -> "_slowAtBlockId" to v
                        else     -> k to v
                    }
                }
            }
            val input = mapOf("rows" to emptyList<Any?>(), "workflowId" to "") + extras
            val id = ConductorStarter(client).start(def.name, input)
            println("workflowId=$id")
        }
        "result" -> {
            val id = args.getOrNull(1) ?: ctlUsage()
            val starter = ConductorStarter(client)
            val wf = starter.getWorkflow(id)
            println("status=${wf.status}")
            if (wf.status.name == "COMPLETED") {
                println("rows=${wf.output["rows"] ?: wf.output}")
            }
            // When this run failed, a `<name>-compensation` failureWorkflow may have been dispatched. Surface
            // its status for the gate (R-3: located by the failed workflow id Conductor injects as input.workflowId).
            if (wf.status.name == "FAILED" || wf.status.name == "TERMINATED") {
                val compName = "${wf.workflowName}-compensation"
                val comp = starter.awaitCompensationTerminal(id, compName, timeoutMs = 30_000)
                println("compStatus=${comp?.status ?: "NONE"}")
            }
        }
        "approve" -> {
            val id = args.getOrNull(1) ?: ctlUsage()
            val ok = ConductorStarter(client).approve(id)
            println("approve=$ok")
        }
        "reject" -> {
            val id = args.getOrNull(1) ?: ctlUsage()
            val reason = args.getOrNull(2) ?: "rejected"
            val ok = ConductorStarter(client).reject(id, reason)
            println("reject=$ok")
        }
        "retry" -> {
            val id = args.getOrNull(1) ?: ctlUsage()
            val newId = ConductorStarter(client).rerunFromStart(id)
            println("retried workflowId=$newId")
        }
        "abort" -> {
            val id = args.getOrNull(1) ?: ctlUsage()
            val reason = args.getOrNull(2) ?: "operator abort"
            ConductorStarter(client).abortWithCompensation(id, reason)
            println("aborted=$id reason=$reason")
        }
        else -> { System.err.println("unknown command: $cmd"); ctlUsage() }
    }
}

private fun compileBundle(wf: String): ConductorBackend.ConductorBundle {
    val def = loadWorkflow(wf)
    val registry = DispatchAssembly.buildRegistry(BlockIndex { Db.connect() }, BlockStore())
    val ir = WorkflowCompiler.compile(def, registry)
    return ConductorBackend.emitBundle(ir)
}

private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
private data class DefDto(val name: String, val steps: List<StepDto>)
private data class StepDto(
    val block: String,
    val version: String,
    val id: String? = null,
    val params: Map<String, String> = emptyMap(),
    val wiring: Map<String, String> = emptyMap(),
)

/** Replicates :runtime WorkflowDefLoader (conductor-runtime must not depend on :runtime/Temporal). */
private fun loadWorkflow(wf: String): WorkflowDef {
    val direct = File(wf)
    val file = if (direct.exists()) direct else File("app/src/main/resources/workflows/$wf.yaml")
    if (!file.exists()) {
        System.err.println("ERROR: workflow not found: $wf (looked at $file)")
        exitProcess(1)
    }
    val d: DefDto = yamlMapper.readValue(file.readText())
    return WorkflowDef(d.name, d.steps.map { WorkflowStep(it.block, it.version, it.id, it.params, it.wiring) })
}

private fun ctlUsage(): Nothing {
    System.err.println("usage: ConductorCtl <deploy|start|result|approve|reject|retry|abort> <wf|workflowId> [key=value...] [reason]")
    System.err.println("  start extra args: failAt=<blockId> slow=<ms> slowAt=<blockId>  =>  inject _failAtBlockId/_slowMs/_slowAtBlockId into workflow input")
    exitProcess(2)
}
