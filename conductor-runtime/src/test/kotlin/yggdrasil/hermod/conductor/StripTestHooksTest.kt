package yggdrasil.hermod.conductor

import kotlin.test.Test
import kotlin.test.assertEquals

class StripTestHooksTest {
    @Test fun `drops exactly the three test-only hook keys, preserves the rest`() {
        val input = mapOf(
            "rows" to emptyList<Any?>(),
            "workflowId" to "",
            "_failAtBlockId" to "transform.map",
            "_slowMs" to "500",
            "_slowAtBlockId" to "db.upsert",
        )
        val clean = stripTestHooks(input)
        assertEquals(mapOf("rows" to emptyList<Any?>(), "workflowId" to ""), clean)
    }

    @Test fun `is a no-op when no hooks are present`() {
        val input = mapOf<String, Any?>("rows" to emptyList<Any?>(), "workflowId" to "wf-1")
        assertEquals(input, stripTestHooks(input))
    }
}
