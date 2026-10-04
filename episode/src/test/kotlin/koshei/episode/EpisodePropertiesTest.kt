package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * design §17: whatever arrives, in whatever order, the fixed rules hold and every run ends. A failure names its seed;
 * rerun that one seed to reproduce it.
 */
class EpisodePropertiesTest {
    private val seeds = 0L until 1000L
    private val maxEvents = 60

    private class Stats {
        val phases = mutableSetOf<Phase>()
        var dispatches = 0
        /** ConfirmedUnknown events the machine accepted. */
        var accepted = 0
        /** Execution Dispatches the episode's unknowns would have blocked had no person confirmed any. */
        var unblocked = 0
        /** Accepted confirmations of one episode-level item while the episode held another one besides. */
        var crowded = 0
        /** "It holds" confirmations of the waiting proposal that came without a proposition and were refused for it (§8.3). */
        var bareRefused = 0
        /** RESOLVED on an arriving report (design §12 ③). */
        var evidenceResolved = 0
        /** RESOLVED on an answer, by a report kept while DISPATCHED. */
        var replayResolved = 0
    }

    /** Runs that start from UNKNOWN(OUTCOME) with two units in doubt, so the episode-level items are crowded from the first event. */
    private val directedSeeds = 0L until 200L

    @Test fun `random event sequences keep the fixed rules and always end`() {
        for (seed in seeds) {
            try {
                run(seed, Stats(), directed = false)
            } catch (e: AssertionError) {
                throw AssertionError("seed $seed: ${e.message}", e)
            }
        }
        for (seed in directedSeeds) {
            try {
                run(seed, Stats(), directed = true)
            } catch (e: AssertionError) {
                throw AssertionError("directed seed $seed: ${e.message}", e)
            }
        }
    }

    @Test fun `the random runs reach every phase, dispatch often, and walk the confirmation path`() {
        val stats = Stats()
        for (seed in seeds) run(seed, stats, directed = false)
        assertEquals(Phase.entries.toSet(), stats.phases)
        val seen = "dispatches=${stats.dispatches}, accepted=${stats.accepted}, unblocked=${stats.unblocked}, crowded=${stats.crowded}, " +
            "bareRefused=${stats.bareRefused}, evidenceResolved=${stats.evidenceResolved}, replayResolved=${stats.replayResolved}"
        assertTrue(stats.dispatches > 100, "too few dispatches: $seen")
        assertTrue(stats.accepted >= 100, "too few accepted ConfirmedUnknown: $seen")
        assertTrue(stats.unblocked >= 10, "too few execution Dispatches that a confirmation unblocked: $seen")
        assertTrue(stats.crowded >= 2, "too few confirmations of one episode item among several: $seen")
        assertTrue(stats.bareRefused >= 3, "too few precondition confirmations refused for want of a proposition: $seen")
        // No floor for evidenceResolved / replayResolved yet: these seeds measure 0 for both (plan C2 Task 2 follow-up).
        val directed = Stats()
        for (seed in directedSeeds) run(seed, directed, directed = true)
        assertTrue(directed.crowded >= 20, "too few confirmations of one episode item among several in the directed runs: crowded=${directed.crowded}")
    }

    /** Seeds of the directed report runs: each starts from an accepted remedy, sometimes with reports kept before the answer. */
    private val reportSeeds = 0L until 500L
    private val maxReports = 8

    @Test fun `a report resolves only when linked, complete, online and clean - directed`() {
        val stats = Stats()
        for (seed in reportSeeds) {
            try {
                reportRun(seed, stats)
            } catch (e: AssertionError) {
                throw AssertionError("report seed $seed: ${e.message}", e)
            }
        }
        val seen = "evidenceResolved=${stats.evidenceResolved}, replayResolved=${stats.replayResolved}"
        assertTrue(stats.evidenceResolved >= EVIDENCE_RESOLVED_FLOOR, "too few RESOLVED by an arriving report: $seen")
        assertTrue(stats.replayResolved >= REPLAY_RESOLVED_FLOOR, "too few RESOLVED by a kept report on the answer: $seen")
    }

    /**
     * One directed run: DISPATCHED, 0–3 reports kept on half the seeds, an APPROVED answer for one or two units from mw-1
     * or mw-2, then up to [maxReports] random reports until the episode ends. Each report is right or wrong in each way
     * with even odds, so every combination comes up. Both directions are checked: what resolves passes [checkResolution],
     * and what [resolves] says completes the attempt does resolve.
     */
    private fun reportRun(seed: Long, stats: Stats) {
        val rnd = kotlin.random.Random(seed)
        val units = if (rnd.nextBoolean()) listOf(PicassoAnswers.UNIT) else listOf("remedy-1-pick_place", "remedy-2-pick_place")
        val instance = if (rnd.nextBoolean()) "mw-1" else "mw-2"
        val otherInstance = if (instance == "mw-1") "mw-2" else "mw-1"
        val execution = PicassoAnswers.EXECUTION
        val ids = mutableListOf<String>()
        fun report(): Evidence {
            val id = if (ids.isNotEmpty() && rnd.nextInt(4) == 0) ids.random(rnd) else "jr-${ids.size}".also { ids += it }
            val doubt = if (rnd.nextBoolean()) -1 else rnd.nextInt(6)
            return jobResponse(
                id,
                executionId = if (rnd.nextBoolean()) execution else "exec-other",
                completed = if (rnd.nextBoolean()) units else listOf(units.dropLast(1), null).random(rnd),
                inDoubt = when (doubt) { 0 -> listOf(units.first()); 4 -> null; else -> emptyList() },
                unverified = when (doubt) { 1 -> listOf(units.first()); 5 -> null; else -> emptyList() },
                operatorRequired = when (doubt) { 2 -> true; 3 -> null; else -> false },
                instance = if (rnd.nextBoolean()) instance else listOf(otherInstance, null).random(rnd),
                connection = if (rnd.nextBoolean()) PICASSO_ONLINE else NOT_ONLINE.random(rnd),
            )
        }
        var step = dispatched()
        var now = step.state.enteredAt
        fun advance(event: EpisodeEvent) {
            now = now.plusSeconds(1)
            val before = step.state
            step = transition(before, event, null, now)
            checkResolution(before, event, step, stats)
            val resolved = before.phase != Phase.RESOLVED && step.state.phase == Phase.RESOLVED
            when (event) {
                is EpisodeEvent.EvidenceArrived -> {
                    val e = event.evidence
                    val a = before.attempt
                    val should = a != null && (before.phase == Phase.AWAITING_EVIDENCE || before.phase == Phase.UNKNOWN_OUTCOME) &&
                        e.reportKey !in before.seenReports && resolves(e, a.picassoInstanceId, a.executionId, a.approvedUnits)
                    assertEquals(should, resolved, "a report in ${before.phase} resolved=$resolved: $e")
                }
                is EpisodeEvent.DispatchReturned -> {
                    val (instanceId, executionId, approved) = approvedExecution(event)
                    val kept = before.bufferedEvidence
                    val should = kept.none { linked(it, instanceId, executionId) && !clean(it) } &&
                        kept.any { resolves(it, instanceId, executionId, approved) }
                    assertEquals(should, resolved, "the answer resolved=$resolved with kept $kept")
                }
                else -> {}
            }
        }
        if (rnd.nextBoolean()) repeat(rnd.nextInt(4)) { advance(EpisodeEvent.EvidenceArrived(report())) }
        advance(EpisodeEvent.DispatchReturned(DispatchResult.Answer(PicassoAnswers.approved(execution, instance, units)), step.state.token))
        var sent = 0
        while (sent < maxReports && !step.state.phase.terminal && step.state.phase != Phase.ESCALATED) {
            advance(EpisodeEvent.EvidenceArrived(report()))
            sent++
        }
    }

    /**
     * One run: a fuzzed event sequence, then the timers alone until the end. A [directed] run starts instead from
     * UNKNOWN(OUTCOME) with unit u-1 in doubt and u-2 unverified, reached by the ordinary steps — each of them checked too,
     * so the run's own bookkeeping (dispatched keys, counts, record numbers, unknowns) starts from what really happened.
     */
    private fun run(seed: Long, stats: Stats, directed: Boolean) {
        val f = EpisodeFuzzer(seed)
        var now = Episodes.T0
        val run = Run()
        var step: Step
        var lastSeq: Long
        if (!directed) {
            step = startEpisode(EpisodeEvent.Detected(Episodes.INSTANCE, PicassoRun1.manifest, f.first()), f.startPolicy(), now)
            lastSeq = check(null, null, step, 0, run, stats)
        } else {
            step = Episodes.open(Episodes.search("search-1"), Episodes.policy(), now)
            lastSeq = check(null, null, step, 0, run, stats)
            fun advance(event: EpisodeEvent, at: java.time.Instant) {
                val before = step.state
                step = transition(before, event, null, at)
                lastSeq = check(before, event, step, lastSeq, run, stats)
                now = at
            }
            advance(EpisodeEvent.DeadlineExpired(Timer.STATE, step.state.token), step.state.stateDeadline!!)
            val offered = step.request.candidates.first { it.kind != CandidateKind.ESCALATE }.candidateId
            advance(EpisodeEvent.DiagnosisReturned(Responses.forRequest(step.request, "RECOMMENDED", offered).toString()), step.state.enteredAt.plusSeconds(60))
            advance(EpisodeEvent.Revalidated(TriState.TRUE, step.state.token), step.state.enteredAt.plusSeconds(1))
            advance(EpisodeEvent.Recorded(step.state.attempt!!.intentSeq!!), step.state.enteredAt.plusSeconds(1))
            advance(EpisodeEvent.DispatchReturned(DispatchResult.Answer(PicassoAnswers.approved()), step.state.token), step.state.enteredAt.plusSeconds(2))
            advance(
                EpisodeEvent.EvidenceArrived(jobResponse("jr-start", inDoubt = listOf("u-1"), unverified = listOf("u-2"))),
                step.state.enteredAt.plusSeconds(5),
            )
            assertEquals(Phase.UNKNOWN_OUTCOME, step.state.phase, "the directed start")
            assertEquals(2, step.state.episodeUnknowns.size, "the directed start")
        }
        var n = 0
        while (n < maxEvents && !step.state.phase.terminal) {
            now = now.plusSeconds(f.gap())
            val before = step.state
            val event = f.next(before, n)
            step = transition(before, event, f.read(), now)
            lastSeq = check(before, event, step, lastSeq, run, stats)
            n++
        }
        // Termination (design §5.1, §17): timers alone end every run, in a few steps.
        var closing = 0
        while (!step.state.phase.terminal) {
            val s = step.state
            val deadline = s.stateDeadline
            val (event, at) = when {
                s.phase == Phase.ESCALATED -> EpisodeEvent.DeadlineExpired(Timer.RETENTION, s.token) to now.plusSeconds(172_800)
                deadline != null -> EpisodeEvent.DeadlineExpired(Timer.STATE, s.token) to maxOf(now, deadline)
                else -> EpisodeEvent.DeadlineExpired(Timer.EPISODE, EPISODE_TOKEN) to maxOf(now, s.episodeDeadline ?: now)
            }
            now = at
            step = transition(s, event, null, now)
            lastSeq = check(s, event, step, lastSeq, run, stats)
            closing++
            assertTrue(closing <= 6, "no end after $closing timers, still ${step.state.phase}")
        }
    }

    /** What the test itself remembers of one run: dispatched keys and counts, and the confirmations it saw accepted. */
    private class Run {
        val keys = mutableSetOf<String>()
        val counts = mutableMapOf<String, Int>()
        val confirmations = mutableListOf<Triple<Map<String, String?>, UnknownWhat, Int>>()
        /** The test's account of the unknowns after the last step. */
        var unknown: List<Unknown> = emptyList()
    }

    /**
     * The test's own account of what is unknown (design §9.1), computed without `currentUnknowns()`: each incident line's
     * unknowns, minus a confirmation accepted after that line had arrived, plus the episode's own items.
     */
    private fun oracle(s: EpisodeState, confirmations: List<Triple<Map<String, String?>, UnknownWhat, Int>>): List<Unknown> {
        val manifest = mapper.readTree(s.manifestJson) as ObjectNode
        val derived = s.symptoms.withIndex().filter { it.value.kind == SymptomKind.INCIDENT }.flatMap { (i, sym) ->
            deriveUnknowns(Snapshot(manifest, listOf(sym.line), emptyList())).filterNot { u ->
                confirmations.any { (subject, what, covers) -> u.names(subject, what) && i < covers }
            }
        }
        return derived + s.episodeUnknowns
    }

    /**
     * What the rules say about a ConfirmedUnknown, stated again here and not read off the code: it is wanted when it names
     * something unknown now, by a subject that names something, not the candidate's own outcome and not the outcome of the
     * execution the attempt is still waiting on.
     */
    private fun wanted(event: EpisodeEvent.ConfirmedUnknown, known: List<Unknown>, after: EpisodeState): Boolean {
        val executionId = after.attempt?.executionId
        val waitingOnIt = event.what == UnknownWhat.OUTCOME && executionId != null && event.subject["executionId"] == executionId &&
            (after.phase == Phase.AWAITING_EVIDENCE || after.phase == Phase.UNKNOWN_OUTCOME)
        return event.subject.values.any { it != null } && "candidateId" !in event.subject && !waitingOnIt &&
            known.any { it.names(event.subject, event.what) }
    }

    /** The test's own matching of an item by subject and kind — not the production `Unknown.matches`, which is under test. */
    private fun Unknown.names(subject: Map<String, String?>, what: UnknownWhat): Boolean = this.subject == subject && this.what == what

    /**
     * DONE from a report (design §12 ③): only a report linked to the attempt by (instanceId, executionId) that completed
     * every approved unit, online, with nothing in doubt, resolves — whether it arrives now or was kept for the answer.
     */
    private fun checkResolution(before: EpisodeState?, event: EpisodeEvent?, step: Step, stats: Stats) {
        if (before == null || before.phase == Phase.RESOLVED || step.state.phase != Phase.RESOLVED) return
        when (event) {
            is EpisodeEvent.EvidenceArrived -> {
                val a = before.attempt
                assertNotNull(a, "RESOLVED by a report with no attempt: $event")
                assertTrue(resolves(event.evidence, a.picassoInstanceId, a.executionId, a.approvedUnits), "RESOLVED by a report that cannot complete the attempt: $event")
                stats.evidenceResolved++
            }
            is EpisodeEvent.DispatchReturned -> {
                val (instanceId, executionId, units) = approvedExecution(event)
                assertFalse("$instanceId/$executionId" in before.usedExecutions, "RESOLVED on an answer whose execution was used before: $event")
                assertTrue(
                    before.bufferedEvidence.any { resolves(it, instanceId, executionId, units) },
                    "RESOLVED on an answer with no kept report that completes it: $event, kept ${before.bufferedEvidence}",
                )
                stats.replayResolved++
            }
            else -> {}
        }
    }

    /** The (instanceId, executionId, unit ids) an APPROVED answer names, read off its JSON. */
    private fun approvedExecution(event: EpisodeEvent.DispatchReturned): Triple<String?, String?, List<String>> {
        val answer = (event.result as? DispatchResult.Answer)?.let { mapper.readTree(it.json) }
        assertNotNull(answer, "a dispatch result that is no answer: $event")
        return Triple(
            answer.get("instanceId")?.textValue(),
            answer.get("executionId")?.textValue(),
            answer.get("steps")?.map { it.get("unitId").textValue() }.orEmpty(),
        )
    }

    /** ① of design §12, stated again here: linked by instance and execution, both known. */
    private fun linked(e: Evidence, instanceId: String?, executionId: String?): Boolean =
        instanceId != null && executionId != null && e.picassoInstanceId == instanceId && e.executionId == executionId

    /** ② of design §12, stated again here: nothing in doubt, unverified or left to a person, and all of it reported. */
    private fun clean(e: Evidence): Boolean =
        e.inDoubtUnits?.isEmpty() == true && e.unverifiedUnits?.isEmpty() == true && e.operatorRequired == false

    /** What design §12 ③ asks of a report that ends an attempt as DONE, stated again here and not read off the code. */
    private fun resolves(e: Evidence, instanceId: String?, executionId: String?, units: List<String>): Boolean =
        linked(e, instanceId, executionId) && units.isNotEmpty() && e.completedUnits.orEmpty().containsAll(units) &&
            e.connection == PICASSO_ONLINE && clean(e)

    /** True when the global rows (policy, episode deadline, agent layer) escalated this very step, whatever the event was. */
    private fun escalatedByGlobalRow(before: EpisodeState, after: EpisodeState): Boolean =
        before.phase != Phase.ESCALATED && after.phase == Phase.ESCALATED && after.escalation?.reason in GLOBAL_REASONS

    private fun check(before: EpisodeState?, event: EpisodeEvent?, step: Step, lastSeq: Long, run: Run, stats: Stats): Long {
        val s = step.state
        stats.phases += s.phase
        val keys = run.keys
        val counts = run.counts
        // A person's confirmation: accepted exactly when the rules say so, and then it takes out that one item and no other.
        if (event is EpisodeEvent.ConfirmedUnknown && before != null) {
            val open = s.phase != Phase.ESCALATED && !s.phase.terminal   // the global rows may have escalated first
            val want = wanted(event, run.unknown, s)
            if (step.reply == Reply.ACCEPTED) {
                assertTrue(open && want, "accepted a confirmation the rules do not allow: $event in ${s.phase}")
                run.confirmations += Triple(event.subject, event.what, before.symptoms.size)
                stats.accepted++
                if (before.episodeUnknowns.size >= 2 && before.episodeUnknowns.any { it.names(event.subject, event.what) }) stats.crowded++
                assertEquals(
                    before.episodeUnknowns.filterNot { it.names(event.subject, event.what) }, s.episodeUnknowns,
                    "episodeUnknowns after $event",
                )
            } else if (open) assertFalse(want, "refused a confirmation of something unknown: $event in ${s.phase}")
        }
        // §8.3: "the precondition holds" without the proposition it confirms is never accepted and never lets an intent be
        // recorded. Naming the proposal that waits in UNKNOWN(PRECONDITION), it is refused for exactly that, and nothing moves.
        if (event is EpisodeEvent.ConfirmedPrecondition && event.holds && event.proposition.isNullOrBlank() && before != null) {
            assertNotEquals(Reply.ACCEPTED, step.reply, "accepted a precondition confirmed to hold without a proposition: $event in ${before.phase}")
            assertTrue(step.commands.none { it is Command.RecordIntent }, "intent on a confirmation without a proposition: $event")
            val a = before.attempt
            if (before.phase == Phase.UNKNOWN_PRECONDITION && !escalatedByGlobalRow(before, s) && a != null &&
                event.candidateId == a.candidate?.candidateId && event.proposalId == a.proposalId
            ) {
                assertEquals(Reply.REFUSED_NO_PROPOSITION, step.reply, "reply to $event")
                assertEquals(Phase.UNKNOWN_PRECONDITION, s.phase, "a confirmation without a proposition moved the phase: $event")
                stats.bareRefused++
            }
        }
        // Without a person's confirmation the episode's items only grow, except where an outcome check ends the attempt
        // (design §9.4): that clears the candidate's own OUTCOME item and nothing else.
        if (before != null && event !is EpisodeEvent.ConfirmedUnknown) {
            val removed = before.episodeUnknowns.filter { it !in s.episodeUnknowns }
            if (removed.isNotEmpty()) {
                assertTrue(event is EpisodeEvent.ConfirmedOutcome || s.phase == Phase.RESOLVED, "episodeUnknowns lost $removed on $event")
                assertTrue(removed.all { "candidateId" in it.subject }, "episodeUnknowns lost a unit-level item: $removed on $event")
            }
        }
        val unknownNow = oracle(s, run.confirmations)
        run.unknown = unknownNow
        assertEquals(unknownNow, s.currentUnknowns(), "currentUnknowns differs from the test's account after $event")
        if (run.confirmations.isEmpty())
            assertEquals(deriveUnknowns(s.snapshot()) + s.episodeUnknowns, s.currentUnknowns(), "currentUnknowns differs from the derived list after $event")
        // Every Update gets a value back (design §7.2); a duplicated eventId changes nothing (§6).
        if (event != null && event.isUpdateForTest()) assertNotNull(step.reply, "no reply to $event")
        if (event is EpisodeEvent.SymptomJoined && before != null && event.symptom.eventId in before.seenEventIds)
            assertEquals(before.symptoms, s.symptoms, "a duplicated eventId was joined")
        // A JobResponse seen before moves nothing (design §12) — unless the global rows escalated this very step.
        if (event is EpisodeEvent.EvidenceArrived && before != null && event.evidence.reportKey in before.seenReports &&
            !escalatedByGlobalRow(before, s)
        ) {
            assertEquals(before.phase, s.phase, "a duplicated jobResponseId moved the phase: $event")
            assertEquals(before.attempt, s.attempt, "a duplicated jobResponseId changed the attempt: $event")
            assertEquals(before.episodeUnknowns, s.episodeUnknowns, "a duplicated jobResponseId changed the unknowns: $event")
        }
        checkResolution(before, event, step, stats)
        // Records are numbered 1, 2, 3, … across the run, the intent records included (design §13).
        var seq = lastSeq
        for (c in step.commands) {
            val n = when (c) {
                is Command.Record -> c.seq
                is Command.RecordIntent -> c.seq
                else -> continue
            }
            assertEquals(seq + 1, n, "record seq after $event")
            seq = n
        }
        assertEquals(seq, s.recordSeq, "state recordSeq after $event")
        // Dispatch only from DISPATCH_PENDING on this attempt's Recorded, once per key, never past an unknown (design §6).
        for (d in step.commands.filterIsInstance<Command.Dispatch>()) {
            stats.dispatches++
            assertEquals(Phase.DISPATCH_PENDING, before?.phase, "Dispatch outside DISPATCH_PENDING after $event")
            assertTrue(event is EpisodeEvent.Recorded && event.seq == before?.attempt?.intentSeq, "Dispatch without this attempt's Recorded: $event")
            assertTrue(keys.add(d.intent.idempotencyKey), "second Dispatch for ${d.intent.idempotencyKey}")
            if (d.intent.candidate.executionClass) {
                assertTrue(unknownNow.isEmpty(), "execution Dispatch while unknown: $unknownNow")
                if (oracle(s, emptyList()).isNotEmpty()) stats.unblocked++
            }
            val id = d.intent.candidate.candidateId
            counts[id] = (counts[id] ?: 0) + 1
        }
        assertEquals(counts.toMap(), s.dispatchCounts, "dispatch counts after $event")
        // An intent is recorded only on leaving a precondition phase for DISPATCH_PENDING, never past an unknown.
        for (i in step.commands.filterIsInstance<Command.RecordIntent>()) {
            assertTrue(before?.phase == Phase.REVALIDATING || before?.phase == Phase.UNKNOWN_PRECONDITION, "intent from ${before?.phase}")
            assertEquals(Phase.DISPATCH_PENDING, s.phase)
            if (i.intent.candidate.executionClass) assertTrue(unknownNow.isEmpty(), "execution intent while unknown: $unknownNow")
        }
        // Every wait with a deadline of its own has one (design §5.1).
        if (s.phase in OWN_DEADLINE) assertNotNull(s.stateDeadline, "${s.phase} without a deadline after $event")
        // No timer but the retention one runs past the episode deadline; entering ESCALATED starts the retention clock.
        val episodeEnd = s.episodeDeadline
        for (t in step.commands.filterIsInstance<Command.SetTimer>())
            if (t.which != Timer.RETENTION && episodeEnd != null) assertFalse(t.at.isAfter(episodeEnd), "${t.which} timer at ${t.at} past the episode deadline $episodeEnd")
        if (before?.phase != Phase.ESCALATED && s.phase == Phase.ESCALATED)
            assertTrue(step.commands.any { it is Command.SetTimer && it.which == Timer.RETENTION }, "entered ESCALATED without a retention timer after $event")
        // Every activity the runtime is asked to run has a positive time to run in.
        for (c in step.commands) when (c) {
            is Command.Diagnose -> assertTrue(c.budget.scheduleToCloseMs > 0, "Diagnose without a time budget after $event")
            is Command.Revalidate -> assertTrue(c.timeoutMs > 0, "Revalidate without a timeout after $event")
            is Command.RecordIntent -> assertTrue(c.timeoutMs > 0, "RecordIntent without a timeout after $event")
            is Command.Dispatch -> assertTrue(c.timeoutMs > 0, "Dispatch without a timeout after $event")
            else -> {}
        }
        // History entries carry their eight keys (contract §3.4).
        for (h in s.history) assertEquals(HISTORY_KEYS, h.toJson().fieldNames().asSequence().toList())
        // ESCALATED and the end states issue no agent commands.
        if (before != null && (before.phase == Phase.ESCALATED || before.phase.terminal))
            assertTrue(step.commands.none { it is Command.Diagnose || it is Command.Revalidate || it is Command.RecordIntent || it is Command.Dispatch }, "agent command from ${before.phase}")
        return seq
    }

    private fun EpisodeEvent.isUpdateForTest(): Boolean =
        this is EpisodeEvent.ApprovalDecided || this is EpisodeEvent.ConfirmedPrecondition || this is EpisodeEvent.ConfirmedOutcome ||
            this is EpisodeEvent.ConfirmedUnknown || this is EpisodeEvent.TakenOver || this is EpisodeEvent.Closed

    private companion object {
        val NOT_ONLINE = listOf("CONNECTION_STATE_UNSPECIFIED", "CONNECTION_STATE_OFFLINE", "CONNECTION_STATE_HIBERNATING", "CONNECTION_STATE_CONNECTION_BROKEN", null)
        /** About half of what the 500 report seeds measure (82 and 12): fails if either path becomes unreachable. */
        const val EVIDENCE_RESOLVED_FLOOR = 40
        const val REPLAY_RESOLVED_FLOOR = 6
        val OWN_DEADLINE = setOf(Phase.CORRELATING, Phase.AWAITING_APPROVAL, Phase.UNKNOWN_PRECONDITION, Phase.AWAITING_EVIDENCE, Phase.UNKNOWN_OUTCOME)
        val HISTORY_KEYS = listOf("attempt", "candidatesVersion", "diagnosis", "approval", "dispatch", "evidence", "closedAs", "at")
        val GLOBAL_REASONS = setOf(EscalationReason.AGENT_LAYER_OFF, EscalationReason.POLICY_MISSING, EscalationReason.POLICY_EXPIRED, EscalationReason.EPISODE_EXPIRED)
    }
}
