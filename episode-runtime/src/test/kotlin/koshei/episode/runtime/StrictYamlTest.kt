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

    @Test fun `an anchor on a scalar is refused`() = rejects("a: &x 1\n", "anchor &x")

    @Test fun `an anchor in a sequence is refused`() = rejects("a: [&x 1, 2]\n", "anchor &x")

    @Test fun `an anchor on an empty value is refused`() = rejects("a: &x\nb: 1\n", "anchor &x")

    // The anchor comes first in the event stream and is refused there, before the alias is read.
    @Test fun `an anchored value and its alias are refused at the anchor`() = rejects("a: &x 1\nb: *x\n", "anchor &x")

    // No anchor precedes it, so this is the alias event itself (a full loader would call it an undefined alias).
    @Test fun `an alias alone is refused`() = rejects("b: *x\n", "alias *x")

    @Test fun `an anchor on a key is refused`() = rejects("&k allowed: true\n", "anchor &k")

    @Test fun `a tag on a mapping value is refused`() = rejects("a: !foo {b: 1}\n", "tag")

    @Test fun `a standard tag on a scalar is refused`() = rejects("a: !!str x\n", "tag")

    @Test fun `the non-specific tag is refused`() = rejects("a: ! x\n", "tag")   // SnakeYAML marks it implicit; its tag is "!"

    @Test fun `a tag on a key is refused`() = rejects("!!str k: 1\n", "tag")

    @Test fun `a YAML directive is refused`() = rejects("%YAML 1.2\n---\na: 1\n", "directive")

    @Test fun `a TAG directive is refused`() = rejects("%TAG !e! tag:example.com,2000:\n---\na: 1\n", "directive")

    @Test fun `an explicit document start without directives passes`() =
        assertEquals("""{"a":1}""", StrictYaml.toJson("---\na: 1\n"))

    // YAML 1.1 forms that Jackson reads as another value (or type) than their JSON look-alike would have.
    @Test fun `an octal-looking integer is refused`() = rejects("a: 010\n", "'010'")

    @Test fun `a hex integer is refused`() = rejects("a: 0x10\n", "'0x10'")

    @Test fun `a binary integer is refused`() = rejects("a: 0b1\n", "'0b1'")

    @Test fun `an integer with underscores is refused`() = rejects("a: 1_000\n", "'1_000'")

    @Test fun `an integer with a plus sign is refused`() = rejects("a: +1\n", "'+1'")

    @Test fun `a float with a trailing dot is refused`() = rejects("a: 1000.\n", "'1000.'")

    @Test fun `a float with a leading dot is refused`() = rejects("a: .5\n", "'.5'")

    @Test fun `yes is not a boolean`() = rejects("a: yes\n", "'yes'")

    @Test fun `on is not a boolean`() = rejects("a: on\n", "'on'")

    @Test fun `off is not a boolean`() = rejects("a: off\n", "'off'")

    @Test fun `True is not a boolean`() = rejects("a: True\n", "'True'")

    @Test fun `canonical scalars and quoted look-alikes pass`() = assertEquals(
        json.readTree("""{"a":-12,"b":0,"c":1.5,"d":-0.25e3,"e":true,"f":false,"g":null,"h":"010","i":"yes"}"""),
        json.readTree(StrictYaml.toJson("a: -12\nb: 0\nc: 1.5\nd: -0.25e3\ne: true\nf: false\ng: null\nh: \"010\"\ni: 'yes'\n")),
    )

    @Test fun `a second document is refused`() = rejects("version: \"1\"\n---\nversion: \"2\"\n", "document")

    @Test fun `an empty file is refused`() = rejects("", "empty")

    @Test fun `a comment-only file is refused`() = rejects("# nothing\n", "empty")

    @Test fun `a syntax error is refused`() = rejects("a: [1, 2\n", "")

    @Test fun `an empty value is null`() = assertEquals("""{"a":null}""", StrictYaml.toJson("a:\n"))

    @Test fun `a lone document start is a null document`() = assertEquals("null", StrictYaml.toJson("---\n"))

    @Test fun `a list root passes through - the core decides what a table is`() =
        assertEquals("[1,2]", StrictYaml.toJson("- 1\n- 2\n"))
}
