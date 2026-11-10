package yggdrasil.hermod.opcua

import kotlin.test.*

class FsmSpecTest {
    private val valid = """
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
          - { id: complete,   from: Execute, to: Aborted, command: null, driver: field }
    """.trimIndent()

    @Test fun `parses states and transitions`() {
        val f = FsmSpec.parse(valid)
        assertEquals("packml-line1", f.name)
        assertEquals("line1.stateCurrent", f.stateNode)
        assertEquals(3, f.states.size)
        assertEquals(4, f.states.first { it.id == "Idle" }.code)
        val lr = f.transitions.first { it.id == "loadRecipe" }
        assertEquals("hermod", lr.driver)
        assertEquals("ot-recipe-stage-activate", lr.action?.workflow)
        assertNull(f.transitions.first { it.id == "complete" }.command)   // null command parses
    }

    @Test fun `valid spec passes the validator`() {
        assertTrue(FsmValidator.validate(FsmSpec.parse(valid)).ok)
    }

    @Test fun `unknown from-state is an error`() {
        val bad = valid.replace("from: Execute, to: Aborted", "from: Nope, to: Aborted")
        val r = FsmValidator.validate(FsmSpec.parse(bad))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("Nope") }, "${r.errors}")
    }

    @Test fun `hermod transition without action is an error`() {
        val bad = valid.replace(", action: { workflow: ot-recipe-stage-activate } }", " }")
        val r = FsmValidator.validate(FsmSpec.parse(bad))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("loadRecipe") && it.contains("action") }, "${r.errors}")
    }

    @Test fun `field transition WITH action is an error`() {
        val bad = valid.replace(
            "{ id: start,      from: Idle, to: Execute, command: Start, driver: field }",
            "{ id: start, from: Idle, to: Execute, command: Start, driver: field, action: { workflow: ot-recipe-stage-activate } }")
        val r = FsmValidator.validate(FsmSpec.parse(bad))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("start") && it.contains("action") }, "${r.errors}")
    }

    @Test fun `duplicate from+command is an error`() {
        val bad = valid.replace(
            "{ id: start,      from: Idle, to: Execute, command: Start, driver: field }",
            "{ id: start, from: Idle, to: Execute, command: LoadRecipe, driver: field }")   // (Idle, LoadRecipe) now twice
        val r = FsmValidator.validate(FsmSpec.parse(bad))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("duplicate") }, "${r.errors}")
    }

    @Test fun `duplicate transition id is an error`() {
        val bad = valid.replace("{ id: start,      from: Idle, to: Execute, command: Start, driver: field }",
                                "{ id: loadRecipe, from: Idle, to: Execute, command: Start, driver: field }")   // id reused
        val r = FsmValidator.validate(FsmSpec.parse(bad))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("duplicate transition id") }, "${r.errors}")
    }

    @Test fun `version parses when present and defaults to empty when absent`() {
        val withV = FsmSpec.parse(
            "name: x\nunit: line1\nversion: v2\nstateNode: line1.stateCurrent\n" +
            "states: [{id: Idle, code: 4}]\n" +
            "transitions: [{id: t, from: Idle, to: Idle, command: C, driver: field}]\n"
        )
        assertEquals("v2", withV.version)
        val noV = FsmSpec.parse(
            "name: x\nunit: line1\nstateNode: line1.stateCurrent\n" +
            "states: [{id: Idle, code: 4}]\n" +
            "transitions: [{id: t, from: Idle, to: Idle, command: C, driver: field}]\n"
        )
        assertEquals("", noV.version)
    }

    @Test fun `duplicate state code is an error`() {
        val bad = valid.replace("{ id: Execute, code: 6 }", "{ id: Execute, code: 4 }")   // code 4 twice
        val r = FsmValidator.validate(FsmSpec.parse(bad))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("code") }, "${r.errors}")
    }
}
