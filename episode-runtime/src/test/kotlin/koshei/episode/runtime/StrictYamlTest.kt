package koshei.episode.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StrictYamlTest {
    private val json = ObjectMapper()

    private fun rejects(yaml: String, mention: String) {
        val e = assertThrows<IllegalArgumentException> { StrictYaml.toJson(yaml) }
        assertTrue(mention in (e.message ?: ""), "message names '$mention': ${e.message}")
    }

    @Test fun `a mapping becomes the same JSON tree`() {
        val out = StrictYaml.toJson(
            """
            version: "2026-10-01.1"
            expiresAt: null
            agentLayerEnabled: true
            autoApprove:
              APPROVE_REMEDY: { allowed: false, requireClean: true, skills: [pick_place] }
            deadlines: { episodeMs: 3600000 }
            ratio: 0.5
            """.trimIndent(),
        )
        assertEquals(
            json.readTree(
                """{"version":"2026-10-01.1","expiresAt":null,"agentLayerEnabled":true,
                   "autoApprove":{"APPROVE_REMEDY":{"allowed":false,"requireClean":true,"skills":["pick_place"]}},
                   "deadlines":{"episodeMs":3600000},"ratio":0.5}""",
            ),
            json.readTree(out),
        )
    }

    @Test fun `a repeated top-level key is refused, so a later switch cannot override an earlier one`() =
        rejects("agentLayerEnabled: false\nversion: \"1\"\nagentLayerEnabled: true\n", "agentLayerEnabled")

    @Test fun `a repeated nested key is refused`() =
        rejects("autoApprove:\n  APPROVE_REMEDY:\n    allowed: false\n    allowed: true\n", "allowed")

    @Test fun `a repeated key in a flow mapping is refused`() =
        rejects("autoApprove: { APPROVE_REMEDY: { allowed: false, allowed: true } }\n", "allowed")

    @Test fun `an anchor is refused`() = rejects("base: &b { allowed: false }\nother: 1\n", "anchor")

    @Test fun `an alias is refused`() = rejects("a: &x 1\nb: *x\n", "anchor")   // the anchor is met first

    @Test fun `an alias alone is refused`() = rejects("b: *x\n", "alias")   // an undefined alias arrives as a string token

    @Test fun `an anchor on a key is refused`() = rejects("&k allowed: true\n", "key")

    @Test fun `a second document is refused`() = rejects("version: \"1\"\n---\nversion: \"2\"\n", "document")

    @Test fun `an empty file is refused`() = rejects("", "empty")

    @Test fun `a comment-only file is refused`() = rejects("# nothing\n", "empty")

    @Test fun `a syntax error is refused`() = rejects("a: [1, 2\n", "")

    @Test fun `a list root passes through - the core decides what a table is`() =
        assertEquals("[1,2]", StrictYaml.toJson("- 1\n- 2\n"))
}
