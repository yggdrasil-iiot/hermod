package yggdrasil.hermod.opcua.emit

import org.eclipse.tahu.message.SparkplugBPayloadDecoder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpbNodeCodecDefRefTest {
    private fun metrics(bytes: ByteArray): Map<String, Any?> =
        SparkplugBPayloadDecoder().buildFromByteArray(bytes, null).metrics.associate { it.name to it.value }

    @Test fun `NBIRTH declares Governance-DefRef`() {
        val m = metrics(SpbNodeCodec.encodeNbirth(emptyList(), bdSeq = 0L))
        assertTrue(m.containsKey("Governance/DefRef"), "NBIRTH must declare Governance/DefRef")
        assertEquals("", m["Governance/DefRef"])
    }

    @Test fun `NDATA carries the def_ref`() {
        val ev = GovernanceEvent(
            type = "CONFIRMED", runId = "r1", workflow = "wf:1.0.0", engine = "temporal",
            status = "COMPLETED", compOutcome = "NONE", atMillis = 1L, nodes = emptyList(),
            defRef = "deadbeef",
        )
        val m = metrics(SpbNodeCodec.encodeNdata(ev, seq = 1L))
        assertEquals("deadbeef", m["Governance/DefRef"])
    }
}
