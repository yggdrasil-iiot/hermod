package koshei.opcua.ncmd

import koshei.sdk.ApplyOutcome
import koshei.sdk.ApplyPort
import koshei.sdk.DoneClearMode
import koshei.sdk.ReadResult
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * "Approach B" [ApplyPort]: instead of writing OPC-UA directly (R1), koshei publishes a
 * Sparkplug B NCMD to Bifrost's Heimdall (the command edge) over MQTT and awaits the correlated
 * answer on Heimdall's RESULT topic.
 *
 * Topics follow Heimdall's wire contract (bifrost `docs/INTEGRATION.md`): commands on
 * `spBv1.0/<group>/NCMD/<edge>`, reads on `bifrost/<group>/QUERY/<edge>`, and every answer
 * (command outcome, read-back, overload refusal) on `bifrost/<group>/RESULT/<edge>`. QUERY and
 * RESULT are not Sparkplug topics; the payloads are still Sparkplug B encoded ([SpbCodec]).
 *
 * Correlation: `cmdId` = the request payload uuid; the bridge echoes it in the response uuid.
 * Fail-closed: every method has a deadline; a missing/late/duplicate/unknown response yields a
 * NOT-confirmed outcome (never throws out of an ApplyPort method).
 *
 * @param transport MQTT seam (Paho in prod, a fake in tests).
 * @param group/edge Sparkplug identity of Heimdall's command edge (topics derived below).
 * @param deadlineMs base await deadline for write/read (call adds the bridge's own poll budget).
 * @param idgen cmdId generator (overridable for deterministic tests).
 */
class SparkplugNcmdApplyPort(
    private val transport: MqttTransport,
    group: String,
    edge: String,
    private val deadlineMs: Long = 15_000,
    private val idgen: () -> String = { UUID.randomUUID().toString() },
) : ApplyPort, AutoCloseable {

    private val ncmdTopic  = "spBv1.0/$group/NCMD/$edge"
    private val queryTopic  = "bifrost/$group/QUERY/$edge"
    private val resultTopic = "bifrost/$group/RESULT/$edge"

    private val pending = ConcurrentHashMap<String, CompletableFuture<NcmdResponse>>()

    init {
        transport.subscribe(resultTopic) { onResponse(it) }
    }

    private fun onResponse(bytes: ByteArray) {
        val resp = try { SpbCodec.decodeResponse(bytes) } catch (_: Exception) { return }
        // remove returns null for a late/duplicate/unknown cmdId → dropped.
        pending.remove(resp.cmdId)?.complete(resp)
    }

    /** Registers a future, publishes the command, and awaits its correlated response up to [deadline]. */
    private fun exchange(cmdId: String, topic: String, cmd: NcmdCommand, deadline: Long): NcmdResponse? {
        val future = CompletableFuture<NcmdResponse>()
        pending[cmdId] = future
        try {
            transport.publish(topic, SpbCodec.encodeCommand(cmd))
            return future.get(deadline, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return null
        } catch (_: Exception) {
            return null
        } finally {
            pending.remove(cmdId)
        }
    }

    override fun read(nodeId: String): ReadResult {
        val cmdId = idgen()
        val resp = exchange(cmdId, queryTopic, NcmdCommand(cmdId, "read", nodeId, null, ""), deadlineMs)
            ?: return ReadResult(null, false)
        return ReadResult(resp.value, resp.good ?: false)
    }

    override fun write(nodeId: String, type: String, value: String): ApplyOutcome {
        val cmdId = idgen()
        val v: Any = if (type == "Double") value.toDouble() else value
        val resp = exchange(cmdId, ncmdTopic, NcmdCommand(cmdId, "write", nodeId, v, type), deadlineMs)
            ?: return ApplyOutcome(false, "ncmd write timeout")
        return ApplyOutcome(resp.ok, resp.detail)
    }

    override fun call(commandNodeId: String, doneNodeId: String, timeoutMs: Long,
                      doneClear: DoneClearMode): ApplyOutcome {
        // Fail closed BEFORE publishing if we can't complete this equipment's handshake on the NCMD path.
        // Only ON_RELEASE is supported (symmetric with OpcUaApplyPort direct); Heimdall releases the
        // trigger after confirm so the equipment rearms. explicit-reset / master-clears are declared in the
        // enum but unimplemented on both apply paths → fail closed (no actuation, no NCMD published).
        if (doneClear != DoneClearMode.ON_RELEASE) {
            return ApplyOutcome(ok = false,
                detail = "doneClear mode $doneClear not implemented in the NCMD apply path")
        }
        val cmdId = idgen()
        val cmd = NcmdCommand(cmdId, "call", commandNodeId, true, "Boolean",
            doneNode = doneNodeId, timeoutMs = timeoutMs)
        // The bridge itself may poll up to timeoutMs; add that to our base deadline.
        val resp = exchange(cmdId, ncmdTopic, cmd, deadlineMs + timeoutMs)
            ?: return ApplyOutcome(false, "ncmd call timeout")
        return ApplyOutcome(resp.ok, resp.detail)
    }

    override fun close() {
        try { transport.close() } catch (_: Exception) {}
    }

    companion object {
        fun default(): SparkplugNcmdApplyPort {
            val url   = System.getenv("KOSHEI_MQTT_URL") ?: "tcp://localhost:1883"
            // Defaults = Heimdall's own (NcmdOpcUaBridgeMain: SPB_GROUP, SPB_EDGE).
            val group = System.getenv("KOSHEI_SPB_GROUP") ?: "Bifrost:Line1"
            val edge  = System.getenv("KOSHEI_SPB_EDGE") ?: "recipe-edge-cmd"
            val transport = PahoMqttTransport(url, "koshei-ncmd-${UUID.randomUUID()}")
            return SparkplugNcmdApplyPort(transport, group, edge)
        }
    }
}
