package yggdrasil.hermod.opcua

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CanonicalSetpointsTest {

    private fun model() = SiteModel.parse(
        """
        endpoint: "opc.tcp://localhost:48400"
        nodes:
          recipe.rpmSetpoint:  { nodeId: "ns=2;s=Recipe/Rpm",  type: Double, euRange: { low: 0, high: 3000 } }
          recipe.tempSetpoint: { nodeId: "ns=2;s=Recipe/Temp", type: Double, euRange: { low: 0, high: 450  } }
        activate:
          command:  { nodeId: "ns=2;s=Recipe/ApplyRecipe", type: Method }
          doneNode: { nodeId: "ns=2;s=Recipe/ApplyDone", type: Boolean }
        """.trimIndent()
    )

    private fun policy() = CommandPolicy.parse(
        """{ "default": "deny", "rules": [
            { "id": "rpm-ok",  "node": "recipe.rpmSetpoint",  "allow": true },
            { "id": "temp-ok", "node": "recipe.tempSetpoint", "allow": true } ] }"""
    )

    private val good = """
        endpoint: "opc.tcp://localhost:48400"
        setpoints:
          recipe.rpmSetpoint:  { nodeId: "ns=2;s=Recipe/Rpm",  desired: 1500, tolerance: 1.0 }
          recipe.tempSetpoint: { nodeId: "ns=2;s=Recipe/Temp", desired: 200,  tolerance: 1.0 }
    """.trimIndent()

    @Test fun `parses and resolves by key`() {
        val c = CanonicalSetpoints.parse(good, model(), policy())
        assertEquals("opc.tcp://localhost:48400", c.endpoint)
        assertEquals(1500.0, c.byKey("recipe.rpmSetpoint")!!.desired)
        assertEquals("ns=2;s=Recipe/Rpm", c.byKey("recipe.rpmSetpoint")!!.nodeId)
        assertEquals(1.0, c.byKey("recipe.rpmSetpoint")!!.tolerance)
        assertNull(c.byKey("recipe.unknown"))
    }

    @Test fun `fail-closed when node not in site model`() {
        val bad = good + "\n  recipe.ghost: { nodeId: \"ns=2;s=Recipe/Ghost\", desired: 1, tolerance: 1.0 }"
        val e = assertFailsWith<IllegalStateException> { CanonicalSetpoints.parse(bad, model(), policy()) }
        assert(e.message!!.contains("recipe.ghost"))
    }

    @Test fun `fail-closed on nodeId mismatch vs site model`() {
        val bad = good.replace("ns=2;s=Recipe/Rpm", "ns=2;s=Recipe/WRONG")
        assertFailsWith<IllegalStateException> { CanonicalSetpoints.parse(bad, model(), policy()) }
    }

    @Test fun `fail-closed when desired out of EURange`() {
        val bad = good.replace("desired: 1500", "desired: 9999")
        val e = assertFailsWith<IllegalStateException> { CanonicalSetpoints.parse(bad, model(), policy()) }
        assert(e.message!!.contains("EURange"))
    }

    @Test fun `fail-closed when node denied by policy`() {
        val denyTemp = CommandPolicy.parse(
            """{ "default": "deny", "rules": [ { "id": "rpm-ok", "node": "recipe.rpmSetpoint", "allow": true } ] }"""
        )
        assertFailsWith<IllegalStateException> { CanonicalSetpoints.parse(good, model(), denyTemp) }
    }

    @Test fun `shipped model recipe-setpoints loads against shipped ot-site + policy`() {
        val yaml = CanonicalSetpointsTest::class.java
            .getResourceAsStream("/model/recipe-setpoints.yaml")!!.bufferedReader().use { it.readText() }
        val c = CanonicalSetpoints.parse(yaml, SiteModel.fromClasspath(), CommandPolicy.fromClasspath())
        assertEquals(setOf("recipe.rpmSetpoint", "recipe.tempSetpoint"), c.keys())
    }
}
