package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals

class PicassoRunsTest {
    @Test fun `every copied run carries schema 5 and its own runId`() {
        val runIds = (1..4).map { n ->
            val m = mapper.readTree(PicassoRun(n).manifest)
            assertEquals("5", m.text("schemaVersion"))
            m.text("runId")
        }
        assertEquals(4, runIds.toSet().size)
    }

    @Test fun `run-3 has incidents but no searches`() {
        assertEquals("incident-3", mapper.readTree(PicassoRun(3).incident("incident-3")).text("incidentId"))
        assertEquals(0, PicassoRun(3).searchCount)
    }
}
