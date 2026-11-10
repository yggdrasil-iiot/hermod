package yggdrasil.hermod.app

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ConformanceTest {

    // --- helpers ---------------------------------------------------------
    /** Walk up from the test working dir (the :app project dir) to the repo root (has settings.gradle.kts). */
    private fun repoRoot(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) { if (File(d, "settings.gradle.kts").exists()) return d; d = d.parentFile }
        error("repo root (settings.gradle.kts) not found from ${System.getProperty("user.dir")}")
    }
    private val realModel get() = File(repoRoot(), "model")
    private val realWorkflows get() = File(repoRoot(), "app/src/main/resources/workflows")

    /** Temp roots created during a test, deleted in @AfterTest so runs don't leak under the system temp dir. */
    private val tempRoots = mutableListOf<File>()
    private fun tempDir(prefix: String): File = createTempDirectory(prefix).toFile().also { tempRoots += it }
    @AfterTest fun cleanup() { tempRoots.forEach { it.deleteRecursively() }; tempRoots.clear() }

    /** Fresh writable copy of the real model/ tree, for negative mutations. */
    private fun freshModelCopy(): File {
        val tmp = tempDir("conf-model")
        realModel.copyRecursively(File(tmp, "model"), overwrite = true)
        return File(tmp, "model")
    }

    // --- tests -----------------------------------------------------------
    @Test fun `shipped canonical set is conformant`() {
        val r = Conformance.run(realModel, realWorkflows)
        assertTrue(r.ok, "expected 0 errors, got: ${r.errors}")
        assertTrue(r.errors.isEmpty())
        // canonical policy rules all reference existing nodes -> no cross-ref warnings
        assertTrue(r.warnings.isEmpty(), "expected 0 warnings, got: ${r.warnings}")
    }

    @Test fun `T2a duplicate command-policy rule id is an ERROR`() {
        val m = freshModelCopy()
        File(m, "command-policy.json").writeText(
            """{ "default": "deny", "rules": [
                 { "id": "dup", "node": "recipe.rpmSetpoint", "allow": true },
                 { "id": "dup", "node": "recipe.tempSetpoint", "allow": true } ] }""")
        val r = Conformance.run(m, realWorkflows)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("command-policy.json") && it.contains("duplicate rule id") }, "${r.errors}")
    }

    @Test fun `T2b malformed site nodeId is an ERROR`() {
        val m = freshModelCopy()
        val f = File(m, "ot-site.yaml")
        f.writeText(f.readText().replace("ns=2;s=Recipe/Rpm", "ns=2;x=Recipe/Rpm"))
        val r = Conformance.run(m, realWorkflows)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("ot-site.yaml") && it.contains("nodeId") }, "${r.errors}")
    }

    @Test fun `T2c recipe desired outside EURange is an ERROR`() {
        val m = freshModelCopy()
        val f = File(m, "recipe-setpoints.yaml")
        f.writeText(f.readText().replace("desired: 1500", "desired: 99999"))
        val r = Conformance.run(m, realWorkflows)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("recipe-setpoints.yaml") && it.contains("EURange") }, "${r.errors}")
    }

    @Test fun `T2d delegation threshold out of range is an ERROR`() {
        val m = freshModelCopy()
        val f = File(m, "delegation-policy.json")
        f.writeText(f.readText().replace("\"threshold\": 0.80", "\"threshold\": 1.5"))
        val r = Conformance.run(m, realWorkflows)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("delegation-policy.json") && it.contains("threshold") }, "${r.errors}")
    }

    @Test fun `T2e workflow with unregistered block is an ERROR (offline, deterministic)`() {
        val wf = tempDir("conf-wf")
        File(wf, "ot-bad.yaml").writeText(
            "name: ot-bad\nsteps:\n  - { block: no.such.block, version: \"1.0.0\" }\n")
        val r = Conformance.run(realModel, wf)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("ot-bad.yaml") }, "${r.errors}")
    }

    @Test fun `T1b cross-ref warning is surfaced but does NOT fail`() {
        val m = freshModelCopy()
        // add a policy rule referencing a node absent from the site model -> cross-ref WARNING
        File(m, "command-policy.json").writeText(
            """{ "default": "deny", "rules": [
                 { "id": "rpm-ok",  "node": "recipe.rpmSetpoint",  "allow": true },
                 { "id": "temp-ok", "node": "recipe.tempSetpoint", "allow": true },
                 { "id": "ghost",   "node": "recipe.ghostNode",    "allow": true } ] }""")
        val r = Conformance.run(m, realWorkflows)
        assertTrue(r.ok, "warning must not fail the gate; errors=${r.errors}")
        assertTrue(r.warnings.any { it.contains("ghost") && it.contains("not in this site model") }, "${r.warnings}")
    }

    @Test fun `orphaned recipe with no matching site is an ERROR`() {
        val m = freshModelCopy()
        File(m, "recipe-setpoints-orphan.yaml").writeText(
            "endpoint: \"opc.tcp://x\"\nsetpoints: {}\n")
        val r = Conformance.run(m, realWorkflows)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("recipe-setpoints-orphan.yaml") && it.contains("orphaned") }, "${r.errors}")
    }

    @Test fun `a template that unexpectedly PASSES model validation is an ERROR`() {
        val m = freshModelCopy()
        // drop a fully-valid site model into templates/ -> it should be flagged (inverted severity)
        File(m, "templates").mkdirs()
        File(m, "templates/valid.yaml").writeText(File(m, "ot-site.yaml").readText())
        val r = Conformance.run(m, realWorkflows)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("templates/valid.yaml") && it.contains("PASSED") }, "${r.errors}")
    }

    @Test fun `the shipped template is correctly rejected (self-test stays green)`() {
        // packml-unit.yaml uses Int setpoint types -> rejected -> contributes no error
        val r = Conformance.run(realModel, realWorkflows)
        assertFalse(r.errors.any { it.contains("packml-unit.yaml") }, "${r.errors}")
    }

    @Test fun `deleting the canonical primary site+recipe pair is an ERROR (not fail-open)`() {
        val m = freshModelCopy()
        // remove BOTH default-variant files; without a canonical-presence check the run would go GREEN
        assertTrue(File(m, "ot-site.yaml").delete())
        assertTrue(File(m, "recipe-setpoints.yaml").delete())
        val r = Conformance.run(m, realWorkflows)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("canonical ot-site.yaml") }, "${r.errors}")
    }

    @Test fun `valid FSM spec passes conformance`() {
        // the shipped model/fsm/packml-line1.yaml is valid -> no fsm errors
        val r = Conformance.run(realModel, realWorkflows)
        assertFalse(r.errors.any { it.contains("fsm/") }, "${r.errors}")
    }

    @Test fun `FSM with unknown stateNode is an ERROR`() {
        val m = freshModelCopy()
        val f = File(m, "fsm/packml-line1.yaml")
        f.writeText(f.readText().replace("stateNode: line1.stateCurrent", "stateNode: line1.ghostNode"))
        val r = Conformance.run(m, realWorkflows)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("packml-line1.yaml") && it.contains("stateNode") }, "${r.errors}")
    }

    @Test fun `FSM with a bad structure is an ERROR`() {
        val m = freshModelCopy()
        val f = File(m, "fsm/packml-line1.yaml")
        f.writeText(f.readText().replace("from: Idle, to: Execute, command: Start", "from: Nope, to: Execute, command: Start"))
        val r = Conformance.run(m, realWorkflows)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("packml-line1.yaml") && it.contains("Nope") }, "${r.errors}")
    }

    @Test fun `FSM hermod action referencing a non-ot workflow is an ERROR`() {
        val m = freshModelCopy()
        val f = File(m, "fsm/packml-line1.yaml")
        f.writeText(f.readText().replace("workflow: ot-recipe-stage-activate", "workflow: demo-with-greet"))
        val r = Conformance.run(m, realWorkflows)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("packml-line1.yaml") && it.contains("demo-with-greet") }, "${r.errors}")
    }
}
