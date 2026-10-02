package koshei.episode.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.client.WorkflowUpdateException
import io.temporal.testing.TestWorkflowEnvironment
import koshei.runtime.RawJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeWorkflowTest {
    private lateinit var env: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient
    private val acts = FakeEpisodeActivities()
    private val narrator = ProgrammableNarrator()

    /** An environment with both workers registered, not yet started. */
    private fun environment(): TestWorkflowEnvironment = episodeEnvironment { fakeWorkers(acts, narrator) }

    @BeforeEach fun up() {
        env = environment()
        env.start()
        client = env.workflowClient
    }

    @AfterEach fun down() = env.close()

    private fun options(id: String): WorkflowOptions = episodeOptions(id)

    private fun open(
        id: String = "ep:test",
        symptom: SymptomSignal = searchSignal("search-1"),
        start: EpisodeStart = EpisodeStart(Picasso.manifest),
    ): EpisodeWorkflow = client.openEpisode(id, symptom, start)

    private fun EpisodeWorkflow.until(seconds: Int = 120, cond: (EpisodeView) -> Boolean): EpisodeView = until(env, seconds, cond)

    private fun <T : Any> eventually(seconds: Int = 20, probe: () -> T?): T = eventually(seconds, { "records ${acts.records}" }, probe)

    /** One instance's records in seq order. */
    private fun records(instanceId: String): List<Rec> =
        synchronized(acts.records) { acts.records.filter { it.instanceId == instanceId } }.sortedBy { it.seq }

    /** One instance's records once they are gap-free from seq 1 (records run concurrently) and [done] holds on them. */
    private fun settledRecords(instanceId: String, done: (List<Rec>) -> Boolean): List<Rec> = eventually {
        records(instanceId).takeIf { r -> r.map { it.seq } == (1L..r.size.toLong()).toList() && done(r) }
    }

    private fun Rec.isEscalation(): Boolean = kind == "TRANSITION" && "\"to\":\"ESCALATED\"" in payload

    @Test fun `an episode runs through the mock narrator to a person's DONE`() {
        val ep = open()
        val waiting = ep.until { it.phase == "AWAITING_EVIDENCE" }
        assertEquals(1, waiting.attempt)
        val reply = ep.confirm(ConfirmRequest("OUTCOME", waiting.candidateId!!, waiting.proposalId!!, true, operatorId = "op-1"))
        assertEquals("ACCEPTED", reply)
        assertEquals(EpisodeResult("RESOLVED", null), ep.result())
        val intent = acts.calls.indexOfFirst { it.startsWith("intent:") }
        val dispatch = acts.calls.indexOfFirst { it.startsWith("dispatch:") }
        assertTrue(intent in 0 until dispatch, "the intent is recorded before anything is dispatched: ${acts.calls}")
        assertEquals("dispatch:AGENT", acts.calls[dispatch])
    }

    @Test fun `policy v1 waits for a person - a stale decide is refused as a value, the right one approves`() {
        acts.policyJson = TestPolicies.table(autoApprove = false)
        val ep = open()
        val waiting = ep.until { it.phase == "AWAITING_APPROVAL" }
        assertEquals("REFUSED_STALE", ep.decide(DecideRequest("${waiting.instanceId}#0", waiting.candidatesVersion!!, true, "op-1")))
        assertEquals("ACCEPTED", ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1")))
        ep.until { it.phase == "AWAITING_EVIDENCE" }
        assertTrue("dispatch:PERSON" in acts.calls)
    }

    @Test fun `the approval deadline escalates and a late decide is refused`() {
        acts.policyJson = TestPolicies.table(autoApprove = false)
        val ep = open()
        val waiting = ep.until { it.phase == "AWAITING_APPROVAL" }
        env.sleep(Duration.ofMinutes(6))
        val escalated = ep.until { it.phase == "ESCALATED" }
        assertEquals("APPROVAL_EXPIRED", escalated.escalationReason)
        assertEquals("REFUSED_NOT_AWAITING", ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1")))
    }

    @Test fun `a malformed Update is rejected by the validator, not by the episode`() {
        val ep = open()
        val correlating = ep.until { it.phase == "CORRELATING" }
        assertThrows<WorkflowUpdateException> { ep.decide(DecideRequest(operatorId = "")) }
        assertThrows<WorkflowUpdateException> { ep.confirm(ConfirmRequest("MAYBE", operatorId = "op-1")) }
        assertEquals("CORRELATING", ep.view().phase)
        // Had either reached the core, it would have been recorded IGNORED before the records of this valid Update.
        assertEquals("ACCEPTED", ep.takeover(TakeoverRequest("op-1")))
        val recs = settledRecords(correlating.instanceId) { r -> r.any { it.isEscalation() } }
        assertTrue(recs.none { it.kind == "IGNORED" }, "the rejected Updates never reached the core: $recs")
    }

    @Test fun `takeover cancels a diagnosis in flight`() {
        acts.policyJson = TestPolicies.table(autoApprove = true, heartbeatMs = 2_000)
        narrator.block = true
        val ep = open()
        ep.until { it.phase == "DIAGNOSING" }
        assertEquals("ACCEPTED", ep.takeover(TakeoverRequest("op-1")))
        assertEquals("TAKEN_OVER", ep.view().escalationReason)
        // The activity learns of the cancellation only from a heartbeat reply, and the SDK sends heartbeats at most every
        // 0.8 × heartbeatTimeout of wall-clock time (1.6 s for this table's 2 s) — time skipping does not shorten it.
        repeat(50) { if (!narrator.cancelled) Thread.sleep(200) }
        assertTrue(narrator.cancelled, "the diagnose activity saw the cancellation")
    }

    @Test fun `agentOff escalates and close ends the workflow`() {
        val ep = open()
        ep.until { it.phase == "CORRELATING" }
        ep.agentOff()
        assertEquals("AGENT_LAYER_OFF", ep.until { it.phase == "ESCALATED" }.escalationReason)
        assertEquals("ACCEPTED", ep.close(CloseRequest("op-1", "handled on site")))
        assertEquals(EpisodeResult("CLOSED", "AGENT_LAYER_OFF"), ep.result())
    }

    @Test fun `narrator's answer crosses as raw text, so a duplicated key is caught`() {
        narrator.answer = { r ->
            val mock = MockNarratorActivities().diagnose(r).text
            RawJson(mock.replaceFirst("\"outcome\":", "\"outcome\":\"NO_GROUNDS\",\"outcome\":"))
        }
        val ep = open()
        assertEquals("DIAGNOSIS_FAILED", ep.until { it.phase == "ESCALATED" }.escalationReason)
    }

    @Test fun `no policy at the start escalates at once and retention closes it`() {
        acts.policyJson = null
        val ep = open()
        assertEquals("POLICY_MISSING", ep.until { it.phase == "ESCALATED" }.escalationReason)
        env.sleep(Duration.ofHours(25))
        assertEquals(EpisodeResult("CLOSED", "POLICY_MISSING"), ep.result())
    }

    // --- B3a review -------------------------------------------------------------------------------------------------

    @Test fun `the workflow drains work in flight - a dispatch answer that comes after close is still recorded`() {
        val gate = CountDownLatch(1)
        acts.dispatchGate = gate
        val ep = open()
        ep.until { it.phase == "DISPATCHED" }
        assertEquals("ACCEPTED", ep.takeover(TakeoverRequest("op-1")))
        assertEquals("ACCEPTED", ep.close(CloseRequest("op-1", "handled on site")))
        gate.countDown()
        assertEquals(EpisodeResult("CLOSED", "TAKEN_OVER"), ep.result())
        // The workflow ended only after the answer was taken in and its record written.
        val dispatch = acts.calls.indexOf("dispatch:AGENT")
        assertTrue(acts.calls.lastIndexOf("record:IGNORED") > dispatch, "IGNORED recorded after the dispatch: ${acts.calls}")
        assertTrue(acts.records.any { it.kind == "IGNORED" && "exec-42" in it.payload }, "the late answer is kept: ${acts.records}")
    }

    @Test fun `a symptom after the workflow has ended starts a new run on the same id`() {
        val ep = open(id = "ep:later")
        val first = ep.until { it.phase == "CORRELATING" }.instanceId
        assertEquals("ACCEPTED", ep.takeover(TakeoverRequest("op-1")))
        assertEquals("ACCEPTED", ep.close(CloseRequest("op-1", "handled on site")))
        assertEquals(EpisodeResult("CLOSED", "TAKEN_OVER"), ep.result())
        open(id = "ep:later", symptom = searchSignal("search-2"))
        val next = client.newWorkflowStub(EpisodeWorkflow::class.java, "ep:later").until { it.instanceId != first && it.phase != "OPENING" }
        assertTrue(next.instanceId.startsWith("ep:later/"))
        assertEquals(1, next.symptoms)
    }

    @Test fun `a symptom that arrives while the ended run drains continues the workflow as new with a new episode`() {
        val gate = CountDownLatch(1)
        acts.dispatchGate = gate
        val ep = open(id = "ep:again")
        val first = ep.until { it.phase == "DISPATCHED" }.instanceId
        assertEquals("ACCEPTED", ep.takeover(TakeoverRequest("op-1")))
        assertEquals("ACCEPTED", ep.close(CloseRequest("op-1", "handled on site")))
        ep.symptom(searchSignal("search-2"))   // the run is still draining: the dispatch is in flight
        gate.countDown()
        val next = client.newWorkflowStub(EpisodeWorkflow::class.java, "ep:again").until { it.instanceId != first && it.phase != "OPENING" }
        assertTrue(next.instanceId.startsWith("ep:again/"))
        assertEquals(1, next.symptoms)
        fun describe(runId: String) = env.workflowServiceStubs.blockingStub().describeWorkflowExecution(
            DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(env.namespace)
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId("ep:again").setRunId(runId))
                .build(),
        ).workflowExecutionInfo
        assertEquals(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW, describe(first.substringAfterLast('/')).status)
        // A continued run is a new episode instance, named by its own run (design §7.1). The chain's first run id is shared
        // by every run of the chain and would fold two episodes' records into one instance.
        assertEquals("ep:again/${describe("").execution.runId}", next.instanceId)
        assertNotEquals(first, next.instanceId)
        assertTrue(records(first).any { it.kind == "IGNORED" && "SymptomJoined" in it.payload }, "the ended episode recorded it: ${records(first)}")
        eventually { records(next.instanceId).firstOrNull { it.kind == "OPENED" && "search:search-2" in it.payload } }
    }

    @Test fun `a null narrator answer is a failed diagnosis, not a lost event`() {
        narrator.answer = { null }
        val ep = open()
        assertEquals("DIAGNOSIS_FAILED", ep.until { it.phase == "ESCALATED" }.escalationReason)
    }

    @Test fun `a null dispatch outcome is an unknown outcome, not a lost event`() {
        acts.dispatchOutcome = null
        val ep = open()
        ep.until { it.phase == "UNKNOWN_OUTCOME" }
    }

    @Test fun `a dispatch that keeps failing is an unknown outcome`() {
        acts.dispatchError = IllegalStateException("approval window unreachable")
        val ep = open()
        ep.until { it.phase == "UNKNOWN_OUTCOME" }
        assertEquals(3, acts.calls.count { it.startsWith("dispatch:") })
    }

    @Test fun `evidence in AWAITING_EVIDENCE is recorded and, with no target unit known, does not resolve`() {
        val ep = open()
        val waiting = ep.until { it.phase == "AWAITING_EVIDENCE" }
        ep.evidence(EvidenceSignal("jr-1", "exec-42", "IDLE", "E2", listOf("u-1"), emptyList(), emptyList(), false, 0))
        val rec = eventually { records(waiting.instanceId).firstOrNull { it.kind == "EVIDENCE" && "jr-1" in it.payload } }
        assertTrue("target unit not known" in rec.payload, rec.payload)
        assertEquals("AWAITING_EVIDENCE", ep.view().phase)
    }

    @Test fun `a symptom joined after the start counts in the view`() {
        val ep = open()
        ep.until { it.phase == "CORRELATING" }
        ep.symptom(searchSignal("search-2"))
        assertEquals(2, ep.until { it.symptoms == 2 }.symptoms)
    }

    @Test fun `an unknown precondition waits for a person, whose confirmation dispatches`() {
        acts.revalidation = "UNKNOWN"
        val ep = open()
        val unknown = ep.until { it.phase == "UNKNOWN_PRECONDITION" }
        val bare = ConfirmRequest("PRECONDITION", unknown.candidateId!!, unknown.proposalId!!, true, operatorId = "op-1")
        assertEquals("REFUSED_NO_PROPOSITION", ep.confirm(bare))   // §8.3: the core refuses "it holds" with nothing to hold
        assertEquals("UNKNOWN_PRECONDITION", ep.view().phase)
        assertEquals("ACCEPTED", ep.confirm(bare.copy(proposition = "the proposition the card showed")))
        ep.until { it.phase == "AWAITING_EVIDENCE" }
        val intent = acts.calls.indexOfFirst { it.startsWith("intent:") }
        assertTrue(intent in 0 until acts.calls.indexOf("dispatch:AGENT"), "${acts.calls}")
    }

    @Test fun `a failing policy read mid-episode keeps the last table in force and suspends auto-approval`() {
        acts.policyFault = { it >= 2 }   // the opening read succeeds, every later one throws
        val ep = open()
        val waiting = ep.until { it.phase == "AWAITING_APPROVAL" }
        assertNull(waiting.escalationReason)
        eventually { records(waiting.instanceId).firstOrNull { it.kind == "POLICY_REJECTED" } }
    }

    @Test fun `the opening policy read outlasts a short outage instead of escalating for good`() {
        acts.policyFault = { it <= 3 }
        val ep = open()
        assertNull(ep.until { it.phase == "AWAITING_EVIDENCE" }.escalationReason)
    }

    @Test fun `a heartbeat timeout is a failed diagnosis, not an expired one`() {
        acts.policyJson = TestPolicies.table(autoApprove = true, heartbeatMs = 2_000)
        narrator.stallMs = 5_000
        val ep = open()
        assertEquals("DIAGNOSIS_FAILED", ep.until { it.phase == "ESCALATED" }.escalationReason)
    }

    @Test fun `signals that arrive before the episode opens keep their arrival order`() {
        env.close()
        env = environment()   // not started yet: every signal below reaches the workflow in its first task
        client = env.workflowClient
        val ep = client.newWorkflowStub(EpisodeWorkflow::class.java, options("ep:early"))
        WorkflowStub.fromTyped(ep).signalWithStart("evidence", arrayOf(EvidenceSignal("jr-0")), arrayOf(EpisodeStart(Picasso.manifest)))
        ep.symptom(searchSignal("search-1"))
        ep.symptom(searchSignal("search-2"))
        ep.agentOff()
        env.start()
        val escalated = ep.until { it.phase == "ESCALATED" }
        assertEquals("AGENT_LAYER_OFF", escalated.escalationReason)
        assertEquals(2, escalated.symptoms)
        val recs = settledRecords(escalated.instanceId) { r -> r.any { it.isEscalation() } && r.any { it.kind == "SYMPTOM_JOINED" } }
        val evidence = recs.first { it.kind == "IGNORED" && "EvidenceArrived" in it.payload }.seq
        val joined = recs.first { it.kind == "SYMPTOM_JOINED" }.seq
        val off = recs.first { it.isEscalation() }.seq
        assertTrue(evidence < joined && joined < off, "arrival order evidence, search-2, agentOff: $recs")
    }

    // --- B3b --------------------------------------------------------------------------------------------------------

    private val json = ObjectMapper()

    /** Notices attempted so far whose `kind` is [kind] (retries included). */
    private fun notices(kind: String): List<String> =
        synchronized(acts.notices) { acts.notices.filter { json.readTree(it)["kind"]?.textValue() == kind } }

    /** picasso's manifest with a `marker` key, so a test can tell which manifest a run opened with. */
    private fun manifest(marker: String): String =
        Picasso.manifest.replace("\"schemaVersion\"", "\"marker\":\"$marker\",\"schemaVersion\"").also {
            assertTrue(it != Picasso.manifest, "the marker went in")   // `Snapshot` keeps manifest keys it does not know
        }

    /** The OPENED record of the run [instanceId]. */
    private fun opened(instanceId: String): Rec = eventually { records(instanceId).firstOrNull { it.kind == "OPENED" } }

    /**
     * Ends the current run of [workflowId] while its dispatch is held at [gate], sends [late] during the drain, holds the
     * next run's dispatch at [nextGate] (none when null), then releases [gate]: the run continues as new. Returns the
     * next run's view once its episode has opened.
     */
    private fun forward(workflowId: String, gate: CountDownLatch, late: SymptomSignal, nextGate: CountDownLatch? = null): EpisodeView {
        val ep = client.newWorkflowStub(EpisodeWorkflow::class.java, workflowId)
        val current = ep.until { it.phase == "DISPATCHED" }.instanceId
        eventually { acts.dispatchedFor.toList().takeIf { current in it } }   // the dispatch is in the activity, held at [gate]
        assertEquals("ACCEPTED", ep.takeover(TakeoverRequest("op-1")))
        assertEquals("ACCEPTED", ep.close(CloseRequest("op-1", "handled on site")))
        ep.symptom(late)
        acts.dispatchGate = nextGate
        gate.countDown()
        return ep.until { it.instanceId != current && it.phase != "OPENING" }
    }

    @Test fun `a record that keeps failing raises RECORD_LAG once, and the episode goes on`() {
        acts.recordFault = { it == "PROPOSED" || it == "APPROVAL" }
        val ep = open()
        ep.until { it.phase == "AWAITING_EVIDENCE" }
        // Both failing records reach their third and last attempt in the fake. The workflow learns of each failure only
        // after that, so this does not yet show both failures handled: the sleep below covers that gap.
        eventually {
            acts.calls.toList().takeIf { c -> c.count { it == "record-failed:PROPOSED" } >= 3 && c.count { it == "record-failed:APPROVAL" } >= 3 }
        }
        val lag = eventually { notices("RECORD_LAG").takeIf { it.isNotEmpty() } }
        Thread.sleep(2_000)   // time for the other failure to reach the workflow: without the once-per-run flag, a second RECORD_LAG lands here
        assertEquals(1, notices("RECORD_LAG").size, "one RECORD_LAG per run: ${acts.notices}")
        val detail = json.readTree(lag.first())["detail"].textValue()
        assertTrue("PROPOSED" in detail || "APPROVAL" in detail, "the notice names the failed record: $detail")
        assertTrue("store down for" in detail, "the notice names the application's cause: $detail")
        assertTrue("message=" !in detail && "type=" !in detail, "the bare message, not the failure's toString: $detail")
        assertEquals("AWAITING_EVIDENCE", ep.view().phase, "the failed records did not hold the episode up")
    }

    @Test fun `a failing notice does not raise RECORD_LAG`() {
        acts.noticeFault = true                                  // every notice fails, after being remembered as attempted
        acts.policyJson = TestPolicies.table(autoApprove = false)
        val ep = open()
        ep.until { it.phase == "AWAITING_APPROVAL" }
        eventually { notices("APPROVAL_NEEDED").takeIf { it.size >= 3 } }   // three failed attempts of APPROVAL_NEEDED
        // Seeing the fake's third attempt does not mean the workflow has processed that failure: the sleep below is
        // what gives the last APPROVAL_NEEDED failure time to reach the workflow before RECORD_LAG is checked.
        assertEquals("ACCEPTED", ep.takeover(TakeoverRequest("op-1")))
        eventually { notices("ESCALATED").takeIf { it.isNotEmpty() } }
        Thread.sleep(2_000)
        assertTrue(notices("RECORD_LAG").isEmpty(), "a failed notice is not a lagging record: ${acts.notices}")
    }

    @Test fun `an Update during the drain is refused as a value`() {
        val gate = CountDownLatch(1)
        acts.dispatchGate = gate
        val ep = open()
        val dispatched = ep.until { it.phase == "DISPATCHED" }
        val dispatchedFor = eventually { acts.dispatchedFor.toList().takeIf { it.isNotEmpty() } }
        assertEquals(listOf(dispatched.instanceId), dispatchedFor, "dispatch names its episode")
        assertEquals("ACCEPTED", ep.takeover(TakeoverRequest("op-1")))
        assertEquals("ACCEPTED", ep.close(CloseRequest("op-1", "handled on site")))
        assertEquals("REFUSED_CLOSED", ep.close(CloseRequest("op-2", "again")))   // the run is still draining
        gate.countDown()
        assertEquals(EpisodeResult("CLOSED", "TAKEN_OVER"), ep.result())
    }

    @Test fun `a run continued for a late symptom opens with that symptom's manifest`() {
        val gate = CountDownLatch(1)
        acts.dispatchGate = gate
        val ep = open(id = "ep:manifest")
        val first = ep.until { it.phase == "DISPATCHED" }.instanceId
        assertEquals("ACCEPTED", ep.takeover(TakeoverRequest("op-1")))
        assertEquals("ACCEPTED", ep.close(CloseRequest("op-1", "handled on site")))
        val later = Picasso.manifest.replace("\"schemaVersion\"", "\"marker\":\"run-later\",\"schemaVersion\"")
        assertTrue(later != Picasso.manifest)
        ep.symptom(searchSignal("search-2").copy(manifestJson = later))
        gate.countDown()
        val next = client.newWorkflowStub(EpisodeWorkflow::class.java, "ep:manifest").until { it.instanceId != first && it.phase != "OPENING" }
        val opened = eventually { records(next.instanceId).firstOrNull { it.kind == "OPENED" } }
        assertTrue("run-later" in opened.payload, "the new run opened with the late symptom's manifest: ${opened.payload}")
        val ignored = records(first).first { it.kind == "IGNORED" && "SymptomJoined" in it.payload }
        assertTrue("search:search-2" in ignored.payload, "the ended run names what it forwarded: ${ignored.payload}")
    }

    @Test fun `the first run opens with its first symptom's manifest, not the start argument's`() {
        val ep = open(
            id = "ep:first-manifest",
            symptom = searchSignal("search-1").copy(manifestJson = manifest("first-line")),
            start = EpisodeStart(manifest("start-arg")),
        )
        val payload = opened(ep.until { it.phase != "OPENING" }.instanceId).payload
        assertTrue("first-line" in payload, "opened with the first line's manifest: $payload")
        assertTrue("start-arg" !in payload, "not with the start argument's: $payload")
    }

    @Test fun `a first symptom with a blank manifest opens with the start argument's manifest`() {
        val ep = open(
            id = "ep:blank-manifest",
            symptom = searchSignal("search-1").copy(manifestJson = ""),
            start = EpisodeStart(manifest("start-arg")),
        )
        val payload = opened(ep.until { it.phase != "OPENING" }.instanceId).payload
        assertTrue("start-arg" in payload, "a blank manifest falls back to the start argument's: $payload")
    }

    @Test fun `a manifest-less late line keeps the manifest of the run it continues from, across two continuations`() {
        val gate1 = CountDownLatch(1)
        acts.dispatchGate = gate1
        val run1 = open(id = "ep:chain").until { it.phase != "OPENING" }.instanceId
        val gate2 = CountDownLatch(1)
        // Run 2 must reach DISPATCHED to be held while it drains; search-2 ends in NO_GROUNDS, so search-1's line comes again
        // under a new eventId.
        val late = searchSignal("search-1").copy(eventId = "search:chain-2", manifestJson = manifest("from-run-1"))
        val run2 = forward("ep:chain", gate1, late, nextGate = gate2).instanceId
        assertTrue("from-run-1" in opened(run2).payload, "run 2 opened with its late line's manifest: ${opened(run2).payload}")
        val run3 = forward("ep:chain", gate2, searchSignal("search-3")).instanceId   // this late line carries no manifest
        assertEquals(3, setOf(run1, run2, run3).size)
        assertTrue("from-run-1" in opened(run3).payload, "run 3 kept run 2's manifest: ${opened(run3).payload}")
    }

    @Test fun `a run started with one manifest but opened with another forwards the one it opened with`() {
        val gate = CountDownLatch(1)
        acts.dispatchGate = gate
        open(
            id = "ep:opened-manifest",
            symptom = searchSignal("search-1").copy(manifestJson = manifest("first-line")),
            start = EpisodeStart(manifest("start-arg")),
        )
        val next = forward("ep:opened-manifest", gate, searchSignal("search-2"))   // the late line carries no manifest
        val payload = opened(next.instanceId).payload
        assertTrue("first-line" in payload, "the next run opened with the manifest this run opened with: $payload")
        assertTrue("start-arg" !in payload, "not with this run's start argument: $payload")
    }

    @Test fun `a late line with a blank manifest keeps the manifest the run opened with`() {
        val gate = CountDownLatch(1)
        acts.dispatchGate = gate
        open(id = "ep:blank-late", symptom = searchSignal("search-1").copy(manifestJson = manifest("first-line")))
        val next = forward("ep:blank-late", gate, searchSignal("search-2").copy(manifestJson = ""))
        val payload = opened(next.instanceId).payload
        assertTrue("first-line" in payload, "a blank manifest on the late line falls back to the opened one: $payload")
    }

    // --- B3c --------------------------------------------------------------------------------------------------------

    @Test fun `the view carries the candidate, the candidates offered and the diagnosis that chose it`() {
        acts.policyJson = TestPolicies.table(autoApprove = false)
        val ep = open()
        val waiting = ep.until { it.phase == "AWAITING_APPROVAL" }
        val candidate = json.readTree(waiting.candidateJson!!)
        assertEquals(waiting.candidateId, candidate["candidateId"].textValue())
        assertEquals("APPROVE_REMEDY", candidate["kind"].textValue())
        assertEquals(json.readTree(Picasso.search("search-1"))["searchId"].textValue(), candidate["ref"]["searchId"].textValue())
        val candidates = json.readTree(waiting.candidatesJson!!)
        assertTrue(candidates.any { it["candidateId"].textValue() == waiting.candidateId })
        assertTrue(candidates.any { it["kind"].textValue() == "ESCALATE" })
        val diagnosis = json.readTree(waiting.diagnosisJson!!)
        assertEquals("RECOMMENDED", diagnosis["outcome"].textValue())
        assertEquals(waiting.candidateId, diagnosis["candidateId"].textValue())
    }

    @Test fun `before a diagnosis the view has no candidate and no diagnosis`() {
        val ep = open()
        val v = ep.until { it.phase == "CORRELATING" }
        assertNull(v.candidateJson)
        assertNull(v.diagnosisJson)
    }

    @Test fun `the card is drawn from the view and records the core actually wrote`() {
        acts.policyJson = TestPolicies.table(autoApprove = false)
        val ep = open()
        val waiting = ep.until { it.phase == "AWAITING_APPROVAL" }
        val events = settledRecords(waiting.instanceId) { r -> r.any { it.kind == "PROPOSED" } }.map { StoredEvent(it.seq, it.kind, it.payload) }
        val card = operatorCard(waiting, events)
        assertEquals(waiting.candidateId, card.proposal!!.candidateId)
        assertEquals(CardDecision("decide", waiting.proposalId!!, waiting.candidatesVersion!!, waiting.stateDeadlineMillis), card.decision)
        assertEquals("mock: the first offered candidate [mock-sop, 1]", card.rationale)
        assertEquals("mock-sop", card.citations.single()["title"].textValue())
        assertEquals(1, card.symptoms.size)
    }

    @Test fun `an unknown precondition's card names the proposition from the real candidate`() {
        acts.revalidation = "UNKNOWN"
        val ep = open()
        val v = ep.until { it.phase == "UNKNOWN_PRECONDITION" }
        val line = json.readTree(Picasso.search("search-1"))
        val card = operatorCard(v, emptyList())
        assertEquals(
            "(${line["robotId"].textValue()}, ${line["jobOrderId"].textValue()})에 searchId ${line["searchId"].textValue()} 뒤로 더 새 탐색 줄이 없다",
            card.precondition,
        )
        assertEquals("confirm", card.decision!!.update)
    }

    // --- B3c chunk 1 review -----------------------------------------------------------------------------------------

    @Test fun `an escalated card shows the answer that escalated it, read from the record`() {
        narrator.answer = { r ->
            val answer = json.readTree(MockNarratorActivities().diagnose(r).text) as ObjectNode
            answer.put("outcome", "NO_GROUNDS").putNull("candidateId")
            answer.put("rationale", "no grounds in the bundle [mock-sop, 1]")
            RawJson(answer.toString())
        }
        val ep = open()
        val escalated = ep.until { it.phase == "ESCALATED" }
        assertEquals("NO_GROUNDS", escalated.escalationReason)
        assertNull(escalated.diagnosisJson, "the escalating verdict closed the attempt in the same step")
        val events = settledRecords(escalated.instanceId) { r -> r.any { it.kind == "DIAGNOSIS_RESULT" } && r.any { it.isEscalation() } }
            .map { StoredEvent(it.seq, it.kind, it.payload) }
        val card = operatorCard(escalated, events)
        assertEquals("no grounds in the bundle [mock-sop, 1]", card.rationale)
        assertTrue(card.diagnosisFromRecord)
        assertNull(card.diagnosisRefused)
        assertEquals(false, card.responseUnreadable)
        assertNull(card.decision)
    }

    @Test fun `the view counts the symptoms that joined after the attempt's snapshot`() {
        acts.revalidation = "UNKNOWN"
        val ep = open()
        assertNull(ep.until { it.phase == "CORRELATING" }.joinedAfterSnapshot, "no attempt, no snapshot")
        assertEquals(0, ep.until { it.phase == "UNKNOWN_PRECONDITION" }.joinedAfterSnapshot)
        ep.symptom(searchSignal("search-2"))
        val joined = ep.until { it.symptoms == 2 }
        assertEquals(1, joined.joinedAfterSnapshot)
    }
}
