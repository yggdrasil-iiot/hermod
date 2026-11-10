package yggdrasil.hermod.authoring.assist

import yggdrasil.hermod.opcua.FsmSpec
import yggdrasil.hermod.opcua.FsmState
import yggdrasil.hermod.opcua.FsmTransition
import yggdrasil.hermod.opcua.FsmAction
import kotlin.test.Test
import kotlin.test.assertEquals

class FsmAssistMappingTest {
    private val spec = FsmSpec(
        name = "packml-line1", unit = "line1", stateNode = "line1.stateCurrent",
        states = listOf(FsmState("Idle", 4), FsmState("Execute", 6)),
        transitions = listOf(
            FsmTransition("loadRecipe", "Idle", "Idle", "LoadRecipe", "hermod", FsmAction("ot-recipe-stage-activate")),
            FsmTransition("complete", "Execute", "Execute", null, "field", null),
        ),
        version = "v1",
    )

    @Test fun `spec round-trips through the dto`() {
        assertEquals(spec.name, spec.toDto().name)
        // NOTE: FsmSpec is a plain class (no equals/copy) — compare via the DTO, which IS a data class.
        assertEquals(spec.toDto(), spec.toDto().toFsmSpec().toDto())
    }

    @Test fun `dto preserves null command and absent action`() {
        val dto = spec.toDto()
        val complete = dto.transitions.first { it.id == "complete" }
        assertEquals(null, complete.command)
        assertEquals(null, complete.action)
    }
}
