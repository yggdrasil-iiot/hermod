package yggdrasil.hermod.runtime

import yggdrasil.hermod.compiler.IrNode
import yggdrasil.hermod.core.*
import kotlin.test.Test
import kotlin.test.assertEquals

class CompensationOrderTest {
    private fun node(id: String) = IrNode(
        id,
        BlockContract(id, "1.0.0", BlockCategory.transform, forwardHandler = "x.$id",
            idempotency = IdempotencySpec(IdempotencyStrategy.NONE),
            compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.STATIC, "x#c"),
            retry = RetrySpec(3, 100, 1000)),
        emptyMap(), emptyList())

    @Test fun `walks topo-ordered nodes in reverse, keeping only completed`() {
        val nodes = listOf("src", "b", "c", "join", "sink").map { node(it) }
        val completed = mapOf(
            "b" to BoundComp("b", "db.upsert", "1.2.0", mapOf("ids" to "r1")),
            "c" to BoundComp("c", "db.upsert", "1.2.0", mapOf("ids" to "r2")),
        )
        val order = CompensationOrder.reverseTopological(nodes, completed)
        // reverse topo of {b,c}: c before b (c declared after b -> reverse puts c first)
        assertEquals(listOf("r2", "r1"), order.map { it.boundState["ids"] })
    }

    @Test fun `empty completed yields empty order`() {
        val nodes = listOf("src", "b").map { node(it) }
        assertEquals(emptyList(), CompensationOrder.reverseTopological(nodes, emptyMap()))
    }
}
