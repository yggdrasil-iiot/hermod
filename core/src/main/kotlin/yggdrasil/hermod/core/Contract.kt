package yggdrasil.hermod.core

enum class IdempotencyStrategy { NONE, KEY_DEDUP, UPSERT, NATURAL }
enum class Reversibility { REVERSIBLE, MITIGATABLE, IRREVERSIBLE }
enum class CompensationKind { STATIC, CONTEXTUAL, NONE }
enum class SideEffect { DB_WRITE, EXTERNAL_CALL, MESSAGE_SEND, ACTUATION, NONE }
enum class BlockCategory { source, transform, sink, control, external }

data class ParamSpec(
    val name: String,
    val type: String,
    val required: Boolean = false,
    val label: String = "",                       // operator-facing field label
    val help: String = "",                        // hint text
    val default: String? = null,                  // prefilled value
    val widget: String? = null,                   // text|number|select|secret (canvas hint)
    val enumValues: List<String> = emptyList(),   // choices when widget == "select"
)
data class IoSpec(val name: String, val type: String, val label: String = "")  // label = port display name
data class IdempotencySpec(val strategy: IdempotencyStrategy, val keyExpression: String? = null)
data class CompensationSpec(
    val reversibility: Reversibility,
    val kind: CompensationKind,
    val handler: String? = null,
    val requiresState: List<String> = emptyList(),
)
data class StateBindingSpec(val key: String, val description: String = "")
data class RetrySpec(val maxAttempts: Int, val initialMs: Long, val maxMs: Long)
data class HumanSpec(val requireApprovalBefore: Boolean)

data class BlockContract(
    val id: String,
    val version: String,
    val category: BlockCategory,
    val displayName: String = "",      // carried-but-inert in v0.1 (operator palette metadata; §5 note)
    val description: String = "",      // carried-but-inert
    val params: List<ParamSpec> = emptyList(),
    val inputs: List<IoSpec> = emptyList(),
    val outputs: List<IoSpec> = emptyList(),
    val forwardHandler: String,
    val idempotency: IdempotencySpec,
    val compensation: CompensationSpec,
    val stateBinding: List<StateBindingSpec> = emptyList(),
    val retry: RetrySpec,
    val timeoutMs: Long = 30_000,
    val sideEffects: List<SideEffect> = listOf(SideEffect.NONE),
    val human: HumanSpec = HumanSpec(false),
)
