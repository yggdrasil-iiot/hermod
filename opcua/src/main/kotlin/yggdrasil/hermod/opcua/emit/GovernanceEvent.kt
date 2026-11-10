package yggdrasil.hermod.opcua.emit

/** One governed setpoint as it appears in an emitted event. */
data class GovernedNode(
    val logicalKey: String,   // e.g. "recipe.rpmSetpoint" -> metric "Setpoint/recipe.rpmSetpoint"
    val value: Double?,       // confirmed/restored value, or desired canonical for STAGED
    val outcome: String,      // real AuditOutcome name, or synthetic "STAGED"
    val opcuaNode: String?,   // resolved NodeId
)

/** Header + governed nodes for one NDATA. Consumed by SpbNodeCodec. */
data class GovernanceEvent(
    val type: String,         // RECONCILING | CONFIRMED | RECON_FAILED
    val runId: String,
    val workflow: String,     // "name:version"
    val engine: String,
    val status: String,       // RAW terminal status, or the parked marker for RECONCILING
    val compOutcome: String,  // NONE | COMPENSATED | COMP_FAILED
    val atMillis: Long,
    val nodes: List<GovernedNode>,
    val defRef: String? = null,   // ③ version reference: git SHA of the canonical that governed this run
)
