package yggdrasil.hermod.registry

import java.time.Instant

/** Auto-rollback-or-promote sweep over soaking deployments. Pure but for the injected store; `now` injected
 *  for deterministic gating. Per-row isolation so one unit's error can't starve the rest. See design 2026-07-03. */
object SoakSupervisor {
    sealed interface Action { val unit: String }
    data class RolledBack(override val unit: String, val from: String, val to: String) : Action
    data class Promoted(override val unit: String, val version: String) : Action

    fun sweep(store: FsmDeploymentStore, now: Instant): List<Action> =
        store.soaking().mapNotNull { r ->
            try {
                when {
                    r.failCount >= r.failThreshold -> RolledBack(r.unit, r.activeVersion, store.rollback(r.unit, "supervisor", "AUTO_ROLLBACK"))
                    !now.isBefore(r.soakUntil)      -> { store.promote(r.unit); Promoted(r.unit, r.activeVersion) }
                    else                            -> null
                }
            } catch (e: Exception) { null }   // per-row isolation
        }
}
