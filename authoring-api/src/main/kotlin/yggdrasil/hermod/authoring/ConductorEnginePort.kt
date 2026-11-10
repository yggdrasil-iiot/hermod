package yggdrasil.hermod.authoring

import com.netflix.conductor.client.http.ConductorClient
import yggdrasil.hermod.compiler.WorkflowCompiler
import yggdrasil.hermod.compiler.conductor.ConductorBackend
import yggdrasil.hermod.conductor.CompLedger
import yggdrasil.hermod.conductor.ConductorDeployer
import yggdrasil.hermod.conductor.ConductorNodeStates
import yggdrasil.hermod.conductor.ConductorStarter
import yggdrasil.hermod.registry.Registry
import yggdrasil.hermod.registry.WorkflowStore
import yggdrasil.hermod.runtime.CompensationEvent
import yggdrasil.hermod.runtime.EnginePort
import yggdrasil.hermod.runtime.WorkflowInput
import yggdrasil.hermod.runtime.WorkflowOutput

/**
 * Conductor-backed EnginePort. Lives in the :authoring-api edge so :conductor-runtime stays 0-temporal.
 * start/queryStatus/approve/reject/awaitResult, node-state lighting (v0.6b), compensation timeline (v0.6c),
 * and retry/abort (v0.6d) are all real implementations.
 *
 * Limitation: the Conductor workflow name is the bare `def.name` (not name@version), so a Conductor def isn't
 * hermod-version-keyed like Temporal's `name@version`. Fine for the single-version slice; multi-version is future.
 */
class ConductorEnginePort(
    private val client: ConductorClient,
    private val store: WorkflowStore,
    private val registry: Registry,
    private val ledger: CompLedger,
) : EnginePort {
    private val starter = ConductorStarter(client)
    private val deployer = ConductorDeployer(client)
    private val deployed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()  // per-process dedupe

    override fun start(workflowId: String, input: WorkflowInput): String {
        val (name, version) = parse(input.workflowName)        // "name@version"
        ensureDeployed(name, version)
        // Mirror ConductorCtl's start input: rows seed + workflowId placeholder + fault hooks.
        val ext = buildMap<String, Any?> {
            put("rows", emptyList<Any?>()); put("workflowId", "")
            input.failAtBlockId?.let { put("_failAtBlockId", it) }
            if (input.slowMs > 0) put("_slowMs", input.slowMs.toString())
        }
        return starter.start(name, ext)   // Conductor server generates + returns the workflowId
    }

    private fun ensureDeployed(name: String, version: String) {
        val key = "$name@$version"
        if (!deployed.add(key)) return                          // already deployed this process
        val def = store.get(name, version) ?: error("workflow $key not found")
        val ir = WorkflowCompiler.compile(def, registry)
        deployer.deploy(ConductorBackend.emitBundle(ir))        // idempotent overwrite-by-name on the server
    }

    override fun queryStatus(workflowId: String): String = starter.getWorkflow(workflowId).status.name

    override fun signalApproval(workflowId: String) { starter.approve(workflowId) }
    override fun signalReject(workflowId: String, reason: String) { starter.reject(workflowId, reason) }

    override fun awaitResult(workflowId: String): WorkflowOutput {
        val wf = starter.awaitTerminal(workflowId, timeoutMs = 120_000)
        return WorkflowOutput(completed = wf.status.name == "COMPLETED", compensatedInReverseOrder = emptyList())
    }

    // --- v0.6b/d: forward node states + COMPENSATED overlay, but the overlay applies ONLY when the main run is
    //     FAILED/TERMINATED (post-retry a COMPLETED run must not show the prior failure's stale COMPENSATED).
    override fun queryNodeStates(workflowId: String): Map<String, String> {
        val main = starter.getWorkflow(workflowId)
        val failed = main.status.name == "FAILED" || main.status.name == "TERMINATED"
        val comp = if (failed) {
            val compName = "${main.workflowName}-compensation"
            starter.findCompensationWorkflowId(workflowId, compName, timeoutMs = 0)
                ?.let { starter.getWorkflow(it) }
        } else null
        return ConductorNodeStates.nodeStates(main, comp)
    }

    override fun queryCompensationTimeline(workflowId: String): List<CompensationEvent> =
        ledger.readTimeline(workflowId).map {
            CompensationEvent(it.index, it.nodeId, it.blockId, it.version, it.outcome, it.atMillis)
        }
    override fun signalRetry(workflowId: String, nodeId: String) {
        // Conductor retry is whole-run (no PARKED node); nodeId is intentionally ignored. See design §2.1.
        // The re-run reuses the SAME workflowId, so drop the failed attempt's compensation ledger first —
        // else a clean re-run still reads the stale COMPENSATED rows (live timeline AND the durable archive).
        ledger.clearForWorkflow(workflowId)
        starter.rerunFromStart(workflowId)
    }
    override fun signalAbort(workflowId: String) {
        starter.abortWithCompensation(workflowId, reason = "operator abort")
    }

    private fun parse(nameAtVersion: String): Pair<String, String> {
        val i = nameAtVersion.lastIndexOf('@'); return nameAtVersion.substring(0, i) to nameAtVersion.substring(i + 1)
    }
}
