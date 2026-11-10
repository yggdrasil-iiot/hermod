package yggdrasil.hermod.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessagesTest {
    @Test fun `slowAtBlockId defaults null and back-compat fields unchanged`() {
        val i = WorkflowInput()
        assertNull(i.slowAtBlockId)
        assertEquals(0L, i.slowMs)
        assertEquals("demo", i.workflowName)
    }

    @Test fun `slowAtBlockId targets a specific block id`() {
        val i = WorkflowInput(slowMs = 500, slowAtBlockId = "transform.map")
        assertEquals("transform.map", i.slowAtBlockId)
        assertEquals(500L, i.slowMs)
    }
}
