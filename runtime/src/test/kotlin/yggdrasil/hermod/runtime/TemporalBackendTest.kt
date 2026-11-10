package yggdrasil.hermod.runtime

import yggdrasil.hermod.compiler.*
import yggdrasil.hermod.core.*
import kotlin.test.*

class TemporalBackendTest {
    @Test fun `lower yields contracts in IR node order`() {
        val a = mkContract("a"); val b = mkContract("b", outputs = emptyList())
        val ir = WorkflowIR("w", listOf(
            IrNode("s0", a, emptyMap(), emptyList()),
            IrNode("s1", b, emptyMap(), emptyList()),
        ))
        assertEquals(listOf("a", "b"), TemporalBackend.lower(ir).map { it.id })
        assertSame(a, TemporalBackend.lower(ir)[0])
    }
    private fun mkContract(id: String, outputs: List<IoSpec> = listOf(IoSpec("rows","Record[]"))) =
        BlockContract(id, "1.0.0", BlockCategory.transform, outputs = outputs, forwardHandler = "x.$id",
            idempotency = IdempotencySpec(IdempotencyStrategy.NONE),
            compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.STATIC, "x#c"),
            retry = RetrySpec(3,100,1000))
}
