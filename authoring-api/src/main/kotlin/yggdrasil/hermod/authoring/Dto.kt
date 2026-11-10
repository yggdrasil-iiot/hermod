package yggdrasil.hermod.authoring

data class PortCard(val name: String, val type: String, val label: String)
data class ParamCard(val name: String, val type: String, val required: Boolean,
                     val label: String, val help: String, val default: String?, val widget: String?, val enumValues: List<String>)
data class PaletteCard(
    val id: String, val latestVersion: String, val versions: List<String>,
    val category: String, val displayName: String, val description: String, val risk: String,
    val inputs: List<PortCard>, val outputs: List<PortCard>, val params: List<ParamCard>, val complete: Boolean,
)
data class BlockRow(val card: PaletteCard, val deprecated: Boolean, val diagnostics: List<Map<String, String>>)

/** Response of POST /api/contracts/validate: runtime-safety result (ContractValidator) + canvas-readiness. */
data class ValidationResponse(
    val valid: Boolean, val errors: List<String>,
    val readiness: List<Map<String, String>>, val complete: Boolean, val risk: String,
)

/** Response of POST /api/publish. */
data class PublishResponse(val ok: Boolean, val errors: List<String> = emptyList())
