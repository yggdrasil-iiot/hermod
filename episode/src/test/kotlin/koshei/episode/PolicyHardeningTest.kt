package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Review notes carried over from plan B2a. */
class PolicyHardeningTest {
    private val now = Instant.parse("2026-10-01T00:00:00Z")

    @Test fun `the off switch wins over expiry in a valid table, as it does in a rejected one`() {
        val tree = Policies.node { put("agentLayerEnabled", false); put("expiresAt", "2026-09-01T00:00:00Z") }
        val r = resolvePolicy(PolicyRead.Found(tree), null, now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.AGENT_LAYER_OFF, r.reason)
    }

    @Test fun `the source tree is handed out as a copy`() {
        val p = Policies.valid()
        (p.source as ObjectNode).put("version", "tampered")
        assertEquals("2026-09-30.1", p.source.get("version").textValue())
    }

    @Test fun `an unparsable expiresAt in a rejected table falls back to the last valid table`() {
        val r = resolvePolicy(PolicyRead.Found(Policies.node { put("expiresAt", "not a time"); put("bogus", 1) }), Policies.valid(), now)
        assertIs<PolicyResolution.Active>(r)
        assertTrue(r.autoApproveSuspended)
    }
}
