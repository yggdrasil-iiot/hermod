package yggdrasil.hermod.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplyPortTypesTest {
    @Test fun `read result carries value and good flag`() {
        val r = ReadResult(value = "1500", good = true)
        assertEquals("1500", r.value); assertTrue(r.good)
    }
    @Test fun `apply outcome carries ok and detail`() {
        val o = ApplyOutcome(ok = false, detail = "read-back mismatch")
        assertEquals("read-back mismatch", o.detail); assertEquals(false, o.ok)
    }
    @Test fun `fromToken maps known kebab tokens, null otherwise`() {
        assertEquals(DoneClearMode.ON_RELEASE, DoneClearMode.fromToken("on-release"))
        assertEquals(DoneClearMode.EXPLICIT_RESET, DoneClearMode.fromToken("explicit-reset"))
        assertEquals(DoneClearMode.MASTER_CLEARS, DoneClearMode.fromToken("master-clears"))
        assertNull(DoneClearMode.fromToken(null))
        assertNull(DoneClearMode.fromToken("On-Release"))   // exact-match, fail-closed on wrong case
        assertNull(DoneClearMode.fromToken("nonsense"))
    }
}
