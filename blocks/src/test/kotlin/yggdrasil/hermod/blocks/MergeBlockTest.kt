package yggdrasil.hermod.blocks

import yggdrasil.hermod.sdk.BlockInput
import yggdrasil.hermod.sdk.PermanentBlockFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MergeBlockTest {
    @Test fun `concatenates left then right deterministically`() {
        val out = MergeBlock().forward(BlockInput(namedInputs = mapOf(
            "left" to listOf(mapOf("id" to "A"), mapOf("id" to "B")),
            "right" to listOf(mapOf("id" to "C")),
        )))
        assertEquals(listOf("A", "B", "C"), out.rows.map { it["id"] })
    }

    @Test fun `missing side is treated as empty`() {
        val out = MergeBlock().forward(BlockInput(namedInputs = mapOf(
            "left" to listOf(mapOf("id" to "A")),
        )))
        assertEquals(listOf("A"), out.rows.map { it["id"] })
    }

    @Test fun `forward honors failAtBlockId injection`() {
        assertFailsWith<PermanentBlockFailure> {
            MergeBlock().forward(BlockInput(failAtBlockId = "merge"))
        }
    }
}
