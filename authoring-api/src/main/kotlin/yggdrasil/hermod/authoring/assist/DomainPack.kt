package yggdrasil.hermod.authoring.assist

/**
 * Static IIoT/OT domain-knowledge injection for the assist system prompt (design §5). RAG-ready: the
 * exemplar step is behind [ExemplarSource]; Phase A ships [StaticExemplarSource] (fixed repo artifacts),
 * a RetrievalExemplarSource can slot in later with no port/controller change.
 */
object DomainPack {
    /** PackML state model + hermod governance conventions. Authoritative grounding for the model. */
    val SYSTEM_PROMPT: String = """
        You are an assistant that authors hermod FSM specs for industrial (OT/IIoT) equipment.
        Output MUST be a single FSM spec object matching the provided JSON schema — nothing else.

        DOMAIN (PackML unit state model): typical states and their integer state codes are
        Idle=4, Execute=6, Held=11, Complete=17, Aborted=9. Use these codes unless the user specifies others.

        GOVERNANCE CONVENTIONS (hermod):
        - A GOVERNED transition (hermod drives it) has driver="hermod" AND MUST declare action.workflow
          (an ot-* workflow id, e.g. "ot-recipe-stage-activate"). Use hermod for high-consequence,
          human-gateable commands like loading/activating a recipe.
        - A run-tempo / reactive transition has driver="field" and MUST NOT declare an action. Start, Stop,
          Hold, Abort, and automatic transitions (command=null) are field-driven.
        - command=null means a reactive/PLC-driven transition (no operator command).
        - Deny-by-default: only mark a transition hermod-governed when the user clearly intends hermod to drive it.

        STRUCTURE RULES the output must satisfy (a validator will reject violations):
        - every transition's from/to must name a declared state; state ids and codes are unique;
          transition ids are unique and non-blank; (from, command) pairs are unique.

        First think through the states and transitions, THEN emit the spec object.
    """.trimIndent()

    /** User-message framing used only when editing an existing FSM (current spec supplied). */
    val EDIT_INSTRUCTION: String =
        "You are EDITING an existing FSM. Apply the requested change and return the COMPLETE updated FSM " +
        "spec. Preserve every state and transition the instruction does not ask you to change — do NOT drop, " +
        "rename, or alter unmentioned elements. Return the whole spec, not a diff."

    /** The few-shot exemplars to inject (curated, not random). */
    fun exemplars(source: ExemplarSource = StaticExemplarSource): List<String> = source.exemplars()
}

/** RAG-ready seam: returns the FSM-spec exemplars (as YAML/JSON text) to few-shot the model. */
interface ExemplarSource { fun exemplars(): List<String> }

/**
 * Phase A: the repo's own canonical artifacts as fixed exemplars. Anchored to the exact house schema —
 * stronger than synthetic examples. Inlined verbatim to avoid a filesystem dependency at runtime.
 */
object StaticExemplarSource : ExemplarSource {
    // Two contrasting canonical patterns: V1 has a GOVERNED (driver:hermod) transition; V2 is ALL-field
    // (no governed transition — deny-by-default in action). NOTE: model/templates/packml-unit.yaml is a
    // SITE MODEL (OPC-UA nodes), NOT an FSM, so it is deliberately NOT used as an FSM exemplar; and
    // packml-line2.v1.yaml is a near-duplicate of V1 (only the unit differs), so V2 gives real diversity.
    override fun exemplars(): List<String> = listOf(EXEMPLAR_LINE1_V1, EXEMPLAR_LINE1_V2)

    // Verbatim copy of model/fsm/packml-line1.v1.yaml (governed loadRecipe + field transitions).
    // KEEP IN SYNC with that file if the canonical exemplar changes.
    private val EXEMPLAR_LINE1_V1 = """
        name: packml-line1
        unit: line1
        version: v1
        stateNode: line1.stateCurrent
        states:
          - { id: Idle,     code: 4 }
          - { id: Execute,  code: 6 }
          - { id: Held,     code: 11 }
          - { id: Complete, code: 17 }
          - { id: Aborted,  code: 9 }
        transitions:
          - { id: loadRecipe, from: Idle, to: Idle, command: LoadRecipe, driver: hermod, action: { workflow: ot-recipe-stage-activate } }
          - { id: start, from: Idle, to: Execute, command: Start, driver: field }
          - { id: hold, from: Execute, to: Held, command: Hold, driver: field }
          - { id: complete, from: Execute, to: Complete, command: null, driver: field }
          - { id: abort, from: Execute, to: Aborted, command: Abort, driver: field }
    """.trimIndent()

    // Verbatim copy of model/fsm/packml-line1.v2.yaml (ALL field-driven — no governed transition).
    // KEEP IN SYNC with that file.
    private val EXEMPLAR_LINE1_V2 = """
        name: packml-line1
        unit: line1
        version: v2
        stateNode: line1.stateCurrent
        states:
          - { id: Idle,     code: 4 }
          - { id: Execute,  code: 6 }
          - { id: Held,     code: 11 }
          - { id: Complete, code: 17 }
          - { id: Aborted,  code: 9 }
        transitions:
          - { id: start, from: Idle, to: Execute, command: Start, driver: field }
          - { id: hold, from: Execute, to: Held, command: Hold, driver: field }
          - { id: complete, from: Execute, to: Complete, command: null, driver: field }
          - { id: abort, from: Execute, to: Aborted, command: Abort, driver: field }
    """.trimIndent()
}
