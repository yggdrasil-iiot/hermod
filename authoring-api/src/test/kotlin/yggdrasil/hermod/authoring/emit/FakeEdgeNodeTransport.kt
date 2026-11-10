package yggdrasil.hermod.authoring.emit

import yggdrasil.hermod.opcua.emit.EdgeNodeTransport

/**
 * In-memory [EdgeNodeTransport] double for unit tests. Records published (topic,bytes);
 * lets the test fire onConnected and inbound (NCMD) messages by topic.
 */
class FakeEdgeNodeTransport(override var connected: Boolean = true) : EdgeNodeTransport {
    val published = mutableListOf<Pair<String, ByteArray>>()
    var willTopic: String = ""
        private set
    var willPayload: ByteArray = ByteArray(0)
        private set

    private var onConnectedCb: (Boolean) -> Unit = {}
    private val subs = mutableMapOf<String, (ByteArray) -> Unit>()

    override fun connectWithWill(willTopic: String, willPayload: ByteArray) {
        this.willTopic = willTopic; this.willPayload = willPayload
        connected = true
    }

    override fun publish(topic: String, payload: ByteArray) { published += topic to payload }
    override fun subscribe(topic: String, handler: (ByteArray) -> Unit) { subs[topic] = handler }
    override fun onConnected(handler: (Boolean) -> Unit) { onConnectedCb = handler }
    override fun close() {}

    /** Simulate the transport's (re)connect callback. */
    fun fireConnected(reconnect: Boolean) { onConnectedCb(reconnect) }

    /** Deliver an inbound payload to the handler subscribed for [topic]. */
    fun fireInbound(topic: String, bytes: ByteArray) { subs[topic]?.invoke(bytes) }
}
