package yggdrasil.hermod.opcua.ncmd

import org.eclipse.tahu.message.SparkplugBPayloadDecoder
import org.eclipse.tahu.message.model.MetricDataType
import org.eclipse.tahu.message.model.PropertyDataType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-repo wire-contract pin for the hermod side of the Sparkplug B NCMD command/response format.
 *
 * Unlike [SpbCodecTest] (round-trip only), this asserts the **raw Sparkplug shape** of the bytes
 * `SpbCodec` emits: payload `uuid` == cmdId; exactly ONE command metric whose `name` is the OPC-UA
 * nodeId; the metric's `PropertySet` carries EXACTLY the keys `op`/`doneNode`/`timeoutMs` with
 * PropertyDataType String/String/Int64; response metrics named exactly `ok`/`detail`/`value`/`good`
 * with Boolean/String/String/Boolean types.
 *
 * Heimdall's `NcmdWireContractTest` (Java, bifrost `heimdall/`) pins the identical contract from the
 * bridge side using the same canonical cases (cmdId `vec-w1`/`vec-c1`/`vec-r1`, node `ns=2;s=Recipe/Rpm`, value 1500.0…).
 * If either repo renames a property key or a metric name/type, its own test goes RED at unit-test
 * time — instead of surfacing only as a T1 timeout at the live cross-repo gate.
 */
class WireContractTest {

    private fun decode(bytes: ByteArray) = SparkplugBPayloadDecoder().buildFromByteArray(bytes, null)

    // ---- Command shape ----

    @Test fun `write command has uuid=cmdId, one nodeId metric (Double), and op-property String`() {
        val bytes = SpbCodec.encodeCommand(
            NcmdCommand("vec-w1", "write", "ns=2;s=Recipe/Rpm", 1500.0, "Double"),
        )
        val p = decode(bytes)

        assertEquals("vec-w1", p.uuid, "payload uuid must carry the cmdId")
        assertEquals(1, p.metrics.size, "exactly ONE authorizable command metric")

        val m = p.metrics.first()
        assertEquals("ns=2;s=Recipe/Rpm", m.name, "command metric name must be the OPC-UA nodeId")
        assertEquals(MetricDataType.Double, m.dataType)
        assertEquals(1500.0, m.value)

        val props = assertNotNull(m.properties, "command metric must carry a PropertySet")
        // Only `op` is present for a bare write (doneNode/timeoutMs are null -> omitted).
        assertEquals(setOf("op"), props.names, "write carries exactly the `op` property")
        val op = assertNotNull(props.getPropertyValue("op"))
        assertEquals(PropertyDataType.String, op.type, "`op` PropertyDataType must be String")
        assertEquals("write", op.value)
    }

    @Test fun `call command carries op+doneNode (String) and timeoutMs (Int64) as properties`() {
        val bytes = SpbCodec.encodeCommand(
            NcmdCommand(
                "vec-c1", "call", "ns=2;s=Recipe/ApplyRecipe", true, "Boolean",
                doneNode = "ns=2;s=Recipe/ApplyDone", timeoutMs = 30000L,
            ),
        )
        val p = decode(bytes)

        assertEquals("vec-c1", p.uuid)
        assertEquals(1, p.metrics.size)

        val m = p.metrics.first()
        assertEquals("ns=2;s=Recipe/ApplyRecipe", m.name)
        assertEquals(MetricDataType.Boolean, m.dataType)
        assertEquals(true, m.value)

        val props = assertNotNull(m.properties)
        assertEquals(
            setOf("op", "doneNode", "timeoutMs"), props.names,
            "call carries exactly op/doneNode/timeoutMs — no more, no less",
        )
        assertEquals(PropertyDataType.String, props.getPropertyValue("op")!!.type)
        assertEquals("call", props.getPropertyValue("op")!!.value)

        val done = assertNotNull(props.getPropertyValue("doneNode"))
        assertEquals(PropertyDataType.String, done.type, "`doneNode` PropertyDataType must be String")
        assertEquals("ns=2;s=Recipe/ApplyDone", done.value)

        val timeout = assertNotNull(props.getPropertyValue("timeoutMs"))
        assertEquals(PropertyDataType.Int64, timeout.type, "`timeoutMs` PropertyDataType must be Int64")
        assertEquals(30000L, (timeout.value as Number).toLong())
    }

    @Test fun `read command has op=read String property and no value`() {
        val bytes = SpbCodec.encodeCommand(
            NcmdCommand("vec-r1", "read", "ns=2;s=Recipe/Temp", null, ""),
        )
        val p = decode(bytes)

        assertEquals("vec-r1", p.uuid)
        assertEquals(1, p.metrics.size)

        val m = p.metrics.first()
        assertEquals("ns=2;s=Recipe/Temp", m.name)
        assertNull(m.value, "a read carries no setpoint value")

        val props = assertNotNull(m.properties)
        assertEquals(setOf("op"), props.names)
        assertEquals(PropertyDataType.String, props.getPropertyValue("op")!!.type)
        assertEquals("read", props.getPropertyValue("op")!!.value)
    }

    // ---- Response shape ----

    @Test fun `ok response has uuid=cmdId and metrics ok(Boolean)+detail(String)`() {
        val bytes = SpbCodec.encodeResponse(
            NcmdResponse("vec-w1", true, detail = "written+confirmed"),
        )
        val p = decode(bytes)

        assertEquals("vec-w1", p.uuid, "response uuid must carry the cmdId")
        val byName = p.metrics.associateBy { it.name }
        assertEquals(setOf("ok", "detail"), byName.keys, "write/ok response carries exactly ok+detail")

        val ok = assertNotNull(byName["ok"])
        assertEquals(MetricDataType.Boolean, ok.dataType, "`ok` metric must be Boolean")
        assertEquals(true, ok.value)

        val detail = assertNotNull(byName["detail"])
        assertEquals(MetricDataType.String, detail.dataType, "`detail` metric must be String")
        assertEquals("written+confirmed", detail.value)
    }

    @Test fun `read response adds value(String)+good(Boolean) metrics`() {
        val bytes = SpbCodec.encodeResponse(
            NcmdResponse("vec-r1", true, value = "200.0", good = true),
        )
        val p = decode(bytes)

        assertEquals("vec-r1", p.uuid)
        val byName = p.metrics.associateBy { it.name }
        assertEquals(setOf("ok", "detail", "value", "good"), byName.keys)

        val value = assertNotNull(byName["value"])
        assertEquals(MetricDataType.String, value.dataType, "`value` metric must be String")
        assertEquals("200.0", value.value)

        val good = assertNotNull(byName["good"])
        assertEquals(MetricDataType.Boolean, good.dataType, "`good` metric must be Boolean")
        assertEquals(true, good.value)
    }

    @Test fun `deny response carries ok=false and detail, no value or good`() {
        val bytes = SpbCodec.encodeResponse(
            NcmdResponse("vec-c1", false, detail = "denied: above-max"),
        )
        val p = decode(bytes)

        assertEquals("vec-c1", p.uuid)
        val byName = p.metrics.associateBy { it.name }
        assertEquals(setOf("ok", "detail"), byName.keys, "deny omits value/good (both null)")
        assertEquals(false, byName["ok"]!!.value)
        assertEquals("denied: above-max", byName["detail"]!!.value)
    }

    // ---- Full round-trip (encode -> decode == input) for every canonical case ----

    @Test fun `command round-trips for all canonical cases`() {
        val cases = listOf(
            NcmdCommand("vec-w1", "write", "ns=2;s=Recipe/Rpm", 1500.0, "Double"),
            NcmdCommand(
                "vec-c1", "call", "ns=2;s=Recipe/ApplyRecipe", true, "Boolean",
                doneNode = "ns=2;s=Recipe/ApplyDone", timeoutMs = 30000L,
            ),
            NcmdCommand("vec-r1", "read", "ns=2;s=Recipe/Temp", null, ""),
        )
        for (c in cases) {
            val back = SpbCodec.decodeCommand(SpbCodec.encodeCommand(c))
            assertEquals(c.cmdId, back.cmdId)
            assertEquals(c.op, back.op)
            assertEquals(c.name, back.name)
            assertEquals(c.value, back.value)
            assertEquals(c.type, back.type)
            assertEquals(c.doneNode, back.doneNode)
            assertEquals(c.timeoutMs, back.timeoutMs)
        }
    }

    @Test fun `response round-trips for all canonical cases`() {
        val cases = listOf(
            NcmdResponse("vec-w1", true, detail = "written+confirmed"),
            NcmdResponse("vec-r1", true, value = "200.0", good = true),
            NcmdResponse("vec-c1", false, detail = "denied: above-max"),
        )
        for (r in cases) {
            val back = SpbCodec.decodeResponse(SpbCodec.encodeResponse(r))
            assertEquals(r.cmdId, back.cmdId)
            assertEquals(r.ok, back.ok)
            assertEquals(r.value, back.value)
            assertEquals(r.good, back.good)
            assertEquals(r.detail, back.detail)
        }
    }

    /**
     * Pin the topic wire-strings that production ([SparkplugNcmdApplyPort]) actually publishes on
     * and subscribes to — the same three Heimdall derives (NCMD, `bifrost/` QUERY and RESULT). Topics are private production vals,
     * so we observe them through a [FakeMqttTransport]: a write must publish on the NCMD topic, a
     * read on the QUERY topic, and both only complete because the response was delivered on the
     * RESULT topic the port subscribed to. If a topic format drifts on this side, this goes RED.
     */
    @Test fun `production publishes commands and awaits responses on the pinned topics`() {
        val group = "Bifrost:Line1"
        val edge = "recipe-edge-cmd"
        val ncmd = "spBv1.0/$group/NCMD/$edge"
        val query = "bifrost/$group/QUERY/$edge"
        val result = "bifrost/$group/RESULT/$edge"

        val fake = FakeMqttTransport()
        // Echo a correlated response on the RESULT topic; the port only completes if it subscribed there.
        fake.onPublish { _, bytes ->
            val cmd = SpbCodec.decodeCommand(bytes)
            val resp = if (cmd.op == "read") NcmdResponse(cmd.cmdId, true, value = "200.0", good = true)
            else NcmdResponse(cmd.cmdId, true, detail = "ok")
            fake.deliver(result, SpbCodec.encodeResponse(resp))
        }
        val port = SparkplugNcmdApplyPort(fake, group, edge, deadlineMs = 2000, idgen = { "fixed-1" })

        val w = port.write("ns=2;s=Recipe/Rpm", "Double", "1500")
        assertTrue(w.ok, "write must complete via the RESULT subscription (proves RESULT topic pinned)")
        assertNotNull(fake.lastPublished(ncmd), "write must publish on the NCMD topic")

        val r = port.read("ns=2;s=Recipe/Temp")
        assertEquals("200.0", r.value, "read must complete via the RESULT subscription")
        assertNotNull(fake.lastPublished(query), "read must publish on the QUERY topic")
    }
}
