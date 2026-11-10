package yggdrasil.hermod.authoring

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import yggdrasil.hermod.registry.RunStore
import yggdrasil.hermod.registry.WorkflowStore
import yggdrasil.hermod.runtime.WorkflowInput
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class RunRequest(
    val runId: String? = null,
    val failAtBlockId: String? = null,
    val slowMs: Long = 0,
    val interactive: Boolean = false,
    val engine: String = "temporal",   // v0.6a: per-run engine selection ("temporal" | "conductor")
)

@RestController
@RequestMapping("/api")
class RunController(
    private val store: WorkflowStore,
    private val runStore: RunStore,
    private val router: EngineRouter,    // routes each call to the per-run engine's EnginePort (lazy per engine)
    private val reconciler: RunReconciler,   // durable archive: lazy reconcile on first terminal observation + isFrozen
) {
    private val log = org.slf4j.LoggerFactory.getLogger(RunController::class.java)
    private val mapper = jacksonObjectMapper()   // RunController had no Jackson mapper; needed to serialize params_json

    @PostMapping("/workflows/{name}/{version}/run")
    fun run(@PathVariable name: String, @PathVariable version: String,
            @RequestBody(required = false) req: RunRequest?): ResponseEntity<Any> {
        if (store.get(name, version) == null) return ResponseEntity.notFound().build()
        val runId = req?.runId ?: UUID.randomUUID().toString()      // controller-side (request), not in a workflow
        val engine = req?.engine ?: "temporal"
        // start returns the engine-effective workflowId (Conductor generates its own); record THAT id so queries resolve.
        val id = router.port(engine).start(runId, WorkflowInput(
            failAtBlockId = req?.failAtBlockId, slowMs = req?.slowMs ?: 0,
            workflowName = "$name@$version", slowAtBlockId = null,
            interactive = req?.interactive ?: false,
        ))
        try {
            runStore.record(id, name, version, mapper.writeValueAsString(req ?: RunRequest()), engine)
        } catch (e: Exception) {
            log.warn("run {} started but was not recorded in run_index: {}", id, e.toString())
        }
        return ResponseEntity.ok(mapOf("runId" to id))
    }

    @GetMapping("/runs")
    fun list(): List<RunSummary> = runStore.list().map { r ->
        val status = statusOrUnknown(r.runId)
        val awaiting = if (!RunStatus.isTerminal(status)) awaitingApprovalOf(r.runId, r.engine) else false
        RunSummary(r.runId, r.workflowName, r.workflowVersion, r.startedAtEpochMs, status, r.engine, awaiting, r.compOutcome)
    }

    // Best-effort: a query failure => not awaiting (never an error), matching statusOrUnknown's posture.
    private fun awaitingApprovalOf(runId: String, engine: String): Boolean =
        try { RunStatus.isAwaitingApproval(router.port(engine).queryNodeStates(runId)) }
        catch (e: Exception) { false }

    private fun statusOrUnknown(runId: String): String {
        val row = runStore.get(runId)
        if (row?.finalStatus != null) {
            // Archived. final_status is write-once/immutable → always serve it RAW from the DB.
            // BUT while still within the grace window (not yet frozen), keep driving reconcile so the node/comp
            // SNAPSHOTS converge to the final live state. This matters for Conductor: its compensation lands in
            // comp_ledger AFTER the main run is terminal, so the first terminal observation captures a partial
            // timeline; the read path (a watched run is polled via GET /api/runs ~1.5s) progressively completes
            // the durable snapshot before it freezes — independent of the background @Scheduled sweep.
            if (!reconciler.isFrozen(row)) reconciler.reconcile(runId)
            return row.finalStatus!!
        }
        return try {
            val s = router.port(runStore.engineOf(runId)).queryStatus(runId)
            if (RunStatus.isTerminal(s)) reconciler.reconcile(runId)      // lazy archive on first terminal observation
            s
        } catch (e: Exception) {
            log.warn("status query failed for run {}: {}", runId, e.toString())  // best-effort; aged/down != error
            "UNKNOWN"
        }
    }

    @GetMapping("/runs/{runId}")
    fun status(@PathVariable runId: String, @RequestParam(required = false) wait: Boolean = false): Map<String, Any> {
        val port = router.port(runStore.engineOf(runId))
        return if (wait) port.awaitResult(runId).let { mapOf("completed" to it.completed, "compensatedInReverseOrder" to it.compensatedInReverseOrder) }
        else mapOf("status" to statusOrUnknown(runId))
    }

    @GetMapping("/runs/{runId}/nodes")
    fun nodeStates(@PathVariable runId: String): Map<String, String> {
        val row = runStore.get(runId)
        if (row != null && reconciler.isFrozen(row)) return runStore.readNodeStates(runId)   // archived snapshot
        return try { router.port(runStore.engineOf(runId)).queryNodeStates(runId) } catch (e: Exception) {
            log.warn("node-state query failed for run {}: {}", runId, e.toString())  // diagnose real faults; empty != "nothing running"
            // fall back to any persisted snapshot before giving up empty
            runStore.readNodeStates(runId).ifEmpty { emptyMap() }
        }
    }

    @GetMapping("/runs/{runId}/compensation")
    fun compensation(@PathVariable runId: String): List<yggdrasil.hermod.runtime.CompensationEvent> {
        val row = runStore.get(runId)
        if (row != null && reconciler.isFrozen(row)) return runStore.readCompEvents(runId).map { it.toEvent() }
        return try { router.port(runStore.engineOf(runId)).queryCompensationTimeline(runId) } catch (e: Exception) {
            log.warn("compensation query failed for run {}: {}", runId, e.toString())  // best-effort; empty != "no compensation"
            runStore.readCompEvents(runId).map { it.toEvent() }   // fall back to snapshot if present
        }
    }

    @PostMapping("/runs/{runId}/approve") fun approve(@PathVariable runId: String) { router.port(runStore.engineOf(runId)).signalApproval(runId) }
    @PostMapping("/runs/{runId}/reject")  fun reject(@PathVariable runId: String, @RequestBody(required = false) body: Map<String, String>?) {
        router.port(runStore.engineOf(runId)).signalReject(runId, body?.get("reason") ?: "rejected")
    }
    @PostMapping("/runs/{runId}/retry") fun retry(@PathVariable runId: String,
                                                  @RequestBody(required = false) body: Map<String, String>?) {
        router.port(runStore.engineOf(runId)).signalRetry(runId, body?.get("nodeId") ?: "")
        // The run is alive again under the same runId (v0.6d Conductor whole-run retry reuses the workflowId).
        // Drop any prior terminal archive so statusOrUnknown re-tracks it live — else the write-once final_status
        // from the failed attempt masks the re-run as terminal and the gate's approve button never renders.
        // For Temporal a parked retry never went terminal (final_status is null), so this is a benign no-op.
        runStore.clearArchive(runId)
    }
    @PostMapping("/runs/{runId}/abort") fun abort(@PathVariable runId: String) { router.port(runStore.engineOf(runId)).signalAbort(runId) }
}

// edge mapping (boundary): :registry CompEventRow -> :runtime CompensationEvent
private fun yggdrasil.hermod.registry.RunStore.CompEventRow.toEvent() =
    yggdrasil.hermod.runtime.CompensationEvent(idx, nodeId, blockId, version, outcome, atMillis)
