package koshei.episode

import koshei.episode.Policies.obj
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PolicyResolutionTest {
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private val broken = Policies.node { obj("deadlines").put("recordMs", 0) }

    @Test fun `no active policy is POLICY_MISSING, not in force, but the last valid one is kept for retention`() {
        val last = Policies.valid()
        val r = resolvePolicy(PolicyRead.Missing, lastValid = last, now = now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.POLICY_MISSING, r.reason)
        assertSame(last, r.policy)
        assertNull(r.adopt)
        assertTrue(r.rejectedErrors.isEmpty())
        assertNull((resolvePolicy(PolicyRead.Missing, null, now) as PolicyResolution.Off).policy)
    }

    @Test fun `a valid, live, enabled policy is active and adopted`() {
        val r = resolvePolicy(PolicyRead.Found(Policies.node()), lastValid = null, now = now)
        assertIs<PolicyResolution.Active>(r)
        assertEquals("2026-09-30.1", r.policy.version)
        assertFalse(r.autoApproveSuspended)
        assertNotNull(r.adopt)
        assertSame(r.policy, r.adopt)
        assertTrue(r.rejectedErrors.isEmpty())
    }

    @Test fun `a valid table with a future expiresAt is active`() {
        val r = resolvePolicy(PolicyRead.Found(Policies.node { put("expiresAt", "2026-10-01T00:00:00Z") }), null, now)
        assertIs<PolicyResolution.Active>(r)
    }

    @Test fun `a switched-off policy is AGENT_LAYER_OFF and keeps the table`() {
        val r = resolvePolicy(PolicyRead.Found(Policies.node { put("agentLayerEnabled", false) }), null, now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.AGENT_LAYER_OFF, r.reason)
        assertEquals("2026-09-30.1", r.policy!!.version)
        assertNotNull(r.adopt)
    }

    @Test fun `a policy expiring now is already expired`() {
        val r = resolvePolicy(PolicyRead.Found(Policies.node { put("expiresAt", now.toString()) }), null, now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.POLICY_EXPIRED, r.reason)
    }

    @Test fun `an invalid read keeps the last valid policy, suspends auto-approval and reports why`() {
        val last = Policies.valid()
        val r = resolvePolicy(PolicyRead.Found(broken), lastValid = last, now = now)
        assertIs<PolicyResolution.Active>(r)
        assertSame(last, r.policy)
        assertTrue(r.autoApproveSuspended)
        assertNull(r.adopt)
        assertTrue(r.rejectedErrors.any { "recordMs" in it })
    }

    @Test fun `an invalid read with no last valid policy is POLICY_MISSING with the reasons`() {
        val r = resolvePolicy(PolicyRead.Found(broken), lastValid = null, now = now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.POLICY_MISSING, r.reason)
        assertNull(r.policy)
        assertNull(r.adopt)
        assertTrue(r.rejectedErrors.any { "recordMs" in it })
    }

    @Test fun `an unreadable file keeps the last valid policy like an invalid one`() {
        val last = Policies.valid()
        val r = resolvePolicy(PolicyRead.Unreadable("line 3: mapping values are not allowed"), lastValid = last, now = now)
        assertIs<PolicyResolution.Active>(r)
        assertSame(last, r.policy)
        assertTrue(r.autoApproveSuspended)
        assertNull(r.adopt)
        assertTrue(r.rejectedErrors.single().contains("unreadable"))
        assertEquals(PolicyOffReason.POLICY_MISSING, (resolvePolicy(PolicyRead.Unreadable("x"), null, now) as PolicyResolution.Off).reason)
    }

    @Test fun `an explicit off switch wins even in a table rejected for another reason`() {
        val last = Policies.valid()
        val offButBroken = Policies.node { put("agentLayerEnabled", false); obj("deadlines").put("recordMs", 0) }
        val r = resolvePolicy(PolicyRead.Found(offButBroken), lastValid = last, now = now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.AGENT_LAYER_OFF, r.reason)
        assertSame(last, r.policy)
        assertNull(r.adopt)
        assertTrue(r.rejectedErrors.any { "recordMs" in it })
    }

    @Test fun `an off switch in a rejected table with no last valid policy is still AGENT_LAYER_OFF`() {
        val offButBroken = Policies.node { put("agentLayerEnabled", false); obj("deadlines").put("recordMs", 0) }
        val r = resolvePolicy(PolicyRead.Found(offButBroken), lastValid = null, now = now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.AGENT_LAYER_OFF, r.reason)
        assertNull(r.policy)
    }

    @Test fun `any agentLayerEnabled value other than true is read as off in a rejected table`() {
        listOf<(com.fasterxml.jackson.databind.node.ObjectNode) -> Unit>(
            { it.put("agentLayerEnabled", "false") },
            { it.put("agentLayerEnabled", 0) },
            { it.put("agentLayerEnabled", "off") },
            { it.putNull("agentLayerEnabled") },
        ).forEach { edit ->
            val tree = Policies.node { obj("deadlines").put("recordMs", 0); edit(this) }
            val r = resolvePolicy(PolicyRead.Found(tree), lastValid = Policies.valid(), now = now)
            assertIs<PolicyResolution.Off>(r, tree.toString())
            assertEquals(PolicyOffReason.AGENT_LAYER_OFF, r.reason)
        }
    }

    @Test fun `a past expiresAt in a rejected table expires the policy instead of falling back`() {
        val last = Policies.valid()
        val tree = Policies.node { put("expiresAt", "2026-09-01T00:00:00Z"); obj("deadlines").put("recordMs", 0) }
        val r = resolvePolicy(PolicyRead.Found(tree), lastValid = last, now = now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.POLICY_EXPIRED, r.reason)
        assertSame(last, r.policy)
        assertNull(r.adopt)
    }

    @Test fun `a rejected table that turns auto-approval off suspends it on the last valid policy`() {
        val last = Policies.valid()
        val tree = Policies.node {
            (obj("autoApprove").get("APPROVE_REMEDY") as com.fasterxml.jackson.databind.node.ObjectNode).put("allowed", false)
            obj("deadlines").put("recordMs", 0)
        }
        val r = resolvePolicy(PolicyRead.Found(tree), lastValid = last, now = now)
        assertIs<PolicyResolution.Active>(r)
        assertSame(last, r.policy)
        assertTrue(r.autoApproveSuspended)
    }

    @Test fun `falling back to an expired last valid policy is POLICY_EXPIRED`() {
        val last = Policies.valid { put("expiresAt", "2026-09-01T00:00:00Z") }
        val r = resolvePolicy(PolicyRead.Found(broken), lastValid = last, now = now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.POLICY_EXPIRED, r.reason)
        assertSame(last, r.policy)
    }
}
