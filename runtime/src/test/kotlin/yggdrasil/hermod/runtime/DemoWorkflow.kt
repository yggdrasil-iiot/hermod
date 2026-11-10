package yggdrasil.hermod.runtime

import yggdrasil.hermod.core.WorkflowDef
import yggdrasil.hermod.core.WorkflowStep

/**
 * The demo linear workflow used by the runtime tests, mirroring app's eventual demo def.
 * Pinned versions match the shipped manifests (db.upsert is 1.2.0, the rest 1.0.0).
 */
object DemoWorkflow {
    val DEF = WorkflowDef(
        name = "demo",
        steps = listOf(
            WorkflowStep("db.read", "1.0.0"),
            WorkflowStep("transform.map", "1.0.0"),
            WorkflowStep("db.upsert", "1.2.0", params = mapOf("table" to "target_rows")),
            WorkflowStep("notify.email", "1.0.0"),
            WorkflowStep("actuate", "1.0.0"),
        ),
    )
}
