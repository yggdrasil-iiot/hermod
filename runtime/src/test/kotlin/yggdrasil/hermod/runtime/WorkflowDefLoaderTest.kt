package yggdrasil.hermod.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkflowDefLoaderTest {

    @Test
    fun `loads steps mapping block-version keys to WorkflowStep`() {
        val def = WorkflowDefLoader.load(
            """
            name: demo
            steps:
              - { block: db.read, version: "1.0.0" }
              - { block: db.upsert, version: "1.2.0", params: { table: target_rows } }
            """.trimIndent()
        )
        assertEquals("demo", def.name)
        assertEquals(listOf("db.read", "db.upsert"), def.steps.map { it.blockId })
        assertEquals("1.2.0", def.steps[1].pinnedVersion)
        assertEquals(mapOf("table" to "target_rows"), def.steps[1].params)
    }

    @Test
    fun `loads steps with no params defaults to empty map`() {
        val def = WorkflowDefLoader.load(
            """
            name: simple
            steps:
              - { block: actuate, version: "1.0.0" }
            """.trimIndent()
        )
        assertEquals("simple", def.name)
        assertEquals(1, def.steps.size)
        assertEquals("actuate", def.steps[0].blockId)
        assertEquals("1.0.0", def.steps[0].pinnedVersion)
        assertEquals(emptyMap(), def.steps[0].params)
    }

    @Test fun `loads id and wiring for DAG steps`() {
        val def = WorkflowDefLoader.load(
            """
            name: diamond
            steps:
              - { block: db.read, version: "1.0.0", id: src }
              - { block: transform.map, version: "1.0.0", id: b, wiring: { rows: "src.rows" } }
              - { block: merge, version: "1.0.0", id: join, wiring: { left: "b.rows", right: "c.rows" } }
            """.trimIndent()
        )
        assertEquals(listOf("src", "b", "join"), def.steps.map { it.id })
        assertEquals(mapOf("rows" to "src.rows"), def.steps[1].wiring)
        assertEquals(mapOf("left" to "b.rows", "right" to "c.rows"), def.steps[2].wiring)
    }

    @Test fun `id and wiring default when absent`() {
        val def = WorkflowDefLoader.load(
            """
            name: linear
            steps:
              - { block: db.read, version: "1.0.0" }
            """.trimIndent()
        )
        assertNull(def.steps[0].id)
        assertEquals(emptyMap(), def.steps[0].wiring)
    }
}
