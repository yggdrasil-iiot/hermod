package yggdrasil.hermod.authoring
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
class RunStatusAwaitingTest {
    @Test fun `isAwaitingApproval true when any node awaits approval`() {
        assertTrue(RunStatus.isAwaitingApproval(mapOf("a" to "DONE", "applyPLC" to "AWAITING_APPROVAL")))
    }
    @Test fun `isAwaitingApproval false when none awaits`() {
        assertFalse(RunStatus.isAwaitingApproval(mapOf("a" to "DONE", "b" to "RUNNING")))
        assertFalse(RunStatus.isAwaitingApproval(emptyMap()))
    }
}
