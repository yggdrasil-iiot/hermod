package yggdrasil.hermod.opcua.ncmd

/**
 * In-memory [MqttTransport] fake for unit tests — NO broker, NO threads.
 *
 * - [subscribe] registers a per-topic handler.
 * - [onPublish] arms an optional hook invoked synchronously on every [publish] (lets a test echo a
 *   correlated response before the port awaits).
 * - [deliver] pushes an inbound message to a subscribed handler.
 * - [lastPublished] captures the last published payload per topic for assertions.
 */
class FakeMqttTransport : MqttTransport {
    private val handlers = mutableMapOf<String, (ByteArray) -> Unit>()
    private var publishHook: ((String, ByteArray) -> Unit)? = null
    val published = mutableListOf<Pair<String, ByteArray>>()

    fun onPublish(hook: (topic: String, payload: ByteArray) -> Unit) { publishHook = hook }

    fun lastPublished(topic: String): ByteArray? = published.lastOrNull { it.first == topic }?.second

    /** Push an inbound message to the handler subscribed on [topic] (no-op if none). */
    fun deliver(topic: String, payload: ByteArray) { handlers[topic]?.invoke(payload) }

    override fun publish(topic: String, payload: ByteArray) {
        published += topic to payload
        publishHook?.invoke(topic, payload)
    }

    override fun subscribe(topic: String, handler: (ByteArray) -> Unit) { handlers[topic] = handler }

    override fun close() {}
}
