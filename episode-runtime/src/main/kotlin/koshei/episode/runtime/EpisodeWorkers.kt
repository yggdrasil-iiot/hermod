package koshei.episode.runtime

import io.temporal.worker.WorkerFactory
import io.temporal.worker.WorkerOptions
import java.nio.file.Path

/** Who answers `diagnose` on narrator-tq: koshei's Mock (plan B, P0) or narrator's own worker. Never both. */
enum class NarratorMode { MOCK, REMOTE }

/**
 * The approval window. Only the Mock exists until the host that reaches picasso's loopback window is placed (§19 C).
 * OFF (the default) runs no episode worker at all: the Mock is the test's (design §8.3, §11) — with it a worker would
 * take a fabricated APPROVED for a remedy that never reached picasso — so it runs only when chosen by name.
 */
enum class PicassoMode {
    OFF, MOCK;

    companion object {
        /** `KOSHEI_PICASSO`, read the same way by every process that must agree on episode mode (worker, control plane). */
        fun fromEnv(env: Map<String, String>): PicassoMode = when (val v = env["KOSHEI_PICASSO"] ?: "off") {
            "off" -> OFF
            "mock" -> MOCK
            else -> throw IllegalArgumentException("KOSHEI_PICASSO: only 'mock' until the host is placed (design §19 C), was '$v'")
        }
    }
}

/** The episode runtime's settings, read once at worker start (the workflow itself reads none, design §7.5). */
data class EpisodeRuntimeConfig(val policyPath: Path, val narrator: NarratorMode, val picasso: PicassoMode) {
    companion object {
        fun fromEnv(env: Map<String, String>): EpisodeRuntimeConfig = EpisodeRuntimeConfig(
            policyPath = Path.of(env["KOSHEI_EPISODE_POLICY"] ?: "model/episode-policy/active.yaml"),
            narrator = when (val v = env["KOSHEI_NARRATOR"] ?: "mock") {
                "mock" -> NarratorMode.MOCK
                "remote" -> NarratorMode.REMOTE
                else -> throw IllegalArgumentException("KOSHEI_NARRATOR must be mock or remote, was '$v'")
            },
            picasso = PicassoMode.fromEnv(env),
        )
    }
}

/**
 * Registers the episode workflow and its activities (design §4.1, §4.2). The caller starts the factory. [config]'s
 * [PicassoMode] decides whether anything is registered; [picasso] is the window used when it is (the Mock by default,
 * the only one until §19 C).
 */
object EpisodeWorkers {
    fun register(factory: WorkerFactory, config: EpisodeRuntimeConfig, store: EpisodeStore, picasso: ApprovalWindow = MockPicasso()) {
        if (config.picasso == PicassoMode.OFF) return
        factory.newWorker(
            EPISODE_TASK_QUEUE,
            // The same guard as the saga worker: a cold machine replaying parked episodes can exceed the 1 s default.
            WorkerOptions.newBuilder().setDefaultDeadlockDetectionTimeout(10_000).build(),
        ).apply {
            registerWorkflowImplementationTypes(EpisodeWorkflowImpl::class.java)
            registerActivitiesImplementations(EpisodeActivitiesImpl(PolicyFileReader(config.policyPath), store, picasso))
        }
        if (config.narrator == NarratorMode.MOCK) {
            factory.newWorker(NARRATOR_TASK_QUEUE).registerActivitiesImplementations(MockNarratorActivities())
        }
    }
}
