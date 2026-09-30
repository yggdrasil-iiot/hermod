package koshei.episode

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Shared machinery for the committed diagnosis-contract fixtures under `src/test/resources/contract/diagnosis/<sub>/`.
 * Files must equal the serialized JSON byte for byte (UTF-8, LF; CRLF is normalised on the read side only as a
 * defence). With `-Dkoshei.updateFixtures=true` the files are rewritten and the check then FAILS on purpose, so an
 * update run can never pass for a green one.
 */
internal object ContractFixtures {
    private val lf = DefaultIndenter("  ", "\n")
    private val writer = mapper.writer(DefaultPrettyPrinter().withObjectIndenter(lf).withArrayIndenter(lf))

    fun check(sub: String, files: Map<String, ObjectNode>) {
        val root = System.getProperty("koshei.contractFixturesDir")
        assertTrue(!root.isNullOrBlank(), "system property koshei.contractFixturesDir is not set (episode/build.gradle.kts sets it)")
        val dir = File(root, sub)
        val update = System.getProperty("koshei.updateFixtures") == "true"
        for ((name, json) in files) {
            val expected = writer.writeValueAsString(json) + "\n"
            val f = File(dir, name)
            if (update) {
                dir.mkdirs()
                f.writeText(expected, Charsets.UTF_8)
            } else {
                assertTrue(f.isFile, "missing fixture ${f.path}: run with -Dkoshei.updateFixtures=true")
                assertEquals(expected, f.readText(Charsets.UTF_8).replace("\r\n", "\n"), "$sub/$name no longer matches the projection byte for byte")
            }
        }
        if (update) fail("fixtures regenerated; rerun without -Dkoshei.updateFixtures")
    }

    /** The diagnosis contract's request-reading rules (contract 0.6 §3). */
    fun assertReadingRules(r: ObjectNode, label: String) {
        val m = r.get("snapshot").get("manifest")
        assertTrue(m.get("schemaVersion").isTextual && m.get("schemaVersion").textValue() == "5", label)
        assertTrue(r.get("snapshot").get("incidents").size() + r.get("snapshot").get("searches").size() > 0, label)
        val cs = r.get("candidates")
        val ids = cs.map { it.get("candidateId").textValue() }
        assertTrue(ids.isNotEmpty() && ids.size == ids.toSet().size && "ESCALATE" in ids, label)
        cs.forEach { c ->
            val escalate = c.get("kind").textValue() == "ESCALATE"
            assertEquals(escalate, c.get("candidateId").textValue() == "ESCALATE", label)
            assertEquals(escalate, c.get("ref").isNull, label)
            c.get("sawSkillTypes")?.let { st -> assertTrue(st.isArray && st.all { it.isTextual }, label) }
        }
        assertTrue(Regex("sha256:[0-9a-f]{64}").matches(r.get("candidatesVersion").textValue()), label)
        r.get("unknowns").forEach { assertEquals(listOf("subject", "what", "since", "source"), it.fieldNames().asSequence().toList(), label) }
        assertTrue(r.get("history").isArray, label)
    }
}
