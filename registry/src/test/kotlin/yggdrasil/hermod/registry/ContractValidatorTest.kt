package yggdrasil.hermod.registry

import yggdrasil.hermod.core.*
import kotlin.test.*

class ContractValidatorTest {
    private fun base() = BlockContract(
        id = "x", version = "1.0.0", category = BlockCategory.sink, forwardHandler = "H",
        idempotency = IdempotencySpec(IdempotencyStrategy.UPSERT, "row:id"),
        compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.STATIC, "H#c", listOf("priorValues")),
        stateBinding = listOf(StateBindingSpec("priorValues")),
        retry = RetrySpec(5, 200, 10_000), sideEffects = listOf(SideEffect.DB_WRITE), human = HumanSpec(false),
    )

    @Test fun `rule1 requiresState subset of stateBinding - violation is an error`() {
        val bad = base().copy(compensation = base().compensation.copy(requiresState = listOf("priorValues", "missingKey")))
        val r = ContractValidator.validate(bad)
        assertTrue(r.errors.any { it.contains("missingKey") }, "expected error for requiresState not in stateBinding")
    }

    @Test fun `rule2 idempotency NONE yields a warning`() {
        val none = base().copy(idempotency = IdempotencySpec(IdempotencyStrategy.NONE))
        val r = ContractValidator.validate(none)
        assertTrue(r.warnings.any { it.contains("NONE") })
        assertTrue(r.errors.isEmpty(), "NONE is a warning, not an error")
    }

    @Test fun `rule3 mitigatable without handler is an error`() {
        val bad = base().copy(
            compensation = CompensationSpec(Reversibility.MITIGATABLE, CompensationKind.CONTEXTUAL, handler = null, requiresState = emptyList())
        )
        val r = ContractValidator.validate(bad)
        assertTrue(r.errors.any { it.contains("handler") })
    }

    @Test fun `rule3 reversible without compensation handler is an error`() {
        val bad = base().copy(compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.STATIC, handler = null, requiresState = emptyList()))
        val r = ContractValidator.validate(bad)
        assertTrue(r.errors.any { it.contains("handler") })
    }

    @Test fun `rule3 reversible with kind NONE needs no handler - valid`() {
        val noKind = base().copy(
            idempotency = IdempotencySpec(IdempotencyStrategy.NATURAL),
            compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.NONE, handler = null, requiresState = emptyList()),
            stateBinding = emptyList(),
        )
        val r = ContractValidator.validate(noKind)
        assertTrue(r.errors.isEmpty(), "REVERSIBLE/NONE with no handler must be valid (nothing to compensate): ${r.errors}")
    }

    @Test fun `rule3 irreversible needs no handler - valid`() {
        val irr = base().copy(
            idempotency = IdempotencySpec(IdempotencyStrategy.NONE),
            compensation = CompensationSpec(Reversibility.IRREVERSIBLE, CompensationKind.NONE),
            stateBinding = emptyList(),
        )
        val r = ContractValidator.validate(irr)
        assertTrue(r.errors.isEmpty(), "IRREVERSIBLE with no handler must be valid: ${r.errors}")
    }

    // rule4 (WorkflowValidator union via Registry.contains) lives in RegistryTest — it needs a Registry
    // (builtin ∪ Postgres index), not the old in-memory ContractRegistry.
}
