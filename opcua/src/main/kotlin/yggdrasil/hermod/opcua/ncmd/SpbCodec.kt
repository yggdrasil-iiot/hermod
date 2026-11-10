package yggdrasil.hermod.opcua.ncmd

import org.eclipse.tahu.message.SparkplugBPayloadDecoder
import org.eclipse.tahu.message.SparkplugBPayloadEncoder
import org.eclipse.tahu.message.model.Metric.MetricBuilder
import org.eclipse.tahu.message.model.MetricDataType
import org.eclipse.tahu.message.model.PropertyDataType
import org.eclipse.tahu.message.model.PropertySet
import org.eclipse.tahu.message.model.PropertySet.PropertySetBuilder
import org.eclipse.tahu.message.model.PropertyValue
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder
import java.util.Date

/** One authorizable command metric + out-of-band control (properties, never authorized). */
data class NcmdCommand(
    val cmdId: String,          // payload uuid
    val op: String,             // "write" | "call" | "read"
    val name: String,           // metric name = OPC-UA nodeId (the authorization key)
    val value: Any?,            // Double (write) | Boolean true (call) | null (read)
    val type: String,           // "Double" | "Boolean" | "" (read)
    val doneNode: String? = null,
    val timeoutMs: Long? = null,
)

data class NcmdResponse(
    val cmdId: String, val ok: Boolean,
    val value: String? = null, val good: Boolean? = null, val detail: String = "",
)

/**
 * Sparkplug B command/response codec — hermod-local (does NOT import the lab's `TahuTypes`).
 *
 * Command = ONE authorizable metric: `name` = OPC-UA nodeId, `value` = setpoint/trigger,
 * `dataType` accordingly. `op`/`doneNode`/`timeoutMs` are metric **properties** (out of band).
 * `cmdId` = the Sparkplug payload uuid.
 */
object SpbCodec {

    /** hermod-local name→MetricDataType lookup (do NOT import the lab's TahuTypes). */
    private fun mdt(type: String): MetricDataType = when (type) {
        "Double" -> MetricDataType.Double
        "Boolean" -> MetricDataType.Boolean
        else -> MetricDataType.String
    }

    /** MetricDataType→hermod type string (inverse of [mdt] for the three supported types). */
    private fun typeName(dt: MetricDataType): String = when (dt) {
        MetricDataType.Double -> "Double"
        MetricDataType.Boolean -> "Boolean"
        else -> ""
    }

    fun encodeCommand(cmd: NcmdCommand): ByteArray {
        val propsBuilder = PropertySetBuilder()
            .addProperty("op", PropertyValue(PropertyDataType.String, cmd.op))
        cmd.doneNode?.let { propsBuilder.addProperty("doneNode", PropertyValue(PropertyDataType.String, it)) }
        cmd.timeoutMs?.let { propsBuilder.addProperty("timeoutMs", PropertyValue(PropertyDataType.Int64, it)) }
        val props: PropertySet = propsBuilder.createPropertySet()

        val metric = MetricBuilder(cmd.name, mdt(cmd.type), cmd.value)
            .properties(props)
            .createMetric()

        val payload = SparkplugBPayloadBuilder()
            .setUuid(cmd.cmdId)
            .setTimestamp(Date())
            .addMetric(metric)
            .createPayload()

        return SparkplugBPayloadEncoder().getBytes(payload, false)
    }

    fun decodeCommand(bytes: ByteArray): NcmdCommand {
        val payload = SparkplugBPayloadDecoder().buildFromByteArray(bytes, null)
        val metric = payload.metrics.first()
        val props = metric.properties
        val op = props?.getPropertyValue("op")?.value as? String ?: ""
        val doneNode = props?.getPropertyValue("doneNode")?.value as? String
        val timeoutMs = (props?.getPropertyValue("timeoutMs")?.value as? Number)?.toLong()
        return NcmdCommand(
            cmdId = payload.uuid,
            op = op,
            name = metric.name,
            value = metric.value,
            type = typeName(metric.dataType),
            doneNode = doneNode,
            timeoutMs = timeoutMs,
        )
    }

    fun encodeResponse(resp: NcmdResponse): ByteArray {
        val builder = SparkplugBPayloadBuilder()
            .setUuid(resp.cmdId)
            .setTimestamp(Date())
            .addMetric(MetricBuilder("ok", MetricDataType.Boolean, resp.ok).createMetric())
            .addMetric(MetricBuilder("detail", MetricDataType.String, resp.detail).createMetric())
        resp.value?.let { builder.addMetric(MetricBuilder("value", MetricDataType.String, it).createMetric()) }
        resp.good?.let { builder.addMetric(MetricBuilder("good", MetricDataType.Boolean, it).createMetric()) }
        return SparkplugBPayloadEncoder().getBytes(builder.createPayload(), false)
    }

    fun decodeResponse(bytes: ByteArray): NcmdResponse {
        val payload = SparkplugBPayloadDecoder().buildFromByteArray(bytes, null)
        val byName = payload.metrics.associateBy({ it.name }, { it.value })
        return NcmdResponse(
            cmdId = payload.uuid,
            ok = byName["ok"] as? Boolean ?: false,
            value = byName["value"] as? String,
            good = byName["good"] as? Boolean,
            detail = byName["detail"] as? String ?: "",
        )
    }
}
