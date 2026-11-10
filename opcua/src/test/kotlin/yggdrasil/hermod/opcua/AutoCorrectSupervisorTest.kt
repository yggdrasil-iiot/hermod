package yggdrasil.hermod.opcua

import kotlin.test.*

class AutoCorrectSupervisorTest {
    // line1 FSM: Idle(4) -Start-> Execute(6) [field]; Execute -Hold-> Held(11) [field];
    //            Execute -SafeHold-> Held [hermod, ot-safe-hold]. Held has NO outgoing transition.
    private val fsm = FsmSpec.parse("""
        name: packml-line1
        unit: line1
        stateNode: line1.stateCurrent
        states: [{id: Idle, code: 4}, {id: Execute, code: 6}, {id: Held, code: 11}]
        transitions:
          - { id: start,    from: Idle,    to: Execute, command: Start,    driver: field }
          - { id: hold,     from: Execute, to: Held,    command: Hold,     driver: field }
          - { id: safeHold, from: Execute, to: Held,    command: SafeHold, driver: hermod, action: { workflow: ot-safe-hold } }
    """.trimIndent())

    private val observations = mutableListOf<String>()   // "unit:from->to:verdict"
    private fun record(): (String, Int?, Int, String, String) -> Unit =
        { u, f, t, v, _ -> observations.add("$u:$f->$t:$v") }

    private fun sweep(units: List<String>, live: Map<String, Int?>, prior: Map<String, Int?>,
                      resolve: (String) -> FsmSpec? = { fsm }) =
        AutoCorrectSupervisor.sweep(units, readState = { live[it.unit] }, resolveFsm = resolve,
            lastState = { prior[it] }, recordObservation = record())

    @Test fun `baseline when no prior`() {
        val a = sweep(listOf("line1"), mapOf("line1" to 4), emptyMap())
        assertIs<AutoCorrectAction.Baseline>(a[0]); assertEquals(4, (a[0] as AutoCorrectAction.Baseline).code)
        assertEquals(listOf("line1:null->4:BASELINE"), observations)
    }
    @Test fun `declared move is Ok`() {
        val a = sweep(listOf("line1"), mapOf("line1" to 6), mapOf("line1" to 4))  // Idle->Execute declared
        assertIs<AutoCorrectAction.Ok>(a[0]); assertEquals("line1:4->6:OK", observations.single())
    }
    @Test fun `undeclared move into Execute is DriftCorrectable (SafeHold available)`() {
        val a = sweep(listOf("line1"), mapOf("line1" to 6), mapOf("line1" to 11)) // Held->Execute undeclared
        val d = assertIs<AutoCorrectAction.DriftCorrectable>(a[0])
        assertEquals("ot-safe-hold", d.workflow); assertEquals(11, d.from); assertEquals(6, d.to)
        assertEquals("line1:11->6:DRIFT", observations.single())
    }
    @Test fun `unknown observed state is DriftBlocked (govern DENY)`() {
        val a = sweep(listOf("line1"), mapOf("line1" to 99), mapOf("line1" to 6))
        val d = assertIs<AutoCorrectAction.DriftBlocked>(a[0]); assertTrue(d.governReason.contains("99"), d.governReason)
    }
    // IMPORTANT: readState receives the RESOLVED FsmSpec (per §2.1), so `it.unit` is the SPEC's unit field.
    // "unreadable" must resolve to its OWN spec (distinct unit) so readState can return null for it — if it
    // resolved to `fsm` (unit "line1") the closure could never distinguish it. Do NOT change the supervisor
    // signature: `readState: (FsmSpec) -> Int?` is the authoritative contract (keeps the callers + 0-registry).
    private val fsmU = FsmSpec.parse("name: u\nunit: unreadable\nstateNode: u.s\nstates: []\ntransitions: []\n")
    @Test fun `no active FSM or unreadable state is Skipped, and one bad unit does not abort the sweep`() {
        val a = AutoCorrectSupervisor.sweep(listOf("gone", "unreadable", "line1"),
            readState = { if (it.unit == "unreadable") null else 4 },
            resolveFsm = { when (it) { "gone" -> null; "unreadable" -> fsmU; else -> fsm } },
            lastState = { null }, recordObservation = record())
        assertIs<AutoCorrectAction.Skipped>(a[0])   // gone: no FSM
        assertIs<AutoCorrectAction.Skipped>(a[1])   // unreadable: null read
        assertIs<AutoCorrectAction.Baseline>(a[2])  // line1 still processed
    }
}
