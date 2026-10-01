package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    }

    @Test fun `random event sequences keep the fixed rules and always end`() {
        for (seed in seeds) {
            try {
                run(seed, Stats())
            } catch (e: AssertionError) {
                throw AssertionError("seed $seed: ${e.message}", e)
            }
        }
    }

    @Test fun `the random runs reach every phase, dispatch often, and walk the confirmation path`() {
        val stats = Stats()
        for (seed in seeds) run(seed, stats)
        assertEquals(Phase.entries.toSet(), stats.phases)
        val seen = "dispatches=${stats.dispatches}, accepted=${stats.accepted}, unblocked=${stats.unblocked}, crowded=${stats.crowded}"
        assertTrue(stats.dispatches > 100, "too few dispatches: $seen")
        assertTrue(stats.accepted >= 100, "too few accepted ConfirmedUnknown: $seen")
        assertTrue(stats.unblocked >= 10, "too few execution Dispatches that a confirmation unblocked: $seen")
        assertTrue(stats.crowded >= 2, "too few confirmations of one episode item among several: $seen")
    }

    private fun run(seed: Long, stats: Stats) {
        val f = EpisodeFuzzer(seed)
        var now = Episodes.T0
        var step = startEpisode(EpisodeEvent.Detected(Episodes.INSTANCE, PicassoRun1.manifest, f.first()), f.startPolicy(), now)
        val run = Run()
        var lastSeq = check(null, null, step, 0, run, stats)
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
                confirmations.any { (subject, what, covers) -> u.matches(subject, what) && i < covers }
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
            known.any { it.matches(event.subject, event.what) }
    }

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
                if (before.episodeUnknowns.size >= 2 && before.episodeUnknowns.any { it.matches(event.subject, event.what) }) stats.crowded++
                assertEquals(
                    before.episodeUnknowns.filterNot { it.matches(event.subject, event.what) }, s.episodeUnknowns,
                    "episodeUnknowns after $event",
                )
            } else if (open) assertFalse(want, "refused a confirmation of something unknown: $event in ${s.phase}")
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
        if (event is EpisodeEvent.EvidenceArrived && before != null && event.evidence.jobResponseId in before.seenJobResponseIds &&
            !escalatedByGlobalRow(before, s)
        ) {
            assertEquals(before.phase, s.phase, "a duplicated jobResponseId moved the phase: $event")
            assertEquals(before.attempt, s.attempt, "a duplicated jobResponseId changed the attempt: $event")
            assertEquals(before.episodeUnknowns, s.episodeUnknowns, "a duplicated jobResponseId changed the unknowns: $event")
        }
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
        val OWN_DEADLINE = setOf(Phase.CORRELATING, Phase.AWAITING_APPROVAL, Phase.UNKNOWN_PRECONDITION, Phase.AWAITING_EVIDENCE, Phase.UNKNOWN_OUTCOME)
        val HISTORY_KEYS = listOf("attempt", "candidatesVersion", "diagnosis", "approval", "dispatch", "evidence", "closedAs", "at")
        val GLOBAL_REASONS = setOf(EscalationReason.AGENT_LAYER_OFF, EscalationReason.POLICY_MISSING, EscalationReason.POLICY_EXPIRED, EscalationReason.EPISODE_EXPIRED)
    }
}
