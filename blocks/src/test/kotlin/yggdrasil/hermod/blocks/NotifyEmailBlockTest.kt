package yggdrasil.hermod.blocks

import yggdrasil.hermod.sdk.*
import kotlin.test.*

class NotifyEmailBlockTest {
    @Test fun `forward sends and binds sent=true`() {
        val sink = mutableListOf<String>()
        val out = NotifyEmailBlock(sink).forward(BlockInput(rows = listOf(mapOf("id" to "A", "val" to "x"))))
        assertEquals("true", out.boundState["sent"])
        assertEquals(1, sink.size)
        assertTrue(sink[0].startsWith("SENT:"))
    }

    @Test fun `compensate when sent corrects (CONTEXTUAL)`() {
        val sink = mutableListOf<String>()
        val action = NotifyEmailBlock(sink).compensate(mapOf("sent" to "true"), CompensationContext())
        assertEquals("CORRECT", action.kind)
        assertTrue(sink.contains("CORRECTION"))
    }

    @Test fun `compensate when not sent is NOOP`() {
        val sink = mutableListOf<String>()
        val action = NotifyEmailBlock(sink).compensate(emptyMap(), CompensationContext())
        assertEquals("NOOP", action.kind)
        assertTrue(sink.isEmpty())
    }

    @Test fun `forward honors failAtBlockId injection`() {
        assertFailsWith<PermanentBlockFailure> {
            NotifyEmailBlock(mutableListOf()).forward(BlockInput(failAtBlockId = "notify.email"))
        }
    }
}
