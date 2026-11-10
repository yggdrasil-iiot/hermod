package yggdrasil.hermod.authoring

import yggdrasil.hermod.runtime.CompensationEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunStatusTest {
    @Test fun `isTerminal matches Temporal proto names after normalization`() {
        assertTrue(RunStatus.isTerminal("WORKFLOW_EXECUTION_STATUS_COMPLETED"))
        assertTrue(RunStatus.isTerminal("WORKFLOW_EXECUTION_STATUS_FAILED"))
        assertTrue(RunStatus.isTerminal("WORKFLOW_EXECUTION_STATUS_TERMINATED"))
        assertFalse(RunStatus.isTerminal("WORKFLOW_EXECUTION_STATUS_RUNNING"))
    }
    @Test fun `isTerminal matches Conductor short names`() {
        assertTrue(RunStatus.isTerminal("COMPLETED"))
        assertTrue(RunStatus.isTerminal("TIMED_OUT"))
        assertTrue(RunStatus.isTerminal("CANCELED")); assertTrue(RunStatus.isTerminal("CANCELLED"))
        assertFalse(RunStatus.isTerminal("RUNNING")); assertFalse(RunStatus.isTerminal("PAUSED"))
    }
    @Test fun `summarizeCompOutcome - empty timeline is NONE`() {
        assertEquals("NONE", RunStatus.summarizeCompOutcome(emptyList()))
    }
    @Test fun `summarizeCompOutcome - all compensated is COMPENSATED`() {
        assertEquals("COMPENSATED", RunStatus.summarizeCompOutcome(listOf(
            CompensationEvent(0, "a", "x", "1.0.0", "COMPENSATED", 1),
            CompensationEvent(1, "b", "y", "1.0.0", "COMPENSATED", 2))))
    }
    @Test fun `summarizeCompOutcome - any failed is COMP_FAILED`() {
        assertEquals("COMP_FAILED", RunStatus.summarizeCompOutcome(listOf(
            CompensationEvent(0, "a", "x", "1.0.0", "FAILED", 1),
            CompensationEvent(1, "b", "y", "1.0.0", "COMPENSATED", 2))))
    }
}
