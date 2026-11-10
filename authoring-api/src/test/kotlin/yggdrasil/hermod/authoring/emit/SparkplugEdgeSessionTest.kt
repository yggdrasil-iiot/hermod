package yggdrasil.hermod.authoring.emit

import yggdrasil.hermod.opcua.emit.GovernanceEvent
import org.eclipse.tahu.message.SparkplugBPayloadDecoder
import org.eclipse.tahu.message.SparkplugBPayloadEncoder
import org.eclipse.tahu.message.model.Metric.MetricBuilder
import org.eclipse.tahu.message.model.MetricDataType
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder
import org.eclipse.tahu.message.model.SparkplugMeta
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Date

class SparkplugEdgeSessionTest {
    private fun seqOf(b: ByteArray) = SparkplugBPayloadDecoder().buildFromByteArray(b, null).seq

    private fun sampleEvent() =
        GovernanceEvent("CONFIRMED", "wf-1", "wf:1", "temporal", "COMPLETED", "NONE", 1L, emptyList())

    private fun rebirthCommandBytes(): ByteArray =
        SparkplugBPayloadEncoder().getBytes(
            SparkplugBPayloadBuilder().setTimestamp(Date())
                .addMetric(MetricBuilder(SparkplugMeta.METRIC_NODE_REBIRTH, MetricDataType.Boolean, true).createMetric())
                .createPayload(), false)

    @Test fun `start connects with will and publishes NBIRTH at seq 0`() {
        val t = FakeEdgeNodeTransport()
        val s = SparkplugEdgeSession(t, "Hermod", "Governance", { emptyList() })
        s.start(); t.fireConnected(false)
        assertEquals("spBv1.0/Hermod/NDEATH/Governance", t.willTopic)
        val (topic, bytes) = t.published.last()
        assertEquals("spBv1.0/Hermod/NBIRTH/Governance", topic)
        assertEquals(0L, seqOf(bytes))
    }

    @Test fun `publishNdata increments seq and targets NDATA topic`() {
        val t = FakeEdgeNodeTransport(); val s = SparkplugEdgeSession(t, "Hermod", "Governance", { emptyList() })
        s.start(); t.fireConnected(false)
        s.publishNdata(sampleEvent()); s.publishNdata(sampleEvent())
        val ndata = t.published.filter { it.first.contains("/NDATA/") }
        assertEquals(1L, seqOf(ndata[0].second)); assertEquals(2L, seqOf(ndata[1].second))
    }

    @Test fun `rebirth NCMD re-publishes NBIRTH at seq 0`() {
        val t = FakeEdgeNodeTransport(); val s = SparkplugEdgeSession(t, "Hermod", "Governance", { emptyList() })
        s.start(); t.fireConnected(false); s.publishNdata(sampleEvent())
        t.fireInbound("spBv1.0/Hermod/NCMD/Governance", rebirthCommandBytes())
        val births = t.published.filter { it.first.contains("/NBIRTH/") }
        assertEquals(0L, seqOf(births.last().second))   // rebirth resets seq
    }

    @Test fun `publishNdata is dropped when disconnected (fail-open)`() {
        val t = FakeEdgeNodeTransport(connected = false)
        val s = SparkplugEdgeSession(t, "Hermod", "Governance", { emptyList() })
        s.publishNdata(sampleEvent())   // no connect → no throw, nothing published
        assertTrue(t.published.isEmpty())
    }
}
