package yggdrasil.hermod.delegation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DelegationPolicyTest {
    private val json = """
        { "default": "deny", "endpoints": [
          { "id": "quality-scorer", "url": "http://x/score", "metric": "qualityScore", "threshold": 0.8, "allow": true } ] }
    """.trimIndent()

    @Test fun `allow-listed endpoint is allowed with accessors`() {
        val p = DelegationPolicy.parse(json)
        assertTrue(p.evaluate("quality-scorer").allowed)
        assertEquals("quality-scorer", p.evaluate("quality-scorer").ruleId)
        assertEquals(0.8, p.thresholdFor("quality-scorer"))
        assertEquals("http://x/score", p.urlFor("quality-scorer"))
        assertEquals("qualityScore", p.metricFor("quality-scorer"))
    }

    @Test fun `unknown endpoint denied by default`() {
        val p = DelegationPolicy.parse(json)
        assertFalse(p.evaluate("rogue-scorer").allowed)
    }

    @Test fun `classpath default loads the shipped canonical policy`() {
        val p = DelegationPolicy.default()
        assertTrue(p.evaluate("quality-scorer").allowed)
    }

    @Test fun `bad default token is rejected`() {
        val bad = """{ "default": "maybe", "endpoints": [] }"""
        try { DelegationPolicy.parse(bad); error("expected IllegalArgumentException") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("deny")) }
    }
}
