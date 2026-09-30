package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CandidatesTest {
    private fun ids(snapshot: Snapshot, unknowns: List<Unknown> = deriveUnknowns(snapshot)) =
        projectCandidates(snapshot, unknowns).map { it.candidateId }

    private fun edited(line: String, edit: ObjectNode.() -> Unit): String =
        mapper.writeValueAsString((mapper.readTree(line) as ObjectNode).apply(edit))

    @Test fun `a found remedy becomes APPROVE_REMEDY next to ESCALATE`() {
        val s = PicassoRun1.snapshot(searchIds = listOf("search-1"))
        val c = projectCandidates(s, emptyList())
        assertEquals(listOf("APPROVE_REMEDY:hum-02:PATROL-1:pick_place", "ESCALATE"), c.map { it.candidateId })
        assertEquals(mapOf("robotId" to "hum-02", "jobOrderId" to "PATROL-1", "searchId" to "search-1"), c[0].ref)
        assertEquals(listOf("pick_place"), c[0].sawSkillTypes)
    }

    @Test fun `NONE and WITHHELD searches offer nothing but ESCALATE`() {
        assertEquals(listOf("ESCALATE"), ids(PicassoRun1.snapshot(searchIds = listOf("search-2"))))
        assertEquals(listOf("ESCALATE"), ids(PicassoRun1.snapshot(searchIds = listOf("search-3"))))
    }

    @Test fun `a missing source offers each alternative`() {
        assertEquals(listOf("CHOOSE_SOURCE:SEQ-RELOCATE:ENGINE-COVER-B:SEQ-IN-03.BIN-B", "ESCALATE"),
            ids(PicassoRun1.snapshot(searchIds = listOf("search-4"))))
    }

    @Test fun `alternatives null and alternatives empty both offer no source, for different reasons`() {
        val nullAlts = edited(PicassoRun1.search("search-4")) { putNull("alternatives") }
        val noAlts = edited(PicassoRun1.search("search-4")) { putArray("alternatives") }
        assertEquals(listOf("ESCALATE"), ids(Snapshot.parse(PicassoRun1.manifest, emptyList(), listOf(nullAlts))))
        assertEquals(listOf("ESCALATE"), ids(Snapshot.parse(PicassoRun1.manifest, emptyList(), listOf(noAlts))))
    }

    @Test fun `an unresolved incident awaiting a decision offers CONFIRM_DONE and REWORK`() {
        val s = PicassoRun1.snapshot(incidentIds = listOf("incident-6"))
        assertEquals(
            listOf("ESCALATE", "OPERATOR_DECISION:exec-8:RACK-204.S06:CONFIRM_DONE", "OPERATOR_DECISION:exec-8:RACK-204.S06:REWORK"),
            ids(s, unknowns = emptyList()),
        )
    }

    @Test fun `unknowns remove every execution-class candidate but keep CONFIRM_DONE and ESCALATE`() {
        val s = PicassoRun1.snapshot(incidentIds = listOf("incident-6"), searchIds = listOf("search-1", "search-4"))
        assertEquals(listOf("ESCALATE", "OPERATOR_DECISION:exec-8:RACK-204.S06:CONFIRM_DONE"), ids(s))
    }

    @Test fun `an episode-level unknown passed by the caller blocks execution too`() {
        val outcome = Unknown(mapOf("robotId" to "hum-02"), UnknownWhat.OUTCOME, null, "picasso")
        assertEquals(listOf("ESCALATE"), ids(PicassoRun1.snapshot(searchIds = listOf("search-1")), listOf(outcome)))
    }

    @Test fun `a resolved or already-decided incident offers no decision`() {
        assertEquals(listOf("ESCALATE"), ids(PicassoRun1.snapshot(incidentIds = listOf("incident-1")), emptyList()))
        val decided = edited(PicassoRun1.incident("incident-6")) {
            putObject("resolution").put("decision", "REWORK")
        }
        assertEquals(listOf("ESCALATE"), ids(Snapshot.parse(PicassoRun1.manifest, listOf(decided), emptyList()), emptyList()))
    }

    @Test fun `several steps join with plus, and colons or percent signs in values are encoded`() {
        val line = edited(PicassoRun1.search("search-1")) {
            put("jobOrderId", "A:B%C")
            putArray("steps").apply { addObject().put("skillType", "pick_place"); addObject().put("skillType", "navigate_to") }
        }
        assertEquals(listOf("APPROVE_REMEDY:hum-02:A%3AB%25C:pick_place+navigate_to", "ESCALATE"),
            ids(Snapshot.parse(PicassoRun1.manifest, emptyList(), listOf(line)), emptyList()))
    }

    @Test fun `a step without a skillType withholds the remedy instead of offering a partial one`() {
        val line = edited(PicassoRun1.search("search-1")) {
            putArray("steps").apply { addObject().put("skillType", "pick_place"); addObject().put("expectedHold", "HOLD_KIND_EMPTY") }
        }
        assertEquals(listOf("ESCALATE"), ids(Snapshot.parse(PicassoRun1.manifest, emptyList(), listOf(line)), emptyList()))
    }

    @Test fun `the same action seen twice is one candidate, the first line wins`() {
        val again = edited(PicassoRun1.search("search-1")) { put("searchId", "search-9") }
        val s = Snapshot.parse(PicassoRun1.manifest, emptyList(), listOf(PicassoRun1.search("search-1"), again))
        val c = projectCandidates(s, emptyList())
        assertEquals(2, c.size)
        assertEquals("search-1", c[0].ref!!["searchId"])
    }

    @Test fun `JSON shape follows the contract example`() {
        val c = projectCandidates(PicassoRun1.snapshot(searchIds = listOf("search-1", "search-4")), emptyList())
        val remedy = c.single { it.kind == CandidateKind.APPROVE_REMEDY }.toJson()
        assertEquals(listOf("candidateId", "kind", "ref", "sawSkillTypes"), remedy.fieldNames().asSequence().toList())
        val source = c.single { it.kind == CandidateKind.CHOOSE_SOURCE }.toJson()
        assertEquals(listOf("candidateId", "kind", "ref"), source.fieldNames().asSequence().toList())
        val escalate = c.single { it.kind == CandidateKind.ESCALATE }.toJson()
        assertEquals("ESCALATE", escalate.get("candidateId").textValue())
        assertEquals(true, escalate.get("ref").isNull)
        assertNull(escalate.get("sawSkillTypes"))
    }
}
