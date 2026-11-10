package yggdrasil.hermod.opcua

import kotlin.test.*

class CommandPolicyTest {
    private val p = CommandPolicy.fromClasspath()
    @Test fun `allows a node with an allow rule`() {
        val d = p.evaluate("recipe.rpmSetpoint"); assertTrue(d.allowed); assertEquals("rpm-ok", d.ruleId)
    }
    @Test fun `denies by default an unlisted node`() {
        val d = p.evaluate("recipe.secretValve"); assertFalse(d.allowed); assertEquals(null, d.ruleId)
    }
    @Test fun `first match wins`() {
        val d = p.evaluate("recipe.tempSetpoint"); assertTrue(d.allowed); assertEquals("temp-ok", d.ruleId)
    }

    @Test fun `exposes rules and default for validation`() {
        val p = CommandPolicy.fromClasspath()
        assertEquals(setOf("recipe.rpmSetpoint", "recipe.tempSetpoint"), p.rules().map { it.node }.toSet())
        assertTrue(p.denyByDefault)   // shipped policy default is "deny"
    }
    @Test fun `parse rejects an unknown default token (fail-closed at load)`() {
        // B-1: parse() must NOT silently collapse a bad default to allow-all. Reject deny|allow only.
        assertFailsWith<IllegalArgumentException> {
            CommandPolicy.parse("""{"default":"nope","rules":[]}""")
        }
    }
    @Test fun `parse accepts allow as a valid default`() {
        val p = CommandPolicy.parse("""{"default":"allow","rules":[]}""")
        assertFalse(p.denyByDefault)
    }
}
