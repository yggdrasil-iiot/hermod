package yggdrasil.hermod.registry

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class FsmDeploymentStoreTest {
    private lateinit var store: FsmDeploymentStore

    @BeforeTest fun setup() {
        RegistryDbSupport.reset()
        RegistryDbSupport.connection().use {
            it.createStatement().execute("TRUNCATE fsm_deployment, fsm_deployment_audit")
        }
        store = FsmDeploymentStore(RegistryDbSupport::connection)
    }

    @Test fun `first deploy sets active with null previous and a DEPLOY audit`() {
        store.deploy("line1", "v1")
        assertEquals("v1", store.activeVersion("line1"))
        val a = store.audit("line1")
        assertEquals(1, a.size)
        assertEquals("DEPLOY", a[0].action); assertNull(a[0].fromVersion); assertEquals("v1", a[0].toVersion)
    }

    @Test fun `second deploy chains previous_version and rollback swaps back`() {
        store.deploy("line1", "v1")
        store.deploy("line1", "v2")
        assertEquals("v2", store.activeVersion("line1"))
        val rolledTo = store.rollback("line1")
        assertEquals("v1", rolledTo)
        assertEquals("v1", store.activeVersion("line1"))
        val a = store.audit("line1").last()
        assertEquals("ROLLBACK", a.action); assertEquals("v2", a.fromVersion); assertEquals("v1", a.toVersion)
    }

    @Test fun `rollback with no previous version fails closed`() {
        store.deploy("line1", "v1")
        assertFailsWith<IllegalStateException> { store.rollback("line1") }
    }

    @Test fun `rollback on an undeployed unit fails closed`() {
        assertFailsWith<IllegalStateException> { store.rollback("ghost") }
    }

    @Test fun `deploying the already-active version leaves previous_version untouched`() {
        store.deploy("line1", "v1")
        store.deploy("line1", "v2")
        store.deploy("line1", "v2")
        assertEquals("v1", store.rollback("line1"))
    }

    @Test fun `per-unit isolation - deploying one unit does not touch another`() {
        store.deploy("line2", "v1")
        store.deploy("line1", "v2")
        assertEquals("v2", store.activeVersion("line1"))
        assertEquals("v1", store.activeVersion("line2"))
    }

    @Test fun `activeVersion is null for an undeployed unit`() {
        assertNull(store.activeVersion("nope"))
    }

    @Test fun `redeploying the already-active version still appends a DEPLOY audit row`() {
        store.deploy("line1", "v1")
        store.deploy("line1", "v1")   // already-active: pointer no-op, but an honest DEPLOY attempt log
        val a = store.audit("line1")
        assertEquals(2, a.size)
        assertEquals(listOf("DEPLOY", "DEPLOY"), a.map { it.action })
        assertEquals("v1", a.last().toVersion)
        assertEquals("v1", a.last().fromVersion)   // from == to on a redeploy of the active version
    }

    @Test fun `soak deploy sets soaking state with a future window`() {
        // arrange: v1 promoted (rollback target), then soak v2
        store.deploy("u", "v1")
        store.deploy("u", "v2", soakSeconds = 3600, failThreshold = 2)
        val r = store.soaking().single { it.unit == "u" }
        assertEquals("v2", r.activeVersion); assertEquals("v1", r.previousVersion)
        assertEquals(0, r.failCount); assertEquals(2, r.failThreshold)
        assertTrue(r.soakUntil.isAfter(java.time.Instant.now()))
    }

    @Test fun `plain deploy is promoted and not soaking`() {
        store.deploy("u", "v1"); store.deploy("u", "v2")
        assertTrue(store.soaking().none { it.unit == "u" })
    }

    @Test fun `recordFailure increments only while soaking`() {
        store.deploy("u", "v1"); store.deploy("u", "v2", soakSeconds = 3600, failThreshold = 5)
        assertTrue(store.recordFailure("u")); assertTrue(store.recordFailure("u"))
        assertEquals(2, store.soaking().single { it.unit == "u" }.failCount)
        store.promote("u")
        assertFalse(store.recordFailure("u"))   // promoted => no-op
    }

    @Test fun `soak on a first-ever deploy is rejected`() {
        val ex = assertFailsWith<IllegalStateException> { store.deploy("fresh", "v1", soakSeconds = 3600) }
        assertTrue(ex.message!!.contains("no prior version"))
        assertNull(store.activeVersion("fresh"))    // pointer unchanged
    }

    @Test fun `same-version soak with a null previous is rejected`() {
        store.deploy("u", "v1")   // previous_version is null here
        assertFailsWith<IllegalStateException> { store.deploy("u", "v1", soakSeconds = 3600) }
    }

    // Exercises the SAME-VERSION soak UPDATE branch (5-placeholder interval bind) — the plan's #1 latent-bug
    // risk. A different-version deploy first gives a non-null previous, so the same-version re-soak is allowed.
    @Test fun `same-version soak re-enters soaking via the update branch`() {
        store.deploy("u", "v1"); store.deploy("u", "v2")           // previous_version := v1
        store.deploy("u", "v2", soakSeconds = 3600, failThreshold = 3)  // same-version soak -> UPDATE branch
        val r = store.soaking().single { it.unit == "u" }
        assertEquals("v2", r.activeVersion); assertEquals("v1", r.previousVersion)
        assertEquals(3, r.failThreshold); assertEquals(0, r.failCount)
        assertTrue(r.soakUntil.isAfter(java.time.Instant.now()))
    }

    @Test fun `auto-rollback via rollback action sets promoted and audits AUTO_ROLLBACK`() {
        store.deploy("u", "v1"); store.deploy("u", "v2", soakSeconds = 3600, failThreshold = 1)
        val to = store.rollback("u", "supervisor", "AUTO_ROLLBACK")
        assertEquals("v1", to); assertEquals("v1", store.activeVersion("u"))
        assertTrue(store.soaking().none { it.unit == "u" })   // status promoted
        assertEquals("AUTO_ROLLBACK", store.audit("u").last().action)
    }

    @Test fun `promote flips status and audits PROMOTE`() {
        store.deploy("u", "v1"); store.deploy("u", "v2", soakSeconds = 3600, failThreshold = 2)
        store.promote("u")
        assertTrue(store.soaking().none { it.unit == "u" })
        assertEquals("PROMOTE", store.audit("u").last().action)
    }

    @Test fun `activeUnits returns every deployed unit, ordered, empty when none`() {
        assertEquals(emptyList<String>(), store.activeUnits())
        store.deploy("line2", "v1")
        store.deploy("line1", "v1")
        assertEquals(listOf("line1", "line2"), store.activeUnits())   // ORDER BY unit
    }
}
