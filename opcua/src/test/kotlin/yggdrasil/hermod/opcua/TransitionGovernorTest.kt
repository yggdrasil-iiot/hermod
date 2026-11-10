package yggdrasil.hermod.opcua

import kotlin.test.*

class TransitionGovernorTest {
    private val fsm = FsmSpec.parse("""
        name: packml-line1
        unit: line1
        stateNode: line1.stateCurrent
        states:
          - { id: Idle,    code: 4 }
          - { id: Execute, code: 6 }
          - { id: Aborted, code: 9 }
        transitions:
          - { id: loadRecipe, from: Idle, to: Idle, command: LoadRecipe, driver: hermod, action: { workflow: ot-recipe-stage-activate } }
          - { id: start,      from: Idle, to: Execute, command: Start, driver: field }
    """.trimIndent())

    @Test fun `legal hermod transition ALLOWs with its workflow`() {
        val d = TransitionGovernor.govern(fsm, 4, "LoadRecipe")   // Idle
        assertIs<GovernDecision.Allow>(d); assertEquals("ot-recipe-stage-activate", d.workflow)
    }

    @Test fun `same command from another state DENYs (state-aware)`() {
        val d = TransitionGovernor.govern(fsm, 9, "LoadRecipe")   // Aborted — no such transition
        assertIs<GovernDecision.Deny>(d); assertTrue(d.reason.contains("Aborted"), d.reason)
    }

    @Test fun `unknown state code DENYs fail-closed`() {
        val d = TransitionGovernor.govern(fsm, 999, "LoadRecipe")
        assertIs<GovernDecision.Deny>(d); assertTrue(d.reason.contains("999"), d.reason)
    }

    @Test fun `field-driven command is not hermod-dispatched (DENY)`() {
        val d = TransitionGovernor.govern(fsm, 4, "Start")   // legal transition but field-driven
        assertIs<GovernDecision.Deny>(d); assertTrue(d.reason.contains("field"), d.reason)
    }

    private val holdFsm = FsmSpec.parse("""
        name: packml-line1
        unit: line1
        stateNode: line1.stateCurrent
        states:
          - { id: Idle,    code: 4 }
          - { id: Execute, code: 6 }
          - { id: Held,    code: 11 }
        transitions:
          - { id: hold,     from: Execute, to: Held, command: Hold,     driver: field }
          - { id: safeHold, from: Execute, to: Held, command: SafeHold, driver: hermod, action: { workflow: ot-safe-hold } }
    """.trimIndent())

    @Test fun `SafeHold from Execute ALLOWs the safe-hold workflow (distinct from field Hold)`() {
        val d = TransitionGovernor.govern(holdFsm, 6, "SafeHold")   // Execute
        assertIs<GovernDecision.Allow>(d); assertEquals("ot-safe-hold", d.workflow)
    }

    @Test fun `SafeHold from a state with no such transition DENYs (Idle)`() {
        val d = TransitionGovernor.govern(holdFsm, 4, "SafeHold")   // Idle — no SafeHold declared
        assertIs<GovernDecision.Deny>(d); assertTrue(d.reason.contains("Idle"), d.reason)
    }

    @Test fun `SafeHold from an unknown state code DENYs fail-closed`() {
        val d = TransitionGovernor.govern(holdFsm, 99, "SafeHold")
        assertIs<GovernDecision.Deny>(d); assertTrue(d.reason.contains("99"), d.reason)
    }

    @Test fun `the operator field Hold stays field-driven (not hermod-dispatched)`() {
        val d = TransitionGovernor.govern(holdFsm, 6, "Hold")   // Execute — legal but field-driven
        assertIs<GovernDecision.Deny>(d); assertTrue(d.reason.contains("field"), d.reason)
    }
}
