package yggdrasil.hermod.conductor

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.conductor.client.http.ConductorClient
import com.netflix.conductor.client.http.MetadataClient
import com.netflix.conductor.common.metadata.tasks.TaskDef
import com.netflix.conductor.common.metadata.workflow.WorkflowDef
import yggdrasil.hermod.compiler.conductor.ConductorBackend

/**
 * Registers an emitted [ConductorBackend.ConductorBundle] against a Conductor server's metadata API.
 *
 * The bundle's three fields are already-serialized JSON strings (the engine-neutral emit). We deserialize
 * them into the conductor-client's own `WorkflowDef`/`TaskDef` types (field names round-trip:
 * name/version/tasks/taskReferenceName/type/inputParameters/failureWorkflow/schemaVersion for workflows;
 * name/retryCount/timeoutSeconds/timeoutPolicy/retryLogic for taskdefs) and push them via [MetadataClient].
 *
 * Idempotent: taskdefs are registered (overwrite), workflow defs use `updateWorkflowDefs` (overwrite-by-name).
 */
class ConductorDeployer(client: ConductorClient) {
    private val metadata = MetadataClient(client)
    private val mapper = ObjectMapper().findAndRegisterModules()

    fun deploy(bundle: ConductorBackend.ConductorBundle) {
        val taskDefs: List<TaskDef> = mapper.readValue(bundle.taskDefs, object : TypeReference<List<TaskDef>>() {})
        val workflow: WorkflowDef = mapper.readValue(bundle.workflow, WorkflowDef::class.java)
        val compensation: WorkflowDef = mapper.readValue(bundle.compensation, WorkflowDef::class.java)

        // taskdefs first (workflow tasks reference them)
        metadata.registerTaskDefs(taskDefs)
        // overwrite-by-name+version: updateWorkflowDefs upserts, so re-deploy is idempotent
        metadata.updateWorkflowDefs(listOf(workflow, compensation))
    }
}
