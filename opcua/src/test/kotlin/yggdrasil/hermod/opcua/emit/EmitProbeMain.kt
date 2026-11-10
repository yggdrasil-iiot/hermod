package yggdrasil.hermod.opcua.emit

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.eclipse.tahu.message.SparkplugBPayloadDecoder
import org.eclipse.tahu.message.SparkplugBPayloadEncoder
import org.eclipse.tahu.message.model.Metric.MetricBuilder
import org.eclipse.tahu.message.model.MetricDataType
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder
import org.eclipse.tahu.message.model.SparkplugMeta
import java.io.File
import java.util.Date

/**
 * GATE TOOLING ONLY (run-outbound-emit-gate.sh) — NOT wired into any manifest/registry/workflow.
 * Lives in the :opcua TEST source set (cf. PerturbMain) so :opcua/src/main stays untouched. Reuses the
 * Paho + Tahu already on the :opcua classpath to observe/inject the Sparkplug wire WITHOUT any external
 * MQTT client (the gate environment has no mosquitto):
 *
 *   capture <mqttUrl> <group> <edge> <outFile> <seconds>
 *       Subscribe spBv1.0/{group}/+/{edge}; for each message append one line "<KIND> <LastEventType|->"
 *       to <outFile> (KIND = the topic's 3rd segment: NBIRTH/NDATA/NDEATH/NCMD). The gate uses this to
 *       assert an NBIRTH line precedes any NDATA line, and that a rebirth NCMD yields a fresh NBIRTH.
 *
 *   rebirth <mqttUrl> <group> <edge>
 *       Publish a Tahu-encoded NCMD carrying one Boolean metric "Node Control/Rebirth"=true to
 *       spBv1.0/{group}/NCMD/{edge} — the standard Sparkplug host rebirth request.
 */
fun main(args: Array<String>) {
    require(args.size >= 4) { "usage: <capture|rebirth> <mqttUrl> <group> <edge> [outFile seconds]" }
    val mode = args[0]; val url = args[1]; val group = args[2]; val edge = args[3]
    when (mode) {
        "capture" -> {
            require(args.size >= 6) { "capture needs: <mqttUrl> <group> <edge> <outFile> <seconds>" }
            capture(url, group, edge, args[4], args[5].toInt())
        }
        "rebirth" -> rebirth(url, group, edge)
        "emitself" -> emitself(url, group, edge)
        else -> error("unknown mode '$mode' (expected capture|rebirth|emitself)")
    }
}

/**
 * DIAGNOSTIC (not used by the gate): reproduce the live edge publish path in isolation — connect a real
 * [PahoEdgeNodeTransport], birth on the Paho callback thread (NBIRTH works), then publish one NDATA from the
 * MAIN (app) thread, exactly like SparkplugEdgeSession.publishNdata. Logs transport.connected at each step so
 * we can see whether the app-thread NDATA publish sees a connected client.
 */
private fun emitself(url: String, group: String, edge: String) {
    val transport = PahoEdgeNodeTransport(url, "hermod-$group-$edge")
    transport.onConnected { reconnect ->
        val nb = SpbNodeCodec.encodeNbirth(sampleNodes("UNKNOWN"), 0L)
        transport.publish("spBv1.0/$group/NBIRTH/$edge", nb)
        println("[emitself] connectComplete(reconnect=$reconnect) connected=${transport.connected}; NBIRTH published (${nb.size}B)")
    }
    transport.connectWithWill("spBv1.0/$group/NDEATH/$edge", SpbNodeCodec.encodeNdeath(0L))
    Thread.sleep(2500)
    println("[emitself] after connect: transport.connected=${transport.connected}")
    val ev = GovernanceEvent("RECON_FAILED", "repro-1", "wf:1.0.0", "temporal",
        "WORKFLOW_EXECUTION_STATUS_FAILED", "COMPENSATED", System.currentTimeMillis(), sampleNodes("RESTORED"))
    val nd = SpbNodeCodec.encodeNdata(ev, 1L)
    println("[emitself] MAIN-thread publish NDATA: transport.connected=${transport.connected} bytes=${nd.size}")
    transport.publish("spBv1.0/$group/NDATA/$edge", nd)
    println("[emitself] NDATA publish call returned")
    Thread.sleep(3000)
    transport.close()
    println("[emitself] done")
}

private fun sampleNodes(outcome: String) =
    listOf(GovernedNode("recipe.rpmSetpoint", 1500.0, outcome, "ns=2;s=Recipe/Rpm"))

private fun capture(url: String, group: String, edge: String, outFile: String, seconds: Int) {
    val out = File(outFile)
    out.parentFile?.mkdirs()
    val decoder = SparkplugBPayloadDecoder()
    val client = MqttClient(url, "hermod-emit-probe-cap-${System.currentTimeMillis()}", MemoryPersistence())
    client.connect(MqttConnectOptions().apply { isCleanSession = true; isAutomaticReconnect = true })
    val filter = "spBv1.0/$group/+/$edge"
    client.subscribe(filter, 1) { topic, message ->
        val parts = topic.split("/")
        val kind = if (parts.size >= 3) parts[2] else "?"                 // spBv1.0/{group}/{KIND}/{edge}
        val evType = try {
            val p = decoder.buildFromByteArray(message.payload, null)
            (p.metrics.firstOrNull { it.name == "Governance/LastEventType" }?.value as? String)
                ?.ifEmpty { "-" } ?: "-"                                  // NBIRTH default LastEventType="" -> "-"
        } catch (e: Exception) { "-" }
        synchronized(out) { out.appendText("$kind $evType\n") }
    }
    println("[emit-probe] capturing $filter -> $outFile for ${seconds}s")
    Thread.sleep(seconds * 1000L)
    try { client.disconnect() } catch (_: Exception) {}
    try { client.close() } catch (_: Exception) {}
}

private fun rebirth(url: String, group: String, edge: String) {
    val payload = SparkplugBPayloadBuilder().setTimestamp(Date())
        .addMetric(MetricBuilder(SparkplugMeta.METRIC_NODE_REBIRTH, MetricDataType.Boolean, true).createMetric())
        .createPayload()
    val bytes = SparkplugBPayloadEncoder().getBytes(payload, false)
    val client = MqttClient(url, "hermod-emit-probe-reb-${System.currentTimeMillis()}", MemoryPersistence())
    client.connect(MqttConnectOptions().apply { isCleanSession = true })
    client.publish("spBv1.0/$group/NCMD/$edge", bytes, 1, false)
    println("[emit-probe] published rebirth NCMD to spBv1.0/$group/NCMD/$edge")
    try { client.disconnect() } catch (_: Exception) {}
    try { client.close() } catch (_: Exception) {}
}
