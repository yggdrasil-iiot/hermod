package yggdrasil.hermod.opcua.emit

import org.eclipse.tahu.message.SparkplugBPayloadDecoder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpbNodeCodecWireContractTest {
    private fun decode(b: ByteArray) = SparkplugBPayloadDecoder().buildFromByteArray(b, null)

    @Test fun `NBIRTH declares header + setpoint metrics + bdSeq + rebirth control, seq 0`() {
        val bytes = SpbNodeCodec.encodeNbirth(
            setpoints = listOf(GovernedNode("recipe.rpmSetpoint", 1400.0, "UNKNOWN", "ns=2;s=Recipe/Rpm")),
            bdSeq = 3L,
        )
        val p = decode(bytes)
        assertEquals(0L, p.seq)
        val names = p.metrics.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf(
            "Governance/LastEventType","Governance/LastRunId","Governance/LastWorkflow",
            "Governance/LastEngine","Governance/LastStatus","Governance/LastCompOutcome",
            "Governance/LastEventTimestamp","Setpoint/recipe.rpmSetpoint","bdSeq","Node Control/Rebirth")))
        val sp = p.metrics.first { it.name == "Setpoint/recipe.rpmSetpoint" }
        assertEquals("ns=2;s=Recipe/Rpm", sp.properties.getPropertyValue("opcuaNode").value)
        assertEquals("UNKNOWN", sp.properties.getPropertyValue("outcome").value)
        assertEquals(3L, (p.metrics.first { it.name == "bdSeq" }.value as Number).toLong())
    }

    @Test fun `NDATA carries the event header + touched setpoints with real outcome, given seq`() {
        val ev = GovernanceEvent("CONFIRMED","wf-1","ot-recipe-stage-activate:1","temporal",
            "COMPLETED","NONE",1234L, listOf(GovernedNode("recipe.rpmSetpoint",1450.0,"CONFIRMED","ns=2;s=Recipe/Rpm")))
        val p = decode(SpbNodeCodec.encodeNdata(ev, seq = 7L))
        assertEquals(7L, p.seq)
        val byName = p.metrics.associateBy { it.name }
        assertEquals("CONFIRMED", byName["Governance/LastEventType"]!!.value)
        assertEquals("wf-1", byName["Governance/LastRunId"]!!.value)
        val sp = byName["Setpoint/recipe.rpmSetpoint"]!!
        assertEquals(1450.0, sp.value); assertEquals("CONFIRMED", sp.properties.getPropertyValue("outcome").value)
    }

    @Test fun `NDEATH carries matching bdSeq`() {
        val p = decode(SpbNodeCodec.encodeNdeath(bdSeq = 5L))
        assertEquals(5L, (p.metrics.first { it.name == "bdSeq" }.value as Number).toLong())
    }

    @Test fun `NDATA encodes a governed node with a null Double value (e_g_ a DENIED node)`() {
        // CommandAuditReader can yield value=null (non-numeric/null audit value) → Double-typed null metric.
        val ev = GovernanceEvent("RECON_FAILED","wf-2","ot-recipe-stage-activate:1","temporal",
            "COMPLETED","COMPENSATED",1234L, listOf(GovernedNode("recipe.rpmSetpoint", null, "DENIED", "ns=2;s=Recipe/Rpm")))
        val p = decode(SpbNodeCodec.encodeNdata(ev, seq = 1L))   // must not throw
        val sp = p.metrics.first { it.name == "Setpoint/recipe.rpmSetpoint" }
        assertEquals("DENIED", sp.properties.getPropertyValue("outcome").value)
        assertNull(sp.value)                                     // null Double round-trips as null
    }
}
