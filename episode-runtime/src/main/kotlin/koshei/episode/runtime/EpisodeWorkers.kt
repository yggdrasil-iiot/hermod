package koshei.episode.runtime

import io.temporal.worker.WorkerFactory
import io.temporal.worker.WorkerOptions
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
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
 * sends as AGENT. [timeoutMs] bounds one request, the whole exchange; the activity's three attempts must fit in the policy's
 * `dispatchMs` with the default backoff (1 s, 2 s), so keep `T ≤ (dispatchMs − 3000) / 3` (9000 for `dispatchMs` 30000).
 *
 * Made only by [fromEnv], which checks every value: the constructor (and `copy`) is internal, so no caller outside the
 * module can name a non-loopback URL.
 */
@ConsistentCopyVisibility
data class PicassoWindowConfig internal constructor(val url: URI, val agentId: String, val timeoutMs: Long) {
    companion object {
        /** IPv4 loopback only: picasso's reference host binds InetAddress.getLoopbackAddress() (127.0.0.1). */
        private val LOOPBACK = setOf("127.0.0.1", "localhost")

        /**
         * [resolve] looks `localhost` up (a test passes its own): every address must be loopback and the first IPv4, as
         * the client connects to the first and the reference host listens on 127.0.0.1 only.
         */
        fun fromEnv(env: Map<String, String>, resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName): PicassoWindowConfig {
            val raw = requireNotNull(env["KOSHEI_PICASSO_URL"]?.takeIf { it.isNotBlank() }) { "KOSHEI_PICASSO=picasso needs KOSHEI_PICASSO_URL (http://127.0.0.1:<port>)" }
            // The raw value may carry user info (user:password@): no message below repeats it, only scheme, host and port.
            val base = try { URI(raw.trimEnd('/')) } catch (e: URISyntaxException) {
                throw IllegalArgumentException("KOSHEI_PICASSO_URL is not a URL (${e.reason} at index ${e.index}); use http://127.0.0.1:<port>")
            }
            val shown = "${base.scheme}://${base.host ?: "<no host>"}${if (base.port >= 0) ":${base.port}" else ""}"
            require(base.scheme == "http") { "KOSHEI_PICASSO_URL must be http:// (the window is loopback only), was $shown" }
            require(base.host in LOOPBACK) { "KOSHEI_PICASSO_URL must name a loopback host (the window does not authenticate, picasso §15.3), was $shown" }
            require(base.port in 1..65535) { "KOSHEI_PICASSO_URL must name the port the window printed (1-65535), was $shown" }
            require(base.path.isNullOrEmpty()) { "KOSHEI_PICASSO_URL is the window's origin; the client adds /approvals, was $shown with a path" }
            require(base.rawQuery == null && base.rawFragment == null && base.rawUserInfo == null) { "KOSHEI_PICASSO_URL carries a query, fragment or user info, which would be dropped: $shown" }
            if (base.host == "localhost") {
                val addresses = try { resolve("localhost").toList() } catch (e: UnknownHostException) { emptyList() }
                require(addresses.isNotEmpty() && addresses.all { it.isLoopbackAddress } && addresses.first() is Inet4Address) {
                    "KOSHEI_PICASSO_URL: localhost resolves to $addresses here, not IPv4 loopback first (the window listens on 127.0.0.1 only); use http://127.0.0.1:${base.port}"
                }
            }
            val agent = requireNotNull(env["KOSHEI_PICASSO_AGENT_ID"]?.takeIf { it.isNotBlank() }) { "KOSHEI_PICASSO=picasso needs KOSHEI_PICASSO_AGENT_ID (the AGENT approver id picasso declares)" }
            val timeout = env["KOSHEI_PICASSO_TIMEOUT_MS"]?.let { t -> t.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("KOSHEI_PICASSO_TIMEOUT_MS must be a positive number of ms, was $t") } ?: 8_000
            // The verified loopback, pinned: `localhost` was checked above to mean 127.0.0.1 here, so the client never looks
            // it up again at runtime (where the answer could differ). The reference host does not check Host.
            return PicassoWindowConfig(URI("http://127.0.0.1:${base.port}/approvals"), agent, timeout)
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
