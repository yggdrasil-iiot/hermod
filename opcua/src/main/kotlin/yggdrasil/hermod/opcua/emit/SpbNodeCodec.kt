package yggdrasil.hermod.opcua.emit

import org.eclipse.tahu.message.SparkplugBPayloadEncoder
import org.eclipse.tahu.message.model.Metric.MetricBuilder
import org.eclipse.tahu.message.model.MetricDataType
import org.eclipse.tahu.message.model.PropertyDataType
import org.eclipse.tahu.message.model.PropertySet.PropertySetBuilder
import org.eclipse.tahu.message.model.PropertyValue
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder
import org.eclipse.tahu.message.model.SparkplugMeta
import java.util.Date

/** Encodes Hermod's governance state as standard Sparkplug B node payloads (spec 2026-07-01 §3.5). */
object SpbNodeCodec {
    private fun str(name: String, v: String?) =
        MetricBuilder(name, MetricDataType.String, v ?: "").createMetric()

    private fun setpointMetric(n: GovernedNode) = MetricBuilder(
        "Setpoint/${n.logicalKey}", MetricDataType.Double, n.value   // Double-typed; null value stays a null Double
    ).properties(
        PropertySetBuilder()
            .addProperty("outcome", PropertyValue(PropertyDataType.String, n.outcome))
            .addProperty("opcuaNode", PropertyValue(PropertyDataType.String, n.opcuaNode ?: ""))
            .createPropertySet()
    ).createMetric()

    private fun bdSeqMetric(bdSeq: Long) =
        MetricBuilder("bdSeq", MetricDataType.Int64, bdSeq).createMetric()

    fun encodeNbirth(setpoints: List<GovernedNode>, bdSeq: Long): ByteArray {
        val b = SparkplugBPayloadBuilder().setTimestamp(Date()).setSeq(0L)
            .addMetric(str("Governance/LastEventType", ""))
            .addMetric(str("Governance/LastRunId", ""))
            .addMetric(str("Governance/LastWorkflow", ""))
            .addMetric(str("Governance/LastEngine", ""))
            .addMetric(str("Governance/LastStatus", ""))
            .addMetric(str("Governance/LastCompOutcome", ""))
            .addMetric(str("Governance/DefRef", ""))
            .addMetric(MetricBuilder("Governance/LastEventTimestamp", MetricDataType.Int64, 0L).createMetric())
            .addMetric(MetricBuilder(SparkplugMeta.METRIC_NODE_REBIRTH, MetricDataType.Boolean, false).createMetric())
            .addMetric(bdSeqMetric(bdSeq))
        setpoints.forEach { b.addMetric(setpointMetric(it)) }
        return SparkplugBPayloadEncoder().getBytes(b.createPayload(), false)
    }

    fun encodeNdata(ev: GovernanceEvent, seq: Long): ByteArray {
        val b = SparkplugBPayloadBuilder().setTimestamp(Date()).setSeq(seq).setUuid(ev.runId)
            .addMetric(str("Governance/LastEventType", ev.type))
            .addMetric(str("Governance/LastRunId", ev.runId))
            .addMetric(str("Governance/LastWorkflow", ev.workflow))
            .addMetric(str("Governance/LastEngine", ev.engine))
            .addMetric(str("Governance/LastStatus", ev.status))
            .addMetric(str("Governance/LastCompOutcome", ev.compOutcome))
            .addMetric(str("Governance/DefRef", ev.defRef))
            .addMetric(MetricBuilder("Governance/LastEventTimestamp", MetricDataType.Int64, ev.atMillis).createMetric())
        ev.nodes.forEach { b.addMetric(setpointMetric(it)) }
        return SparkplugBPayloadEncoder().getBytes(b.createPayload(), false)
    }

    fun encodeNdeath(bdSeq: Long): ByteArray =
        SparkplugBPayloadEncoder().getBytes(
            SparkplugBPayloadBuilder().setTimestamp(Date()).addMetric(bdSeqMetric(bdSeq)).createPayload(), false)
}
