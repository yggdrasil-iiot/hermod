package yggdrasil.hermod.authoring

import com.zaxxer.hikari.HikariDataSource
import yggdrasil.hermod.authoring.emit.GovernanceEventEmitter
import yggdrasil.hermod.authoring.emit.SparkplugEdgeSession
import yggdrasil.hermod.opcua.CanonicalSetpoints
import yggdrasil.hermod.opcua.emit.GovernedNode
import yggdrasil.hermod.opcua.emit.PahoEdgeNodeTransport
import yggdrasil.hermod.registry.EmittedEventStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Outbound governance-event surface wiring (spec 2026-07-01 §3.7). Mirrors [RegistryConfig]'s style:
 * reads `HERMOD_*` via System.getenv INSIDE the @Bean methods. When `HERMOD_EMIT_MODE` is unset the
 * session/emitter beans are null → RunReconciler's nullable emitter stays null → the reconcile path is
 * byte-identical to today (no session constructed, no broker connect). The `emitted_event` dedup store
 * is always available (cheap, lazy connection) — it is also used by RunStore.clearArchive on retry.
 */
@Configuration
class EmitConfig {
    private fun on() = System.getenv("HERMOD_EMIT_MODE") != null

    @Bean fun emittedEventStore(ds: HikariDataSource): EmittedEventStore = EmittedEventStore { ds.connection }

    @Bean fun edgeSession(cs: CanonicalSetpoints?): SparkplugEdgeSession? {
        if (!on()) return null
        val group = System.getenv("HERMOD_EMIT_GROUP") ?: "Hermod"
        val edge  = System.getenv("HERMOD_EMIT_EDGE") ?: "Governance"
        val url   = System.getenv("HERMOD_EMIT_MQTT_URL") ?: error("HERMOD_EMIT_MQTT_URL required when HERMOD_EMIT_MODE is on")
        val transport = PahoEdgeNodeTransport(url, "hermod-$group-$edge")
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
