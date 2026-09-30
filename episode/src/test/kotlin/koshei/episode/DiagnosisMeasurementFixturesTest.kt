package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * narrator's recommendation-measurement requests (contract §7), built by koshei's real projection. The line sets are
 * narrator's choice (from the corpus's synthetic procedures, not from answers); the first line of each list opened the
 * episode. Regenerate with `./gradlew :episode:test --tests 'koshei.episode.DiagnosisMeasurementFixturesTest'
 * -Dkoshei.updateFixtures=true` (that run fails on purpose; rerun without the property).
 *
 * Only R02, R09, R15, R16 and R17 have tests that pin a cause; the other cases are pinned only by the committed files,
 * which catches drift, not wrong behaviour.
 */
class DiagnosisMeasurementFixturesTest {
    private class Case(val id: String, val run: Int, val incidents: List<String> = emptyList(), val searches: List<String> = emptyList())

    private val cases = listOf(
        Case("R01", 1, listOf("incident-1"), listOf("search-1")),
        Case("R02", 2, listOf("incident-1"), listOf("search-1")),
        Case("R03", 1, searches = listOf("search-1")),
        Case("R04", 1, searches = listOf("search-2")),
        Case("R05", 1, searches = listOf("search-3")),
        Case("R06", 1, searches = listOf("search-4")),
        Case("R07", 1, listOf("incident-6")),
        Case("R08", 1, listOf("incident-5")),
        Case("R09", 1, listOf("incident-5"), listOf("search-4")),
        Case("R10", 1, searches = listOf("search-2", "search-4")),
        Case("R11", 1, listOf("incident-2")),
        Case("R12", 1, listOf("incident-2"), listOf("search-3")),
        Case("R13", 1, listOf("incident-9")),
        Case("R14", 1, listOf("incident-4")),
        Case("R15", 1, listOf("incident-4", "incident-7", "incident-8", "incident-9")),
        Case("R16", 1, listOf("incident-4", "incident-6", "incident-7", "incident-8", "incident-9")),
        Case("R17", 3, listOf("incident-1", "incident-2", "incident-3")),
        Case("R18", 3, listOf("incident-3")),
        Case("R19", 4, listOf("incident-1"), listOf("search-1")),
        Case("R20", 4, listOf("incident-2"), listOf("search-2")),
        Case("R21", 4, listOf("incident-3")),
        Case("R22", 1, listOf("incident-3")),
    )

    private fun request(id: String): ObjectNode {
        val c = cases.single { it.id == id }
        return diagnosisRequest("ep:measure-${c.id}", 1, PicassoRun(c.run).snapshot(c.incidents, c.searches)).toJson()
    }

    private fun ids(id: String) = request(id).get("candidates").map { it.get("candidateId").textValue() }
    private fun whats(id: String) = request(id).get("unknowns").map { it.get("what").textValue() }

    @Test fun `there are 22 cases with distinct ids`() {
        assertEquals((1..22).map { "R%02d".format(it) }, cases.map { it.id })
    }

    @Test fun `every case obeys the contract's request-reading rules`() {
        cases.forEach { ContractFixtures.assertReadingRules(request(it.id), it.id) }
    }

    @Test fun `R02 repeats R01 on another run - same candidates, same version`() {
        assertEquals(ids("R01"), ids("R02"))
        assertEquals(request("R01").get("candidatesVersion"), request("R02").get("candidatesVersion"))
    }

    @Test fun `R09 - the unobserved incident withholds the alternative source`() {
        assertEquals(listOf("ESCALATE"), ids("R09"))
        assertEquals(listOf("PROGRESS_UNOBSERVED"), whats("R09"))
        // Cause, not just outcome: the same search without the unobserved incident (R06) does offer the alternative.
        assertTrue("CHOOSE_SOURCE:SEQ-RELOCATE:ENGINE-COVER-B:SEQ-IN-03.BIN-B" in ids("R06"))
    }

    @Test fun `R15 - four pending units offer both decisions each`() {
        val ids = ids("R15")
        assertEquals(9, ids.size)
        assertEquals(4, ids.count { it.endsWith(":CONFIRM_DONE") })
        assertEquals(4, ids.count { it.endsWith(":REWORK") })
    }

    @Test fun `R16 - an unknown from a joined line withholds every REWORK`() {
        val ids = ids("R16")
        assertEquals(listOf("LINK_BROKEN"), whats("R16"))
        assertTrue(ids.none { it.endsWith(":REWORK") })
        assertEquals(5, ids.count { it.endsWith(":CONFIRM_DONE") })
        // Cause, not just outcome: without the joined unknown line (R15) the same units do offer REWORK.
        assertTrue(ids("R15").any { it.endsWith(":REWORK") })
    }

    @Test fun `R17 - only the undecided unit offers decisions`() {
        assertEquals(
            listOf("ESCALATE", "OPERATOR_DECISION:exec-2:RACK-204.S02:CONFIRM_DONE", "OPERATOR_DECISION:exec-2:RACK-204.S02:REWORK"),
            ids("R17"),
        )
    }

    @Test fun `committed measurement requests match the projection`() {
        ContractFixtures.check("measurement", cases.associate { "${it.id}.json" to request(it.id) })
    }
}
