package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnknownsTest {
    private fun incidentWith(observation: String?): String {
        val base = mapper.readTree(PicassoRun1.incident("incident-1")) as com.fasterxml.jackson.databind.node.ObjectNode
        if (observation == null) base.remove("observation") else base.set<com.fasterxml.jackson.databind.JsonNode>("observation", mapper.readTree(observation))
        return mapper.writeValueAsString(base)
    }

    private fun whats(incident: String) =
        deriveUnknowns(Snapshot.parse(PicassoRun1.manifest, listOf(incident), emptyList())).map { it.what }

    @Test fun `a broken link is an unknown with the incident's subject and time`() {
        val u = deriveUnknowns(PicassoRun1.snapshot(incidentIds = listOf("incident-6")))
        assertEquals(1, u.size)
        assertEquals(UnknownWhat.LINK_BROKEN, u[0].what)
        assertEquals(mapOf("robotId" to "hum-07", "executionId" to "exec-8", "unitId" to "RACK-204.S06"), u[0].subject)
        assertEquals("2026-09-06T00:02:31Z", u[0].since)
        assertEquals("picasso", u[0].source)
    }

    @Test fun `a fully observed healthy incident has no unknowns`() {
        assertTrue(deriveUnknowns(PicassoRun1.snapshot(incidentIds = listOf("incident-1"))).isEmpty())
    }

    @Test fun `progressObservable null is an unknown, not a safe value`() {
        assertEquals(listOf(UnknownWhat.PROGRESS_UNOBSERVED), whats(PicassoRun1.incident("incident-5")))
    }

    @Test fun `anything other than an explicit safe value is an unknown`() {
        assertEquals(listOf(UnknownWhat.OBSERVATION_ABSENT), whats(incidentWith(null)))
        assertEquals(listOf(UnknownWhat.OBSERVATION_ABSENT), whats(incidentWith("null")))
        assertEquals(listOf(UnknownWhat.LINK_BROKEN),
            whats(incidentWith("""{"lateEvents":[],"progressObservable":true,"progressStalled":false}""")))
        assertEquals(listOf(UnknownWhat.LATE_EVENTS),
            whats(incidentWith("""{"linkBroken":false,"lateEvents":["e1"],"progressObservable":true,"progressStalled":false}""")))
        assertEquals(listOf(UnknownWhat.PROGRESS_UNOBSERVED),
            whats(incidentWith("""{"linkBroken":false,"lateEvents":[],"progressObservable":false,"progressStalled":false}""")))
        assertEquals(listOf(UnknownWhat.PROGRESS_STALLED),
            whats(incidentWith("""{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":true}""")))
    }

    @Test fun `search lines carry no observation and produce no unknowns`() {
        assertTrue(deriveUnknowns(PicassoRun1.snapshot(searchIds = listOf("search-1", "search-4"))).isEmpty())
    }

    @Test fun `the JSON shape keeps all four fields`() {
        val json = deriveUnknowns(PicassoRun1.snapshot(incidentIds = listOf("incident-6")))[0].toJson()
        assertEquals(listOf("subject", "what", "since", "source"), json.fieldNames().asSequence().toList())
        assertEquals("LINK_BROKEN", json.get("what").textValue())
    }
}
