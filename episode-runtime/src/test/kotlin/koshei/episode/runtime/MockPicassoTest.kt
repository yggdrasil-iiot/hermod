package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import koshei.episode.PICASSO_APPROVAL_SCHEMA
import koshei.episode.PICASSO_REFUSALS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockPicassoTest {
    private val json = ObjectMapper()

    private fun remedy(robot: String = "hum-02", order: String = "PATROL-1", search: String = "search-1") =
        """{"candidateId":"APPROVE_REMEDY:$robot:$order:pick_place","kind":"APPROVE_REMEDY",
            "ref":{"robotId":"$robot","jobOrderId":"$order","searchId":"$search"},"sawSkillTypes":["pick_place"]}"""

    private fun intent(candidate: String) = json.readTree("""{"candidate":$candidate,"idempotencyKey":"k","approval":null}""") as ObjectNode

    private fun answer(text: String) = json.readTree(text)

    @Test fun `the first approval of a proposal is APPROVED in picasso's schema 4 with the steps that went out`() {
        val a = answer(MockPicasso(MockPicasso.INSTANCE).approve(intent(remedy()), "PERSON"))
        assertEquals("4", a["schemaVersion"].textValue())
        assertEquals(MockPicasso.INSTANCE, a["instanceId"].textValue())
        assertEquals("APPROVED", a["outcome"].textValue())
        assertEquals("mock-exec-1", a["executionId"].textValue())
        assertEquals("pick_place", a["steps"][0]["skillType"].textValue())
        assertEquals("remedy-1-pick_place", a["steps"][0]["unitId"].textValue())
        // a unit per step, named as picasso names it: remedy-{n}-{skillType}, n from 1 in step order
        val two = answer(
            MockPicasso(MockPicasso.INSTANCE).approve(
                intent("""{"candidateId":"APPROVE_REMEDY:hum-02:PATROL-1:pick_place","kind":"APPROVE_REMEDY",
                    "ref":{"robotId":"hum-02","jobOrderId":"PATROL-1","searchId":"search-1"},"sawSkillTypes":["pick_place","place"]}"""),
                "PERSON",
            ),
        )
        assertEquals(listOf("remedy-1-pick_place", "remedy-2-place"), two["steps"].map { it["unitId"].textValue() })
    }

    @Test fun `the same proposal approved again is CONSUMED with the consumption record`() {
        val picasso = MockPicasso()
        val first = answer(picasso.approve(intent(remedy()), "PERSON"))
        val second = answer(picasso.approve(intent(remedy()), "PERSON"))
        assertEquals("REFUSED", second["outcome"].textValue())
        assertEquals("CONSUMED", second["refusal"].textValue())
        assertEquals(first["executionId"].textValue(), second["consumed"]["executionId"].textValue())
        assertEquals(first["steps"], second["consumed"]["steps"])
    }

    @Test fun `a newer search for the consumed (robot, order) is CONSUMED - picasso records consumption per (robot, order)`() {
        val picasso = MockPicasso()
        picasso.approve(intent(remedy(search = "search-1")), "PERSON")
        assertEquals("CONSUMED", answer(picasso.approve(intent(remedy(search = "search-2")), "AGENT"))["refusal"].textValue())
    }

    @Test fun `each mock is its own instance unless told - picasso never names one deterministically (ADR 48)`() {
        val a = answer(MockPicasso().approve(intent(remedy()), "PERSON"))["instanceId"].textValue()
        val b = answer(MockPicasso().approve(intent(remedy()), "PERSON"))["instanceId"].textValue()
        assertTrue(a.startsWith("mock-") && b.startsWith("mock-") && a != b, "$a $b")
    }

    @Test fun `another (robot, order) is its own proposal`() {
        val picasso = MockPicasso()
        picasso.approve(intent(remedy()), "PERSON")
        val other = answer(picasso.approve(intent(remedy(order = "PATROL-2")), "PERSON"))
        assertEquals("APPROVED", other["outcome"].textValue())
        assertEquals("mock-exec-2", other["executionId"].textValue())
    }

    @Test fun `revalidation is TRUE until the proposal is consumed, then FALSE`() {
        val picasso = MockPicasso()
        assertEquals("TRUE", picasso.revalidate(remedy()))
        picasso.approve(intent(remedy()), "PERSON")
        assertEquals("FALSE", picasso.revalidate(remedy()))
    }

    @Test fun `revalidation of a person task is TRUE - the mock has no source to read`() =
        assertEquals("TRUE", MockPicasso().revalidate("""{"candidateId":"CHOOSE_SOURCE:x","kind":"CHOOSE_SOURCE","ref":{}}"""))

    @Test fun `a candidate it cannot read is UNKNOWN`() = assertEquals("UNKNOWN", MockPicasso().revalidate("{not json"))

    @Test fun `both person-task kinds are TRUE`() {
        for (kind in listOf("CHOOSE_SOURCE", "OPERATOR_DECISION"))
            assertEquals("TRUE", MockPicasso().revalidate("""{"candidateId":"$kind:x","kind":"$kind","ref":{}}"""), kind)
    }

    @Test fun `a candidate that is not an object, or names no kind it knows, is UNKNOWN - it does not act on what it cannot read`() {
        val picasso = MockPicasso()
        for (c in listOf("", "null", "[]", "\"APPROVE_REMEDY\"", "{}", """{"kind":""}""", """{"kind":"  "}""", """{"kind":7}""", """{"kind":"SOMETHING_NEW"}"""))
            assertEquals("UNKNOWN", picasso.revalidate(c), c)
    }

    @Test fun `a remedy that does not name its robot and order is UNKNOWN`() {
        val unnamed = listOf(
            remedy(robot = ""), remedy(order = ""), remedy(robot = " "),
            """{"kind":"APPROVE_REMEDY","ref":{"jobOrderId":"PATROL-1","searchId":"s"}}""",
            """{"kind":"APPROVE_REMEDY","ref":{"robotId":7,"jobOrderId":"PATROL-1","searchId":"s"}}""",
        )
        for (c in unnamed) assertEquals("UNKNOWN", MockPicasso().revalidate(c), c)
    }

    @Test fun `a remedy that does not name its robot and order is never approved and consumes nothing`() {
        val picasso = MockPicasso()
        val unnamed = answer(picasso.approve(intent(remedy(robot = "")), "PERSON"))
        assertRefusal(unnamed, "NO_PROPOSAL")
        assertEquals("mock: no proposal for an unnamed robot/order", unnamed["reason"].textValue())
        // Asked again: still NO_PROPOSAL, not CONSUMED - nothing was consumed.
        assertEquals("NO_PROPOSAL", answer(picasso.approve(intent(remedy(robot = "")), "PERSON"))["refusal"].textValue())
        assertEquals("NO_PROPOSAL", answer(picasso.approve(intent(remedy(order = " ")), "PERSON"))["refusal"].textValue())
        assertEquals("UNKNOWN", picasso.revalidate(remedy(robot = "")))
        assertEquals("mock-exec-1", answer(picasso.approve(intent(remedy()), "PERSON"))["executionId"].textValue(), "no execution was counted")
    }

    // The core's reader of these answers (judgePicassoAnswer) is internal to :episode and has no public entry point, so
    // each answer shape is held to the exact field set that reader needs (picasso ApprovalWire schema 4).

    @Test fun `an APPROVED answer has exactly picasso's schema-4 fields - an execution and a unit per step`() {
        val a = answer(MockPicasso().approve(intent(remedy()), "PERSON"))
        assertEquals(setOf("schemaVersion", "contractSemver", "instanceId", "outcome", "executionId", "steps"), a.fieldNames().asSequence().toSet())
        assertEnvelope(a, "APPROVED")
        assertTrue(a["executionId"].isTextual && a["executionId"].textValue().isNotBlank())
        assertTrue(a["steps"].isArray && a["steps"].size() == 1)
        val step = a["steps"][0]
        assertEquals(setOf("unitId", "skillType", "parameters"), step.fieldNames().asSequence().toSet())
        assertTrue(step["unitId"].isTextual && step["skillType"].isTextual && step["parameters"].isObject)
    }

    @Test fun `a CONSUMED answer has exactly picasso's schema-4 fields - with the consumption record`() {
        val picasso = MockPicasso()
        picasso.approve(intent(remedy()), "PERSON")
        val a = answer(picasso.approve(intent(remedy()), "PERSON"))
        assertRefusal(a, "CONSUMED")
        assertEquals(setOf("approverId", "approverKind", "at", "wallClockAt", "executionId", "steps"), a["consumed"].fieldNames().asSequence().toSet())
    }

    @Test fun `a NO_PROPOSAL answer has exactly picasso's schema-4 fields - consumed is null`() {
        val a = answer(MockPicasso().approve(intent("""{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{},"sawSkillTypes":["pick_place"]}"""), "PERSON"))
        assertRefusal(a, "NO_PROPOSAL")
        assertTrue(a["consumed"].isNull)
    }

    private fun assertEnvelope(a: JsonNode, outcome: String) {
        assertTrue(a.isObject)
        assertEquals(PICASSO_APPROVAL_SCHEMA, a["schemaVersion"].textValue())
        assertTrue(a["contractSemver"].isTextual)
        assertTrue(a["instanceId"].isTextual && a["instanceId"].textValue().isNotBlank())
        assertEquals(outcome, a["outcome"].textValue())
    }

    private fun assertRefusal(a: JsonNode, refusal: String) {
        assertEquals(setOf("schemaVersion", "contractSemver", "instanceId", "outcome", "refusal", "reason", "consumed"), a.fieldNames().asSequence().toSet())
        assertEnvelope(a, "REFUSED")
        assertEquals(refusal, a["refusal"].textValue())
        assertTrue(a["refusal"].textValue() in PICASSO_REFUSALS, "a refusal the core understands")
        assertTrue(a["reason"].isTextual)
    }
}
