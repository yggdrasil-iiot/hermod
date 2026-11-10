// Conductor workflow-definition model (subset).
// Source: https://conductor-oss.github.io/conductor/documentation/configuration/workflowdef.html
//   (Conductor OSS workflow def schema) — vendored 2026-06-17. Used for HARD round-trip validation of emit().
package yggdrasil.hermod.compiler.conductor

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

@JsonIgnoreProperties(ignoreUnknown = false)
data class ConductorWorkflowDef(
    val name: String,
    val version: Int,
    val tasks: List<ConductorTask>,
    val failureWorkflow: String? = null,
    val schemaVersion: Int = 2,
)

@JsonIgnoreProperties(ignoreUnknown = false)
data class ConductorTask(
    val name: String,
    val taskReferenceName: String,
    val type: String,                 // SIMPLE | WAIT | FORK_JOIN | JOIN ...
    val inputParameters: Map<String, Any?> = emptyMap(),
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val forkTasks: List<List<ConductorTask>>? = null,   // FORK_JOIN branches (each branch = a task list)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val joinOn: List<String>? = null,                   // JOIN: the fork branch task refs to wait on
)

@JsonIgnoreProperties(ignoreUnknown = false)
data class ConductorTaskDef(
    val name: String,
    val retryCount: Int,
    val timeoutSeconds: Long,
    // Conductor HARD-validates 0 < responseTimeoutSeconds < timeoutSeconds at registerTaskDefs time
    // (the client default is 3600, which would exceed our short timeouts and be rejected). Pin it just
    // under timeoutSeconds. (Discovered during v0.2d integration against conductor-standalone 3.15.0.)
    val responseTimeoutSeconds: Long = (timeoutSeconds - 1).coerceAtLeast(1),
    val timeoutPolicy: String = "TIME_OUT_WF",
    val retryLogic: String = "FIXED",
)
