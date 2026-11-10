package yggdrasil.hermod.opcua.emit

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/** Edge-node transport seam: unit tests use a fake; the live gate uses [PahoEdgeNodeTransport]. */
interface EdgeNodeTransport : AutoCloseable {
    val connected: Boolean
    /** Register the NDEATH will (topic+payload) THEN connect. */
    fun connectWithWill(willTopic: String, willPayload: ByteArray)
    fun publish(topic: String, payload: ByteArray)
    fun subscribe(topic: String, handler: (ByteArray) -> Unit)
    /** Fired on a successful (re)connect; the session (re)issues NBIRTH here. */
    fun onConnected(handler: (reconnect: Boolean) -> Unit)
}

/**
 * Paho MQTT v3 edge-node transport with a Sparkplug NDEATH will. Gate-only (not unit-tested).
 *
 * Uses the ASYNC client on purpose: [publish] runs on the Paho callback thread for NBIRTH (via
 * [onConnected]/connectComplete). The synchronous `MqttClient.publish` blocks on `waitForCompletion()`,
 * and on the callback thread that self-deadlocks — the callback thread is the one that must complete the
 * delivery token, so it waits forever while holding [SparkplugEdgeSession]'s publish lock, permanently
 * starving every NDATA publish. `MqttAsyncClient.publish` returns immediately (true fire-and-forget), which
 * is also the non-blocking, fail-open property the emit surface requires. connect/subscribe are awaited on
 * the (main) caller thread so the session is ready before the first birth.
 */
class PahoEdgeNodeTransport(brokerUrl: String, clientId: String) : EdgeNodeTransport {
    private val client = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
    private var onConnectedCb: (Boolean) -> Unit = {}
    private val subs = mutableListOf<Pair<String, (ByteArray) -> Unit>>()
    @Volatile private var willTopic: String = ""
    @Volatile private var willPayload: ByteArray = ByteArray(0)

    override val connected: Boolean get() = client.isConnected

    override fun connectWithWill(willTopic: String, willPayload: ByteArray) {
        this.willTopic = willTopic; this.willPayload = willPayload
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                subs.forEach { (t, h) -> client.subscribe(t, 1) { _, m -> h(m.payload) } }
                onConnectedCb(reconnect)
            }
            override fun connectionLost(cause: Throwable?) {}
            override fun messageArrived(topic: String, message: MqttMessage) {}
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        client.connect(MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            setWill(willTopic, willPayload, 0, false)   // Sparkplug B: NDEATH at QoS 0
        }).waitForCompletion()
    }

    override fun publish(topic: String, payload: ByteArray) {
        // Sparkplug B mandates QoS 0 for NBIRTH/NDATA (fire-and-forget). The ASYNC publish returns without
        // waiting for a delivery token, so this is safe to call from the Paho callback thread (NBIRTH) and
        // never blocks the reconcile/API thread (NDATA) — the emit surface is FAIL-OPEN.
        if (client.isConnected) client.publish(topic, payload, 0, false)
    }
    override fun subscribe(topic: String, handler: (ByteArray) -> Unit) {
        subs += topic to handler
        if (client.isConnected) client.subscribe(topic, 1) { _, m -> handler(m.payload) }
    }
    override fun onConnected(handler: (Boolean) -> Unit) { onConnectedCb = handler }
    override fun close() {
        try { if (client.isConnected) client.disconnect().waitForCompletion(2000) } catch (_: Exception) {}
        try { client.close() } catch (_: Exception) {}
    }
}
