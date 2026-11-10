package yggdrasil.hermod.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContractTest {
    @Test fun `contract carries §5 load-bearing fields`() {
        val c = BlockContract(
            id = "db.upsert", version = "1.0.0", category = BlockCategory.sink,
            displayName = "DB Upsert", description = "upsert by PK",
            params = listOf(ParamSpec("table", "string", required = true)),
            inputs = listOf(IoSpec("rows", "Record[]")),
            outputs = listOf(IoSpec("written", "Record[]")),
            forwardHandler = "yggdrasil.hermod.blocks.DbUpsertBlock",
            idempotency = IdempotencySpec(IdempotencyStrategy.UPSERT, "row:id"),
            compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.STATIC,
                handler = "yggdrasil.hermod.blocks.DbUpsertBlock#compensate", requiresState = listOf("priorValues", "insertedIds")),
            stateBinding = listOf(StateBindingSpec("priorValues", "prior snapshot"), StateBindingSpec("insertedIds", "newly inserted PKs")),
            retry = RetrySpec(5, 200, 10_000), timeoutMs = 30_000,
            sideEffects = listOf(SideEffect.DB_WRITE), human = HumanSpec(false),
        )
        assertEquals("db.upsert", c.id)
        assertEquals(IdempotencyStrategy.UPSERT, c.idempotency.strategy)
        assertTrue(c.compensation.requiresState.containsAll(listOf("priorValues", "insertedIds")))
    }

    @Test fun `workflow def preserves step order and pins`() {
        val def = WorkflowDef("demo", listOf(
            WorkflowStep("db.read", "1.0.0"),
            WorkflowStep("db.upsert", "1.0.0"),
        ))
        assertEquals(listOf("db.read", "db.upsert"), def.steps.map { it.blockId })
        assertEquals("1.0.0", def.steps.first().pinnedVersion)
    }
}
