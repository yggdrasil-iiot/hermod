package koshei.opcua

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

class DefaultEndpointResolverTest {
    private fun model(endpoint: String) = SiteModel(
        endpoint = endpoint,
        nodes = emptyMap(),
        activate = ActivateDef(NodeDef("ns=2;s=Cmd", "Boolean"), NodeDef("ns=2;s=Done", "Boolean")),
    )

    @Test fun `KOSHEI_OPCUA_URL wins when set`() {
        assertEquals(
            "opc.tcp://plc:4840",
            OpcUaApplyPort.defaultEndpoint(mapOf("KOSHEI_OPCUA_URL" to "opc.tcp://plc:4840")) { model("opc.tcp://localhost:62541") },
        )
    }

    @Test fun `unset KOSHEI_OPCUA_URL falls back to the site model endpoint`() {
        assertEquals(
            "opc.tcp://localhost:62541",
            OpcUaApplyPort.defaultEndpoint(emptyMap()) { model("opc.tcp://localhost:62541") },
        )
    }

    @Test fun `site model is not loaded when KOSHEI_OPCUA_URL is set`() {
        var loaded = false
        OpcUaApplyPort.defaultEndpoint(mapOf("KOSHEI_OPCUA_URL" to "opc.tcp://plc:4840")) { loaded = true; model("unused") }
        assertFalse(loaded, "the site model must not be loaded (a bad KOSHEI_OPCUA_MODEL must not matter) when the URL is set")
    }

    @Test fun `default arguments resolve against the bundled model when no env override is present`() {
        // Runs in the test JVM: only meaningful when neither override is exported in the build environment.
        assumeTrue(System.getenv("KOSHEI_OPCUA_URL") == null && System.getenv("KOSHEI_OPCUA_MODEL") == null,
            "KOSHEI_OPCUA_URL / KOSHEI_OPCUA_MODEL exported in the build env")
        assertEquals(SiteModel.fromClasspath().endpoint, OpcUaApplyPort.defaultEndpoint())
    }
}
