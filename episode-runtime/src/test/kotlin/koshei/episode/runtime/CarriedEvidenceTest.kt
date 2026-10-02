package koshei.episode.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CarriedEvidenceTest {
    private fun read(json: String) = CarriedEvidence.of(strictJson.readTree(json), receivedAtMillis = 9)

    @Test fun `a report travels field by field`() {
        val r = assertIs<CarriedEvidence.Line.Routed>(read(
            """{"jobResponseId":"resp-1","jobOrderId":"PATROL-1","executionId":"exec-2","physicalState":"S","reachedEvidence":"E1",
               "completedUnits":["u1"],"inDoubtUnits":[],"unverifiedUnits":[],"operatorRequired":false,"blockedBy":["X"]}""",
        ))
        assertEquals("PATROL-1", r.jobOrderId)
        assertEquals(EvidenceSignal("resp-1", "exec-2", "S", "E1", listOf("u1"), emptyList(), emptyList(), false, 9), r.signal)
    }

    @Test fun `null and absent are not reported - never empty, never false`() {
        val r = assertIs<CarriedEvidence.Line.Routed>(read("""{"jobResponseId":"resp-1","jobOrderId":"P","completedUnits":null}"""))
        assertNull(r.signal.executionId)
        assertNull(r.signal.completedUnits)
        assertNull(r.signal.inDoubtUnits)
        assertNull(r.signal.operatorRequired)
    }

    @Test fun `no order is unrouted, not broken`() {
        assertIs<CarriedEvidence.Line.Unrouted>(read("""{"jobResponseId":"resp-1"}"""))
        assertIs<CarriedEvidence.Line.Unrouted>(read("""{"jobResponseId":"resp-1","jobOrderId":7}"""))
    }

    @Test fun `a field of the wrong shape is broken`() {
        listOf(
            """{"jobOrderId":"P"}""",
            """{"jobResponseId":"","jobOrderId":"P"}""",
            """{"jobResponseId":"r","jobOrderId":"P","executionId":3}""",
            """{"jobResponseId":"r","jobOrderId":"P","completedUnits":"u1"}""",
            """{"jobResponseId":"r","jobOrderId":"P","inDoubtUnits":[1]}""",
            """{"jobResponseId":"r","jobOrderId":"P","operatorRequired":"false"}""",
        ).forEach { assertIs<CarriedEvidence.Line.Broken>(read(it), it) }
    }
}
