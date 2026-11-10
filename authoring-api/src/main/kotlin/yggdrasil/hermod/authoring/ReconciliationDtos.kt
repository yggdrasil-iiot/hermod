package yggdrasil.hermod.authoring

/**
 * Inbound reconciliation SIGNAL (R2 inbound-trigger early slice). Carries only WHICH logical nodes
 * drifted — never a target value. hermod resolves the desired value from its own Git canonical
 * (model/recipe-setpoints.yaml) and validates it against its own policy + EURange. An external system
 * can prompt a reconciliation but can never inject an arbitrary setpoint.
 */
data class ReconciliationRequest(
    val reconciliationId: String? = null,
    val nodes: List<String> = emptyList(),
    val source: String? = null,
    val proposalRef: String? = null,
)

data class ReconciliationResponse(
    val runId: String,
    val reconciliationId: String,
    val source: String?,
    val proposalRef: String?,
    val nodes: List<String>,
)
