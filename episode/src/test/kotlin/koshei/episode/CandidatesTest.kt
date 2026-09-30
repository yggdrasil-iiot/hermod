package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test fun `skill lists that only differ by a plus sign get different ids`() {
        val joined = Candidate.approveRemedy("r", "j", listOf("a+b"), "s1")
        val two = Candidate.approveRemedy("r", "j", listOf("a", "b"), "s2")
        assertEquals("APPROVE_REMEDY:r:j:a%2Bb", joined.candidateId)
        assertEquals("APPROVE_REMEDY:r:j:a+b", two.candidateId)
        assertNotEquals(joined.candidateId, two.candidateId)
    }

    @Test fun `a line missing a field its candidate needs is skipped, ESCALATE stays`() {
        val noRobot = edited(PicassoRun1.search("search-1")) { remove("robotId") }
        val noJobOrder = edited(PicassoRun1.search("search-1")) { remove("jobOrderId") }
        val noMaterial = edited(PicassoRun1.search("search-4")) { remove("material") }
        val noSourceJob = edited(PicassoRun1.search("search-4")) { remove("jobOrderId") }
        for (line in listOf(noRobot, noJobOrder, noMaterial, noSourceJob))
            assertEquals(listOf("ESCALATE"), ids(Snapshot.parse(PicassoRun1.manifest, emptyList(), listOf(line)), emptyList()))
        for (field in listOf("executionId", "unitId")) {
            val pending = edited(PicassoRun1.incident("incident-6")) { remove(field) }
            assertEquals(listOf("ESCALATE"), ids(Snapshot.parse(PicassoRun1.manifest, listOf(pending), emptyList()), emptyList()))
        }
    }

    @Test fun `candidates with the same content are equal, different ones are not`() {
        assertEquals(Candidate.approveRemedy("r", "j", listOf("x"), "s"), Candidate.approveRemedy("r", "j", listOf("x"), "s"))
        assertEquals(Candidate.approveRemedy("r", "j", listOf("x"), "s").hashCode(), Candidate.approveRemedy("r", "j", listOf("x"), "s").hashCode())
        assertNotEquals(Candidate.approveRemedy("r", "j", listOf("x"), "s1"), Candidate.approveRemedy("r", "j", listOf("x"), "s2"))
        assertNotEquals<Any>(Candidate.ESCALATE, Candidate.chooseSource("j", "m", "s", "a", "id"))
        assertEquals(Candidate.ESCALATE, Candidate.ESCALATE)
    }

    @Test fun `only CONFIRM_DONE stays outside the execution class`() {
        val confirm = Candidate.operatorDecision("e", "u", OperatorDecision.CONFIRM_DONE)
        val rework = Candidate.operatorDecision("e", "u", OperatorDecision.REWORK)
        assertFalse(confirm.executionClass)
        assertTrue(rework.executionClass)
        assertEquals("CONFIRM_DONE", confirm.ref!!["decision"])
        assertEquals("OPERATOR_DECISION:e:u:REWORK", rework.candidateId)
        assertTrue(Candidate.approveRemedy("r", "j", listOf("x"), "s").executionClass)
        assertFalse(Candidate.ESCALATE.executionClass)
    }

    @Test fun `the skill list is copied defensively`() {
        val skills = mutableListOf("a")
        val c = Candidate.approveRemedy("r", "j", skills, "s")
        skills.add("b")
        assertEquals(listOf("a"), c.sawSkillTypes)
        assertEquals("APPROVE_REMEDY:r:j:a", c.candidateId)
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

    @Test fun `a missing source's ref names both the missing slot and the alternative`() {
        val c = projectCandidates(PicassoRun1.snapshot(searchIds = listOf("search-4")), emptyList())
            .single { it.kind == CandidateKind.CHOOSE_SOURCE }
        assertEquals(
            mapOf(
                "jobOrderId" to "SEQ-RELOCATE", "material" to "ENGINE-COVER-B", "missingSource" to "SEQ-IN-03.BIN-A",
                "alternative" to "SEQ-IN-03.BIN-B", "searchId" to "search-4",
            ),
            c.ref,
        )
        assertEquals(
            listOf("jobOrderId", "material", "missingSource", "alternative", "searchId"),
            c.toJson().get("ref").fieldNames().asSequence().toList(),
        )
        assertEquals("CHOOSE_SOURCE:SEQ-RELOCATE:ENGINE-COVER-B:SEQ-IN-03.BIN-B", c.candidateId)
    }

    @Test fun `a missing slot picasso left null stays null in the ref`() {
        val line = edited(PicassoRun1.search("search-4")) { putNull("source") }
        val c = projectCandidates(Snapshot.parse(PicassoRun1.manifest, emptyList(), listOf(line)), emptyList())
            .single { it.kind == CandidateKind.CHOOSE_SOURCE }
        assertEquals(null, c.ref!!["missingSource"])
        assertTrue(c.toJson().get("ref").get("missingSource").isNull)
    }
}
