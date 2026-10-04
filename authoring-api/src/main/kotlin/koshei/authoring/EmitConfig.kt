package koshei.authoring

import com.zaxxer.hikari.HikariDataSource
import koshei.authoring.emit.GovernanceEventEmitter
import koshei.authoring.emit.SparkplugEdgeSession
import koshei.opcua.CanonicalSetpoints
import koshei.opcua.emit.GovernedNode
import koshei.opcua.emit.PahoEdgeNodeTransport
import koshei.registry.EmittedEventStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Outbound governance-event surface wiring (spec 2026-07-01 §3.7). Mirrors [RegistryConfig]'s style:
 * reads `KOSHEI_*` via System.getenv INSIDE the @Bean methods. When `KOSHEI_EMIT_MODE` is unset the
 * session/emitter beans are null → RunReconciler's nullable emitter stays null → the reconcile path is
 * byte-identical to today (no session constructed, no broker connect). The `emitted_event` dedup store
 * is always available (cheap, lazy connection) — it is also used by RunStore.clearArchive on retry.
 */
@Configuration
class EmitConfig {
    private fun on() = System.getenv("KOSHEI_EMIT_MODE") != null

    @Bean fun emittedEventStore(ds: HikariDataSource): EmittedEventStore = EmittedEventStore { ds.connection }

    @Bean fun edgeSession(cs: CanonicalSetpoints?): SparkplugEdgeSession? {
        if (!on()) return null
        val group = System.getenv("KOSHEI_EMIT_GROUP") ?: "Koshei"
        val edge  = System.getenv("KOSHEI_EMIT_EDGE") ?: "Governance"
        val url   = System.getenv("KOSHEI_EMIT_MQTT_URL") ?: error("KOSHEI_EMIT_MQTT_URL required when KOSHEI_EMIT_MODE is on")
        val transport = PahoEdgeNodeTransport(url, "koshei-$group-$edge")
        return SparkplugEdgeSession(transport, group, edge, { birthNodes(cs) }).also { it.start() }
    }

    @Bean fun governanceEmitter(
        session: SparkplugEdgeSession?, emittedLog: EmittedEventStore, cs: CanonicalSetpoints?,
    ): GovernanceEventEmitter? =
        if (session == null) null
        else GovernanceEventEmitter(session, emittedLog, { stagedNodes(cs) })

    // desired canonical value → GovernedNode; outcome differs by context (RECONCILING vs NBIRTH last-known).
    // No published canonical (cs == null) → no governed nodes to announce.
    private fun stagedNodes(cs: CanonicalSetpoints?): List<GovernedNode> =
        cs?.let { c -> c.keys().mapNotNull { c.byKey(it) }.map { GovernedNode(it.key, it.desired, "STAGED", it.nodeId) } }.orEmpty()
    private fun birthNodes(cs: CanonicalSetpoints?): List<GovernedNode> =
        cs?.let { c -> c.keys().mapNotNull { c.byKey(it) }.map { GovernedNode(it.key, it.desired, "UNKNOWN", it.nodeId) } }.orEmpty()
}
