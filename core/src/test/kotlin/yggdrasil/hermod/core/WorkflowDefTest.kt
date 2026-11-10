package yggdrasil.hermod.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkflowDefTest {
    @Test fun `step id defaults to null and wiring to empty`() {
        val s = WorkflowStep("db.read", "1.0.0")
        assertNull(s.id)
        assertEquals(emptyMap(), s.wiring)
    }

    @Test fun `step carries an explicit id and wiring`() {
        val s = WorkflowStep("merge", "1.0.0", id = "join", wiring = mapOf("left" to "b.rows"))
        assertEquals("join", s.id)
        assertEquals("b.rows", s.wiring["left"])
    }
}
