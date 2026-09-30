package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import koshei.episode.Policies.obj
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyTest {
    private fun List<String>.has(fragment: String) = assertTrue(any { fragment in it }, "no error containing '$fragment' in $this")

    @Test fun `the valid table parses with every field`() {
        val p = Policies.valid()
        assertEquals("2026-09-30.1", p.version)
        assertNull(p.expiresAt)
        assertTrue(p.agentLayerEnabled)
        assertEquals(AutoApproveRule(allowed = true, requireClean = true, skills = listOf("pick_place")), p.autoApprove[CandidateKind.APPROVE_REMEDY])
        assertEquals(3_600_000, p.deadlines.episodeMs)
        assertEquals(86_400_000, p.deadlines.escalatedRetentionMs)
        assertEquals(DiagnosisBudget(1_200_000, 540_000, 30_000, 2), p.diagnosis)
        assertEquals(3, p.maxAttemptsPerEpisode)
        assertEquals(2, p.repeatedRemedyThreshold)
        assertEquals(262_144, p.snapshotMaxBytes)
    }

    @Test fun `requireClean defaults to true and absent lists default to empty`() {
        val p = Policies.valid {
            (obj("autoApprove").get("APPROVE_REMEDY") as ObjectNode).remove("requireClean")
            remove("actionCatalog"); remove("correlation"); remove("expiresAt")
        }
        assertEquals(true, p.autoApprove[CandidateKind.APPROVE_REMEDY]!!.requireClean)
        assertEquals(emptyList(), p.correlation)
        assertNull(p.expiresAt)
    }

    @Test fun `expiresAt reads an ISO instant and rejects anything else`() {
        assertEquals(Instant.parse("2026-12-31T00:00:00Z"), Policies.valid { put("expiresAt", "2026-12-31T00:00:00Z") }.expiresAt)
        Policies.errors { put("expiresAt", "next week") }.has("expiresAt: expected an ISO-8601 instant or null")
    }

    @Test fun `an unknown key is an error, never a silent default`() {
        Policies.errors { put("maxAttemptsPerEpisod", 3) }.has("maxAttemptsPerEpisod: unknown key")
        Policies.errors { obj("deadlines").put("approvalMS", 1) }.has("deadlines.approvalMS: unknown key")
    }

    @Test fun `every violation is reported, not only the first`() {
        val errors = Policies.errors { put("version", ""); put("repeatedRemedyThreshold", 0) }
        errors.has("version")
        errors.has("repeatedRemedyThreshold")
    }

    @Test fun `missing sections and non-integers are errors`() {
        Policies.errors { remove("deadlines") }.has("deadlines: expected an object")
        Policies.errors { obj("deadlines").put("approvalMs", "5m") }.has("deadlines.approvalMs: expected an integer")
        Policies.errors { obj("deadlines").put("recordMs", 0) }.has("deadlines.recordMs: must be >= 1")
        Policies.errors { obj("diagnosis").remove("heartbeatMs") }.has("diagnosis.heartbeatMs")
    }

    @Test fun `the diagnosis budget must cover every attempt`() {
        Policies.errors { obj("diagnosis").put("scheduleToCloseMs", 1_000_000) }
            .has("diagnosis.scheduleToCloseMs (1000000) must cover maxAttempts × startToCloseMs")
    }

    @Test fun `the heartbeat must be shorter than one attempt`() {
        Policies.errors { obj("diagnosis").put("heartbeatMs", 540_000) }.has("diagnosis.heartbeatMs (540000) must be < startToCloseMs")
        assertEquals(539_999, Policies.valid { obj("diagnosis").put("heartbeatMs", 539_999) }.diagnosis.heartbeatMs)
    }

    @Test fun `the diagnosis budget must fit the episode`() {
        Policies.errors { obj("diagnosis").put("scheduleToCloseMs", 4_000_000) }
            .has("diagnosis.scheduleToCloseMs (4000000) must be <= deadlines.episodeMs")
    }

    @Test fun `the unknown recheck must come before the unknown limit`() {
        Policies.errors { obj("deadlines").put("unknownRecheckMs", 900_000) }
            .has("deadlines.unknownRecheckMs (900000) must be < deadlines.unknownMs")
    }

    @Test fun `state deadlines fit the episode but escalated retention may outlast it`() {
        Policies.errors { obj("deadlines").put("approvalMs", 4_000_000) }.has("deadlines.approvalMs must be <= deadlines.episodeMs")
        // escalatedRetentionMs (86_400_000) > episodeMs (3_600_000) is fine
        assertEquals(86_400_000, Policies.valid().deadlines.escalatedRetentionMs)
    }

    @Test fun `the snapshot limit stays under the payload warning line`() {
        Policies.errors { put("snapshotMaxBytes", 524_288) }.has("snapshotMaxBytes: must be < 524288")
        Policies.errors { put("snapshotMaxBytes", 0) }.has("snapshotMaxBytes: must be >= 1")
        assertEquals(524_287, Policies.valid { put("snapshotMaxBytes", 524_287) }.snapshotMaxBytes)
    }

    @Test fun `a root that is not an object is an error`() {
        val errors = (parsePolicy(mapper.readTree("[1]")) as PolicyParse.Invalid).errors
        errors.has("policy: expected an object")
    }

    @Test fun `agentLayerEnabled must be a boolean`() {
        Policies.errors { put("agentLayerEnabled", "yes") }.has("agentLayerEnabled: expected true or false")
    }

    @Test fun `ESCALATE has no auto-approval rule`() {
        Policies.errors { obj("autoApprove").putObject("ESCALATE").put("allowed", true).putArray("skills") }
            .has("autoApprove.ESCALATE")
    }

    @Test fun `autoApprove is required and skills must be a list`() {
        Policies.errors { remove("autoApprove") }.has("autoApprove: expected an object")
        Policies.errors { (obj("autoApprove").get("APPROVE_REMEDY") as ObjectNode).put("skills", "pick_place") }
            .has("autoApprove.APPROVE_REMEDY.skills: expected a list")
    }

    @Test fun `durations have a sane upper bound and the budget product cannot overflow`() {
        Policies.errors { obj("deadlines").put("escalatedRetentionMs", MAX_DURATION_MS + 1) }.has("deadlines.escalatedRetentionMs")
        // maxAttempts × startToCloseMs ≈ 6.8e19 would wrap negative in plain Long multiplication and pass the check.
        Policies.errors {
            obj("deadlines").put("episodeMs", MAX_DURATION_MS)
            obj("diagnosis").put("scheduleToCloseMs", MAX_DURATION_MS)
            obj("diagnosis").put("startToCloseMs", MAX_DURATION_MS)
            obj("diagnosis").put("maxAttempts", Int.MAX_VALUE)
        }.has("maxAttempts")
    }

    @Test fun `only APPROVE_REMEDY can be auto-approved`() {
        Policies.errors { obj("autoApprove").putObject("CHOOSE_SOURCE").put("allowed", true).putArray("skills") }
            .has("autoApprove.CHOOSE_SOURCE")
        Policies.errors { obj("autoApprove").putObject("OPERATOR_DECISION").put("allowed", true).putArray("skills") }
            .has("autoApprove.OPERATOR_DECISION")
        Policies.errors { obj("autoApprove").putObject("FOO").put("allowed", true) }.has("autoApprove.FOO: unknown key")
    }

    @Test fun `the source tree survives and re-parses to the same table`() {
        val p = Policies.valid()
        val again = parsePolicy(p.source)
        assertIs<PolicyParse.Valid>(again)
        assertEquals(p.version, again.policy.version)
    }

    @Test fun `the table keeps its own copy of the source`() {
        val tree = Policies.node()
        val p = (parsePolicy(tree) as PolicyParse.Valid).policy
        tree.put("version", "changed")
        assertEquals("2026-09-30.1", p.source.get("version").textValue())
    }

    @Test fun `the repeated-remedy threshold must be reachable within the attempt cap`() {
        Policies.errors { put("repeatedRemedyThreshold", 4) }
            .has("repeatedRemedyThreshold (4) must be <= maxAttemptsPerEpisode (3)")
        assertEquals(3, Policies.valid { put("repeatedRemedyThreshold", 3) }.repeatedRemedyThreshold)
    }

    @Test fun `actionCatalog entries wait for plan D`() {
        Policies.errors { putArray("actionCatalog").addObject().put("saga", "ot-safe-hold@1.0.0") }.has("actionCatalog")
    }
}
