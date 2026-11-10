package yggdrasil.hermod.opcua

import kotlin.test.*

class SiteModelTest {
    private val m = SiteModel.fromClasspath()   // loads /model/ot-site.yaml
    @Test fun `resolves a setpoint node`() {
        val n = m.node("recipe.rpmSetpoint")
        assertEquals("ns=2;s=Recipe/Rpm", n.nodeId); assertEquals("Double", n.type)
        assertEquals(0.0, n.euRange!!.low); assertEquals(3000.0, n.euRange!!.high)
    }
    @Test fun `unknown node throws`() {
        assertFailsWith<IllegalArgumentException> { m.node("recipe.nope") }
    }
    @Test fun `exposes activate command + done node`() {
        assertEquals("ns=2;s=Recipe/ApplyRecipe", m.activate.command.nodeId)
        assertEquals("ns=2;s=Recipe/ApplyDone", m.activate.doneNode.nodeId)
    }
    @Test fun `endpoint is parsed`() { assertEquals("opc.tcp://localhost:48400", m.endpoint) }
    @Test fun `setpoints enumerate all model nodes`() {
        val sp = m.setpoints()
        // line1.stateCurrent is the R4 FSM state read-node added to the canonical ot-site.yaml.
        assertEquals(setOf("recipe.rpmSetpoint", "recipe.tempSetpoint", "line1.stateCurrent", "line2.stateCurrent"), sp.keys)
        assertEquals("ns=2;s=Recipe/Rpm", sp.getValue("recipe.rpmSetpoint").nodeId)
    }
    @Test fun `committed model and policy are valid`() {
        val r = ModelValidator.validate(SiteModel.fromClasspath(), CommandPolicy.fromClasspath())
        assertTrue(r.ok, "committed model invalid: ${r.errors}")
    }
    @Test fun `doneClear parses when present and defaults null when absent`() {
        val withField = SiteModel.parse("""
            endpoint: "opc.tcp://x:1"
            nodes: { r.rpm: { nodeId: "ns=2;s=R/Rpm", type: Double } }
            activate: { command: { nodeId: "ns=2;s=R/Go", type: Method }, doneNode: { nodeId: "ns=2;s=R/Done", type: Boolean }, doneClear: on-release }
        """.trimIndent())
        assertEquals("on-release", withField.activate.doneClear)
        val without = SiteModel.parse("""
            endpoint: "opc.tcp://x:1"
            nodes: { r.rpm: { nodeId: "ns=2;s=R/Rpm", type: Double } }
            activate: { command: { nodeId: "ns=2;s=R/Go", type: Method }, doneNode: { nodeId: "ns=2;s=R/Done", type: Boolean } }
        """.trimIndent())
        assertNull(without.activate.doneClear)
    }
}
