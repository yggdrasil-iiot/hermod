package yggdrasil.hermod.authoring

data class ValidateResult(val valid: Boolean, val diagnostics: List<String>, val nodeCount: Int)
data class SaveResponse(val name: String, val version: String)

data class RunSummary(
    val runId: String,
    val name: String,
    val version: String,
    val startedAt: Long,     // epoch millis
    val status: String,      // best-effort live engine status; "UNKNOWN" if the query failed/aged out
    val engine: String,      // which engine started this run (run_index.engine; "temporal" default)
    val awaitingApproval: Boolean = false,   // B1: parked at the human gate
    val compOutcome: String? = null,          // B3: NONE | COMPENSATED | COMP_FAILED (run_index)
)
