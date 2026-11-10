package yggdrasil.hermod.blocks

import yggdrasil.hermod.sdk.*
import kotlin.test.*

class ActuateBlockTest {
    @Test fun `forward records actuation in sink`() {
        val sink = mutableListOf<String>()
        val out = ActuateBlock(sink).forward(BlockInput(rows = listOf(mapOf("id" to "A", "val" to "x"))))
        assertEquals(listOf("ACTUATED"), sink)
        assertEquals(1, out.rows.size)
    }

    @Test fun `compensate is NOOP by default (IRREVERSIBLE)`() {
        val action = ActuateBlock(mutableListOf()).compensate(emptyMap(), CompensationContext())
        assertEquals("NOOP", action.kind)
    }

    @Test fun `forward honors failAtBlockId injection`() {
        assertFailsWith<PermanentBlockFailure> {
            ActuateBlock(mutableListOf()).forward(BlockInput(failAtBlockId = "actuate"))
        }
    }
}
