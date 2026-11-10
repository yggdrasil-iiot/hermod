package yggdrasil.hermod.runtime

import yggdrasil.hermod.compiler.IrNode
import yggdrasil.hermod.compiler.WorkflowIR
import yggdrasil.hermod.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Keyed BoundWorkflow: bind two named WorkflowIR plans; each name reads back its own immutable IR. */
class BoundWorkflowTest {
    private fun contract(id: String) = BlockContract(
        id = id, version = "1.0.0", category = BlockCategory.transform,
        outputs = listOf(IoSpec("rows", "Record[]")),
        forwardHandler = "x.$id",
        idempotency = IdempotencySpec(IdempotencyStrategy.NONE),
        compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.STATIC, "x#c"),
        retry = RetrySpec(3, 100, 1000),
    )

    private fun ir(name: String, vararg ids: String) =
        WorkflowIR(name, ids.map { IrNode(it, contract(it), emptyMap(), emptyList()) })

    @Test fun `bind two plans then read each by name`() {
        val a = ir("a", "a1", "a2"); val b = ir("b", "b1")
        BoundWorkflow.bind("a", a); BoundWorkflow.bind("b", b)
        assertEquals(listOf("a1", "a2"), BoundWorkflow.plan("a").nodes.map { it.nodeId })
        assertEquals(listOf("b1"), BoundWorkflow.plan("b").nodes.map { it.nodeId })
    }

    @Test fun `unbound name errors`() {
        assertFailsWith<IllegalStateException> { BoundWorkflow.plan("never-bound-xyz") }
    }

    @Test fun `boundNames reflects bound keys`() {
        // BoundWorkflow is a process-global object; use a unique versioned key to avoid cross-test coupling.
        val key = "unit-test-wf@1.0.0"
        assertTrue(key !in BoundWorkflow.boundNames())
        BoundWorkflow.bind(key, ir(key, "n1"))      // reuse the file's existing ir() helper
        assertTrue(key in BoundWorkflow.boundNames())
    }
}
