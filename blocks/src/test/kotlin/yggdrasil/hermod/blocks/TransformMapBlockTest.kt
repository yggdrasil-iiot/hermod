package yggdrasil.hermod.blocks

import yggdrasil.hermod.sdk.*
import kotlin.test.*

class TransformMapBlockTest {
    @Test fun `uppercases the val column`() {
        val out = TransformMapBlock().forward(BlockInput(rows = listOf(
            mapOf("id" to "A", "val" to "abc"),
            mapOf("id" to "B", "val" to "Xy"),
        )))
        assertEquals("ABC", out.rows[0]["val"])
        assertEquals("XY", out.rows[1]["val"])
        assertEquals("A", out.rows[0]["id"], "id preserved")
    }

    @Test fun `forward honors failAtBlockId injection`() {
        assertFailsWith<PermanentBlockFailure> {
            TransformMapBlock().forward(BlockInput(failAtBlockId = "transform.map"))
        }
    }
}
