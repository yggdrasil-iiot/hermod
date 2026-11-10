package yggdrasil.hermod.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockInputTest {
    @Test fun `namedInputs defaults empty and back-compat fields unchanged`() {
        val i = BlockInput(rows = listOf(mapOf("id" to "A")))
        assertEquals(emptyMap(), i.namedInputs)
        assertEquals(0L, i.slowMs)
        assertEquals(emptyMap(), i.params)
    }

    @Test fun `namedInputs carries multiple wired inputs by name`() {
        val i = BlockInput(namedInputs = mapOf(
            "left" to listOf(mapOf("id" to "A")),
            "right" to listOf(mapOf("id" to "B")),
        ))
        assertEquals(1, i.namedInputs["left"]!!.size)
        assertEquals("B", i.namedInputs["right"]!!.single()["id"])
    }
}
