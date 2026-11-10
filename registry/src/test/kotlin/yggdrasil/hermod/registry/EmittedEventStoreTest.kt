package yggdrasil.hermod.registry

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmittedEventStoreTest {
    @BeforeTest fun setup() {
        RegistryDbSupport.reset()   // ensures schema (whole registry-schema.sql incl. emitted_event)
        RegistryDbSupport.connection().use {
            it.createStatement().execute("TRUNCATE emitted_event")   // mirror RunStoreTest: isolate per test
        }
    }
    private val store = EmittedEventStore(RegistryDbSupport::connection)
    private var n = 0
    private fun runId() = "wf-emit-${n++}"

    @Test fun `tryClaim returns true once then false for the same key`() {
        val r = runId()
        assertTrue(store.tryClaim(r, "CONFIRMED", 1000L))
        assertFalse(store.tryClaim(r, "CONFIRMED", 2000L))   // duplicate → no re-claim
        assertTrue(store.tryClaim(r, "RECONCILING", 1500L))  // different type → independent
    }

    @Test fun `claimed reflects whether a type was already claimed`() {
        val r = runId()
        assertFalse(store.claimed(r, "CONFIRMED"))
        store.tryClaim(r, "CONFIRMED", 1000L)
        assertTrue(store.claimed(r, "CONFIRMED"))
        assertFalse(store.claimed(r, "RECON_FAILED"))        // independent per type
    }

    @Test fun `clearForRun deletes all rows so a retried run can re-emit`() {
        val r = runId()
        store.tryClaim(r, "CONFIRMED", 1000L)
        store.clearForRun(r)
        assertTrue(store.tryClaim(r, "CONFIRMED", 3000L))     // re-claimable after clear
    }
}
