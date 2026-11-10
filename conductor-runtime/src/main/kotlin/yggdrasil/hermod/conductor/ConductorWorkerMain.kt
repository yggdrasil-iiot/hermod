package yggdrasil.hermod.conductor

import com.netflix.conductor.client.automator.TaskRunnerConfigurer
import com.netflix.conductor.client.http.ConductorClient
import com.netflix.conductor.client.http.TaskClient
import com.netflix.conductor.client.worker.Worker
import yggdrasil.hermod.blocks.Db
import yggdrasil.hermod.dispatch.DispatchAssembly
import yggdrasil.hermod.registry.BlockIndex
import yggdrasil.hermod.registry.BlockStore

/** Default Conductor server URL (the `:18088` host port the compose service publishes). */
const val DEFAULT_CONDUCTOR_URL = "http://localhost:18088/api"

/**
 * Boots the in-process Conductor task workers (gate entrypoint).
 *
 * One [ForwardWorker] per block id the registry knows (task type = blockId) — the builtins and every published
 * plugin — plus one [CompensateWorker], all sharing one registry (built via the neutral [DispatchAssembly] — no
 * Temporal SDK) and one Postgres-backed [CompLedger]. A plugin's handler is loaded from its stored jar path
 * (absolute) on first execution, the same way the Temporal worker loads it.
 *
 * Every `HERMOD_WF_POLL_MS` (default 3000) the registry is re-read and newly published blocks get a poller,
 * so a block published while this worker runs is served without a restart (mirrors the Temporal worker's
 * deploy poll).
 */
fun main() {
    val workerName = System.getenv("HERMOD_WORKER_NAME") ?: "conductor-worker-1"
    val serverUrl = System.getenv("CONDUCTOR_SERVER_URL") ?: DEFAULT_CONDUCTOR_URL
    val pollMs = System.getenv("HERMOD_WF_POLL_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 3000L

    val registry = DispatchAssembly.buildRegistry(BlockIndex { Db.connect() }, BlockStore())
    val ledger = CompLedger { Db.connect() }

    val client = ConductorClient.builder().basePath(serverUrl).build()
    val taskClient = TaskClient(client)
    fun startPolling(workers: List<Worker>) =
        TaskRunnerConfigurer.Builder(taskClient, workers).withThreadCount(workers.size).build().init()

    // The DB being down at boot must not stop the builtins from being served; the refresh adds plugins later.
    fun knownTaskTypes(): List<String> = try {
        forwardTaskTypes(registry.list())
    } catch (e: Exception) {
        System.err.println("[$workerName] registry unavailable, polling builtins only for now: ${e.message}")
        forwardTaskTypes(DispatchAssembly.builtinContracts.values.toList())
    }

    val pollers = BlockPollers(workerFor = { ForwardWorker(it, registry, ledger) }, start = ::startPolling)

    println("[$workerName] starting conductor workers against $serverUrl")
    startPolling(listOf(CompensateWorker(registry, ledger)))
    val initial = pollers.sync(knownTaskTypes())
    println("[$workerName] task types=$initial + $COMPENSATE_TASK")
    println("[$workerName] conductor workers polling")

    Thread {
        while (true) {
            Thread.sleep(pollMs)
            try {
                val fresh = pollers.sync(knownTaskTypes())
                if (fresh.isNotEmpty()) println("[$workerName] now polling newly published block types=$fresh")
            } catch (e: Exception) {
                System.err.println("[$workerName] block refresh error: ${e.message}")
            }
        }
    }.apply { isDaemon = true; name = "block-refresh" }.start()
}
