package yggdrasil.hermod.authoring.assist

import yggdrasil.hermod.opcua.FsmAction
import yggdrasil.hermod.opcua.FsmSpec
import yggdrasil.hermod.opcua.FsmState
import yggdrasil.hermod.opcua.FsmTransition

/**
 * Deterministic LlmAssistPort for tests, keyless runs, and the default bean. Returns specs from a fixed
 * `script` in order (last entry repeats). A script of [invalidSpec, validSpec] exercises the repair loop.
 */
class FakeLlmAssistPort(private val script: List<FsmSpec>) : LlmAssistPort {
    private var i = 0
    init { require(script.isNotEmpty()) { "fake script must be non-empty" } }
    // Return the i-th spec, clamping to the last (which repeats). Cap `i` at lastIndex so it never grows
    // unbounded (a one-sided `minOf(i++, …)` would overflow after ~2.1B calls -> negative index).
    override fun generate(req: LlmAssistRequest): FsmSpec {
        val idx = minOf(i, script.lastIndex)
        if (i < script.lastIndex) i++
        return script[idx]
    }

    companion object {
        /** A fixed, structurally-valid PackML demo draft — the default keyless response. */
        val DEMO: FsmSpec = FsmSpec(
            name = "packml-lineX", unit = "lineX", stateNode = "lineX.stateCurrent",
            states = listOf(FsmState("Idle", 4), FsmState("Execute", 6), FsmState("Aborted", 9)),
            transitions = listOf(
                FsmTransition("loadRecipe", "Idle", "Idle", "LoadRecipe", "hermod", FsmAction("ot-recipe-stage-activate")),
                FsmTransition("start", "Idle", "Execute", "Start", "field", null),
                FsmTransition("abort", "Execute", "Aborted", "Abort", "field", null),
            ),
            version = "v1",
        )
    }
}
