package yggdrasil.hermod.core

import kotlin.test.*

class SemVerTest {
    @Test fun `parse valid and compare`() {
        assertEquals(SemVer(1, 2, 0), SemVer.parse("1.2.0"))
        assertTrue(SemVer.parse("1.2.0") < SemVer.parse("1.10.0"))
        assertTrue(SemVer.parse("2.0.0") > SemVer.parse("1.99.99"))
        assertEquals(SemVer.parse("1.2.0"), SemVer.parse("1.2.0"))
    }
    @Test fun `parse malformed throws or null`() {
        assertFailsWith<IllegalArgumentException> { SemVer.parse("1.2") }
        assertFailsWith<IllegalArgumentException> { SemVer.parse("1.x.0") }
        assertNull(SemVer.parseOrNull("nope"))
        assertEquals(SemVer(1,2,3), SemVer.parseOrNull("1.2.3"))
    }
    @Test fun `VersionSpec parse`() {
        assertEquals(VersionSpec.Latest, VersionSpec.parse("latest"))
        assertEquals(VersionSpec.Caret(SemVer(1,2,0)), VersionSpec.parse("^1.2.0"))
        assertEquals(VersionSpec.Exact(SemVer(1,2,0)), VersionSpec.parse("1.2.0"))
    }
    @Test fun `Latest matches anything, Exact matches only equal`() {
        assertTrue(VersionSpec.Latest.matches(SemVer(0,0,1)))
        assertTrue(VersionSpec.Exact(SemVer(1,2,0)).matches(SemVer(1,2,0)))
        assertFalse(VersionSpec.Exact(SemVer(1,2,0)).matches(SemVer(1,2,1)))
    }
    @Test fun `Caret major-ge-1 matches up to next major`() {
        val c = VersionSpec.Caret(SemVer(1,2,0))
        assertTrue(c.matches(SemVer(1,2,0)))
        assertTrue(c.matches(SemVer(1,9,9)))
        assertFalse(c.matches(SemVer(1,1,9)))
        assertFalse(c.matches(SemVer(2,0,0)))
    }
    @Test fun `Caret 0_y matches up to next minor`() {
        val c = VersionSpec.Caret(SemVer(0,2,0))
        assertTrue(c.matches(SemVer(0,2,5)))
        assertFalse(c.matches(SemVer(0,3,0)))
        assertFalse(c.matches(SemVer(0,1,9)))
    }
    @Test fun `Caret 0_0_z matches only that patch`() {
        val c = VersionSpec.Caret(SemVer(0,0,3))
        assertTrue(c.matches(SemVer(0,0,3)))
        assertFalse(c.matches(SemVer(0,0,4)))
    }
}
