package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeWorkersTest {
    @Test fun `defaults - the repo's policy path, mock narrator, and NO episode worker until picasso is chosen`() {
        val c = EpisodeRuntimeConfig.fromEnv(emptyMap())
        assertEquals(Path.of("model", "episode-policy", "active.yaml"), c.policyPath)
        assertEquals(NarratorMode.MOCK, c.narrator)
        assertEquals(PicassoMode.OFF, c.picasso)   // the Mock is the test's (design §8.3, §11): never by default
    }

    @Test fun `mock picasso is an explicit choice`() =
        assertEquals(PicassoMode.MOCK, EpisodeRuntimeConfig.fromEnv(mapOf("KOSHEI_PICASSO" to "mock")).picasso)

    @Test fun `with picasso off nothing is registered`() {
        val env = episodeEnvironment { }
        try {
            EpisodeWorkers.register(env.workerFactory, EpisodeRuntimeConfig.fromEnv(emptyMap()), EpisodeStore { error("no DB needed") })
            kotlin.test.assertNull(env.workerFactory.tryGetWorker(EPISODE_TASK_QUEUE))
            kotlin.test.assertNull(env.workerFactory.tryGetWorker(NARRATOR_TASK_QUEUE))
        } finally {
            env.close()
        }
    }

    @Test fun `a remote narrator leaves narrator-tq to narrator's own worker, and the episode worker takes any approval window`() {
        val env = episodeEnvironment { }
        val window = object : ApprovalWindow {
            override fun revalidate(candidateJson: String) = "UNKNOWN"
            override fun approve(intent: JsonNode, approverKind: String?) = error("not called")
        }
        try {
            val config = EpisodeRuntimeConfig.fromEnv(mapOf("KOSHEI_PICASSO" to "mock", "KOSHEI_NARRATOR" to "remote"))
            EpisodeWorkers.register(env.workerFactory, config, EpisodeStore { error("no DB needed") }, window)
            kotlin.test.assertNotNull(env.workerFactory.tryGetWorker(EPISODE_TASK_QUEUE))
            kotlin.test.assertNull(env.workerFactory.tryGetWorker(NARRATOR_TASK_QUEUE))
        } finally {
            env.close()
        }
    }

    @Test fun `the environment chooses`() {
        val c = EpisodeRuntimeConfig.fromEnv(mapOf("KOSHEI_EPISODE_POLICY" to "/etc/koshei/p.yaml", "KOSHEI_NARRATOR" to "remote"))
        assertEquals(Path.of("/etc/koshei/p.yaml"), c.policyPath)
        assertEquals(NarratorMode.REMOTE, c.narrator)
    }

    @Test fun `an unknown narrator mode stops the worker at start`() {
        assertThrows<IllegalArgumentException> { EpisodeRuntimeConfig.fromEnv(mapOf("KOSHEI_NARRATOR" to "llm")) }
    }

    @Test fun `only the mock picasso exists until the host is placed`() {
        val e = assertThrows<IllegalArgumentException> { EpisodeRuntimeConfig.fromEnv(mapOf("KOSHEI_PICASSO" to "http")) }
        kotlin.test.assertTrue("§19" in e.message!!, e.message)
    }
}
