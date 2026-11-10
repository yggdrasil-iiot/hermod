package yggdrasil.hermod.registry

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunStoreTest {
    private lateinit var store: RunStore

    @BeforeTest fun setup() {
        RegistryDbSupport.reset()  // ensures schema (whole registry-schema.sql incl. run_index) + truncates block_index
        RegistryDbSupport.connection().use {
            it.createStatement().execute("TRUNCATE run_index, run_node_state, run_comp_event, emitted_event")
        }
        store = RunStore(RegistryDbSupport::connection)
    }

    @Test fun `record then get round-trips the run`() {
        store.record("r1", "diamond", "1.0.0", """{"slowMs":600}""")
        val got = store.get("r1")
        assertEquals("diamond", got?.workflowName)
        assertEquals("1.0.0", got?.workflowVersion)
        assertEquals("""{"slowMs":600}""", got?.paramsJson)
        assertTrue((got?.startedAtEpochMs ?: 0) > 0)
    }

    @Test fun `list returns rows newest-first`() {
        store.record("r1", "a", "1.0.0", "{}")
        Thread.sleep(5)  // ensure distinct started_at so DESC ordering is deterministic
        store.record("r2", "b", "1.0.0", "{}")
        val ids = store.list().map { it.runId }
        assertEquals(listOf("r2", "r1"), ids)
    }

    @Test fun `record is idempotent on duplicate run_id (no-op, no throw)`() {
        store.record("r1", "a", "1.0.0", "{}")
        store.record("r1", "a", "1.0.0", "{}")   // must NOT throw
        assertEquals(1, store.list().size)
    }

    @Test fun `get missing returns null`() { assertNull(store.get("nope")) }

    // --- engine column tests (v0.6a) ---

    @Test fun `record with conductor engine - engineOf returns conductor`() {
        store.record("rc1", "myflow", "1.0.0", "{}", engine = "conductor")
        assertEquals("conductor", store.engineOf("rc1"))
    }

    @Test fun `record with default engine - engineOf returns temporal`() {
        store.record("rt1", "myflow", "1.0.0", "{}")   // default engine = "temporal"
        assertEquals("temporal", store.engineOf("rt1"))
    }

    @Test fun `engineOf unknown runId returns temporal as default`() {
        assertEquals("temporal", store.engineOf("does-not-exist"))
    }

    @Test fun `list Row engine round-trips for conductor run`() {
        store.record("rc2", "myflow", "1.0.0", "{}", engine = "conductor")
        val row = store.list().first { it.runId == "rc2" }
        assertEquals("conductor", row.engine)
    }

    @Test fun `list Row engine round-trips for default temporal run`() {
        store.record("rt2", "myflow", "1.0.0", "{}")
        val row = store.list().first { it.runId == "rt2" }
        assertEquals("temporal", row.engine)
    }

    // --- run-state durable archive tests (v0.7) ---

    @Test fun `markTerminal sets final_status completed_at comp_outcome, then get exposes them`() {
        store.record("r1", "a", "1.0.0", "{}")
        store.markTerminal("r1", "WORKFLOW_EXECUTION_STATUS_COMPLETED", "NONE")
        val got = store.get("r1")!!
        assertEquals("WORKFLOW_EXECUTION_STATUS_COMPLETED", got.finalStatus)   // stored RAW
        assertEquals("NONE", got.compOutcome)
        assertTrue((got.completedAtEpochMs ?: 0) > 0)
    }

    @Test fun `markTerminal is write-once - a second call does not overwrite final_status or completed_at`() {
        store.record("r1", "a", "1.0.0", "{}")
        store.markTerminal("r1", "FAILED", "COMPENSATED")
        val first = store.get("r1")!!.completedAtEpochMs
        Thread.sleep(5)
        store.markTerminal("r1", "COMPLETED", "NONE")   // must be a no-op (WHERE final_status IS NULL)
        val after = store.get("r1")!!
        assertEquals("FAILED", after.finalStatus)
        assertEquals("COMPENSATED", after.compOutcome)
        assertEquals(first, after.completedAtEpochMs)
    }

    @Test fun `refreshCompOutcome updates comp_outcome without touching final_status`() {
        store.record("r1", "a", "1.0.0", "{}")
        store.markTerminal("r1", "FAILED", "COMPENSATED")
        store.refreshCompOutcome("r1", "COMP_FAILED")
        assertEquals("FAILED", store.get("r1")!!.finalStatus)
        assertEquals("COMP_FAILED", store.get("r1")!!.compOutcome)
    }

    @Test fun `snapshotNodeStates replaces the run's rows and readNodeStates round-trips`() {
        store.record("r1", "a", "1.0.0", "{}")
        store.snapshotNodeStates("r1", mapOf("n1" to "DONE", "n2" to "COMPENSATED"))
        assertEquals(mapOf("n1" to "DONE", "n2" to "COMPENSATED"), store.readNodeStates("r1"))
        store.snapshotNodeStates("r1", mapOf("n1" to "DONE"))   // replace-in-tx: n2 gone
        assertEquals(mapOf("n1" to "DONE"), store.readNodeStates("r1"))
    }

    @Test fun `snapshotCompEvents replaces and readCompEvents round-trips ordered by idx`() {
        store.record("r1", "a", "1.0.0", "{}")
        store.snapshotCompEvents("r1", listOf(
            RunStore.CompEventRow(1, "recordPlan", "db.upsert", "1.0.0", "COMPENSATED", 222L),
            RunStore.CompEventRow(0, "interlockAck", "notify.email", "1.0.0", "COMPENSATED", 111L),
        ))
        val got = store.readCompEvents("r1")
        assertEquals(listOf(0, 1), got.map { it.idx })   // ordered by idx
        assertEquals("interlockAck", got[0].nodeId)
    }

    @Test fun `archive columns are null for a freshly recorded run`() {
        store.record("r1", "a", "1.0.0", "{}")
        val got = store.get("r1")!!
        assertNull(got.finalStatus); assertNull(got.completedAtEpochMs); assertNull(got.compOutcome)
    }

    @Test fun `archivedOrInFlight returns in-flight runs and recently-completed ones`() {
        store.record("inflight", "a", "1.0.0", "{}")             // final_status null -> included
        store.record("fresh", "a", "1.0.0", "{}")
        store.markTerminal("fresh", "COMPLETED", "NONE")          // completed_at = now -> within grace -> included
        val ids = store.archivedOrInFlight(graceMs = 30_000).map { it.runId }.toSet()
        assertTrue("inflight" in ids); assertTrue("fresh" in ids)
    }

    @Test fun `clearArchive un-archives the run and drops node and comp snapshots (markTerminal can write again)`() {
        store.record("r1", "a", "1.0.0", "{}")
        store.markTerminal("r1", "FAILED", "COMPENSATED")
        store.snapshotNodeStates("r1", mapOf("preflight" to "FAILED", "recordPlan" to "COMPENSATED"))
        store.snapshotCompEvents("r1", listOf(
            RunStore.CompEventRow(0, "interlockAck", "notify.email", "1.0.0", "COMPENSATED", 111L)))

        store.clearArchive("r1")

        val cleared = store.get("r1")!!
        assertNull(cleared.finalStatus); assertNull(cleared.completedAtEpochMs); assertNull(cleared.compOutcome)
        assertEquals(emptyMap(), store.readNodeStates("r1"))
        assertEquals(emptyList(), store.readCompEvents("r1"))
        // write-once is armed again: a re-run can archive its NEW terminal outcome under the same runId.
        store.markTerminal("r1", "COMPLETED", "NONE")
        assertEquals("COMPLETED", store.get("r1")!!.finalStatus)
    }

    @Test fun `clearArchive also clears emitted_event so a retried run can re-emit`() {
        val emitted = EmittedEventStore(RegistryDbSupport::connection)
        store.record("r1", "a", "1.0.0", "{}")
        store.markTerminal("r1", "COMPLETED", "NONE")
        assertTrue(emitted.tryClaim("r1", "CONFIRMED", 1000L))
        assertFalse(emitted.tryClaim("r1", "CONFIRMED", 2000L))   // already claimed

        store.clearArchive("r1")

        assertTrue(emitted.tryClaim("r1", "CONFIRMED", 3000L))    // re-claimable after clearArchive
    }
}
