package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The four diagnosis-request fixtures of the contract (§8), built by the real projection from picasso run-1 lines.
 * koshei owns the requests; narrator writes the matching responses. The committed files must equal the serialized
 * request byte for byte (LF endings, UTF-8; CRLF is normalised to LF on the read side only as a defence).
 *
 * Regenerate with
 * `./gradlew :episode:test --tests 'koshei.episode.DiagnosisRequestFixturesTest' -Dkoshei.updateFixtures=true`.
 * The update run writes the files and then FAILS on purpose ("fixtures regenerated"), so it can never be mistaken for
 * a green check; rerun without the property to verify.
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
        diagnosisRequest(e.episodeId, 1, PicassoRun1.snapshot(e.incidents, e.searches)).toJson()

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
        for (e in examples) ContractFixtures.assertReadingRules(request(e), e.file)
    }

    @Test fun `committed fixtures match the projection`() {
        ContractFixtures.check("requests", examples.associate { it.file to request(it) })
    }
}
