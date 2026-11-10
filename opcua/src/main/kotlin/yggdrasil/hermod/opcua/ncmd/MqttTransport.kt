package yggdrasil.hermod.opcua.ncmd

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/** Minimal MQTT seam so [SparkplugNcmdApplyPort] is unit-testable with no live broker. */
interface MqttTransport : AutoCloseable {
    fun publish(topic: String, payload: ByteArray)
    fun subscribe(topic: String, handler: (ByteArray) -> Unit)   // deliver payloads for `topic`
}

/**
 * Paho MQTT v3 implementation of [MqttTransport] (straight adaptation of the lab
 * `GuardedEdgeNode.connect` wiring). Exercised only by the live Chunk-3 gate, not unit tests.
 */
class PahoMqttTransport(brokerUrl: String, clientId: String) : MqttTransport {
    private val client = MqttClient(brokerUrl, clientId, MemoryPersistence())

    init {
        val opts = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
        }
        client.connect(opts)
    }

    override fun publish(topic: String, payload: ByteArray) {
        client.publish(topic, payload, 1, false)
    }

    override fun subscribe(topic: String, handler: (ByteArray) -> Unit) {
        client.subscribe(topic, 1) { _, msg: MqttMessage -> handler(msg.payload) }
    }

    override fun close() {
        try { if (client.isConnected) client.disconnect() } catch (_: Exception) {}
        try { client.close() } catch (_: Exception) {}
    }
}
