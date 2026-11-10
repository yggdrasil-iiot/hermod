package yggdrasil.hermod.opcua

import kotlin.test.*

class ModelValidatorTest {
    private val goodModel = SiteModel.fromClasspath()      // committed ot-site.yaml
    private val goodPolicy = CommandPolicy.fromClasspath()

    @Test fun `valid committed model+policy passes`() {
        val r = ModelValidator.validate(goodModel, goodPolicy)
        assertTrue(r.ok, r.errors.toString()); assertTrue(r.warnings.isEmpty())
    }
    @Test fun `non-Double setpoint node is an error`() {
        val m = SiteModel.parse("""
            endpoint: "opc.tcp://x:1"
            nodes: { recipe.rpm: { nodeId: "ns=2;s=R/Rpm", type: Int } }
            activate: { command: { nodeId: "ns=2;s=R/Go", type: Method }, doneNode: { nodeId: "ns=2;s=R/Done", type: Boolean } }
        """.trimIndent())
        val r = ModelValidator.validateModel(m)
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("recipe.rpm") && it.contains("Double") })
    }
    @Test fun `bad nodeId form is an error`() {
        val m = SiteModel.parse("""
            endpoint: "opc.tcp://x:1"
            nodes: { recipe.rpm: { nodeId: "Recipe/Rpm", type: Double } }
            activate: { command: { nodeId: "ns=2;s=R/Go", type: Method }, doneNode: { nodeId: "ns=2;s=R/Done", type: Boolean } }
        """.trimIndent())
        val r = ModelValidator.validateModel(m)
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("recipe.rpm") && it.contains("malformed") })
    }
    @Test fun `inverted euRange is an error`() {
        val m = SiteModel.parse("""
            endpoint: "opc.tcp://x:1"
            nodes: { recipe.rpm: { nodeId: "ns=2;s=R/Rpm", type: Double, euRange: { low: 3000, high: 0 } } }
            activate: { command: { nodeId: "ns=2;s=R/Go", type: Method }, doneNode: { nodeId: "ns=2;s=R/Done", type: Boolean } }
        """.trimIndent())
        val r = ModelValidator.validateModel(m)
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("recipe.rpm") && it.contains("euRange") })
    }
    @Test fun `activate command must be Method and done must be Boolean`() {
        val m = SiteModel.parse("""
            endpoint: "opc.tcp://x:1"
            nodes: { recipe.rpm: { nodeId: "ns=2;s=R/Rpm", type: Double } }
            activate: { command: { nodeId: "ns=2;s=R/Go", type: Boolean }, doneNode: { nodeId: "ns=2;s=R/Done", type: Method } }
        """.trimIndent())
        val r = ModelValidator.validateModel(m)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("activate.command") && it.contains("Method") })
        assertTrue(r.errors.any { it.contains("activate.doneNode") && it.contains("Boolean") })
    }
    @Test fun `duplicate policy rule id is an error`() {
        val p = CommandPolicy.parse("""{"default":"deny","rules":[{"id":"x","node":"a","allow":true},{"id":"x","node":"b","allow":true}]}""")
        assertFalse(ModelValidator.validatePolicy(p).ok)
    }
    @Test fun `policy referencing unknown model node is a warning not error`() {
        val r = ModelValidator.validate(goodModel, CommandPolicy.parse("""{"default":"deny","rules":[{"id":"x","node":"recipe.ghost","allow":true}]}"""))
        assertTrue(r.ok); assertTrue(r.warnings.any { it.contains("recipe.ghost") })
    }
    @Test fun `doneClear unknown token is a model error, known and absent are ok`() {
        fun m(dc: String?) = SiteModel.parse("""
            endpoint: "opc.tcp://x:1"
            nodes: { r.rpm: { nodeId: "ns=2;s=R/Rpm", type: Double } }
            activate: { command: { nodeId: "ns=2;s=R/Go", type: Method }, doneNode: { nodeId: "ns=2;s=R/Done", type: Boolean }${dc?.let { ", doneClear: $it" } ?: ""} }
        """.trimIndent())
        assertTrue(ModelValidator.validateModel(m("on-release")).ok)
        assertTrue(ModelValidator.validateModel(m(null)).ok)
        val bad = ModelValidator.validateModel(m("sometimes"))
        assertFalse(bad.ok)
        assertTrue(bad.errors.any { it.contains("doneClear") })
    }
}
