package koshei.episode

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The four diagnosis-request fixtures of the contract (§8), built by the real projection from picasso run-1 lines.
 * koshei owns the requests; narrator writes the matching responses. Regenerate with
 * `./gradlew :episode:test --tests 'koshei.episode.DiagnosisRequestFixturesTest' -Dkoshei.updateFixtures=true`.
 */
class DiagnosisRequestFixturesTest {
    private class Example(val file: String, val incidents: List<String>, val searches: List<String>) {
        val episodeId = "ep:fixture-" + file.removeSuffix(".json")
    }

    private val examples = listOf(
        Example("01-recommended.json", incidents = listOf("incident-1"), searches = listOf("search-1")),
        Example("02-no-grounds.json", incidents = emptyList(), searches = listOf("search-2")),
        Example("03-out-of-candidates.json", incidents = emptyList(), searches = listOf("search-4")),
        Example("04-unknown.json", incidents = listOf("incident-6"), searches = emptyList()),
    )

    private fun request(e: Example): ObjectNode =
        diagnosisRequest(e.episodeId, 1, PicassoRun1.snapshot(e.incidents, e.searches))

    private fun ids(e: Example) = request(e).get("candidates").map { it.get("candidateId").textValue() }
    private fun whats(e: Example) = request(e).get("unknowns").map { it.get("what").textValue() }

    @Test fun `01 recommended offers the found remedy and ESCALATE`() {
        assertEquals(listOf("APPROVE_REMEDY:hum-02:PATROL-1:pick_place", "ESCALATE"), ids(examples[0]))
        assertEquals(emptyList(), whats(examples[0]))
    }

    @Test fun `02 no-grounds offers only ESCALATE`() {
        assertEquals(listOf("ESCALATE"), ids(examples[1]))
    }

    @Test fun `03 out-of-candidates offers the single alternative source`() {
        assertEquals(listOf("CHOOSE_SOURCE:SEQ-RELOCATE:ENGINE-COVER-B:SEQ-IN-03.BIN-B", "ESCALATE"), ids(examples[2]))
    }

    @Test fun `04 unknown keeps only CONFIRM_DONE and ESCALATE and reports the broken link`() {
        assertEquals(listOf("ESCALATE", "OPERATOR_DECISION:exec-8:RACK-204.S06:CONFIRM_DONE"), ids(examples[3]))
        assertEquals(listOf("LINK_BROKEN"), whats(examples[3]))
    }

    @Test fun `every fixture obeys the contract's request-reading rules`() {
        for (e in examples) {
            val r = request(e)
            val m = r.get("snapshot").get("manifest")
            assertTrue(m.get("schemaVersion").isTextual && m.get("schemaVersion").textValue() == "5", e.file)
            assertTrue(r.get("snapshot").get("incidents").size() + r.get("snapshot").get("searches").size() > 0, e.file)
            val cs = r.get("candidates")
            val ids = cs.map { it.get("candidateId").textValue() }
            assertTrue(ids.isNotEmpty() && ids.size == ids.toSet().size && "ESCALATE" in ids, e.file)
            cs.forEach { c ->
                val escalate = c.get("kind").textValue() == "ESCALATE"
                assertEquals(escalate, c.get("candidateId").textValue() == "ESCALATE", e.file)
                assertEquals(escalate, c.get("ref").isNull, e.file)
                c.get("sawSkillTypes")?.let { st -> assertTrue(st.isArray && st.all { it.isTextual }, e.file) }
            }
            assertTrue(Regex("sha256:[0-9a-f]{64}").matches(r.get("candidatesVersion").textValue()), e.file)
            r.get("unknowns").forEach { assertEquals(listOf("subject", "what", "since", "source"), it.fieldNames().asSequence().toList(), e.file) }
            assertTrue(r.get("history").isArray, e.file)
        }
    }

    @Test fun `committed fixtures match the projection`() {
        val dir = File("src/test/resources/contract/diagnosis/requests")
        val update = System.getProperty("koshei.updateFixtures") == "true"
        val lf = DefaultIndenter("  ", "\n")
        val writer = mapper.writer(DefaultPrettyPrinter().withObjectIndenter(lf).withArrayIndenter(lf))
        for (e in examples) {
            val actual = request(e)
            val f = File(dir, e.file)
            if (update) {
                dir.mkdirs()
                f.writeText(writer.writeValueAsString(actual) + "\n", Charsets.UTF_8)
            } else {
                assertTrue(f.isFile, "missing fixture ${f.path}: run with -Dkoshei.updateFixtures=true")
                assertEquals(mapper.readTree(f), actual, "${e.file} no longer matches the projection")
            }
        }
    }
}
