package yggdrasil.hermod.authoring.assist

import yggdrasil.hermod.opcua.FsmValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-function tests for FsmSpecDto.normalized() — the fix for structured-output schema-forcing
 * (the Anthropic schema marks nullable action/command as required, so the model emits action:{workflow:""}
 * and command:"" placeholders). No LLM, no DB — deterministic. See the 2026-07-03 normalization design spec.
 */
class FsmAssistNormalizeTest {

    // Mirrors the captured real golden-1 DTO shape (all field transitions carry a forced-empty action;
    // the reactive transition carries a forced-empty command; the governed transition carries a real action).
    private fun forcedEmptyGolden(): FsmSpecDto = FsmSpecDto(
        name = "packml-line1", unit = "line1", version = "v1", stateNode = "line1.stateCurrent",
        states = listOf(
            FsmStateDto("Idle", 4), FsmStateDto("Execute", 6), FsmStateDto("Held", 11),
            FsmStateDto("Complete", 17), FsmStateDto("Aborted", 9),
        ),
        transitions = listOf(
            FsmTransitionDto("loadRecipe", "Idle", "Idle", "LoadRecipe", "hermod", FsmActionDto("ot-recipe-stage-activate")),
            FsmTransitionDto("start", "Idle", "Execute", "Start", "field", FsmActionDto("")),
            FsmTransitionDto("hold", "Execute", "Held", "Hold", "field", FsmActionDto("")),
            FsmTransitionDto("complete", "Execute", "Complete", "", "field", FsmActionDto("")),
            FsmTransitionDto("abort", "Execute", "Aborted", "Abort", "field", FsmActionDto("")),
        ),
    )

    @Test fun `blank-workflow action on a field transition folds to null`() {
        val n = forcedEmptyGolden().normalized()
        assertNull(n.transitions.first { it.id == "start" }.action)
    }

    @Test fun `blank command on a reactive transition folds to null`() {
        val n = forcedEmptyGolden().normalized()
        assertNull(n.transitions.first { it.id == "complete" }.command)
    }

    @Test fun `real governed action is preserved`() {
        val n = forcedEmptyGolden().normalized()
        assertEquals("ot-recipe-stage-activate", n.transitions.first { it.id == "loadRecipe" }.action?.workflow)
    }

    @Test fun `real named command is preserved`() {
        val n = forcedEmptyGolden().normalized()
        assertEquals("Start", n.transitions.first { it.id == "start" }.command)
    }

    @Test fun `normalized forced-empty golden passes FsmValidator`() {
        val errors = FsmValidator.validate(forcedEmptyGolden().normalized().toFsmSpec()).errors
        assertTrue(errors.isEmpty(), "expected no validator errors, got: $errors")
    }

    @Test fun `governed transition left with a blank workflow still surfaces a real repairable error`() {
        val dto = forcedEmptyGolden().copy(
            transitions = listOf(
                FsmTransitionDto("loadRecipe", "Idle", "Idle", "LoadRecipe", "hermod", FsmActionDto("")),
                FsmTransitionDto("start", "Idle", "Execute", "Start", "field", null),
            ),
        )
        val n = dto.normalized()
        assertNull(n.transitions.first { it.id == "loadRecipe" }.action)   // folded to null...
        val errors = FsmValidator.validate(n.toFsmSpec()).errors
        assertTrue(errors.any { it.contains("hermod-driven transition must declare action.workflow") },
            "expected the genuine repairable error, got: $errors")
    }

    @Test fun `normalization is collision-neutral for (from, command) uniqueness`() {
        // two same-from reactive transitions both with a forced-empty command
        val dto = forcedEmptyGolden().copy(
            transitions = listOf(
                FsmTransitionDto("a", "Idle", "Execute", "", "field", null),
                FsmTransitionDto("b", "Idle", "Held", "", "field", null),
            ),
        )
        fun dupCount(spec: yggdrasil.hermod.opcua.FsmSpec) =
            FsmValidator.validate(spec).errors.count { it.contains("duplicate") && it.contains("transition") }
        // "neutral" = the duplicate count is UNCHANGED by normalization, not merely "== 1" afterward.
        // Pre-normalization both are (Idle,"") → one duplicate; post-normalization both are (Idle,null) →
        // still exactly one (message text becomes command='null'; assert on presence, not the ''/null literal).
        val before = dupCount(dto.toFsmSpec())
        val after = dupCount(dto.normalized().toFsmSpec())
        assertEquals(1, before, "pre-normalization should already have one duplicate")
        assertEquals(before, after, "normalization must not change the (from,command) duplicate count")
    }
}
