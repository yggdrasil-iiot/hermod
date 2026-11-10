package yggdrasil.hermod.app

import yggdrasil.hermod.compiler.WorkflowCompiler
import yggdrasil.hermod.delegation.DelegationPolicy
import yggdrasil.hermod.delegation.DelegationPolicyValidator
import yggdrasil.hermod.opcua.CanonicalSetpoints
import yggdrasil.hermod.opcua.CommandPolicy
import yggdrasil.hermod.opcua.FsmSpec
import yggdrasil.hermod.opcua.FsmValidator
import yggdrasil.hermod.opcua.ModelValidator
import yggdrasil.hermod.opcua.SiteModel
import yggdrasil.hermod.registry.BlockIndex
import yggdrasil.hermod.registry.BlockStore
import yggdrasil.hermod.runtime.RuntimeAssembly
import yggdrasil.hermod.runtime.WorkflowDefLoader
import java.io.File

/** Aggregate conformance result over the Git-canonical set. Mirrors the runtime ValidationResult. */
data class ConformanceResult(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * CI conformance gate orchestrator. Validates every Git-canonical artifact (well-formedness +
 * cross-artifact integrity), fail-closed: ERROR fails (caller exits non-zero), WARNING is reported.
 * Reuses the runtime validators; adds only artifact discovery + variant pairing. Offline (no DB) for
 * the governed ot-* workflow set (builtins-only registry). See the design spec 2026-07-02.
 */
object Conformance {
    private val SITE = Regex("""^ot-site(.*)\.yaml$""")       // group(1) = variant suffix ("" | "-ignition" | …)
    private val RECIPE = Regex("""^recipe-setpoints(.*)\.yaml$""")

    fun run(modelDir: File, workflowDir: File): ConformanceResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // ---- shared policies -------------------------------------------------
        val policy: CommandPolicy? = guard("command-policy.json", modelDir.resolve("command-policy.json"), errors) { f ->
            CommandPolicy.parse(f.readText()).also { p ->
                ModelValidator.validatePolicy(p).errors.forEach { errors += "command-policy.json: $it" }
            }
        }
        guard("delegation-policy.json", modelDir.resolve("delegation-policy.json"), errors) { f ->
            val dp = DelegationPolicy.parse(f.readText())
            val r = DelegationPolicyValidator.validate(dp)
            r.errors.forEach { errors += "delegation-policy.json: $it" }
            r.warnings.forEach { warnings += "delegation-policy.json: $it" }
        }

        // ---- variant discovery (top-level, non-recursive) --------------------
        val files = modelDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val sites = files.mapNotNull { f -> SITE.matchEntire(f.name)?.let { it.groupValues[1] to f } }.toMap()
        val recipes = files.mapNotNull { f -> RECIPE.matchEntire(f.name)?.let { it.groupValues[1] to f } }.toMap()

        // Fail-closed on a missing canonical primary: a governance gate must not go GREEN when the
        // default-variant ("" suffix) site model is deleted (deleting both site+recipe otherwise leaves
        // sites/recipes empty and every loop a no-op). The orphan checks below cover single deletions.
        if ("" !in sites) errors += "model: canonical ot-site.yaml (default variant) is missing"

        var defaultSite: SiteModel? = null
        for ((suffix, siteFile) in sites) {
            val model: SiteModel? = guard(siteFile.name, siteFile, errors) { f ->
                val m = SiteModel.parse(f.readText())
                val mv = ModelValidator.validateModel(m)
                mv.errors.forEach { errors += "${siteFile.name}: $it" }
                mv.warnings.forEach { warnings += "${siteFile.name}: $it" }
                // cross-ref WARNING (mirrors ModelValidator.validate): policy rule -> node not in THIS model
                if (policy != null) {
                    val known = m.setpoints().keys
                    policy.rules().filter { it.node !in known }.forEach {
                        warnings += "${siteFile.name}: command-policy rule '${it.id}' references node '${it.node}' not in this site model"
                    }
                }
                m
            }
            if (suffix == "") defaultSite = model
            val recipeFile = recipes[suffix]
            if (recipeFile == null) {
                errors += "ot-site$suffix.yaml: no matching recipe-setpoints$suffix.yaml (orphaned site variant)"
            } else if (model != null && policy != null) {
                guard(recipeFile.name, recipeFile, errors) { f ->
                    CanonicalSetpoints.load(f, model, policy)   // throws on any cross-artifact mismatch
                }
            }
        }
        for ((suffix, recipeFile) in recipes) {
            if (suffix !in sites) errors += "${recipeFile.name}: no matching ot-site$suffix.yaml (orphaned recipe variant)"
        }

        // ---- templates: intentional-fail self-test (INVERTED severity) -------
        val templatesDir = modelDir.resolve("templates")
        if (templatesDir.isDirectory) {
            templatesDir.listFiles { f -> f.isFile && f.name.endsWith(".yaml") }?.sortedBy { it.name }?.forEach { tf ->
                // Self-test proves "not accidentally valid": any rejection (validation errors OR a parse
                // failure) is treated as the expected outcome — it does NOT assert the intended reason.
                val rejected = try { !ModelValidator.validateModel(SiteModel.parse(tf.readText())).ok }
                               catch (t: Throwable) { true }   // parse failure also counts as rejected (expected)
                if (!rejected) errors += "templates/${tf.name}: expected to FAIL model validation (intentional-fail exemplar) but PASSED"
            }
        }

        // ---- governed workflows (ot-*): offline builtins-only registry -------
        val wfFiles = workflowDir.listFiles { f -> f.isFile && f.name.startsWith("ot-") && f.name.endsWith(".yaml") }
            ?.sortedBy { it.name } ?: emptyList()
        if (wfFiles.isNotEmpty()) {
            val registry = RuntimeAssembly.buildRegistry(
                BlockIndex { error("conformance: offline — plugin blocks are not resolvable in the CI gate") },
                BlockStore(),
            )
            for (wf in wfFiles) {
                try { WorkflowCompiler.compile(WorkflowDefLoader.load(wf.readText()), registry) }
                catch (t: Throwable) { errors += "${wf.name}: ${t.message ?: t.toString()}" }
            }
        }

        // ---- FSM specs (model/fsm/*.yaml): structural + cross-artifact (stateNode ∈ default site; action.workflow ∈ ot-*) ----
        val fsmDir = modelDir.resolve("fsm")
        if (fsmDir.isDirectory) {
            fsmDir.listFiles { f -> f.isFile && f.name.endsWith(".yaml") }?.sortedBy { it.name }?.forEach { ff ->
                guard("fsm/${ff.name}", ff, errors) { f ->
                    val fsm = FsmSpec.parse(f.readText())
                    FsmValidator.validate(fsm).errors.forEach { errors += "fsm/${ff.name}: $it" }
                    val ds = defaultSite
                    if (ds != null && fsm.stateNode !in ds.setpoints().keys)
                        errors += "fsm/${ff.name}: stateNode '${fsm.stateNode}' not in the default site model"
                    fsm.transitions.filter { it.driver == "hermod" }.forEach { t ->
                        val wf = t.action?.workflow
                        if (wf != null && !(wf.startsWith("ot-") && File(workflowDir, "$wf.yaml").isFile))
                            errors += "fsm/${ff.name}: transition '${t.id}' action.workflow '$wf' is not a governed ot-* workflow"
                    }
                }
            }
        }

        return ConformanceResult(errors, warnings)
    }

    /** Run one artifact's load/validate under a broad catch; missing file or any Throwable -> ERROR, null. */
    private fun <T> guard(label: String, file: File, errors: MutableList<String>, body: (File) -> T): T? =
        try {
            if (!file.exists()) { errors += "$label: missing (expected at ${file.path})"; null }
            else body(file)
        } catch (t: Throwable) { errors += "$label: ${t.message ?: t.toString()}"; null }
}
