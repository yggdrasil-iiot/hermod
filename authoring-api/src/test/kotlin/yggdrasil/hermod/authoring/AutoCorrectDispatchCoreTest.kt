package yggdrasil.hermod.authoring

import yggdrasil.hermod.opcua.AutoCorrectAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoCorrectDispatchCoreTest {
    private fun correctable(unit: String) =
        AutoCorrectAction.DriftCorrectable(unit, from = 11, to = 6, workflow = "ot-safe-hold", driftReason = "bypass")

    @Test fun `dispatches when no correction is pending`() {
        val dispatched = mutableListOf<String>()
        AutoCorrectDispatchCore.run(
            actions = listOf(correctable("line1")),
            pendingRuns = emptyList(), now = 0L, staleAfterMillis = 3_600_000L,
            runStatus = { null }, resolve = { _, _ -> },
            activePending = { false }, dispatch = { u, _, _, _ -> dispatched += u })
        assertEquals(listOf("line1"), dispatched)
    }

    @Test fun `dedup - does not dispatch while a correction is pending`() {
        val dispatched = mutableListOf<String>()
        AutoCorrectDispatchCore.run(
            actions = listOf(correctable("line1")),
            pendingRuns = emptyList(), now = 0L, staleAfterMillis = 3_600_000L,
            runStatus = { null }, resolve = { _, _ -> },
            activePending = { true }, dispatch = { u, _, _, _ -> dispatched += u })
        assertTrue(dispatched.isEmpty())
    }

    @Test fun `reconcile - COMPLETED resolves, other terminal fails, non-terminal stays`() {
        val resolved = mutableMapOf<Long, String>()
        AutoCorrectDispatchCore.run(
            actions = emptyList(),
            pendingRuns = listOf(Triple(1L, "rC", 0L), Triple(2L, "rF", 0L), Triple(3L, "rR", 0L)),
            now = 0L, staleAfterMillis = 3_600_000L,
            runStatus = { when (it) { "rC" -> "WORKFLOW_EXECUTION_STATUS_COMPLETED"; "rF" -> "TERMINATED"; else -> "RUNNING" } },
            resolve = { id, s -> resolved[id] = s }, activePending = { false }, dispatch = { _, _, _, _ -> })
        assertEquals("RESOLVED", resolved[1L]); assertEquals("FAILED", resolved[2L]); assertNull(resolved[3L])
    }

    @Test fun `reconcile runs before dispatch so a just-completed correction frees a new dispatch`() {
        val pending = mutableSetOf("line1")
        val dispatched = mutableListOf<String>()
        AutoCorrectDispatchCore.run(
            actions = listOf(correctable("line1")),
            pendingRuns = listOf(Triple(1L, "r1", 0L)),
            now = 0L, staleAfterMillis = 3_600_000L,
            runStatus = { "COMPLETED" },
            resolve = { _, _ -> pending.remove("line1") },
            activePending = { pending.contains(it) },
            dispatch = { u, _, _, _ -> dispatched += u })
        assertEquals(listOf("line1"), dispatched)
    }

    @Test fun `runStatus null (query failed) leaves the row pending`() {
        val resolved = mutableMapOf<Long, String>()
        AutoCorrectDispatchCore.run(
            actions = emptyList(), pendingRuns = listOf(Triple(1L, "r1", 0L)),
            now = 0L, staleAfterMillis = 3_600_000L,
            runStatus = { null }, resolve = { id, s -> resolved[id] = s },
            activePending = { false }, dispatch = { _, _, _, _ -> })
        assertTrue(resolved.isEmpty())
    }

    @Test fun `age-out - null status past the stale window resolves FAILED`() {
        val resolved = mutableMapOf<Long, String>()
        AutoCorrectDispatchCore.run(
            actions = emptyList(),
            pendingRuns = listOf(Triple(1L, "gone", 0L)),
            now = 10_000L, staleAfterMillis = 5_000L,
            runStatus = { null }, resolve = { id, s -> resolved[id] = s },
            activePending = { false }, dispatch = { _, _, _, _ -> })
        assertEquals("FAILED", resolved[1L])
    }
    @Test fun `age-out - null status within the window stays PENDING`() {
        val resolved = mutableMapOf<Long, String>()
        AutoCorrectDispatchCore.run(
            actions = emptyList(), pendingRuns = listOf(Triple(1L, "maybe", 8_000L)),
            now = 10_000L, staleAfterMillis = 5_000L,
            runStatus = { null }, resolve = { id, s -> resolved[id] = s },
            activePending = { false }, dispatch = { _, _, _, _ -> })
        assertTrue(resolved.isEmpty())
    }
    @Test fun `age-out - a live parked run (non-null RUNNING) is never aged out regardless of age`() {
        val resolved = mutableMapOf<Long, String>()
        AutoCorrectDispatchCore.run(
            actions = emptyList(), pendingRuns = listOf(Triple(1L, "parked", 0L)),
            now = 10_000_000L, staleAfterMillis = 1L,
            runStatus = { "RUNNING" }, resolve = { id, s -> resolved[id] = s },
            activePending = { false }, dispatch = { _, _, _, _ -> })
        assertTrue(resolved.isEmpty())
    }
}
