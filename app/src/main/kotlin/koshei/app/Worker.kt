package koshei.app

import io.temporal.client.WorkflowClient
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.worker.WorkerFactory
import io.temporal.worker.WorkerOptions
import koshei.blocks.Db
import koshei.registry.BlockIndex
import koshei.registry.BlockStore
import koshei.registry.WorkflowStore
import koshei.runtime.BlockActivitiesImpl
import koshei.runtime.BoundWorkflow
import koshei.runtime.DataConverterSupport
import koshei.runtime.RuntimeAssembly
import koshei.runtime.SagaWorkflowImpl
import koshei.runtime.WorkflowDefLoader
import java.io.File

const val TASK_QUEUE = "koshei-v0_1-tq"

fun main() {
    val workerName = System.getenv("KOSHEI_WORKER_NAME") ?: "worker-1"

    // R2/§8: bind ONCE here, on the main thread, BEFORE polling starts. bind() both validates the def
    // against the loaded manifests (throws on a version/contract mismatch) AND warms the reflection/JIT
    // -heavy contract precompute off the deterministic workflow thread, so the first workflow task does
    // not do it on the workflow thread under the deadlock detector (TMPRL1101).
    //
    // Builtin resolution is DB-free (Registry.resolve checks builtins before the index), so
    // builtin-only v0.1 workflows (demo.yaml) never touch the plugin DB here.
    val registry = RuntimeAssembly.buildRegistry(
        BlockIndex { Db.connect() },
        BlockStore()
    )

    // Load every workflows/*.yaml from the classpath (and optionally an external dir override).
    val boundNames = mutableListOf<String>()
    loadWorkflowYamls().forEach { (name, yaml) ->
        println("[$workerName] loading workflow: $name")
        val def = WorkflowDefLoader.load(yaml)
        val plan = RuntimeAssembly.planFor(def)
        BoundWorkflow.bind(def.name, plan)
        boundNames += def.name
        println("[$workerName] bound workflow '${def.name}' (${def.steps.size} steps)")
    }
    check(boundNames.isNotEmpty()) { "no workflows found (classpath workflows/*.yaml or KOSHEI_WORKFLOWS_DIR) — nothing to bind" }
    println("[$workerName] bound workflows: $boundNames")

    val service = WorkflowServiceStubs.newLocalServiceStubs() // localhost:7233
    val client = WorkflowClient.newInstance(service, DataConverterSupport.clientOptions())
    val factory = WorkerFactory.newInstance(client)
    // Temporal's default deadlock detector (1s) is a dev-machine guardrail, not a correctness property.
    // On a cold/loaded machine, replaying parked human-gate workflows under concurrent load (history
    // replay + Kotlin payload deserialization) can briefly exceed 1s and crash the worker JVM
    // (TMPRL1101). Raise it generously; the workflow body itself stays cheap & deterministic.
    val worker = factory.newWorker(
        TASK_QUEUE,
        WorkerOptions.newBuilder()
            .setDefaultDeadlockDetectionTimeout(10_000)
            .build()
    )
    worker.registerWorkflowImplementationTypes(SagaWorkflowImpl::class.java)
    worker.registerActivitiesImplementations(BlockActivitiesImpl(registry))

    // v0.3e: bind operator-composed workflow_defs from Postgres — at startup AND via a background poll —
    // so runs started by the control plane (RunController → start(workflowName="$name@$version", …))
    // execute WITHOUT a worker restart. Binds new NAMES at runtime (no Temporal type re-registration
    // needed; replay-safe; self-heals via workflow-task retry). The classpath/YAML binding above is
    // untouched (back-compat for demo/dag-diamond/conc fixtures).
    val workflowStore = WorkflowStore { Db.connect() }

    // bind helper (versioned key + versioned marker — the existing YAML loop's "bound workflow '<name>'" is unversioned)
    fun bindDeployed(name: String, version: String) {
        val key = "$name@$version"
        if (key in BoundWorkflow.boundNames()) return
        val def = workflowStore.get(name, version) ?: return
        try {
            val plan = RuntimeAssembly.planFor(def.copy(name = key))   // bind under the versioned key
            BoundWorkflow.bind(key, plan)
            println("[worker] bound workflow $key")                    // GATE MARKER (versioned)
        } catch (e: Exception) {
            System.err.println("[worker] ERROR compiling deployed workflow $key: ${e.message}")  // LOUD — never crash, but visible (spec §3.4)
        }
    }

    // startup: bind everything already deployed
    workflowStore.listDeployed().forEach { (n, v) -> bindDeployed(n, v) }

    // poll thread: pick up newly-deployed defs without restart
    val pollMs = (System.getenv("KOSHEI_WF_POLL_MS")?.toLongOrNull() ?: 3000L)
    Thread {
        while (true) {
            try { workflowStore.listDeployed().forEach { (n, v) -> bindDeployed(n, v) } }
            catch (e: Exception) { System.err.println("[worker] poll error: ${e.message}") }
            Thread.sleep(pollMs)
        }
    }.apply { isDaemon = true; name = "wf-deploy-poll" }.start()

    println("[$workerName] starting; polling $TASK_QUEUE")
    factory.start()
}

/**
 * Load workflow YAML files from:
 * 1. Classpath resource directory `workflows/` (packaged yamls like demo.yaml)
 * 2. Optional external directory from env var KOSHEI_WORKFLOWS_DIR (for dev/ops overrides)
 *
 * Returns list of (filename, yamlContent) pairs, deduplicated (external dir wins on name conflict).
 */
private fun loadWorkflowYamls(): List<Pair<String, String>> {
    val loaded = mutableMapOf<String, String>() // name -> yaml

    // 1. Load from classpath resources
    val classLoader = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
    val resourceUrl = classLoader.getResource("workflows/")
    if (resourceUrl != null) {
        // Works for file:// URLs (expanded classpath, e.g. Gradle run task)
        if (resourceUrl.protocol == "file") {
            File(resourceUrl.toURI()).listFiles { f -> f.extension == "yaml" || f.extension == "yml" }
                ?.forEach { f -> loaded[f.name] = f.readText() }
        } else {
            // Packaged in a jar — enumerate entries
            val connection = resourceUrl.openConnection() as? java.net.JarURLConnection
            connection?.jarFile?.use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.startsWith("workflows/") && (it.name.endsWith(".yaml") || it.name.endsWith(".yml")) && !it.isDirectory }
                    .forEach { entry ->
                        val name = entry.name.removePrefix("workflows/")
                        loaded[name] = jar.getInputStream(entry).bufferedReader().readText()
                    }
            }
        }
    }

    // 2. Load from optional external directory (overrides classpath files with same name)
    val externalDir = System.getenv("KOSHEI_WORKFLOWS_DIR")?.let { File(it) }
    if (externalDir != null && externalDir.isDirectory) {
        externalDir.listFiles { f -> f.extension == "yaml" || f.extension == "yml" }
            ?.forEach { f -> loaded[f.name] = f.readText() }
    }

    return loaded.entries.map { it.key to it.value }
}
