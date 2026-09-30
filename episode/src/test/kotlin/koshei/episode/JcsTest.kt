package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JcsTest {
    @Test fun `objects are key-sorted, compact, and nested values keep their order`() {
        val node = mapper.readTree("""{"b":"2","a":["x",null,true],"c":{"z":"é","y":"line\nbreak"}}""")
        assertEquals("""{"a":["x",null,true],"b":"2","c":{"y":"line\nbreak","z":"é"}}""", Jcs.canonical(node))
    }

    @Test fun `control characters use the short escapes or lowercase u-escapes`() {
        val node = mapper.readTree("\"a\\t\\u0001\\\"\\\\\"")
        assertEquals("\"a\\t\\u0001\\\"\\\\\"", Jcs.canonical(node))
    }

    @Test fun `numbers are outside the supported subset`() {
        assertFailsWith<IllegalArgumentException> { Jcs.canonical(mapper.readTree("""{"n":1}""")) }
    }

    @Test fun `sha256 is lowercase hex of the UTF-8 bytes`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Jcs.sha256Hex("abc"))
    }
}
