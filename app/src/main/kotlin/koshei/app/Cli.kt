package koshei.app

import koshei.blocks.Db
import koshei.compiler.CompileException
import koshei.compiler.WorkflowCompiler
import koshei.compiler.conductor.ConductorBackend
import koshei.registry.BlockIndex
import koshei.opcua.AutoCorrectAction
import koshei.opcua.AutoCorrectSupervisor
import koshei.opcua.DriftDecision
import koshei.opcua.DriftDetector
import koshei.opcua.FsmSpec
import koshei.opcua.FsmStateReader
import koshei.opcua.GovernDecision
import koshei.opcua.TransitionGovernor
import koshei.registry.BlockStore
import koshei.registry.DriftStore
import koshei.registry.FsmDeploymentStore
import koshei.registry.SoakSupervisor
import koshei.runtime.RuntimeAssembly
import koshei.runtime.TemporalBackend
import koshei.runtime.WorkflowDefLoader
import java.io.File
import kotlin.system.exitProcess

/**
 * SDK CLI for the Koshei block ecosystem.
 *
 * Usage:
 *   scaffold block <id> [--out <dir>]  — generate a plugin project skeleton from templates
 *   publish <jar>                       — publish a plugin jar to the registry
 *   list                                — list all registered blocks (builtins + plugins)
 *   compile <wf>|--file <path> [--target temporal|conductor]  — compile workflow to IR
 *
 * REF: app/.../Starter.kt for the entrypoint/arg idiom.
 */
fun main(args: Array<String>) {
    val cmd = args.getOrNull(0) ?: usageError("command required (scaffold|publish|list|compile|conformance|fsm)")
    when (cmd) {
        "scaffold"    -> doScaffold(args)
        "publish"     -> doPublish(args)
        "list"        -> doList()
        "compile"     -> doCompile(args)
        "conformance" -> doConformance(args)
        "fsm"         -> doFsm(args)
        else          -> usageError("unknown command: $cmd (expected scaffold|publish|list|compile|conformance|fsm)")
    }
}

// ---------------------------------------------------------------------------
// scaffold block <id> [--out <dir>]
// ---------------------------------------------------------------------------
private fun doScaffold(args: Array<String>) {
    val sub = args.getOrNull(1) ?: usageError("scaffold requires subcommand: block")
    if (sub != "block") usageError("scaffold subcommand must be 'block'")
    val id = args.getOrNull(2) ?: usageError("scaffold block requires <id> (e.g. io.example.greet)")

    // Validate id: must be lowercase dotted form (valid Kotlin package + safe path)
    val idPattern = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+$")
    if (!idPattern.matches(id)) usageError("invalid block id '$id' — expected lowercase dotted form like io.example.greet")

    // Parse optional --out
    var outDir = id.replace('.', '-') // default: io.example.greet -> io-example-greet
    val outIdx = args.indexOf("--out")
    if (outIdx >= 0) {
        outDir = args.getOrNull(outIdx + 1) ?: usageError("--out requires a directory path")
    }

    // Derive package and class name from the id
    // Convention: io.example.greet -> package koshei.plugin.io.example.greet, class GreetBlock
    val lastSegment = id.substringAfterLast('.')
    val className = lastSegment.replaceFirstChar { it.uppercase() } + "Block"
    val packageName = "koshei.plugin.${id.replace('-', '_')}"
    val fqcn = "$packageName.$className"

    // Group = first two segments of id, e.g. io.example
    val groupId = id.split('.').take(2).joinToString(".")

    val out = File(outDir)
    out.mkdirs()

    // Write build.gradle.kts
    val buildGradle = loadTemplate("scaffold/build.gradle.kts.tmpl")
        .replace("__GROUP__", groupId)
        .replace("__ID__", id)
        .replace("__HANDLER__", fqcn)
    File(out, "build.gradle.kts").writeText(buildGradle)

    // Write Block stub under src/main/kotlin/<package path>/
    val blockSrc = loadTemplate("scaffold/Block.kt.tmpl")
        .replace("__PACKAGE__", packageName)
        .replace("__CLASS__", className)
        .replace("__ID__", id)
        .replace("__HANDLER__", fqcn)
    val srcDir = File(out, "src/main/kotlin/${packageName.replace('.', '/')}")
    srcDir.mkdirs()
    File(srcDir, "$className.kt").writeText(blockSrc)

    // Write manifest stub under src/main/resources/manifests/
    val manifestContent = loadTemplate("scaffold/manifest.yaml.tmpl")
        .replace("__ID__", id)
        .replace("__HANDLER__", fqcn)
    val resDir = File(out, "src/main/resources/manifests")
    resDir.mkdirs()
    File(resDir, "$id.yaml").writeText(manifestContent)

    // Write settings.gradle.kts
    File(out, "settings.gradle.kts").writeText("rootProject.name = \"${id.replace('.', '-')}\"\n")

    println("Scaffolded plugin project for '$id' at: ${out.absolutePath}")
    println("  build.gradle.kts")
    println("  settings.gradle.kts")
    println("  src/main/kotlin/${packageName.replace('.', '/')}/$className.kt")
    println("  src/main/resources/manifests/$id.yaml")
    println()
    println("Next steps:")
    println("  1. Implement $className.forward() and $className.compensate()")
    println("  2. Update the manifest at src/main/resources/manifests/$id.yaml")
    println("  3. ./gradlew publishToMavenLocal  (publishes sdk to mavenLocal first if needed)")
    println("  4. ./gradlew jar")
    println("  5. koshei-cli publish build/libs/<jar>")
}

// ---------------------------------------------------------------------------
// publish <jar>
// ---------------------------------------------------------------------------
private fun doPublish(args: Array<String>) {
    val jarPath = args.getOrNull(1) ?: usageError("publish requires <jar> path")
    val jar = File(jarPath)
    if (!jar.exists()) { System.err.println("ERROR: jar not found: ${jar.absolutePath}"); exitProcess(1) }

    val registry = RuntimeAssembly.buildRegistry(BlockIndex { Db.connect() }, BlockStore())
    val result = registry.publish(jar)
    if (result.ok) {
        println("Published ${jar.name} successfully.")
    } else {
        System.err.println("Publish FAILED:")
        result.errors.forEach { System.err.println("  - $it") }
        exitProcess(1)
    }
}

// ---------------------------------------------------------------------------
// list
// ---------------------------------------------------------------------------
private fun doList() {
    // Build a registry backed by the real DB. If DB is unavailable, fall back to builtins-only.
    // registry.list() calls index.list() which hits the DB; we catch that per-call too.
    val registry = RuntimeAssembly.buildRegistry(BlockIndex { Db.connect() }, BlockStore())

    val blocks = try {
        registry.list()
    } catch (e: Exception) {
        // DB not available — only builtins can be listed without a connection.
        System.err.println("WARNING: registry DB unavailable — listing builtins only. (${e.message})")
        RuntimeAssembly.builtinContracts()
    }

    if (blocks.isEmpty()) {
        println("(no blocks registered)")
    } else {
        blocks.forEach { c -> println("${c.id}#${c.version}  [${c.category}]  ${c.displayName}") }
    }
}

private fun loadTemplate(path: String): String =
    object {}.javaClass.classLoader.getResourceAsStream(path)?.use { it.bufferedReader().readText() }
        ?: error("template not found on classpath: $path")

// ---------------------------------------------------------------------------
// compile <workflowName> | --file <path>   [--target temporal|conductor]
// ---------------------------------------------------------------------------
private fun doCompile(args: Array<String>) {
    val fileIdx = args.indexOf("--file")
    val yaml: String = if (fileIdx >= 0) {
        val p = args.getOrNull(fileIdx + 1) ?: usageError("--file requires a path")
        File(p).let {
            if (!it.exists()) { System.err.println("ERROR: workflow file not found: ${it.absolutePath}"); exitProcess(1) }
            it.readText()
        }
    } else {
        val name = args.getOrNull(1) ?: usageError("compile requires <workflowName> or --file <path>")
        (object {}.javaClass.classLoader.getResourceAsStream("workflows/$name.yaml")
            ?: run { System.err.println("ERROR: workflow '$name' not found on classpath (workflows/$name.yaml)"); exitProcess(1) })
            .use { it.bufferedReader().readText() }
    }
    val targetIdx = args.indexOf("--target")
    val target = if (targetIdx >= 0) (args.getOrNull(targetIdx + 1) ?: usageError("--target requires temporal|conductor")) else "conductor"

    val def = WorkflowDefLoader.load(yaml)
    val registry = RuntimeAssembly.buildRegistry(BlockIndex { Db.connect() }, BlockStore())
    val ir = try {
        WorkflowCompiler.compile(def, registry)
    } catch (e: CompileException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    when (target) {
        "temporal"   -> { TemporalBackend.lower(ir); println("[compile] ${ir.name}: ${ir.nodes.size} nodes -> temporal plan OK") }
        "conductor"  -> {
            val b = ConductorBackend.emitBundle(ir)
            // stable, grep-able sections for the gate
            println("=== workflow ==="); println(b.workflow)
            println("=== compensation ==="); println(b.compensation)
            println("=== taskdefs ==="); println(b.taskDefs)
        }
        else         -> usageError("unknown --target: $target (expected temporal|conductor)")
    }
}

// ---------------------------------------------------------------------------
// conformance [--model-dir <dir>] [--workflow-dir <dir>]
// Validates the Git-canonical set (policies + site + recipe + templates + ot-* workflows) for
// well-formedness + cross-artifact integrity. ERROR -> exit 1 (fail-closed); WARNING -> reported.
// Defaults resolve against the repo root (settings.gradle.kts walk-up), NOT the process working dir:
// the :app:cli JavaExec task runs with workingDir=app/, so a plain `model` would wrongly be app/model.
// ---------------------------------------------------------------------------
private fun doConformance(args: Array<String>) {
    val root = repoRoot()
    fun opt(name: String, default: File): File {
        val i = args.indexOf(name)
        return if (i >= 0) File(args.getOrNull(i + 1) ?: usageError("$name requires a directory path")) else default
    }
    val modelDir = opt("--model-dir", File(root, "model"))
    val workflowDir = opt("--workflow-dir", File(root, "app/src/main/resources/workflows"))
    if (!modelDir.isDirectory) { System.err.println("ERROR: --model-dir not a directory: ${modelDir.absolutePath}"); exitProcess(1) }

    val r = Conformance.run(modelDir, workflowDir)
    r.warnings.forEach { println("WARN  $it") }
    r.errors.forEach { System.err.println("ERROR $it") }
    println("conformance: ${r.errors.size} errors, ${r.warnings.size} warnings")
    if (!r.ok) exitProcess(1)
    println("conformance: OK")
}

// ---------------------------------------------------------------------------
// fsm deploy <unit> <version> | fsm rollback <unit> | fsm active <unit> | fsm resolve <unit> <version>
// Canary/rollback of the active FSM-spec version per unit (spec 2026-07-02). deploy is fail-closed on
// (a) a (unit,version) no Git spec declares and (b) whole-set conformance. rollback is an instant atomic
// swap to the prior version. active/resolve are reads the gate uses to pick the version file to govern.
// ---------------------------------------------------------------------------
private fun doFsm(args: Array<String>) {
    val sub = args.getOrNull(1) ?: usageError("fsm requires subcommand: deploy|rollback|active|resolve|report-failure|soak-sweep|status|drift-check|hold|auto-correct-sweep")
    val store = FsmDeploymentStore { Db.connect() }
    when (sub) {
        "deploy"             -> fsmDeploy(args, store)
        "rollback"           -> fsmRollback(args, store)
        "active"             -> fsmActive(args, store)
        "resolve"            -> fsmResolve(args)
        "report-failure"     -> fsmReportFailure(args, store)
        "soak-sweep"         -> fsmSoakSweep(store)
        "status"             -> fsmStatus(args, store)
        "drift-check"        -> fsmDriftCheck(args, store)
        "hold"               -> fsmHold(args, store)
        "auto-correct-sweep" -> fsmAutoCorrectSweep(store)
        else                 -> usageError("unknown fsm subcommand: $sub (expected deploy|rollback|active|resolve|report-failure|soak-sweep|status|drift-check|hold|auto-correct-sweep)")
    }
}

private fun fsmDeploy(args: Array<String>, store: FsmDeploymentStore) {
    val unit = args.getOrNull(2) ?: usageError("fsm deploy requires <unit> <version>")
    val version = args.getOrNull(3) ?: usageError("fsm deploy requires <unit> <version>")
    val root = repoRoot()
    val modelDir = File(root, "model")
    val workflowDir = File(root, "app/src/main/resources/workflows")
    val file = resolveFsmFile(modelDir, unit, version)
        ?: run { System.err.println("ERROR: no FSM spec declares unit='$unit' version='$version' under ${File(modelDir, "fsm").path}"); exitProcess(1) }
    val r = Conformance.run(modelDir, workflowDir)
    if (!r.ok) {
        System.err.println("ERROR: conformance failed — refusing to deploy $unit -> $version (pointer unchanged)")
        r.errors.forEach { System.err.println("  ERROR $it") }
        exitProcess(1)
    }
    val soakSeconds = flagValue(args, "--soak-seconds")?.toLongOrNull()
    val failThreshold = flagValue(args, "--fail-threshold")?.toIntOrNull()
    try { store.deploy(unit, version, soakSeconds = soakSeconds, failThreshold = failThreshold) }
    catch (e: IllegalStateException) { System.err.println("ERROR: ${e.message}"); exitProcess(1) }
    val suffix = if (soakSeconds != null) " (soaking ${soakSeconds}s, threshold ${failThreshold ?: 1})" else ""
    println("deployed $unit -> $version (${file.name})$suffix")
}

private fun fsmRollback(args: Array<String>, store: FsmDeploymentStore) {
    val unit = args.getOrNull(2) ?: usageError("fsm rollback requires <unit>")
    val to = try { store.rollback(unit) }
             catch (e: Exception) { System.err.println("ERROR: cannot roll back '$unit': ${e.message}"); exitProcess(1) }
    println("rolled back $unit -> $to")
}

private fun fsmActive(args: Array<String>, store: FsmDeploymentStore) {
    val unit = args.getOrNull(2) ?: usageError("fsm active requires <unit>")
    val v = store.activeVersion(unit)
        ?: run { System.err.println("ERROR: unit '$unit' has no active deployment"); exitProcess(1) }
    println(v)
}

private fun fsmResolve(args: Array<String>) {
    val unit = args.getOrNull(2) ?: usageError("fsm resolve requires <unit> <version>")
    val version = args.getOrNull(3) ?: usageError("fsm resolve requires <unit> <version>")
    val file = resolveFsmFile(File(repoRoot(), "model"), unit, version)
        ?: run { System.err.println("ERROR: no FSM spec declares unit='$unit' version='$version'"); exitProcess(1) }
    println(file.absolutePath)
}

private fun fsmReportFailure(args: Array<String>, store: FsmDeploymentStore) {
    val unit = args.getOrNull(2) ?: usageError("fsm report-failure requires <unit>")
    println(if (store.recordFailure(unit)) "counted failure for $unit" else "ignored (not soaking): $unit")
}

private fun fsmSoakSweep(store: FsmDeploymentStore) {
    val actions = SoakSupervisor.sweep(store, java.time.Instant.now())
    if (actions.isEmpty()) { println("no soak actions"); return }
    actions.forEach { a -> when (a) {
        is SoakSupervisor.RolledBack -> println("AUTO_ROLLBACK ${a.unit} ${a.from} -> ${a.to}")
        is SoakSupervisor.Promoted   -> println("PROMOTE ${a.unit} ${a.version}")
    } }
}

private fun fsmStatus(args: Array<String>, store: FsmDeploymentStore) {
    val unit = args.getOrNull(2) ?: usageError("fsm status requires <unit>")
    val r = store.soaking().firstOrNull { it.unit == unit }
    val active = store.activeVersion(unit) ?: run { System.err.println("ERROR: unit '$unit' has no active deployment"); exitProcess(1) }
    if (r != null) println("active=$active status=soaking fail_count=${r.failCount}/${r.failThreshold} soak_until=${r.soakUntil}")
    else println("active=$active status=promoted")
}

// ---------------------------------------------------------------------------
// fsm drift-check <unit>
// Reads the equipment's LIVE state node for the unit's active FSM-spec version, compares it against
// the last observed state persisted in DriftStore, and records the verdict (BASELINE/OK/DRIFT). Pure
// detect-only: koshei never drives the equipment here (design 2026-07-03).
// ---------------------------------------------------------------------------
private fun fsmDriftCheck(args: Array<String>, store: FsmDeploymentStore) {
    val unit = args.getOrNull(2) ?: usageError("fsm drift-check requires <unit>")
    val version = store.activeVersion(unit)
        ?: run { System.err.println("ERROR: unit '$unit' has no active deployment"); exitProcess(1) }
    val file = resolveFsmFile(File(repoRoot(), "model"), unit, version)
        ?: run { System.err.println("ERROR: no FSM spec declares unit='$unit' version='$version'"); exitProcess(1) }
    val fsm = FsmSpec.fromFile(file)
    val endpoint = System.getenv("KOSHEI_OPCUA_URL") ?: "opc.tcp://localhost:48400"
    val observed = FsmStateReader.readStateCode(fsm, endpoint)
        ?: run { System.err.println("ERROR: could not read stateNode '${fsm.stateNode}' for unit '$unit'"); exitProcess(1) }
    val drift = DriftStore { Db.connect() }
    val prior = drift.lastState(unit)
    if (prior == null) {
        drift.observe(unit, null, observed, "BASELINE", "-")
        println("baseline $unit $observed")
    } else when (val d = DriftDetector.detect(fsm, prior, observed)) {
        is DriftDecision.Ok -> { drift.observe(unit, prior, observed, "OK", "-"); println("OK $unit $prior -> $observed") }
        is DriftDecision.Drift -> { drift.observe(unit, prior, observed, "DRIFT", d.reason); println("DRIFT $unit $prior -> $observed: ${d.reason}") }
    }
}

// ---------------------------------------------------------------------------
// fsm hold <unit>
// Governs a corrective SafeHold from the equipment's LIVE state for the unit's ACTIVE FSM version.
// ALLOW -> record a HOLD corrective audit row (pointer untouched) + print `ALLOW <workflow>` (the gate
// dispatches the human-gated saga). DENY -> print `DENY <reason>`, dispatch nothing (fail-closed alarm).
// The corrective SafeHold is a koshei-driven command DISTINCT from the operator's field Hold. koshei
// never writes the stateCurrent read-node; the safe transition is the field/PLC response. Design 2026-07-03.
// ---------------------------------------------------------------------------
private fun fsmHold(args: Array<String>, store: FsmDeploymentStore) {
    val unit = args.getOrNull(2) ?: usageError("fsm hold requires <unit>")
    val version = store.activeVersion(unit)
        ?: run { System.err.println("ERROR: unit '$unit' has no active deployment"); exitProcess(1) }
    val file = resolveFsmFile(File(repoRoot(), "model"), unit, version)
        ?: run { System.err.println("ERROR: no FSM spec declares unit='$unit' version='$version'"); exitProcess(1) }
    val fsm = FsmSpec.fromFile(file)
    val endpoint = System.getenv("KOSHEI_OPCUA_URL") ?: "opc.tcp://localhost:48400"
    val code = FsmStateReader.readStateCode(fsm, endpoint)
        ?: run { System.err.println("ERROR: could not read stateNode '${fsm.stateNode}' for unit '$unit'"); exitProcess(1) }
    when (val d = TransitionGovernor.govern(fsm, code, "SafeHold")) {
        is GovernDecision.Allow -> {
            // Allow carries only the workflow; re-look-up the transition's target-state code for the audit.
            val stateId = fsm.states.first { it.code == code }.id
            val t = fsm.transitions.first { it.from == stateId && it.command == "SafeHold" }
            val toState = fsm.states.firstOrNull { it.id == t.to }
                ?: run { System.err.println("ERROR: SafeHold transition targets state '${t.to}' which the FSM does not declare (spec edited after deploy?)"); exitProcess(1) }
            val toCode = toState.code
            DriftStore { Db.connect() }.recordCorrection(unit, code, toCode, d.workflow)
            println("ALLOW ${d.workflow}")
        }
        is GovernDecision.Deny -> {
            DriftStore { Db.connect() }.recordDenyAlarm(unit, code, d.reason)
            println("DENY ${d.reason}")
        }
    }
}

// ---------------------------------------------------------------------------
// fsm auto-correct-sweep
// Sweep all units with an active FSM deployment: read live state, detect drift (recording the observation
// exactly as drift-check does), and on DRIFT evaluate the corrective SafeHold. Prints one line per unit.
// Detect + evaluate + alarm ONLY — never dispatches (human-in-the-loop). Deterministic proof surface for the
// AutoCorrectBean (which wraps the SAME AutoCorrectSupervisor.sweep). Design 2026-07-03.
// ---------------------------------------------------------------------------
private fun fsmAutoCorrectSweep(store: FsmDeploymentStore) {
    val drift = DriftStore { Db.connect() }
    val modelDir = File(repoRoot(), "model")
    val endpoint = System.getenv("KOSHEI_OPCUA_URL") ?: "opc.tcp://localhost:48400"
    val actions = AutoCorrectSupervisor.sweep(
        units = store.activeUnits(),
        readState = { fsm -> FsmStateReader.readStateCode(fsm, endpoint) },
        resolveFsm = { unit -> store.activeVersion(unit)?.let { FsmSpec.resolve(modelDir, unit, it) }?.let(FsmSpec::fromFile) },
        lastState = { unit -> drift.lastState(unit) },
        recordObservation = { unit, from, to, verdict, detail -> drift.observe(unit, from, to, verdict, detail) },
    )
    actions.forEach { a -> when (a) {
        is AutoCorrectAction.Baseline         -> println("BASELINE ${a.unit} ${a.code}")
        is AutoCorrectAction.Ok               -> println("OK ${a.unit} ${a.from} -> ${a.to}")
        is AutoCorrectAction.DriftCorrectable -> println("DRIFT-CORRECTABLE ${a.unit} ${a.from} -> ${a.to}: ${a.workflow} (${a.driftReason})")
        is AutoCorrectAction.DriftBlocked     -> println("DRIFT-BLOCKED ${a.unit} ${a.from} -> ${a.to}: ${a.governReason}")
        is AutoCorrectAction.Skipped          -> println("SKIPPED ${a.unit}: ${a.why}")
    } }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
/** Repo root = nearest ancestor holding settings.gradle.kts (NOT the process working dir; :app:cli runs with workingDir=app/). */
private fun repoRoot(): File {
    var d: File? = File(System.getProperty("user.dir")).absoluteFile
    while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
    return d ?: File(".").absoluteFile
}

/**
 * Resolve an FSM spec by its IN-FILE (unit, version) — NOT by filename (there is no line1->packml-line1
 * name mapping; the in-file fields are the authority). Scans the model/fsm directory, parses each yaml,
 * matches unit+version. Ambiguous (more than one file declaring the same pair) throws (fail-closed).
 * Returns null when no file declares the pair.
 */
private fun resolveFsmFile(modelDir: File, unit: String, version: String): File? =
    try { FsmSpec.resolve(modelDir, unit, version) }
    catch (e: IllegalStateException) {   // FsmSpec.resolve throws (error(...)) on an ambiguous (unit,version)
        System.err.println("ERROR: ambiguous FSM spec — more than one file declares unit='$unit' version='$version'. ${e.message ?: ""}".trim())
        exitProcess(1)
    }

private fun flagValue(args: Array<String>, flag: String): String? {
    val i = args.indexOf(flag); return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

private fun usageError(msg: String): Nothing {
    System.err.println("ERROR: $msg")
    System.err.println()
    System.err.println("Usage:")
    System.err.println("  scaffold block <id> [--out <dir>]   generate plugin project skeleton")
    System.err.println("  publish <jar>                        publish plugin jar to registry")
    System.err.println("  list                                 list all registered blocks")
    System.err.println("  compile <wf>|--file <path> [--target temporal|conductor]   compile workflow to IR -> temporal plan or conductor json")
    System.err.println("  conformance [--model-dir <dir>] [--workflow-dir <dir>]   validate the Git-canonical set (fail-closed)")
    System.err.println("  fsm deploy <unit> <version> [--soak-seconds <s>] [--fail-threshold <n>]   canary-deploy active FSM-spec version (fail-closed on unknown pair + conformance); with --soak-seconds, deploy enters a soaking window (default threshold 1) instead of promoting immediately")
    System.err.println("  fsm rollback <unit>                  instant atomic swap to the prior FSM-spec version")
    System.err.println("  fsm active <unit>                    print the active FSM-spec version")
    System.err.println("  fsm resolve <unit> <version>         print the spec file path for an in-file (unit,version)")
    System.err.println("  fsm report-failure <unit>            count a failure against a soaking deployment (ignored if not soaking)")
    System.err.println("  fsm soak-sweep                       auto-rollback soaking deployments over threshold, or promote ones past soak_until")
    System.err.println("  fsm status <unit>                    print active version + soak status (soaking fail_count/threshold/soak_until, or promoted)")
    System.err.println("  fsm drift-check <unit>                read the live state node for the active FSM version and record BASELINE/OK/DRIFT vs the last observed state")
    System.err.println("  fsm hold <unit>                       govern a corrective SafeHold from the live state; ALLOW records a HOLD audit row + prints the workflow to dispatch (human-gated), DENY = fail-closed alarm")
    System.err.println("  fsm auto-correct-sweep                sweep all deployed units: detect drift + evaluate the corrective SafeHold (BASELINE/OK/DRIFT-CORRECTABLE/DRIFT-BLOCKED/SKIPPED). Detect+alarm only — never dispatches.")
    exitProcess(1)
}
