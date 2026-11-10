package yggdrasil.hermod.conductor

import com.netflix.conductor.client.worker.Worker
import yggdrasil.hermod.core.BlockContract

/** The shared compensation task type ([CompensateWorker]); never handed to a forward poller. */
internal const val COMPENSATE_TASK = "compensate"

/**
 * The forward task types the Conductor worker polls: one per block id the registry knows, builtins and
 * published plugins alike (the emit names every forward task after its block id, version-agnostic).
 * A block whose tasks lower to WAIT (human gate / IRREVERSIBLE) never gets a SIMPLE task, so polling its
 * type is a harmless no-op. Sorted for a stable start log.
 */
internal fun forwardTaskTypes(contracts: List<BlockContract>): List<String> =
    contracts.map { it.id }.filter { it != COMPENSATE_TASK }.distinct().sorted()

/**
 * Starts one forward poller per task type, once. [sync] is called at boot and again on every registry
 * refresh, so a block published after the worker started gets a poller without a restart. Pollers are
 * never stopped (published versions are immutable and the index has no delete path).
 *
 * [start] receives only the not-yet-polled workers; a type is recorded as polled only after [start]
 * returns, so a failed start is retried on the next refresh.
 */
class BlockPollers(
    private val workerFor: (String) -> Worker,
    private val start: (List<Worker>) -> Unit,
) {
    private val polled = LinkedHashSet<String>()

    /** Starts pollers for the types in [taskTypes] not yet polled; returns those types (empty: nothing new). */
    @Synchronized
    fun sync(taskTypes: Collection<String>): List<String> {
        val fresh = taskTypes.filter { it != COMPENSATE_TASK && it !in polled }.distinct()
        if (fresh.isEmpty()) return emptyList()
        start(fresh.map(workerFor))
        polled += fresh
        return fresh
    }

    @Synchronized
    fun polled(): Set<String> = polled.toSet()
}
