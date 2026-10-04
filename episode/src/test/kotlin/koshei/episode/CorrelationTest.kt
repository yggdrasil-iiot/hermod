package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CorrelationTest {
    private val mapper = ObjectMapper()
    private fun line(json: String): JsonNode = mapper.readTree(json)
    private val run = "run-2026-09-22T16:47:37.854173400Z-1"
    private val search1 = line("""{"searchId":"search-1","robotId":"hum-02","jobOrderId":"PATROL-1"}""")
    private val incident1 = line("""{"incidentId":"incident-1","robotId":"hum-02","jobOrderId":"PATROL-1","digest":"d0"}""")
    private val search2 = line("""{"searchId":"search-2","robotId":"hum-03","jobOrderId":"PATROL-2"}""")
    private val byRobotOrder = CorrelationRule(setOf(SymptomKind.SEARCH, SymptomKind.INCIDENT), listOf("robotId", "jobOrderId"))

    @Test fun `no rule - every symptom has its own key, the CLI's default`() =
        assertEquals(Correlation.Own("$run:search-1", emptyList()), correlate(SymptomKind.SEARCH, search1, run, "search-1", emptyList()))

    @Test fun `a search and an incident of one robot and order share a key`() {
        val a = correlate(SymptomKind.SEARCH, search1, run, "search-1", listOf(byRobotOrder))
        val b = correlate(SymptomKind.INCIDENT, incident1, run, "incident-1", listOf(byRobotOrder))
        assertEquals(Correlation.Merged("$run:by:robotId:hum-02:jobOrderId:PATROL-1"), a)
        assertEquals(a, b)
        assertEquals(Correlation.Merged("$run:by:robotId:hum-03:jobOrderId:PATROL-2"), correlate(SymptomKind.SEARCH, search2, run, "search-2", listOf(byRobotOrder)))
    }

    @Test fun `the run is part of the key - a second run of the same seed is not merged into the first`() {
        val other = correlate(SymptomKind.SEARCH, search1, "run-2", "search-1", listOf(byRobotOrder))
        assertEquals(Correlation.Merged("run-2:by:robotId:hum-02:jobOrderId:PATROL-1"), other)
    }

    @Test fun `a rule for other kinds does not apply`() {
        val searchesOnly = CorrelationRule(setOf(SymptomKind.SEARCH), listOf("robotId"))
        assertEquals(Correlation.Own("$run:incident-1", emptyList()), correlate(SymptomKind.INCIDENT, incident1, run, "incident-1", listOf(searchesOnly)))
    }

    @Test fun `a missing, non-text or unsafe value means the rule does not apply - never rewritten`() {
        val rule = listOf(byRobotOrder)
        listOf(
            """{"searchId":"s","robotId":"hum-02"}""",
            """{"searchId":"s","robotId":"hum-02","jobOrderId":null}""",
            """{"searchId":"s","robotId":"hum-02","jobOrderId":7}""",
            """{"searchId":"s","robotId":"hum-02","jobOrderId":"A:B"}""",
            """{"searchId":"s","robotId":"hum 02","jobOrderId":"P"}""",
            """{"searchId":"s","robotId":"","jobOrderId":"P"}""",
        ).forEach { assertEquals(Correlation.Own("$run:s", emptyList()), correlate(SymptomKind.SEARCH, line(it), run, "s", rule), it) }
    }

    @Test fun `two rules that disagree are ambiguous - not merged, both keys kept as references`() {
        val byRobot = CorrelationRule(setOf(SymptomKind.SEARCH), listOf("robotId"))
        val byOrder = CorrelationRule(setOf(SymptomKind.SEARCH), listOf("jobOrderId"))
        assertEquals(
            Correlation.Own("$run:search-1", listOf("$run:by:robotId:hum-02", "$run:by:jobOrderId:PATROL-1")),
            correlate(SymptomKind.SEARCH, search1, run, "search-1", listOf(byRobot, byOrder)),
        )
    }

    @Test fun `two rules that give the same key merge`() {
        val again = CorrelationRule(setOf(SymptomKind.SEARCH), listOf("robotId", "jobOrderId"))
        assertEquals(
            Correlation.Merged("$run:by:robotId:hum-02:jobOrderId:PATROL-1"),
            correlate(SymptomKind.SEARCH, search1, run, "search-1", listOf(byRobotOrder, again)),
        )
    }

    @Test fun `of three rules two give one key - the ambiguity lists exactly two keys in first-seen order`() {
        val byRobot = CorrelationRule(setOf(SymptomKind.SEARCH), listOf("robotId"))
        val again = CorrelationRule(setOf(SymptomKind.SEARCH), listOf("robotId", "jobOrderId"))
        assertEquals(
            Correlation.Own("$run:search-1", listOf("$run:by:robotId:hum-02:jobOrderId:PATROL-1", "$run:by:robotId:hum-02")),
            correlate(SymptomKind.SEARCH, search1, run, "search-1", listOf(byRobotOrder, again, byRobot)),
        )
    }

    @Test fun `the order of the cross-references follows the order of the rules`() {
        val byRobot = CorrelationRule(setOf(SymptomKind.SEARCH), listOf("robotId"))
        val byOrder = CorrelationRule(setOf(SymptomKind.SEARCH), listOf("jobOrderId"))
        assertEquals(
            Correlation.Own("$run:search-1", listOf("$run:by:jobOrderId:PATROL-1", "$run:by:robotId:hum-02")),
            correlate(SymptomKind.SEARCH, search1, run, "search-1", listOf(byOrder, byRobot)),
        )
    }

    @Test fun `the 64-character boundary - an id of 65 is null, a field value of 65 leaves the rule out`() {
        val id64 = "a".repeat(64)
        assertEquals(Correlation.Own("$run:$id64", emptyList()), correlate(SymptomKind.SEARCH, search1, run, id64, emptyList()))
        assertNull(correlate(SymptomKind.SEARCH, search1, run, "a".repeat(65), emptyList()))
        val value64 = line("""{"searchId":"s","robotId":"hum-02","jobOrderId":"${"a".repeat(64)}"}""")
        val value65 = line("""{"searchId":"s","robotId":"hum-02","jobOrderId":"${"a".repeat(65)}"}""")
        assertEquals(Correlation.Merged("$run:by:robotId:hum-02:jobOrderId:${"a".repeat(64)}"), correlate(SymptomKind.SEARCH, value64, run, "s", listOf(byRobotOrder)))
        assertEquals(Correlation.Own("$run:s", emptyList()), correlate(SymptomKind.SEARCH, value65, run, "s", listOf(byRobotOrder)))
    }

    @Test fun `a rule built directly must be well-formed - an empty by would merge every symptom`() {
        listOf<() -> Any>(
            { CorrelationRule(emptySet(), listOf("robotId")) },
            { CorrelationRule(setOf(SymptomKind.SEARCH), emptyList()) },
            { CorrelationRule(setOf(SymptomKind.SEARCH), listOf("a", "b", "c", "d", "e")) },
            { CorrelationRule(setOf(SymptomKind.SEARCH), listOf("robot-id")) },
            { CorrelationRule(setOf(SymptomKind.SEARCH), listOf("robotId", "robotId")) },
        ).forEach { build -> assertFailsWith<IllegalArgumentException> { build() } }
    }

    @Test fun `an own key outside the charset is null - the watcher stops, it does not guess`() {
        assertNull(correlate(SymptomKind.SEARCH, search1, run, "a/b", emptyList()))
        assertNull(correlate(SymptomKind.SEARCH, search1, run, "by:robotId:hum-02", emptyList()), "an id cannot pose as a merged key")
        assertNull(correlate(SymptomKind.SEARCH, search1, "r".repeat(200), "search-1", emptyList()))
    }

    @Test fun `a merged key outside the charset leaves the rule out`() {
        val longRun = "r".repeat(170)
        assertEquals(Correlation.Own("$longRun:search-1", emptyList()), correlate(SymptomKind.SEARCH, search1, longRun, "search-1", listOf(byRobotOrder)))
    }
}
