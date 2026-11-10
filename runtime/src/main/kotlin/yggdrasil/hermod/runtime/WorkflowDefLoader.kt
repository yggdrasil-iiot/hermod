package yggdrasil.hermod.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import yggdrasil.hermod.core.WorkflowDef
import yggdrasil.hermod.core.WorkflowStep

/**
 * Loads a declarative workflow from YAML into a [WorkflowDef].
 *
 * YAML shape:
 * ```yaml
 * name: my-workflow
 * steps:
 *   - { block: db.read, version: "1.0.0" }
 *   - { block: db.upsert, version: "1.2.0", params: { table: target_rows } }
 * ```
 *
 * The DTO uses `block`/`version` keys (human-readable YAML) which map to
 * [WorkflowStep.blockId]/[WorkflowStep.pinnedVersion] via the internal [StepDto].
 * REF: registry/.../ManifestLoader.kt for the YAML-mapper + DTO idiom.
 */
object WorkflowDefLoader {
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    private data class DefDto(val name: String, val steps: List<StepDto>)
    private data class StepDto(
        val block: String,
        val version: String,
        val id: String? = null,
        val params: Map<String, String> = emptyMap(),
        val wiring: Map<String, String> = emptyMap(),
    )

    fun load(yaml: String): WorkflowDef {
        val d: DefDto = mapper.readValue(yaml)
        return WorkflowDef(d.name, d.steps.map { WorkflowStep(it.block, it.version, it.id, it.params, it.wiring) })
    }
}
