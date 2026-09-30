package koshei.episode

import com.fasterxml.jackson.databind.node.TextNode
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

    @Test fun `a lone high or low surrogate is rejected`() {
        assertFailsWith<IllegalArgumentException> { Jcs.canonical(TextNode.valueOf("a\uD800b")) }
        assertFailsWith<IllegalArgumentException> { Jcs.canonical(TextNode.valueOf("a\uDC00b")) }
        assertFailsWith<IllegalArgumentException> { Jcs.canonical(TextNode.valueOf("\uD83D")) }
        assertFailsWith<IllegalArgumentException> { Jcs.canonical(mapper.createObjectNode().put("k\uDE00", "v")) }
    }

    @Test fun `isWellFormed accepts pairs and rejects lone surrogates`() {
        assertEquals(true, Jcs.isWellFormed("a😀b"))
        assertEquals(false, Jcs.isWellFormed("a\uD800"))
        assertEquals(false, Jcs.isWellFormed("\uDC00a"))
        assertEquals(false, Jcs.isWellFormed("\uD800\uD800"))
    }

    @Test fun `a valid surrogate pair passes through unescaped`() {
        assertEquals("\"\uD83D\uDE00\"", Jcs.canonical(TextNode.valueOf("\uD83D\uDE00")))
    }

    @Test fun `DEL is not escaped and 0x1f is a lowercase u-escape`() {
        assertEquals("\"\u007f\"", Jcs.canonical(TextNode.valueOf("\u007f")))
        assertEquals("\"\\u001f\"", Jcs.canonical(TextNode.valueOf("\u001f")))
    }

    @Test fun `keys sort by UTF-16 code units, not by code points`() {
        // U+FB01 (code unit 0xFB01) vs U+1F600 (code units 0xD83D 0xDE00): code-unit order puts the emoji first.
        val node = mapper.createObjectNode().put("\uFB01", "a").put("\uD83D\uDE00", "b")
        assertEquals("{\"\uD83D\uDE00\":\"b\",\"\uFB01\":\"a\"}", Jcs.canonical(node))
    }

    @Test fun `sha256 is lowercase hex of the UTF-8 bytes`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Jcs.sha256Hex("abc"))
    }
}
