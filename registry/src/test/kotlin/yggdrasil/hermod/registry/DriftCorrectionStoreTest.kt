package yggdrasil.hermod.registry

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriftCorrectionStoreTest {
    private lateinit var store: DriftCorrectionStore

    @BeforeTest fun setup() {
        RegistryDbSupport.reset()
        RegistryDbSupport.connection().use { it.createStatement().execute("TRUNCATE drift_correction") }
        store = DriftCorrectionStore(RegistryDbSupport::connection)
    }

    @Test fun `activePending is null until inserted then returns the pending row`() {
        assertNull(store.activePending("line1"))
        store.insertPending("line1", "r1", 11, 6, "ot-safe-hold")
        val row = store.activePending("line1")!!
        assertEquals("line1", row.unit); assertEquals("r1", row.runId); assertEquals("PENDING", row.status)
    }

    @Test fun `resolve terminalizes so activePending returns null`() {
        store.insertPending("line1", "r1", 11, 6, "ot-safe-hold")
        val id = store.activePending("line1")!!.id
        store.resolve(id, "RESOLVED")
        assertNull(store.activePending("line1"))
    }

    @Test fun `activePending returns the newest pending row after a prior failed`() {
        store.insertPending("line1", "r1", 11, 6, "ot-safe-hold")
        store.resolve(store.activePending("line1")!!.id, "FAILED")
        store.insertPending("line1", "r2", 11, 6, "ot-safe-hold")
        assertEquals("r2", store.activePending("line1")!!.runId)
    }

    @Test fun `allPending spans units and excludes terminal rows`() {
        store.insertPending("line1", "r1", 11, 6, "ot-safe-hold")
        store.insertPending("line2", "r2", 11, 6, "ot-safe-hold")
        store.resolve(store.activePending("line1")!!.id, "RESOLVED")
        val p = store.allPending()
        assertEquals(1, p.size); assertEquals("line2", p[0].unit)
    }

    @Test fun `insertPending returns true first then false while one is PENDING (partial-unique index)`() {
        assertTrue(store.insertPending("line1", "r1", 11, 6, "ot-safe-hold"))
        assertFalse(store.insertPending("line1", "r2", 11, 6, "ot-safe-hold"))
        assertEquals(1, store.allPending().size)
        store.resolve(store.activePending("line1")!!.id, "FAILED")
        assertTrue(store.insertPending("line1", "r3", 11, 6, "ot-safe-hold"))
    }
    @Test fun `Row carries dispatchedAtEpochMs`() {
        store.insertPending("line1", "r1", 11, 6, "ot-safe-hold")
        assertTrue(store.activePending("line1")!!.dispatchedAtEpochMs > 0L)
        assertTrue(store.allPending().first().dispatchedAtEpochMs > 0L)
    }
}
