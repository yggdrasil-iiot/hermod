package yggdrasil.hermod.authoring

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import yggdrasil.hermod.opcua.CanonicalSetpoints
import yggdrasil.hermod.registry.RunStore
import yggdrasil.hermod.registry.WorkflowStore
import yggdrasil.hermod.runtime.WorkflowInput
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * `POST /api/reconciliations` — the R2 inbound-trigger early slice. Accepts a SIGNAL (which logical
 * nodes drifted), resolves the desired value from hermod's own Git canonical, fail-closed-validates,
 * stages source_rows, and starts the existing human-gated ot-recipe-stage-activate saga on Temporal.
 *
 * Pre-flight node validation is intentionally redundant with what opcua.write enforces inside the
 * saga — it exists so an ungoverned signal is rejected with a cheap 4xx BEFORE any run starts.
 */
@RestController
@RequestMapping("/api")
class ReconciliationController(
    private val router: EngineRouter,
    private val runStore: RunStore,
    private val workflowStore: WorkflowStore,
    private val seeder: SourceRowSeeder,
    private val canonical: CanonicalSetpoints?,   // null ⇔ no published canonical (see RegistryConfig)
    private val provenance: ProvenanceService,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(ReconciliationController::class.java)
    private val mapper = jacksonObjectMapper()
    private val SAGA = "ot-recipe-stage-activate"
    private val SAGA_VERSION = "1.0.0"

    @PostMapping("/reconciliations")
    fun reconcile(@RequestBody req: ReconciliationRequest): ResponseEntity<Any> {
        if (req.nodes.isEmpty()) return ResponseEntity.badRequest().body(mapOf("error" to "no nodes signalled"))

        // No published canonical → nothing to resolve desired values against: fail closed before any side effect.
        val canonical = canonical
            ?: return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "canonical-unresolvable"))

        val desired = LinkedHashMap<String, String>()
        for (key in req.nodes) {
            val sp = canonical.byKey(key)
                ?: return ResponseEntity.badRequest().body(mapOf("error" to "unknown or ungoverned node: $key"))
            desired[key] = sp.desired.toString()
        }

        if (workflowStore.get(SAGA, SAGA_VERSION) == null)
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to "$SAGA@$SAGA_VERSION not deployed"))

        val prov = when (val r = provenance.resolve()) {
            is ProvenanceService.Result.Unresolvable ->
                return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "canonical-unresolvable"))
            is ProvenanceService.Result.Tampered ->
                return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "canonical-tampered"))
            is ProvenanceService.Result.Ok -> r
        }

        seeder.seed(desired)
        val reconciliationId = req.reconciliationId ?: UUID.randomUUID().toString()
        val runId = router.port("temporal").start(reconciliationId, WorkflowInput(
            failAtBlockId = null, slowMs = 0,
            workflowName = "$SAGA@$SAGA_VERSION", slowAtBlockId = null, interactive = false,
        ))
        try {
            runStore.record(runId, SAGA, SAGA_VERSION, mapper.writeValueAsString(req), "temporal")
        } catch (e: Exception) {
            log.warn("reconciliation run {} started but not recorded: {}", runId, e.toString())
        }
        try { provenance.record(runId, prov.defRef, prov.contentSha256) }
        catch (e: Exception) { log.warn("provenance stamp for run {} failed: {}", runId, e.toString()) }
        return ResponseEntity.ok(ReconciliationResponse(runId, reconciliationId, req.source, req.proposalRef, req.nodes))
    }
}
