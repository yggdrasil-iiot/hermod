// registry/src/test/kotlin/hermod/registry/WorkflowDefJsonTest.kt
package yggdrasil.hermod.registry

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import yggdrasil.hermod.core.WorkflowDef
import yggdrasil.hermod.core.WorkflowStep
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowDefJsonTest {
    private val mapper = jacksonObjectMapper()

    @Test fun `WorkflowDef round-trips through JSON with wiring and params`() {
        val def = WorkflowDef("diamond", listOf(
            WorkflowStep("db.read", "1.0.0", id = "src", params = mapOf("table" to "source_rows")),
            WorkflowStep("transform.map", "1.0.0", id = "b", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("transform.map", "1.0.0", id = "c", wiring = mapOf("rows" to "src.rows")),
            WorkflowStep("merge", "1.0.0", id = "join", wiring = mapOf("left" to "b.rows", "right" to "c.rows")),
            WorkflowStep("db.upsert", "1.2.0", id = "sink", params = mapOf("table" to "target_rows"), wiring = mapOf("rows" to "join.out")),
        ))
        val json = mapper.writeValueAsString(def)
        val back: WorkflowDef = mapper.readValue(json)
        assertEquals(def, back)
        assertEquals("src.rows", back.steps[1].wiring["rows"])
    }
}
