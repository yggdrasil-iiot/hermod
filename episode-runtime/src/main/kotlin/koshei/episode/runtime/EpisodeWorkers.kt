package koshei.episode.runtime

import io.temporal.worker.WorkerFactory
import io.temporal.worker.WorkerOptions
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path

/** Who answers `diagnose` on narrator-tq: koshei's Mock (plan B, P0) or narrator's own worker. Never both. */
enum class NarratorMode { MOCK, REMOTE }

/**
 * The approval window (design §8.3, §8.4). OFF (the default) runs no episode worker at all. MOCK is the test's — with it a
 * worker would take a fabricated APPROVED for a remedy that never reached picasso — so it runs only when chosen by name.
 * PICASSO is the real window, `POST /approvals` on a loopback URL (plan C3): picasso's reference host today, a site's
 * deployment host later (§19 C).
 */
enum class PicassoMode {
    OFF, MOCK, PICASSO;

    companion object {
        /** `KOSHEI_PICASSO`, read the same way by every process that must agree on episode mode (worker, watcher, control plane). */
        fun fromEnv(env: Map<String, String>): PicassoMode = when (val v = env["KOSHEI_PICASSO"] ?: "off") {
            "off" -> OFF
            "mock" -> MOCK
            "picasso" -> PICASSO
            else -> throw IllegalArgumentException("KOSHEI_PICASSO must be off, mock or picasso (design §19 C), was '$v'")
        }
    }
}

/**
 * The real window's settings (plan C3). [url] is the full `…/approvals` URL on a loopback host: the window does not
 * authenticate (picasso §15.3), so approver ids never leave the machine. [agentId] is the approver id a POLICY approval
 * sends as AGENT. [timeoutMs] bounds one request; keep it under the policy's `dispatchMs` so the activity's retries fit.
 */
data class PicassoWindowConfig(val url: URI, val agentId: String, val timeoutMs: Long) {
    companion object {
        /** IPv4 loopback only: picasso's reference host binds InetAddress.getLoopbackAddress() (127.0.0.1). */
        private val LOOPBACK = setOf("127.0.0.1", "localhost")

        fun fromEnv(env: Map<String, String>): PicassoWindowConfig {
            val raw = requireNotNull(env["KOSHEI_PICASSO_URL"]?.takeIf { it.isNotBlank() }) { "KOSHEI_PICASSO=picasso needs KOSHEI_PICASSO_URL (http://127.0.0.1:<port>)" }
            val base = try { URI(raw.trimEnd('/')) } catch (e: URISyntaxException) { throw IllegalArgumentException("KOSHEI_PICASSO_URL is not a URL: $raw") }
            require(base.scheme == "http") { "KOSHEI_PICASSO_URL must be http:// (the window is loopback only), was $raw" }
            require(base.host in LOOPBACK) { "KOSHEI_PICASSO_URL must name a loopback host (the window does not authenticate, picasso §15.3), was ${base.host}" }
            require(base.port > 0) { "KOSHEI_PICASSO_URL must name the port the window printed, was $raw" }
            require(base.path.isNullOrEmpty()) { "KOSHEI_PICASSO_URL is the window's origin; the client adds /approvals, was $raw" }
            require(base.rawQuery == null && base.rawFragment == null && base.rawUserInfo == null) { "KOSHEI_PICASSO_URL carries a query, fragment or user info, which would be dropped: $raw" }
            val agent = requireNotNull(env["KOSHEI_PICASSO_AGENT_ID"]?.takeIf { it.isNotBlank() }) { "KOSHEI_PICASSO=picasso needs KOSHEI_PICASSO_AGENT_ID (the AGENT approver id picasso declares)" }
            val timeout = env["KOSHEI_PICASSO_TIMEOUT_MS"]?.let { t -> t.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("KOSHEI_PICASSO_TIMEOUT_MS must be a positive number of ms, was $t") } ?: 8_000
            return PicassoWindowConfig(URI("http://${base.host}:${base.port}/approvals"), agent, timeout)
        }
    }
}

/** The episode runtime's settings, read once at worker start (the workflow itself reads none, design §7.5). */
data class EpisodeRuntimeConfig(
    val policyPath: Path,
    val narrator: NarratorMode,
    val picasso: PicassoMode,
    val window: PicassoWindowConfig? = null,
) {
    companion object {
        fun fromEnv(env: Map<String, String>): EpisodeRuntimeConfig {
            val picasso = PicassoMode.fromEnv(env)
            return EpisodeRuntimeConfig(
                policyPath = Path.of(env["KOSHEI_EPISODE_POLICY"] ?: "model/episode-policy/active.yaml"),
                narrator = when (val v = env["KOSHEI_NARRATOR"] ?: "mock") {
                    "mock" -> NarratorMode.MOCK
                    "remote" -> NarratorMode.REMOTE
                    else -> throw IllegalArgumentException("KOSHEI_NARRATOR must be mock or remote, was '$v'")
                },
                picasso = picasso,
                window = if (picasso == PicassoMode.PICASSO) PicassoWindowConfig.fromEnv(env) else null,
            )
        }
    }
}

/**
 * Registers the episode workflow and its activities (design §4.1, §4.2). The caller starts the factory. [config]'s
 * [PicassoMode] decides whether anything is registered; [picasso] is the window used when it is (the configured window:
 * the Mock for `mock`, [HttpApprovalWindow] for `picasso`; a test may pass its own).
 */
object EpisodeWorkers {
    fun register(factory: WorkerFactory, config: EpisodeRuntimeConfig, store: EpisodeStore, picasso: ApprovalWindow? = null) {
        if (config.picasso == PicassoMode.OFF) return
        val window = picasso ?: when (config.picasso) {
            PicassoMode.MOCK -> MockPicasso()
            PicassoMode.PICASSO -> HttpApprovalWindow(checkNotNull(config.window) { "KOSHEI_PICASSO=picasso without window settings" })
            PicassoMode.OFF -> error("unreachable")
        }
        factory.newWorker(
            EPISODE_TASK_QUEUE,
            // The same guard as the saga worker: a cold machine replaying parked episodes can exceed the 1 s default.
            WorkerOptions.newBuilder().setDefaultDeadlockDetectionTimeout(10_000).build(),
        ).apply {
            registerWorkflowImplementationTypes(EpisodeWorkflowImpl::class.java)
            registerActivitiesImplementations(EpisodeActivitiesImpl(PolicyFileReader(config.policyPath), store, window))
        }
        if (config.narrator == NarratorMode.MOCK) {
            factory.newWorker(NARRATOR_TASK_QUEUE).registerActivitiesImplementations(MockNarratorActivities())
        }
    }
}
