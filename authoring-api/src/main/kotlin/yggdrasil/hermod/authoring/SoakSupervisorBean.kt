package yggdrasil.hermod.authoring

import yggdrasil.hermod.registry.FsmDeploymentStore
import yggdrasil.hermod.registry.SoakSupervisor
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/** Thin production wrapper: periodically runs SoakSupervisor.sweep. Fail-open (never throws out of the
 *  scheduler). HERMOD_SOAK_DISABLED=1 silences it (mirrors HERMOD_RECONCILER_DISABLED). See design 2026-07-03 §8. */
@Component
class SoakSupervisorBean(private val store: FsmDeploymentStore) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val disabled = System.getenv("HERMOD_SOAK_DISABLED") == "1"

    @Scheduled(fixedDelayString = "\${hermod.soak.fixedDelayMs:15000}",
               initialDelayString = "\${hermod.soak.initialDelayMs:15000}")
    fun sweep() {
        if (disabled) return
        try { SoakSupervisor.sweep(store, Instant.now()) }
        catch (e: Exception) { log.warn("soak sweep failed: {}", e.message) }
    }
}
