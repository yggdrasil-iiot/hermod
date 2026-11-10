package yggdrasil.hermod.opcua

import kotlin.test.Test
import kotlin.test.assertTrue

class DriftDetectorTest {
    private val fsm = FsmSpec(
        name = "packml-line1", unit = "line1", stateNode = "line1.stateCurrent",
        states = listOf(FsmState("Idle", 4), FsmState("Execute", 6), FsmState("Held", 11),
                        FsmState("Complete", 17), FsmState("Aborted", 9)),
        transitions = listOf(
            FsmTransition("loadRecipe", "Idle", "Idle", "LoadRecipe", "hermod", FsmAction("ot-recipe-stage-activate")),
            FsmTransition("start", "Idle", "Execute", "Start", "field", null),
            FsmTransition("hold", "Execute", "Held", "Hold", "field", null),
            FsmTransition("complete", "Execute", "Complete", null, "field", null),
            FsmTransition("abort", "Execute", "Aborted", "Abort", "field", null),
        ),
        version = "v1",
    )
    @Test fun `same code is not drift`() { assertTrue(DriftDetector.detect(fsm, 4, 4) is DriftDecision.Ok) }
    @Test fun `declared transition is ok regardless of driver`() { assertTrue(DriftDetector.detect(fsm, 4, 6) is DriftDecision.Ok) }
    @Test fun `undeclared transition is drift`() {
        val d = DriftDetector.detect(fsm, 6, 4)
        assertTrue(d is DriftDecision.Drift); assertTrue((d as DriftDecision.Drift).reason.contains("Execute -> Idle"))
    }
    @Test fun `unknown observed code is drift`() {
        val d = DriftDetector.detect(fsm, 4, 99)
        assertTrue(d is DriftDecision.Drift); assertTrue((d as DriftDecision.Drift).reason.contains("99"))
    }
    @Test fun `unknown prior code is drift`() { assertTrue(DriftDetector.detect(fsm, 77, 4) is DriftDecision.Drift) }
}
