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

    @Test fun `picasso names the real approval window - a loopback URL and an agent id are required`() {
        val c = EpisodeRuntimeConfig.fromEnv(mapOf(
            "KOSHEI_PICASSO" to "picasso", "KOSHEI_PICASSO_URL" to "http://127.0.0.1:8770", "KOSHEI_PICASSO_AGENT_ID" to "narrator-1",
        ))
        assertEquals(PicassoMode.PICASSO, c.picasso)
        assertEquals(PicassoWindowConfig(java.net.URI("http://127.0.0.1:8770/approvals"), "narrator-1", 8_000), c.window)
        val viaLocalhost = EpisodeRuntimeConfig.fromEnv(mapOf(
            "KOSHEI_PICASSO" to "picasso", "KOSHEI_PICASSO_URL" to "http://localhost:8770/", "KOSHEI_PICASSO_AGENT_ID" to "a",
            "KOSHEI_PICASSO_TIMEOUT_MS" to "15000",
        )).window!!
        assertEquals(15_000, viaLocalhost.timeoutMs)
        // localhost is checked once at start and then pinned: the client never looks it up again.
        assertEquals(java.net.URI("http://127.0.0.1:8770/approvals"), viaLocalhost.url)
    }

    @Test fun `the window's settings are refused unless exact`() {
        val ok = mapOf("KOSHEI_PICASSO" to "picasso", "KOSHEI_PICASSO_URL" to "http://127.0.0.1:8770", "KOSHEI_PICASSO_AGENT_ID" to "a")
        val bad = listOf(
            ok - "KOSHEI_PICASSO_URL",
            ok - "KOSHEI_PICASSO_AGENT_ID",
            ok + ("KOSHEI_PICASSO_AGENT_ID" to " "),
            ok + ("KOSHEI_PICASSO_URL" to "https://127.0.0.1:8770"),
            ok + ("KOSHEI_PICASSO_URL" to "http://10.0.0.5:8770"),
            ok + ("KOSHEI_PICASSO_URL" to "http://picasso.example:8770"),
            ok + ("KOSHEI_PICASSO_URL" to "http://127.0.0.1:8770/approvals"),
            ok + ("KOSHEI_PICASSO_URL" to "http://127.0.0.1"),
            ok + ("KOSHEI_PICASSO_URL" to "http://127.0.0.1:99999"),
            ok + ("KOSHEI_PICASSO_URL" to "http://[::1]:8770"),
            ok + ("KOSHEI_PICASSO_URL" to "http://127.0.0.1:8770?x=1"),
            ok + ("KOSHEI_PICASSO_URL" to "http://127.0.0.1:8770#f"),
            ok + ("KOSHEI_PICASSO_URL" to "http://u:p@127.0.0.1:8770"),
            ok + ("KOSHEI_PICASSO_URL" to "not a url"),
            ok + ("KOSHEI_PICASSO_TIMEOUT_MS" to "0"),
            ok + ("KOSHEI_PICASSO_TIMEOUT_MS" to "ten"),
        )
        for (env in bad) assertThrows<IllegalArgumentException>("$env") { EpisodeRuntimeConfig.fromEnv(env) }
    }

    @Test fun `a refused URL is never echoed with its user info`() {
        val ok = mapOf("KOSHEI_PICASSO" to "picasso", "KOSHEI_PICASSO_AGENT_ID" to "a")
        for (url in listOf(
            "http://u:hunter2@127.0.0.1:8770",
            "https://u:hunter2@127.0.0.1:8770",
            "http://u:hunter2@10.0.0.5:8770",
            "http://u:hunter2@127.0.0.1",
            "http://u:hunter2@127.0.0.1:99999",
            "http://u:hunter2@127.0.0.1:8770/approvals",
            "http://u:hunter2@127.0.0.1:8770?x=1",
            "http://u:hunter 2@127.0.0.1:8770",
        )) {
            val e = assertThrows<IllegalArgumentException>(url) { EpisodeRuntimeConfig.fromEnv(ok + ("KOSHEI_PICASSO_URL" to url)) }
            kotlin.test.assertFalse("hunter" in e.message!!, e.message)
        }
    }

    @Test fun `localhost must resolve to loopback only, IPv4 first - the reference host binds 127_0_0_1`() {
        val env = mapOf("KOSHEI_PICASSO_URL" to "http://localhost:8770", "KOSHEI_PICASSO_AGENT_ID" to "a")
        fun addr(vararg b: Int) = java.net.InetAddress.getByAddress("localhost", ByteArray(b.size) { b[it].toByte() })
        val v4 = addr(127, 0, 0, 1)
        val v6 = addr(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        assertEquals(java.net.URI("http://127.0.0.1:8770/approvals"), PicassoWindowConfig.fromEnv(env) { arrayOf(v4, v6) }.url)
        for (resolved in listOf(arrayOf(v6, v4), arrayOf(v4, addr(10, 0, 0, 5)), emptyArray())) {
            val e = assertThrows<IllegalArgumentException>(resolved.toList().toString()) { PicassoWindowConfig.fromEnv(env) { resolved } }
            kotlin.test.assertTrue("127.0.0.1" in e.message!!, e.message)
        }
        val e = assertThrows<IllegalArgumentException> { PicassoWindowConfig.fromEnv(env) { throw java.net.UnknownHostException("localhost") } }
        kotlin.test.assertTrue("127.0.0.1" in e.message!!, e.message)
        // 127.0.0.1 itself is never looked up.
        assertEquals("127.0.0.1", PicassoWindowConfig.fromEnv(env + ("KOSHEI_PICASSO_URL" to "http://127.0.0.1:8770")) { error("no lookup") }.url.host)
    }

    @Test fun `mock and off carry no window settings`() {
        kotlin.test.assertNull(EpisodeRuntimeConfig.fromEnv(mapOf("KOSHEI_PICASSO" to "mock")).window)
        kotlin.test.assertNull(EpisodeRuntimeConfig.fromEnv(emptyMap()).window)
        assertThrows<IllegalArgumentException> { EpisodeRuntimeConfig.fromEnv(mapOf("KOSHEI_PICASSO" to "http")) }
    }

    @Test fun `PicassoMode reads KOSHEI_PICASSO on its own, as the worker does`() {
        assertEquals(PicassoMode.OFF, PicassoMode.fromEnv(emptyMap()))
        assertEquals(PicassoMode.MOCK, PicassoMode.fromEnv(mapOf("KOSHEI_PICASSO" to "mock")))
        assertEquals(PicassoMode.PICASSO, PicassoMode.fromEnv(mapOf("KOSHEI_PICASSO" to "picasso")))
        assertThrows<IllegalArgumentException> { PicassoMode.fromEnv(mapOf("KOSHEI_PICASSO" to "Mock")) }
    }
}
