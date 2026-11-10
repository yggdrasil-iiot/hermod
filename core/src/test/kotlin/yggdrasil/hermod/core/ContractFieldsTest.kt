package yggdrasil.hermod.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContractFieldsTest {
    @Test fun `ParamSpec presentation fields default to empty so old call sites still compile`() {
        val p = ParamSpec("table", "string", required = true)
        assertEquals("", p.label)
        assertEquals("", p.help)
        assertNull(p.default)
        assertNull(p.widget)
        assertEquals(emptyList(), p.enumValues)
    }

    @Test fun `IoSpec label defaults to empty`() {
        val io = IoSpec("rows", "Record[]")
        assertEquals("", io.label)
    }

    @Test fun `presentation fields are carried when supplied`() {
        val p = ParamSpec("mode", "string", required = true,
            label = "모드", help = "동작 모드", default = "fast", widget = "select", enumValues = listOf("fast", "safe"))
        assertEquals("모드", p.label)
        assertEquals("select", p.widget)
        assertEquals(listOf("fast", "safe"), p.enumValues)
    }
}
