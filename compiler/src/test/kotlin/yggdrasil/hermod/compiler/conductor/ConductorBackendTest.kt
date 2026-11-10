package yggdrasil.hermod.compiler.conductor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import yggdrasil.hermod.compiler.*
import yggdrasil.hermod.core.*
import kotlin.test.*

class ConductorBackendTest {
    private val mapper = ObjectMapper().registerKotlinModule()
    private fun node(id: String, blockId: String, outName: String = "rows",
                     rev: Reversibility = Reversibility.REVERSIBLE, kind: CompensationKind = CompensationKind.STATIC,
                     wire: IrSource? = null) = IrNode(
        id,
        BlockContract(blockId, "1.0.0", BlockCategory.transform,
            inputs = if (wire == null) emptyList() else listOf(IoSpec("rows","Record[]")),
            outputs = if (outName.isBlank()) emptyList() else listOf(IoSpec(outName,"Record[]")),
            forwardHandler = "x", idempotency = IdempotencySpec(IdempotencyStrategy.NONE),
            compensation = CompensationSpec(rev, kind, "x#c"), retry = RetrySpec(3,100,1000)),
        emptyMap(),
        if (wire == null) emptyList() else listOf(IrInputWire("rows","Record[]", wire)))

    @Test fun `emits valid conductor json that round-trips into the typed model`() {
        val ir = WorkflowIR("demo", listOf(
            node("s0","db.read"),
            node("s1","db.upsert", outName="written", wire=IrSource.NodeOutput("s0","rows")),
            node("s2","actuate", rev=Reversibility.IRREVERSIBLE, kind=CompensationKind.NONE,
                 wire=IrSource.NodeOutput("s1","written")),
        ))
        val json = ConductorBackend.emit(ir)
        val cwf: ConductorWorkflowDef = mapper.readValue(json)
        assertEquals("demo", cwf.name); assertEquals(1, cwf.version)
        assertEquals(3, cwf.tasks.size)
        assertEquals(listOf("s0","s1","s2"), cwf.tasks.map { it.taskReferenceName })
        assertEquals("WAIT", cwf.tasks[2].type)
        assertEquals("SIMPLE", cwf.tasks[0].type)
        assertEquals("demo-compensation", cwf.failureWorkflow)
        assertTrue(cwf.tasks.all { it.inputParameters["_pinnedVersion"] != null })
        assertEquals("1.0.0", cwf.tasks[0].inputParameters["_pinnedVersion"])
    }
    @Test fun `emit() throws on param key that shadows an input-wire name`() {
        // node "s0" has no wires; "s1" has an input wire named "rows" AND a param key "rows" — collision
        val collidingNode = IrNode(
            "s1",
            BlockContract("db.upsert", "1.0.0", BlockCategory.transform,
                inputs = listOf(IoSpec("rows", "Record[]")),
                outputs = listOf(IoSpec("written", "Record[]")),
                forwardHandler = "x", idempotency = IdempotencySpec(IdempotencyStrategy.NONE),
                compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.STATIC, "x#c"),
                retry = RetrySpec(3, 100, 1000)),
            params = mapOf("rows" to "x"),
            inputs = listOf(IrInputWire("rows", "Record[]", IrSource.NodeOutput("s0", "rows")))
        )
        val ir = WorkflowIR("demo", listOf(node("s0", "db.read"), collidingNode))
        val ex = assertFailsWith<IllegalArgumentException> { ConductorBackend.emit(ir) }
        assertTrue(ex.message!!.contains("shadow"), "expected 'shadow' in: ${ex.message}")
    }
    @Test fun `validate() passes for a well-formed ir and is invoked by emit`() {
        val ir = WorkflowIR("w", listOf(node("s0","db.read")))
        ConductorBackend.validate(ConductorBackend.emit(ir))
    }

    @Test fun `emitBundle is byte-deterministic across two calls`() {
        val ir = WorkflowIR("demo", listOf(
            node("s0","db.read"),
            node("s1","db.upsert", outName="written", wire=IrSource.NodeOutput("s0","rows")),
        ))
        assertEquals(ConductorBackend.emitBundle(ir), ConductorBackend.emitBundle(ir))
    }
    @Test fun `validateCompensation rejects non-reverse order`() {
        // hand-build a compensation def whose tasks are NOT reverse of compensable forward steps
        val bad = """{"name":"demo-compensation","version":1,"tasks":[
          {"name":"compensate","taskReferenceName":"c0","type":"SIMPLE","inputParameters":{"_blockId":"db.read","_pinnedVersion":"1.0.0","_failedWorkflowId":"x"}},
          {"name":"compensate","taskReferenceName":"c1","type":"SIMPLE","inputParameters":{"_blockId":"db.upsert","_pinnedVersion":"1.0.0","_failedWorkflowId":"x"}}],"schemaVersion":2}"""
        val ir = WorkflowIR("demo", listOf(node("s0","db.read"),
            node("s1","db.upsert", outName="written", wire=IrSource.NodeOutput("s0","rows"))))
        assertFailsWith<IllegalArgumentException> { ConductorBackend.validateCompensation(ir, bad) }
    }

    @Test fun `emitCompensation emits compensate tasks in reverse for compensable steps only`() {
        val ir = WorkflowIR("demo", listOf(
            node("s0","db.read"),                                                    // REVERSIBLE/STATIC -> compensable
            node("s1","db.upsert", outName="written", wire=IrSource.NodeOutput("s0","rows")), // compensable
            node("s2","actuate", rev=Reversibility.IRREVERSIBLE, kind=CompensationKind.NONE,  // NOT compensable
                 wire=IrSource.NodeOutput("s1","written")),
        ))
        val comp: ConductorWorkflowDef = mapper.readValue(ConductorBackend.emitCompensation(ir))
        assertEquals("demo-compensation", comp.name)
        assertEquals(listOf("db.upsert","db.read"), comp.tasks.map { it.inputParameters["_blockId"] }) // reverse, actuate excluded
        assertTrue(comp.tasks.all { it.type == "SIMPLE" && it.name == "compensate" })
        assertTrue(comp.tasks.all { it.inputParameters["_failedWorkflowId"] == "\${workflow.input.workflowId}" })
        assertTrue(comp.tasks.all { it.inputParameters["_pinnedVersion"] != null })
    }

    @Test fun `emitTaskDefs has one def per forward blockId plus a fixed compensate def`() {
        val ir = WorkflowIR("demo", listOf(
            node("s0","db.read"),
            node("s1","db.upsert", outName="written", wire=IrSource.NodeOutput("s0","rows")),
        ))
        val defs: List<ConductorTaskDef> = mapper.readValue(ConductorBackend.emitTaskDefs(ir))
        val byName = defs.associateBy { it.name }
        assertEquals(setOf("db.read","db.upsert","compensate"), byName.keys)
        assertEquals(3, byName["db.read"]!!.retryCount)        // from RetrySpec(3,..)
        assertEquals(0, byName["compensate"]!!.retryCount)     // fixed policy
        assertEquals(30L, byName["compensate"]!!.timeoutSeconds)
    }

    @Test fun `forward task carries _outputName so the worker publishes under the wired name`() {
        val ir = WorkflowIR("demo", listOf(
            node("s0","db.read"),                                   // output "rows"
            node("s1","db.upsert", outName="written", wire=IrSource.NodeOutput("s0","rows")),
        ))
        val cwf: ConductorWorkflowDef = mapper.readValue(ConductorBackend.emit(ir))
        assertEquals("rows", cwf.tasks[0].inputParameters["_outputName"])
        assertEquals("written", cwf.tasks[1].inputParameters["_outputName"])
    }

    // ---- v0.3a DAG: a join node has MULTIPLE input wires; each must surface as its own ref. ----
    @Test fun `join task carries multiple upstream wire refs`() {
        fun blk(id: String, inputs: List<IoSpec>, outputs: List<IoSpec>) = BlockContract(
            id, "1.0.0", BlockCategory.transform, inputs = inputs, outputs = outputs,
            forwardHandler = "x", idempotency = IdempotencySpec(IdempotencyStrategy.NONE),
            compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.STATIC, "x#c"),
            retry = RetrySpec(3, 100, 1000))
        val ir = WorkflowIR("diamond", listOf(
            node("src", "db.read"),
            node("b", "transform.map", wire = IrSource.NodeOutput("src", "rows")),
            node("c", "transform.map", wire = IrSource.NodeOutput("src", "rows")),
            IrNode("join",
                blk("merge", listOf(IoSpec("left","Record[]"), IoSpec("right","Record[]")), listOf(IoSpec("out","Record[]"))),
                emptyMap(),
                listOf(
                    IrInputWire("left","Record[]", IrSource.NodeOutput("b","rows")),
                    IrInputWire("right","Record[]", IrSource.NodeOutput("c","rows")))),
        ))
        val cwf: ConductorWorkflowDef = mapper.readValue(ConductorBackend.emit(ir))
        val join = cwf.tasks.single { it.taskReferenceName == "join" }
        assertEquals("\${b.output.rows}", join.inputParameters["left"])
        assertEquals("\${c.output.rows}", join.inputParameters["right"])
    }

    // ---- v0.2d I-3: same blockId with DISAGREEING (retry,timeout,needsWait) must fail loud, not last-wins. ----
    @Test fun `emitTaskDefs is fail-loud when same blockId disagrees on retry-timeout-needsWait`() {
        fun blk(retry: RetrySpec) = BlockContract(
            "conflicting.block", "1.0.0", BlockCategory.transform,
            forwardHandler = "h", idempotency = IdempotencySpec(IdempotencyStrategy.NONE),
            compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.STATIC, "h#c"),
            retry = retry)
        val ir = WorkflowIR("w", listOf(
            IrNode("n0", blk(RetrySpec(2,100,1000)), emptyMap(), emptyList()),
            IrNode("n1", blk(RetrySpec(5,100,1000)), emptyMap(), emptyList()),
        ))
        val e = assertFailsWith<IllegalArgumentException> { ConductorBackend.emitTaskDefs(ir) }
        // assert the actual error contract: names the conflict + the disagreeing nodeIds (not just a generic substring)
        assertTrue(e.message!!.contains("taskdef conflict for blockId 'conflicting.block'"), e.message)
        assertTrue(e.message!!.contains("n0") && e.message!!.contains("n1"), e.message)
    }

    // ---- v0.3c: a fan-out level (>1 node sharing a level) becomes FORK_JOIN + JOIN. ----
    @Test fun `diamond fan-out level emits FORK_JOIN over the parallel nodes and a JOIN barrier`() {
        fun merge() = BlockContract("merge", "1.0.0", BlockCategory.transform,
            inputs = listOf(IoSpec("left","Record[]"), IoSpec("right","Record[]")),
            outputs = listOf(IoSpec("out","Record[]")),
            forwardHandler = "x", idempotency = IdempotencySpec(IdempotencyStrategy.NONE),
            compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.STATIC, "x#c"),
            retry = RetrySpec(3,100,1000))
        val ir = WorkflowIR("diamond", listOf(
            node("src","db.read"),
            node("b","transform.map", wire=IrSource.NodeOutput("src","rows")),
            node("c","transform.map", wire=IrSource.NodeOutput("src","rows")),
            IrNode("join", merge(), emptyMap(), listOf(
                IrInputWire("left","Record[]", IrSource.NodeOutput("b","rows")),
                IrInputWire("right","Record[]", IrSource.NodeOutput("c","rows")))),
            node("sink","db.upsert", outName="written", wire=IrSource.NodeOutput("join","out")),
        ))
        val cwf: ConductorWorkflowDef = mapper.readValue(ConductorBackend.emit(ir))
        assertEquals(listOf("src","fork_L1","join_L1","join","sink"), cwf.tasks.map { it.taskReferenceName })
        val fork = cwf.tasks.single { it.type == "FORK_JOIN" }
        assertEquals("fork_L1", fork.taskReferenceName)
        assertEquals(listOf(listOf("b"), listOf("c")), fork.forkTasks!!.map { branch -> branch.map { it.taskReferenceName } })
        val join = cwf.tasks.single { it.type == "JOIN" }
        assertEquals(listOf("b","c"), join.joinOn)
        assertEquals("SIMPLE", cwf.tasks.single { it.taskReferenceName == "join" }.type)
    }

    @Test fun `JOIN waits on ALL parallel branches even when a downstream consumes only some`() {
        val ir = WorkflowIR("fanout", listOf(
            node("src","db.read"),
            node("p1","transform.map", wire=IrSource.NodeOutput("src","rows")),
            node("p2","transform.map", wire=IrSource.NodeOutput("src","rows")),
            node("p3","transform.map", wire=IrSource.NodeOutput("src","rows")),
        ))
        val cwf: ConductorWorkflowDef = mapper.readValue(ConductorBackend.emit(ir))
        assertEquals(listOf("p1","p2","p3"), cwf.tasks.single { it.type == "JOIN" }.joinOn)
    }

    @Test fun `forked emit is byte-deterministic across two calls`() {
        val ir = WorkflowIR("fanout", listOf(
            node("src","db.read"),
            node("p1","transform.map", wire=IrSource.NodeOutput("src","rows")),
            node("p2","transform.map", wire=IrSource.NodeOutput("src","rows")),
            node("p3","transform.map", wire=IrSource.NodeOutput("src","rows")),
        ))
        assertEquals(ConductorBackend.emit(ir), ConductorBackend.emit(ir))
    }

    @Test fun `linear chain emits NO fork (every level is single-node)`() {
        val ir = WorkflowIR("lin", listOf(
            node("s0","db.read"),
            node("s1","transform.map", wire=IrSource.NodeOutput("s0","rows")),
            node("s2","db.upsert", outName="written", wire=IrSource.NodeOutput("s1","rows")),
        ))
        val cwf: ConductorWorkflowDef = mapper.readValue(ConductorBackend.emit(ir))
        assertTrue(cwf.tasks.none { it.type == "FORK_JOIN" || it.type == "JOIN" })
        assertEquals(listOf("s0","s1","s2"), cwf.tasks.map { it.taskReferenceName })
    }

    @Test fun `forward task carries _nodeId, _slowMs and _slowAtBlockId injection refs`() {
        val ir = WorkflowIR("demo", listOf(node("s0","db.read")))
        val cwf: ConductorWorkflowDef = mapper.readValue(ConductorBackend.emit(ir))
        val t = cwf.tasks.single { it.taskReferenceName == "s0" }
        assertEquals("s0", t.inputParameters["_nodeId"])
        assertEquals("\${workflow.input._slowMs}", t.inputParameters["_slowMs"])
        assertEquals("\${workflow.input._slowAtBlockId}", t.inputParameters["_slowAtBlockId"])
    }

    @Test fun `compensate task carries _nodeId`() {
        val ir = WorkflowIR("demo", listOf(
            node("s0","db.read"),
            node("s1","db.upsert", outName="written", wire=IrSource.NodeOutput("s0","rows")),
        ))
        val comp: ConductorWorkflowDef = mapper.readValue(ConductorBackend.emitCompensation(ir))
        assertEquals(listOf("s1","s0"), comp.tasks.map { it.inputParameters["_nodeId"] })
        ConductorBackend.validateCompensation(ir, ConductorBackend.emitCompensation(ir))
    }

    @Test fun `validate rejects duplicate ref nested inside a fork branch`() {
        val dup = """{"name":"w","version":1,"schemaVersion":2,"tasks":[
          {"name":"db.read","taskReferenceName":"s0","type":"SIMPLE","inputParameters":{"_pinnedVersion":"1.0.0"}},
          {"name":"fork","taskReferenceName":"fork_L1","type":"FORK_JOIN","inputParameters":{},
           "forkTasks":[[{"name":"transform.map","taskReferenceName":"s0","type":"SIMPLE","inputParameters":{"_pinnedVersion":"1.0.0"}}]]}
        ]}"""
        assertFailsWith<IllegalArgumentException> { ConductorBackend.validate(dup) }
    }
}
