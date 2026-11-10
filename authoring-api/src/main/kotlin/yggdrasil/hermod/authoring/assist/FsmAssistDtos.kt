package yggdrasil.hermod.authoring.assist

import yggdrasil.hermod.opcua.FsmAction
import yggdrasil.hermod.opcua.FsmSpec
import yggdrasil.hermod.opcua.FsmState
import yggdrasil.hermod.opcua.FsmTransition

/** Request body for POST /api/fsm/assist. `context` = the current spec (Phase B forward-compat; unused in Phase A). */
data class FsmAssistRequest(val prompt: String, val context: FsmSpecDto? = null)

/** Wire/structured-output DTO for an FSM spec. Nested but non-recursive. Mirrors the editor's fsmTypes + :opcua FsmSpec. */
data class FsmSpecDto(
    val name: String,
    val unit: String,
    val version: String,
    val stateNode: String,
    val states: List<FsmStateDto>,
    val transitions: List<FsmTransitionDto>,
)
data class FsmStateDto(val id: String, val code: Int)
data class FsmActionDto(val workflow: String)
data class FsmTransitionDto(
    val id: String,
    val from: String,
    val to: String,
    val command: String?,   // null = reactive/PLC-driven; preserved
    val driver: String,     // "hermod" | "field" (validated by FsmValidator, not enforced here)
    val action: FsmActionDto? = null,
)

fun FsmSpec.toDto(): FsmSpecDto = FsmSpecDto(
    name = name, unit = unit, version = version, stateNode = stateNode,
    states = states.map { FsmStateDto(it.id, it.code) },
    transitions = transitions.map { t ->
        FsmTransitionDto(t.id, t.from, t.to, t.command, t.driver, t.action?.let { FsmActionDto(it.workflow) })
    },
)

/**
 * Compensate for structured-output schema-forcing on the real LLM path: the Anthropic JSON schema derived
 * from [FsmSpecDto] marks the nullable-with-default `action`/`command` fields as required, so grammar-
 * constrained decoding forces the model to emit empty placeholders (action:{workflow:""}, command:"") on
 * transitions that have neither. Fold those forced-empties to their semantic absence (null) so the draft
 * matches [yggdrasil.hermod.opcua.FsmValidator]'s own null/blank reasoning. Preserves real values; does NOT hide real
 * errors — a hermod-driven transition left with a blank workflow becomes action=null, surfacing the genuine,
 * repairable "hermod-driven transition must declare action.workflow" error to the repair loop. Collision-
 * neutral for the (from, command) uniqueness check (see the 2026-07-03 normalization design spec §3.1).
 */
fun FsmSpecDto.normalized(): FsmSpecDto = copy(
    transitions = transitions.map { t ->
        t.copy(
            command = t.command?.takeUnless { it.isBlank() },
            action = t.action?.takeUnless { it.workflow.isBlank() },
        )
    },
)

fun FsmSpecDto.toFsmSpec(): FsmSpec = FsmSpec(
    name = name, unit = unit, stateNode = stateNode,
    states = states.map { FsmState(it.id, it.code) },
    transitions = transitions.map { d ->
        FsmTransition(d.id, d.from, d.to, d.command, d.driver, d.action?.let { FsmAction(it.workflow) })
    },
    version = version,
)
