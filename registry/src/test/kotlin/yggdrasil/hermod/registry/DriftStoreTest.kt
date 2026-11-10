package yggdrasil.hermod.registry

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DriftStoreTest {
    private lateinit var store: DriftStore
    @BeforeTest fun setup() {
        RegistryDbSupport.reset()
        RegistryDbSupport.connection().use { it.createStatement().execute("TRUNCATE drift_observation, drift_audit") }
        store = DriftStore(RegistryDbSupport::connection)
    }
    @Test fun `lastState is null until observed then returns the last code`() {
        assertNull(store.lastState("u"))
        store.observe("u", null, 4, "BASELINE", "-"); assertEquals(4, store.lastState("u"))
        store.observe("u", 4, 6, "OK", "-"); assertEquals(6, store.lastState("u"))
    }
    @Test fun `observe appends audit rows oldest-first with baseline null from_code`() {
        store.observe("u", null, 4, "BASELINE", "-")
        store.observe("u", 4, 6, "OK", "-")
        store.observe("u", 6, 4, "DRIFT", "undeclared transition Execute -> Idle")
        val a = store.audit("u")
        assertEquals(3, a.size)
        assertEquals("BASELINE", a[0].verdict); assertNull(a[0].fromCode); assertEquals(4, a[0].toCode)
        assertEquals("OK", a[1].verdict); assertEquals(4, a[1].fromCode)
        assertEquals("DRIFT", a[2].verdict); assertEquals(6, a[2].fromCode); assertEquals(4, a[2].toCode)
        assertEquals("undeclared transition Execute -> Idle", a[2].detail)
    }
    @Test fun `recordCorrection appends a HOLD audit row and leaves the observation pointer unchanged`() {
        // Simulate a prior drift-check that recorded DRIFT and advanced the pointer to Execute(6).
        store.observe("u", 11, 6, "DRIFT", "undeclared transition Held -> Execute")
        assertEquals(6, store.lastState("u"))

        store.recordCorrection("u", 6, 11, "ot-safe-hold")

        val a = store.audit("u")
        assertEquals(2, a.size)
        assertEquals("DRIFT", a[0].verdict)
        assertEquals("HOLD", a[1].verdict)
        assertEquals(6, a[1].fromCode)
        assertEquals(11, a[1].toCode)
        // The KEY invariant: the corrective record does NOT advance the observation pointer.
        assertEquals(6, store.lastState("u"))
    }
    @Test fun `recordDenyAlarm appends a DENY audit row and leaves the observation pointer unchanged`() {
        store.observe("u", 11, 6, "DRIFT", "Held -> Execute")
        store.recordDenyAlarm("u", 6, "unknown state code")
        val a = store.audit("u")
        assertEquals("DENY", a.last().verdict)
        assertEquals(6, a.last().fromCode); assertEquals(6, a.last().toCode)
        assertEquals(6, store.lastState("u"))
    }
}
