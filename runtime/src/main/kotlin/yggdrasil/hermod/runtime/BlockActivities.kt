package yggdrasil.hermod.runtime

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.failure.ApplicationFailure
import yggdrasil.hermod.dispatch.HandlerRegistry
import yggdrasil.hermod.registry.Registry
import yggdrasil.hermod.sdk.*

@ActivityInterface
interface BlockActivities {
    @ActivityMethod fun forward(blockId: String, version: String, input: BlockInput): BlockOutput
    @ActivityMethod fun compensate(blockId: String, version: String, boundState: Map<String, String>, runId: String): CompensationAction
}

/** Dispatches forward/compensate to block handlers via version-keyed [HandlerRegistry] ((id,version) -> Block). */
class BlockActivitiesImpl(registry: Registry = RuntimeAssembly.registry) : BlockActivities {
    private val handlers = HandlerRegistry(registry)
    // Worker identity for P5 distribution evidence (set per process via env).
    private val workerName = System.getenv("HERMOD_WORKER_NAME") ?: "worker"
    // v0.4b: test-only fault injection, OFF unless HERMOD_FAULT_INJECT is set (the run-intervention gate sets it).
    private val faultInject = System.getenv("HERMOD_FAULT_INJECT") != null

    override fun forward(blockId: String, version: String, input: BlockInput): BlockOutput =
        try {
            if (faultInject && isFaultArmed(blockId, "forward")) {
                throw PermanentBlockFailure("injected fault for $blockId")  // mapped to non-retryable below
            }
            println("[$workerName] forward $blockId") // P5 marker: grep "forward " per-worker log
            handlers.get(blockId, version).forward(input)
        } catch (e: PermanentBlockFailure) {
            throw ApplicationFailure.newNonRetryableFailure(e.message ?: "permanent", "PermanentBlockFailure")
        }

    /** test-only: is a fault armed for this (block_id, phase)? Own short-lived JDBC connection over the HERMOD_DB_* env. */
    private fun isFaultArmed(blockId: String, phase: String): Boolean = try {
        java.sql.DriverManager.getConnection(
            System.getenv("HERMOD_DB_URL"), System.getenv("HERMOD_DB_USER"), System.getenv("HERMOD_DB_PASS")
        ).use { c ->
            c.prepareStatement("SELECT 1 FROM fault_inject WHERE block_id = ? AND phase = ?").use { ps ->
                ps.setString(1, blockId); ps.setString(2, phase); ps.executeQuery().use { it.next() }
            }
        }
    } catch (e: Exception) { false }  // missing table / unset env -> not armed (inert)

    override fun compensate(blockId: String, version: String, boundState: Map<String, String>, runId: String): CompensationAction =
        try {
            if (faultInject && isFaultArmed(blockId, "compensate")) {
                throw PermanentBlockFailure("injected compensate fault for $blockId")  // mapped to non-retryable below
            }
            println("[$workerName] compensate $blockId")
            handlers.get(blockId, version).compensate(boundState, CompensationContext(runId = runId))
        } catch (e: PermanentBlockFailure) {
            throw ApplicationFailure.newNonRetryableFailure(e.message ?: "permanent", "PermanentBlockFailure")
        }
}
