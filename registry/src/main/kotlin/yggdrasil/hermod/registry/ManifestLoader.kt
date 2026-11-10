package yggdrasil.hermod.registry

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import yggdrasil.hermod.core.*

object ManifestLoader {
    private val yamlMapper: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    private val jsonMapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /** Parse a manifest from YAML text into a [BlockContract]. */
    fun fromYaml(yaml: String): BlockContract = toContract(yamlMapper.readValue(yaml))

    /** Parse a manifest from ManifestDto-shape JSON into a [BlockContract]. */
    fun fromJson(json: String): BlockContract = toContract(jsonMapper.readValue(json))

    /** Serialize a [BlockContract] as ManifestDto-shape JSON (round-trips with [fromJson]). */
    fun toJson(contract: BlockContract): String = jsonMapper.writeValueAsString(toDto(contract))

    /** v0.1 alias — kept so existing callers (RuntimeAssembly) don't break. */
    fun load(yaml: String): BlockContract = fromYaml(yaml)

    private fun toContract(m: ManifestDto): BlockContract = BlockContract(
        id = m.id, version = m.version, category = parseEnum<BlockCategory>(m.category, "category"),
        displayName = m.displayName ?: "", description = m.description ?: "",
        params = m.params.map { ParamSpec(it.name, it.type, it.required, it.label, it.help, it.default, it.widget, it.enumValues) },
        inputs = m.inputs.map { IoSpec(it.name, it.type, it.label) },
        outputs = m.outputs.map { IoSpec(it.name, it.type, it.label) },
        forwardHandler = m.forward.handler,
        idempotency = IdempotencySpec(parseEnum<IdempotencyStrategy>(m.idempotency.strategy, "idempotency.strategy"), m.idempotency.keyExpression),
        compensation = CompensationSpec(
            parseEnum<Reversibility>(m.compensation.reversibility, "compensation.reversibility"),
            parseEnum<CompensationKind>(m.compensation.kind, "compensation.kind"),
            m.compensation.handler, m.compensation.requiresState,
        ),
        stateBinding = m.stateBinding.map { StateBindingSpec(it.key, it.description ?: "") },
        retry = RetrySpec(m.retry.maxAttempts, m.retry.backoff.initialMs, m.retry.backoff.maxMs),
        timeoutMs = m.timeoutMs,
        sideEffects = m.sideEffects.map { parseEnum<SideEffect>(it, "sideEffects[]") },
        human = HumanSpec(m.human.requireApprovalBefore),
    )

    private fun toDto(c: BlockContract): ManifestDto = ManifestDto(
        id = c.id, version = c.version, category = c.category.name,
        displayName = c.displayName, description = c.description,
        params = c.params.map { ParamDto(it.name, it.type, it.required, it.label, it.help, it.default, it.widget, it.enumValues) },
        inputs = c.inputs.map { IoDto(it.name, it.type, it.label) },
        outputs = c.outputs.map { IoDto(it.name, it.type, it.label) },
        forward = ForwardDto(c.forwardHandler),
        idempotency = IdemDto(c.idempotency.strategy.name, c.idempotency.keyExpression),
        compensation = CompDto(c.compensation.reversibility.name, c.compensation.kind.name, c.compensation.handler, c.compensation.requiresState),
        stateBinding = c.stateBinding.map { StateDto(it.key, it.description) },
        retry = RetryDto(c.retry.maxAttempts, BackoffDto(c.retry.initialMs, c.retry.maxMs)),
        timeoutMs = c.timeoutMs,
        sideEffects = c.sideEffects.map { it.name },
        human = HumanDto(c.human.requireApprovalBefore),
    )

    private inline fun <reified T : Enum<T>> parseEnum(value: String, fieldPath: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Invalid value '$value' for $fieldPath; allowed: ${enumValues<T>().map { it.name }}")
}

// YAML/JSON DTOs — match the manifest shape; defaults make optional fields tolerant.
private data class ManifestDto(
    val id: String, val version: String, val category: String,
    val displayName: String? = null, val description: String? = null,
    val params: List<ParamDto> = emptyList(),
    val inputs: List<IoDto> = emptyList(), val outputs: List<IoDto> = emptyList(),
    val forward: ForwardDto,
    val idempotency: IdemDto,
    val compensation: CompDto,
    val stateBinding: List<StateDto> = emptyList(),
    val retry: RetryDto,
    val timeoutMs: Long = 30_000,
    val sideEffects: List<String> = listOf("NONE"),
    val human: HumanDto = HumanDto(false),
)
private data class ParamDto(
    val name: String, val type: String, val required: Boolean = false,
    val label: String = "", val help: String = "", val default: String? = null,
    val widget: String? = null, val enumValues: List<String> = emptyList(),
)
private data class IoDto(val name: String, val type: String, val label: String = "")
private data class ForwardDto(val handler: String)
private data class IdemDto(val strategy: String, val keyExpression: String? = null)
private data class CompDto(val reversibility: String, val kind: String, val handler: String? = null, val requiresState: List<String> = emptyList())
private data class StateDto(val key: String, val description: String? = null)
private data class BackoffDto(val initialMs: Long, val maxMs: Long)
private data class RetryDto(val maxAttempts: Int, val backoff: BackoffDto)
private data class HumanDto(val requireApprovalBefore: Boolean = false)
