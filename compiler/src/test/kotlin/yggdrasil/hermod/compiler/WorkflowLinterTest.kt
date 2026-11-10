package yggdrasil.hermod.compiler

import yggdrasil.hermod.core.*
import kotlin.test.*

class WorkflowLinterTest {
    private fun n(id: String, idx: Int,
                  rev: Reversibility = Reversibility.REVERSIBLE, kind: CompensationKind = CompensationKind.STATIC,
                  idem: IdempotencyStrategy = IdempotencyStrategy.UPSERT,
                  cat: BlockCategory = BlockCategory.transform,
                  effects: List<SideEffect> = listOf(SideEffect.NONE)) = IrNode(
        "s$idx",
        BlockContract(id, "1.0.0", cat, outputs = listOf(IoSpec("rows","Record[]")), forwardHandler="x",
            idempotency = IdempotencySpec(idem), compensation = CompensationSpec(rev, kind, "x#c"),
            retry = RetrySpec(3,100,1000), sideEffects = effects),
        emptyMap(), emptyList())

    @Test fun `E1 fires when a compensable step follows an IRREVERSIBLE step`() {
        val ir = WorkflowIR("bad", listOf(
            n("actuate", 0, rev = Reversibility.IRREVERSIBLE, kind = CompensationKind.NONE, idem = IdempotencyStrategy.NONE, cat = BlockCategory.external),
            n("db.upsert", 1, cat = BlockCategory.sink, effects = listOf(SideEffect.DB_WRITE)),
        ))
        val d = WorkflowLinter.lint(ir)
        assertTrue(d.any { it.severity == LintSeverity.ERROR && it.rule == "irreversible-ordering" })
    }
    @Test fun `E1 fires on a topo-ordered diamond where irreversible precedes reversible`() {
        // Diamond A -> {B, C} -> D, listed in a valid topological order (A, B, C, D).
        // A is IRREVERSIBLE; the fan-in sink D is compensable (REVERSIBLE/STATIC) and runs after A,
        // so E1 must fire even though ordering came from a topo sort rather than a flat declaration.
        val a = IrNode("s0",
            BlockContract("actuate", "1.0.0", BlockCategory.external, outputs = listOf(IoSpec("rows", "Record[]")),
                forwardHandler = "x", idempotency = IdempotencySpec(IdempotencyStrategy.NONE),
                compensation = CompensationSpec(Reversibility.IRREVERSIBLE, CompensationKind.NONE, "x#c"),
                retry = RetrySpec(3, 100, 1000), sideEffects = listOf(SideEffect.ACTUATION)),
            emptyMap(), listOf(IrInputWire("in", "Record[]", IrSource.WorkflowInput)))
        val b = IrNode("s1",
            BlockContract("enrich.left", "1.0.0", BlockCategory.transform, outputs = listOf(IoSpec("rows", "Record[]")),
                forwardHandler = "x", idempotency = IdempotencySpec(IdempotencyStrategy.UPSERT),
                compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.STATIC, "x#c"),
                retry = RetrySpec(3, 100, 1000), sideEffects = listOf(SideEffect.NONE)),
            emptyMap(), listOf(IrInputWire("in", "Record[]", IrSource.NodeOutput("s0", "rows"))))
        val c = IrNode("s2",
            BlockContract("enrich.right", "1.0.0", BlockCategory.transform, outputs = listOf(IoSpec("rows", "Record[]")),
                forwardHandler = "x", idempotency = IdempotencySpec(IdempotencyStrategy.UPSERT),
                compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.STATIC, "x#c"),
                retry = RetrySpec(3, 100, 1000), sideEffects = listOf(SideEffect.NONE)),
            emptyMap(), listOf(IrInputWire("in", "Record[]", IrSource.NodeOutput("s0", "rows"))))
        val d = IrNode("s3",
            BlockContract("db.upsert", "1.0.0", BlockCategory.sink, outputs = listOf(IoSpec("rows", "Record[]")),
                forwardHandler = "x", idempotency = IdempotencySpec(IdempotencyStrategy.UPSERT),
                compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.STATIC, "x#c"),
                retry = RetrySpec(3, 100, 1000), sideEffects = listOf(SideEffect.DB_WRITE)),
            emptyMap(), listOf(
                IrInputWire("l", "Record[]", IrSource.NodeOutput("s1", "rows")),
                IrInputWire("r", "Record[]", IrSource.NodeOutput("s2", "rows"))))
        val ir = WorkflowIR("diamond", listOf(a, b, c, d))
        val diags = WorkflowLinter.lint(ir)
        assertTrue(diags.any { it.severity == LintSeverity.ERROR && it.rule == "irreversible-ordering" })
    }
    @Test fun `E1 silent when IRREVERSIBLE is last`() {
        val ir = WorkflowIR("ok", listOf(
            n("db.upsert", 0, cat = BlockCategory.sink, effects = listOf(SideEffect.DB_WRITE)),
            n("actuate", 1, rev = Reversibility.IRREVERSIBLE, kind = CompensationKind.NONE, idem = IdempotencyStrategy.NONE, cat = BlockCategory.external),
        ))
        assertFalse(WorkflowLinter.lint(ir).any { it.rule == "irreversible-ordering" })
    }
    @Test fun `W1 fires only for side-effecting non-IRREVERSIBLE NONE`() {
        val ir = WorkflowIR("w", listOf(
            n("risky", 0, idem = IdempotencyStrategy.NONE, cat = BlockCategory.sink, effects = listOf(SideEffect.DB_WRITE)),
            n("pure", 1, idem = IdempotencyStrategy.NONE, effects = listOf(SideEffect.NONE)),
            n("act", 2, rev = Reversibility.IRREVERSIBLE, kind = CompensationKind.NONE, idem = IdempotencyStrategy.NONE, cat = BlockCategory.external, effects = listOf(SideEffect.ACTUATION)),
        ))
        val w1 = WorkflowLinter.lint(ir).filter { it.rule == "idempotency-none" }
        assertEquals(1, w1.size); assertTrue(w1[0].message.contains("risky"))
    }
    @Test fun `W2 fires when no sink`() {
        val ir = WorkflowIR("w", listOf(n("t", 0, cat = BlockCategory.transform)))
        assertTrue(WorkflowLinter.lint(ir).any { it.rule == "no-sink" && it.severity == LintSeverity.WARNING })
    }
    @Test fun `W2 silent when a sink exists`() {
        val ir = WorkflowIR("w", listOf(n("s", 0, cat = BlockCategory.sink, effects = listOf(SideEffect.DB_WRITE))))
        assertFalse(WorkflowLinter.lint(ir).any { it.rule == "no-sink" })
    }
}
