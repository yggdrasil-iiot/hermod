package koshei.episode

import koshei.episode.Policies.obj
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PolicyResolutionTest {
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private val broken = Policies.node { obj("deadlines").put("recordMs", 0) }

    @Test fun `no active policy is POLICY_MISSING even with a last valid one`() {
        val r = resolvePolicy(PolicyRead.Missing, lastValid = Policies.valid(), now = now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.POLICY_MISSING, r.reason)
        assertNull(r.policy)
        assertTrue(r.rejectedErrors.isEmpty())
    }

    @Test fun `a valid, live, enabled policy is active`() {
        val r = resolvePolicy(PolicyRead.Found(Policies.node()), lastValid = null, now = now)
        assertIs<PolicyResolution.Active>(r)
        assertEquals("2026-09-30.1", r.policy.version)
        assertTrue(r.rejectedErrors.isEmpty())
    }

    @Test fun `a switched-off policy is AGENT_LAYER_OFF and keeps the table`() {
        val r = resolvePolicy(PolicyRead.Found(Policies.node { put("agentLayerEnabled", false) }), null, now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.AGENT_LAYER_OFF, r.reason)
        assertEquals("2026-09-30.1", r.policy!!.version)
    }

    @Test fun `a policy expiring now is already expired`() {
        val r = resolvePolicy(PolicyRead.Found(Policies.node { put("expiresAt", now.toString()) }), null, now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.POLICY_EXPIRED, r.reason)
    }

    @Test fun `an invalid read keeps the last valid policy and reports why`() {
        val last = Policies.valid()
        val r = resolvePolicy(PolicyRead.Found(broken), lastValid = last, now = now)
        assertIs<PolicyResolution.Active>(r)
        assertSame(last, r.policy)
        assertTrue(r.rejectedErrors.any { "recordMs" in it })
    }

    @Test fun `an invalid read with no last valid policy is POLICY_MISSING with the reasons`() {
        val r = resolvePolicy(PolicyRead.Found(broken), lastValid = null, now = now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.POLICY_MISSING, r.reason)
        assertTrue(r.rejectedErrors.any { "recordMs" in it })
    }

    @Test fun `an unreadable file keeps the last valid policy like an invalid one`() {
        val last = Policies.valid()
        val r = resolvePolicy(PolicyRead.Unreadable("line 3: mapping values are not allowed"), lastValid = last, now = now)
        assertIs<PolicyResolution.Active>(r)
        assertSame(last, r.policy)
        assertTrue(r.rejectedErrors.single().contains("unreadable"))
        assertEquals(PolicyOffReason.POLICY_MISSING, (resolvePolicy(PolicyRead.Unreadable("x"), null, now) as PolicyResolution.Off).reason)
    }

    @Test fun `an explicit off switch wins even in a table rejected for another reason`() {
        val offButBroken = Policies.node { put("agentLayerEnabled", false); obj("deadlines").put("recordMs", 0) }
        val r = resolvePolicy(PolicyRead.Found(offButBroken), lastValid = Policies.valid(), now = now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.AGENT_LAYER_OFF, r.reason)
        assertTrue(r.rejectedErrors.any { "recordMs" in it })
    }

    @Test fun `falling back to an expired last valid policy is POLICY_EXPIRED`() {
        val last = Policies.valid { put("expiresAt", "2026-09-01T00:00:00Z") }
        val r = resolvePolicy(PolicyRead.Found(broken), lastValid = last, now = now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.POLICY_EXPIRED, r.reason)
        assertSame(last, r.policy)
    }
}
