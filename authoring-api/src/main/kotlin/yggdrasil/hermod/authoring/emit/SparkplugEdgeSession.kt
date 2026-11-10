package yggdrasil.hermod.authoring.emit

import yggdrasil.hermod.opcua.emit.EdgeNodeTransport
import yggdrasil.hermod.opcua.emit.GovernanceEvent
import yggdrasil.hermod.opcua.emit.GovernedNode
import yggdrasil.hermod.opcua.emit.SpbNodeCodec
import org.eclipse.tahu.message.SparkplugBPayloadDecoder
import org.eclipse.tahu.message.model.SparkplugMeta
import org.slf4j.LoggerFactory

/**
 * A standard, spec-compliant Sparkplug B Edge Node session (spec 2026-07-01 §3.4).
 *
 * bdSeq is stable per process lifetime (Paho auto-reconnect = one logical session): the NDEATH
 * will is registered once with the initial bdSeq, so every re-birth after an auto-reconnect carries
 * the same current bdSeq (spec-consistent — bdSeq identifies a birth/death pair per session). seq
 * resets to 0 on every NBIRTH (start + rebirth) and increments per NDATA (0..255 rolling).
 * Thread-safe: publishNdata and NBIRTH share one lock; safe from the sweep thread and HTTP threads.
 */
open class SparkplugEdgeSession(
    private val transport: EdgeNodeTransport,
    private val group: String,
    private val edge: String,
    private val birthSetpoints: () -> List<GovernedNode>,   // last-known state for NBIRTH
    private val bdSeq: Long = 0L,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(SparkplugEdgeSession::class.java)
    private val lock = Any()
    private var seq = 0L
    private val nbirth get() = "spBv1.0/$group/NBIRTH/$edge"
    private val ndata  get() = "spBv1.0/$group/NDATA/$edge"
    private val ndeath get() = "spBv1.0/$group/NDEATH/$edge"
    private val ncmd   get() = "spBv1.0/$group/NCMD/$edge"

    fun start() {
        transport.onConnected { _ -> publishNbirth() }
        transport.subscribe(ncmd) { bytes -> onNcmd(bytes) }
        transport.connectWithWill(ndeath, SpbNodeCodec.encodeNdeath(bdSeq))
    }

    private fun publishNbirth(): Unit = synchronized(lock) {
        // Runs on the Paho connectComplete callback thread — wrap fail-open so an encode/publish
        // throw is logged-and-dropped, never propagated into the reconnect callback (parity with NDATA).
        seq = 0L
        safe { transport.publish(nbirth, SpbNodeCodec.encodeNbirth(birthSetpoints(), bdSeq)) }
        Unit
    }

    open fun publishNdata(ev: GovernanceEvent): Unit = synchronized(lock) {
        if (!transport.connected) { log.debug("emit dropped (disconnected): {}", ev.runId); return }
        seq = (seq + 1) % 256
        safe { transport.publish(ndata, SpbNodeCodec.encodeNdata(ev, seq)) }
        Unit
    }

    private fun onNcmd(bytes: ByteArray) {
        try {
            val p = SparkplugBPayloadDecoder().buildFromByteArray(bytes, null)
            val rebirth = p.metrics.firstOrNull { it.name == SparkplugMeta.METRIC_NODE_REBIRTH }?.value == true
            if (rebirth) publishNbirth()
        } catch (e: Exception) { log.warn("bad NCMD: {}", e.toString()) }
    }

    /** Disposes the underlying MQTT client + its reconnect thread. Spring auto-invokes this as the bean destroy method. */
    override fun close() = transport.close()

    private fun <T> safe(b: () -> T): T? = try { b() } catch (e: Exception) { log.warn("emit failed: {}", e.toString()); null }
}
