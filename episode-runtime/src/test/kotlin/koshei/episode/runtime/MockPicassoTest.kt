package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import koshei.episode.PICASSO_APPROVAL_SCHEMA
import koshei.episode.PICASSO_REFUSALS_V2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockPicassoTest {
    private val json = ObjectMapper()

    private fun remedy(robot: String = "hum-02", order: String = "PATROL-1", search: String = "search-1") =
        """{"candidateId":"APPROVE_REMEDY:$robot:$order:pick_place","kind":"APPROVE_REMEDY",
            "ref":{"robotId":"$robot","jobOrderId":"$order","searchId":"$search"},"sawSkillTypes":["pick_place"]}"""

    private fun intent(candidate: String) = json.readTree("""{"candidate":$candidate,"idempotencyKey":"k","approval":null,"targetUnit":null}""") as ObjectNode

    private fun answer(text: String) = json.readTree(text)

    @Test fun `the first approval of a proposal is APPROVED in picasso's schema 2 with the steps that went out`() {
        val a = answer(MockPicasso().approve(intent(remedy()), "PERSON"))
        assertEquals("2", a["schemaVersion"].textValue())
        assertEquals("APPROVED", a["outcome"].textValue())
        assertEquals("mock-exec-1", a["executionId"].textValue())
        assertEquals("pick_place", a["steps"][0]["skillType"].textValue())
    }

    @Test fun `the same proposal approved again is REMEDY_NOT_APPLIED - it was consumed`() {
        val picasso = MockPicasso()
        picasso.approve(intent(remedy()), "PERSON")
        val again = answer(picasso.approve(intent(remedy()), "PERSON"))
        assertEquals("REFUSED", again["outcome"].textValue())
        assertEquals("REMEDY_NOT_APPLIED", again["refusal"].textValue())
    }

    @Test fun `a newer search for the consumed (robot, order) is NO_PROPOSAL`() {
        val picasso = MockPicasso()
        picasso.approve(intent(remedy(search = "search-1")), "PERSON")
        assertEquals("NO_PROPOSAL", answer(picasso.approve(intent(remedy(search = "search-2")), "AGENT"))["refusal"].textValue())
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

    // The core's reader of these answers (judgePicassoAnswer) is internal to :episode and has no public entry point, so
    // each answer shape is held to the exact field set that reader needs (picasso ApprovalWire schema 2).

    @Test fun `an APPROVED answer has exactly the fields the core reads - executionId and a steps array`() {
        val a = answer(MockPicasso().approve(intent(remedy()), "PERSON"))
        assertEquals(setOf("schemaVersion", "contractSemver", "outcome", "executionId", "steps"), a.fieldNames().asSequence().toSet())
        assertEnvelope(a, "APPROVED")
        assertTrue(a["executionId"].isTextual && a["executionId"].textValue().isNotBlank())
        assertTrue(a["steps"].isArray && a["steps"].size() == 1)
        val step = a["steps"][0]
        assertEquals(setOf("skillType", "parameters"), step.fieldNames().asSequence().toSet())
        assertTrue(step["skillType"].isTextual && step["parameters"].isObject)
    }

    @Test fun `a REMEDY_NOT_APPLIED answer has exactly the fields the core reads - a known refusal and a reason`() {
        val picasso = MockPicasso()
        picasso.approve(intent(remedy()), "PERSON")
        assertRefusal(answer(picasso.approve(intent(remedy()), "PERSON")), "REMEDY_NOT_APPLIED")
    }

    @Test fun `a NO_PROPOSAL answer has exactly the fields the core reads - a known refusal and a reason`() {
        val picasso = MockPicasso()
        picasso.approve(intent(remedy(search = "search-1")), "PERSON")
        assertRefusal(answer(picasso.approve(intent(remedy(search = "search-2")), "PERSON")), "NO_PROPOSAL")
    }

    private fun assertEnvelope(a: JsonNode, outcome: String) {
        assertTrue(a.isObject)
        assertEquals(PICASSO_APPROVAL_SCHEMA, a["schemaVersion"].textValue())
        assertTrue(a["contractSemver"].isTextual)
        assertEquals(outcome, a["outcome"].textValue())
    }

    private fun assertRefusal(a: JsonNode, refusal: String) {
        assertEquals(setOf("schemaVersion", "contractSemver", "outcome", "refusal", "reason"), a.fieldNames().asSequence().toSet())
        assertEnvelope(a, "REFUSED")
        assertEquals(refusal, a["refusal"].textValue())
        assertTrue(a["refusal"].textValue() in PICASSO_REFUSALS_V2, "a refusal the core understands")
        assertTrue(a["reason"].isTextual)
    }
}
