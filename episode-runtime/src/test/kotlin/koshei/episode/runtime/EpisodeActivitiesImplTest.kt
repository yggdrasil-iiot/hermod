package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode
import io.temporal.failure.ApplicationFailure
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeActivitiesImplTest {
    @TempDir lateinit var dir: Path
    private lateinit var store: EpisodeStore
    private val picasso = CountingWindow(MockPicasso())
    private val acts by lazy { EpisodeActivitiesImpl(PolicyFileReader(dir.resolve("active.yaml")), store, picasso) }

    @BeforeEach fun up() { store = EpisodeDb.reset() }

    private fun remedyIntent(key: String = "ep:a/r1:1:APPROVE_REMEDY") =
        """{"candidate":{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{"robotId":"hum-02","jobOrderId":"PATROL-1","searchId":"search-1"},
            "sawSkillTypes":["pick_place"]},"idempotencyKey":"$key","approval":null}"""

    private fun taskIntent(kind: String, candidateId: String = "c") =
        """{"candidate":{"candidateId":"$candidateId","kind":"$kind","ref":{}},"idempotencyKey":"ep:a/r1:1:$kind","approval":null}"""

    @Test fun `readPolicy reads the file`() {
        assertEquals("MISSING", acts.readPolicy().kind)
        Files.writeString(dir.resolve("active.yaml"), "version: \"1\"\n")
        assertEquals("FOUND", acts.readPolicy().kind)
    }

    @Test fun `record and recordIntent share the episode's seq space`() {
        acts.record("ep:a/r1", 1, "OPENED", "{}")
        acts.recordIntent("ep:a/r1", 2, remedyIntent())
        assertEquals(listOf("OPENED", "DISPATCH_INTENT"), store.events("ep:a/r1").map { it.kind })
    }

    @Test fun `a conflicting record fails without retry`() {
        acts.record("ep:a/r1", 1, "OPENED", """{"a":1}""")
        val e = assertThrows<ApplicationFailure> { acts.record("ep:a/r1", 1, "OPENED", """{"a":2}""") }
        assertTrue(e.isNonRetryable)
        assertEquals("RecordConflict", e.type)
    }

    @Test fun `a record the database cannot hold fails without retry`() {
        val e = assertThrows<ApplicationFailure> { acts.record("ep:a/r1", 1, "OPENED", nul) }
        assertTrue(e.isNonRetryable)
        assertEquals("RecordUnstorable", e.type)
        val intent = assertThrows<ApplicationFailure> { acts.recordIntent("ep:a/r1", 2, nul) }
        assertTrue(intent.isNonRetryable)
        assertEquals("RecordUnstorable", intent.type)
    }

    @Test fun `a remedy is dispatched to picasso once - a retry gets the stored answer`() {
        val first = acts.dispatch("ep:a/r1", remedyIntent(), "PERSON")
        val again = acts.dispatch("ep:a/r1", remedyIntent(), "PERSON")
        assertEquals(first, again)
        assertTrue("mock-exec-1" in first.answerJson!!)
        assertEquals(1, picasso.approvals, "the stored answer is returned without asking picasso again")
    }

    @Test fun `a retry after the answer was lost meets picasso's consumption`() {
        val forgetful = object : EpisodeStore(EpisodeDb::connection) {
            var lose = true
            override fun saveOutcome(instanceId: String, key: String, outcome: DispatchOutcome): DispatchOutcome {
                if (lose) { lose = false; throw IllegalStateException("worker died before saving") }
                return super.saveOutcome(instanceId, key, outcome)
            }
        }
        val acts = EpisodeActivitiesImpl(PolicyFileReader(dir.resolve("active.yaml")), forgetful, picasso)
        assertThrows<IllegalStateException> { acts.dispatch("ep:a/r1", remedyIntent(), "PERSON") }
        val retry = acts.dispatch("ep:a/r1", remedyIntent(), "PERSON")
        assertTrue("CONSUMED" in retry.answerJson!!, retry.answerJson)
        assertEquals(2, picasso.approvals, "nothing was stored, so the retry asked again")
    }

    @Test fun `a person task is issued once with its notice`() {
        assertEquals(DispatchOutcome("PERSON_TASK"), acts.dispatch("ep:a/r1", taskIntent("CHOOSE_SOURCE"), null))
        assertEquals(DispatchOutcome("PERSON_TASK"), acts.dispatch("ep:a/r1", taskIntent("CHOOSE_SOURCE"), null))
        assertEquals(1, store.notices("ep:a/r1").size)
    }

    @Test fun `a person task the database cannot hold fails without retry and issues nothing`() {
        val e = assertThrows<ApplicationFailure> { acts.dispatch("ep:a/r1", taskIntent("CHOOSE_SOURCE", candidateId = "\\u0000"), null) }
        assertTrue(e.isNonRetryable)
        assertEquals("RecordUnstorable", e.type)
        assertNull(store.outcome("ep:a/r1:1:CHOOSE_SOURCE"))
        assertEquals(emptyList(), store.notices("ep:a/r1"))
    }

    @Test fun `ESCALATE has no dispatch target and fails without retry`() {
        val e = assertThrows<ApplicationFailure> { acts.dispatch("ep:a/r1", taskIntent("ESCALATE"), null) }
        assertTrue(e.isNonRetryable)
        assertEquals("NoDispatchTarget", e.type)
    }

    @Test fun `an intent without a key fails without retry`() {
        val e = assertThrows<ApplicationFailure> { acts.dispatch("ep:a/r1", """{"candidate":{"kind":"APPROVE_REMEDY"}}""", null) }
        assertTrue(e.isNonRetryable)
        assertEquals("BadIntent", e.type)
    }

    @Test fun `an intent that is not JSON fails as BadIntent and writes nothing`() =
        assertBadIntent("{not json")

    @Test fun `an intent with a repeated key fails as BadIntent and writes nothing - never silently the last value`() =
        assertBadIntent("""{"candidate":{"kind":"CHOOSE_SOURCE","ref":{}},"idempotencyKey":"k","idempotencyKey":"k2"}""")

    private fun assertBadIntent(intent: String) {
        val e = assertThrows<ApplicationFailure> { acts.dispatch("ep:a/r1", intent, null) }
        assertTrue(e.isNonRetryable)
        assertEquals("BadIntent", e.type)
        assertEquals(0, dispatchRows(), "no episode_dispatch row")
        assertEquals(emptyList(), store.notices("ep:a/r1"))
        assertEquals(0, picasso.approvals)
    }

    private fun dispatchRows(): Int = EpisodeDb.connection().use { c ->
        c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM episode_dispatch").use { rs -> rs.next(); rs.getInt(1) } }
    }

    @Test fun `revalidate asks picasso`() =
        assertEquals("TRUE", acts.revalidate("""{"kind":"APPROVE_REMEDY","ref":{"robotId":"r","jobOrderId":"j","searchId":"s"}}"""))

    @Test fun `a notice goes to the channel table`() {
        acts.sendNotice("ep:a/r1", """{"kind":"ESCALATED","phase":"ESCALATED","reason":"AGENT_LAYER_OFF","detail":null}""")
        assertEquals(1, store.notices("ep:a/r1").size)
    }

    @Test fun `a notice the database cannot hold fails without retry`() {
        val e = assertThrows<ApplicationFailure> { acts.sendNotice("ep:a/r1", nul) }
        assertTrue(e.isNonRetryable)
        assertEquals("RecordUnstorable", e.type)
        assertEquals(emptyList(), store.notices("ep:a/r1"))
    }

    /** The approval window with every `approve` counted. */
    private class CountingWindow(private val inner: ApprovalWindow) : ApprovalWindow by inner {
        @Volatile var approvals = 0

        override fun approve(intent: JsonNode, approverKind: String?): String {
            approvals += 1
            return inner.approve(intent, approverKind)
        }
    }

    private companion object {
        /** A NUL character, escaped the way Jackson writes it: valid JSON that jsonb cannot hold. */
        const val nul = """{"a":"\u0000"}"""
    }
}
