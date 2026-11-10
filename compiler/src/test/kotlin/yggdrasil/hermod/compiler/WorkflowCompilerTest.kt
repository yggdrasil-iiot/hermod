package yggdrasil.hermod.compiler

import yggdrasil.hermod.core.*
import kotlin.test.*

class WorkflowCompilerTest {
    private fun c(
        id: String, version: String = "1.0.0",
        inputs: List<IoSpec> = listOf(IoSpec("rows", "Record[]")),
        outputs: List<IoSpec> = listOf(IoSpec("rows", "Record[]")),
        rev: Reversibility = Reversibility.REVERSIBLE, kind: CompensationKind = CompensationKind.STATIC,
    ) = BlockContract(
        id = id, version = version, category = BlockCategory.transform,
        inputs = inputs, outputs = outputs, forwardHandler = "x.$id",
        idempotency = IdempotencySpec(IdempotencyStrategy.NONE),
        compensation = CompensationSpec(rev, kind, handler = "x.$id#c"),
        retry = RetrySpec(3, 100, 1000),
    )
    private val palette = mapOf(
        "db.read#1.0.0" to c("db.read", inputs = emptyList()),
        "transform.map#1.0.0" to c("transform.map"),
        "db.upsert#1.2.0" to c("db.upsert", version = "1.2.0", outputs = listOf(IoSpec("written", "Record[]"))),
        "db.upsert#1.5.0" to c("db.upsert", version = "1.5.0", outputs = listOf(IoSpec("written", "Record[]"))),
        "notify.email#1.0.0" to c("notify.email"),
        "io.example.greet#1.0.0" to c("io.example.greet", inputs = emptyList(), outputs = emptyList()),
        "actuate#1.0.0" to c("actuate", rev = Reversibility.IRREVERSIBLE, kind = CompensationKind.NONE),
        "bad.string#1.0.0" to c("bad.string", inputs = listOf(IoSpec("text", "string"))),
        "merge#1.0.0" to c("merge", inputs = listOf(IoSpec("left","Record[]"), IoSpec("right","Record[]")), outputs = listOf(IoSpec("out","Record[]"))),
        "str.src#1.0.0" to c("str.src", inputs = emptyList(), outputs = listOf(IoSpec("s","string"))),
    )
    private fun resolve(id: String, v: String) = palette["$id#$v"]
    private fun resolveSpecLike(id: String, spec: String): BlockContract? {
        val vs = yggdrasil.hermod.core.VersionSpec.parseOrNull(spec) ?: return null
        if (vs is yggdrasil.hermod.core.VersionSpec.Exact) return palette["$id#${vs.v}"]
        val best = palette.keys.filter { it.startsWith("$id#") }
            .mapNotNull { yggdrasil.hermod.core.SemVer.parseOrNull(it.substringAfter("#")) }
            .filter { vs.matches(it) }.maxOrNull() ?: return null
        return palette["$id#$best"]
    }
    private fun def(name: String, vararg steps: Pair<String, String>) =
        WorkflowDef(name, steps.map { WorkflowStep(it.first, it.second) })
    private fun gdef(name: String, vararg steps: WorkflowStep) = WorkflowDef(name, steps.toList())

    @Test fun `happy linear demo compiles with positional wiring`() {
        val ir = WorkflowCompiler.compile(
            def("demo", "db.read" to "1.0.0", "transform.map" to "1.0.0",
                "db.upsert" to "1.2.0", "notify.email" to "1.0.0", "actuate" to "1.0.0"), ::resolve)
        assertEquals(listOf("s0", "s1", "s2", "s3", "s4"), ir.nodes.map { it.nodeId })
        assertTrue(ir.nodes[0].inputs.isEmpty())
        val s3src = ir.nodes[3].inputs.single().source as IrSource.NodeOutput
        assertEquals("s2", s3src.nodeId); assertEquals("written", s3src.outputName)
        assertEquals("rows", ir.nodes[3].inputs.single().inputName)
    }
    @Test fun `skip-through over a 0-output node`() {
        val ir = WorkflowCompiler.compile(
            def("dwg", "db.read" to "1.0.0", "transform.map" to "1.0.0", "db.upsert" to "1.2.0",
                "notify.email" to "1.0.0", "io.example.greet" to "1.0.0", "actuate" to "1.0.0"), ::resolve)
        assertTrue(ir.nodes[4].inputs.isEmpty())
        val actSrc = ir.nodes[5].inputs.single().source as IrSource.NodeOutput
        assertEquals("s3", actSrc.nodeId)
    }
    @Test fun `missing block aggregates a diagnostic`() {
        val e = assertFailsWith<CompileException> { WorkflowCompiler.compile(def("x", "no.such" to "9.9.9"), ::resolve) }
        assertTrue(e.diagnostics.any { it.contains("no.such") && it.contains("9.9.9") })
    }
    @Test fun `mistyped wire is rejected`() {
        val e = assertFailsWith<CompileException> {
            WorkflowCompiler.compile(def("x", "db.read" to "1.0.0", "bad.string" to "1.0.0"), ::resolve) }
        assertTrue(e.diagnostics.any { it.contains("Record[]") && it.contains("string") })
    }
    @Test fun `compile is deterministic (byte-equal canonical serialization)`() {
        val d = def("demo", "db.read" to "1.0.0", "transform.map" to "1.0.0", "actuate" to "1.0.0")
        assertEquals(WorkflowCompiler.canonical(WorkflowCompiler.compile(d, ::resolve)),
                     WorkflowCompiler.canonical(WorkflowCompiler.compile(d, ::resolve)))
    }
    // ADVISORY case (carried from spec review): first node is a 1-input node -> source = WorkflowInput
    @Test fun `first node with an input wires to WorkflowInput`() {
        val ir = WorkflowCompiler.compile(def("x", "transform.map" to "1.0.0", "actuate" to "1.0.0"), ::resolve)
        assertEquals(IrSource.WorkflowInput, ir.nodes[0].inputs.single().source)
    }

    @Test fun `empty workflow is rejected`() {
        val e = assertFailsWith<CompileException> {
            WorkflowCompiler.compile(def("empty"), ::resolve)
        }
        assertTrue(e.diagnostics.any { it.contains("no steps") },
            "Expected diagnostic containing 'no steps', got: ${e.diagnostics}")
    }

    @Test fun `compile resolves latest to highest palette version`() {
        val ir = WorkflowCompiler.compile(def("d", "db.read" to "1.0.0", "db.upsert" to "latest"), ::resolveSpecLike)
        assertEquals("1.5.0", ir.nodes[1].contract.version)
    }

    @Test fun `compile rejects E1 bad ordering via lint`() {
        val e = assertFailsWith<CompileException> {
            WorkflowCompiler.compile(def("bad", "db.read" to "1.0.0", "actuate" to "1.0.0", "db.upsert" to "1.2.0"), ::resolve)
        }
        assertTrue(e.diagnostics.any { it.contains("irreversible-ordering") })
    }

    @Test fun `node id defaults to s-index when step id omitted`() {
        val ir = WorkflowCompiler.compile(def("demo", "db.read" to "1.0.0", "transform.map" to "1.0.0"), ::resolve)
        assertEquals(listOf("s0", "s1"), ir.nodes.map { it.nodeId })
    }
    @Test fun `explicit step id becomes the node id`() {
        val ir = WorkflowCompiler.compile(gdef("demo",
            WorkflowStep("db.read", "1.0.0", id = "src"),
            WorkflowStep("transform.map", "1.0.0", id = "t", wiring = mapOf("rows" to "src.rows")),
        ), ::resolve)
        assertEquals(listOf("src", "t"), ir.nodes.map { it.nodeId })
    }
    @Test fun `duplicate node ids are rejected`() {
        val e = assertFailsWith<CompileException> {
            WorkflowCompiler.compile(gdef("dup",
                WorkflowStep("db.read", "1.0.0", id = "x"),
                WorkflowStep("transform.map", "1.0.0", id = "x", wiring = mapOf("rows" to "x.rows")),
            ), ::resolve)
        }
        assertTrue(e.diagnostics.any { it.contains("duplicate") && it.contains("x") })
    }
    @Test fun `multi-input join wires both inputs by name and type-checks each`() {
        val ir = WorkflowCompiler.compile(gdef("diamond",
            WorkflowStep("db.read", "1.0.0", id = "src"),
            WorkflowStep("transform.map", "1.0.0", id = "b", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("transform.map", "1.0.0", id = "c", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("merge", "1.0.0", id = "join", wiring = mapOf("left" to "b.rows", "right" to "c.rows")),
        ), ::resolve)
        val join = ir.nodes.single { it.nodeId == "join" }
        assertEquals(2, join.inputs.size)
        val byName = join.inputs.associateBy { it.inputName }
        assertEquals(IrSource.NodeOutput("b", "rows"), byName["left"]!!.source)
        assertEquals(IrSource.NodeOutput("c", "rows"), byName["right"]!!.source)
    }
    @Test fun `multi-input node with an unwired input is rejected`() {
        val e = assertFailsWith<CompileException> {
            WorkflowCompiler.compile(gdef("bad",
                WorkflowStep("db.read", "1.0.0", id = "src"),
                WorkflowStep("transform.map", "1.0.0", id = "b", wiring = mapOf("rows" to "src.rows")),
                WorkflowStep("merge", "1.0.0", id = "join", wiring = mapOf("left" to "b.rows")),
            ), ::resolve)
        }
        assertTrue(e.diagnostics.any { it.contains("merge") && it.contains("right") && it.contains("unwired") })
    }
    @Test fun `wire to a non-existent upstream id is rejected`() {
        val e = assertFailsWith<CompileException> {
            WorkflowCompiler.compile(gdef("bad",
                WorkflowStep("db.read", "1.0.0", id = "src"),
                WorkflowStep("transform.map", "1.0.0", id = "b", wiring = mapOf("rows" to "ghost.rows")),
            ), ::resolve)
        }
        assertTrue(e.diagnostics.any { it.contains("ghost") })
    }
    @Test fun `wire to a non-existent upstream output is rejected`() {
        val e = assertFailsWith<CompileException> {
            WorkflowCompiler.compile(gdef("bad",
                WorkflowStep("db.read", "1.0.0", id = "src"),
                WorkflowStep("transform.map", "1.0.0", id = "b", wiring = mapOf("rows" to "src.nope")),
            ), ::resolve)
        }
        assertTrue(e.diagnostics.any { it.contains("src") && it.contains("nope") })
    }
    @Test fun `multi-input type mismatch on one wire is rejected`() {
        val e = assertFailsWith<CompileException> {
            WorkflowCompiler.compile(gdef("bad",
                WorkflowStep("str.src", "1.0.0", id = "s"),
                WorkflowStep("db.read", "1.0.0", id = "src"),
                WorkflowStep("merge", "1.0.0", id = "join", wiring = mapOf("left" to "s.s", "right" to "src.rows")),
            ), ::resolve)
        }
        assertTrue(e.diagnostics.any { it.contains("string") && it.contains("Record[]") })
    }
    @Test fun `forward reference is resolved by topo sort`() {
        val ir = WorkflowCompiler.compile(gdef("fwd",
            WorkflowStep("transform.map", "1.0.0", id = "b", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("db.read", "1.0.0", id = "src"),
        ), ::resolve)
        assertEquals(listOf("src", "b"), ir.nodes.map { it.nodeId })
    }
    @Test fun `cycle is rejected with participating ids`() {
        val e = assertFailsWith<CompileException> {
            WorkflowCompiler.compile(gdef("cyc",
                WorkflowStep("transform.map", "1.0.0", id = "x", wiring = mapOf("rows" to "y.rows")),
                WorkflowStep("transform.map", "1.0.0", id = "y", wiring = mapOf("rows" to "x.rows")),
            ), ::resolve)
        }
        assertTrue(e.diagnostics.any { it.contains("cycle") && it.contains("x") && it.contains("y") })
    }
    @Test fun `topo tie-break follows declaration order`() {
        val ir = WorkflowCompiler.compile(gdef("diamond",
            WorkflowStep("db.read", "1.0.0", id = "src"),
            WorkflowStep("transform.map", "1.0.0", id = "b", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("transform.map", "1.0.0", id = "c", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("merge", "1.0.0", id = "join", wiring = mapOf("left" to "b.rows", "right" to "c.rows")),
        ), ::resolve)
        assertEquals(listOf("src", "b", "c", "join"), ir.nodes.map { it.nodeId })
    }
    @Test fun `back-compat - wireless single-input linear def compiles to positional IR`() {
        val ir = WorkflowCompiler.compile(
            def("demo", "db.read" to "1.0.0", "transform.map" to "1.0.0",
                "db.upsert" to "1.2.0", "notify.email" to "1.0.0", "actuate" to "1.0.0"), ::resolve)
        assertEquals(listOf("s0", "s1", "s2", "s3", "s4"), ir.nodes.map { it.nodeId })
        assertTrue(ir.nodes[0].inputs.isEmpty())
        val s3src = ir.nodes[3].inputs.single().source as IrSource.NodeOutput
        assertEquals("s2", s3src.nodeId); assertEquals("written", s3src.outputName)
        assertEquals("rows", ir.nodes[3].inputs.single().inputName)
    }
    @Test fun `back-compat - first single-input node with no predecessor binds to WorkflowInput`() {
        val ir = WorkflowCompiler.compile(def("x", "transform.map" to "1.0.0"), ::resolve)
        assertEquals(IrSource.WorkflowInput, ir.nodes[0].inputs.single().source)
    }
    @Test fun `back-compat - canonical IR for demo is byte-stable across runs`() {
        val d = def("demo", "db.read" to "1.0.0", "transform.map" to "1.0.0",
            "db.upsert" to "1.2.0", "notify.email" to "1.0.0", "actuate" to "1.0.0")
        assertEquals(WorkflowCompiler.canonical(WorkflowCompiler.compile(d, ::resolve)),
                     WorkflowCompiler.canonical(WorkflowCompiler.compile(d, ::resolve)))
    }
    @Test fun `explicit single-input wiring now resolves to the named upstream output`() {
        val ir = WorkflowCompiler.compile(gdef("w",
            WorkflowStep("db.read", "1.0.0", id = "src"),
            WorkflowStep("transform.map", "1.0.0", id = "t", wiring = mapOf("rows" to "src.rows")),
        ), ::resolve)
        assertEquals(IrSource.NodeOutput("src", "rows"), ir.nodes.single { it.nodeId == "t" }.inputs.single().source)
    }
}
