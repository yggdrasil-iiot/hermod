package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test fun `the random runs reach every phase and dispatch often`() {
        val stats = Stats()
        for (seed in seeds) run(seed, stats)
        assertEquals(Phase.entries.toSet(), stats.phases)
        assertTrue(stats.dispatches > 100, "only ${stats.dispatches} dispatches")
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
    }

    /**
     * The test's own account of what is unknown (design §9.1), computed without `currentUnknowns()`: each incident line's
     * unknowns, minus a confirmation accepted after that line had arrived, plus the episode's own items.
     */
    private fun oracle(s: EpisodeState, run: Run): List<Unknown> {
        val manifest = mapper.readTree(s.manifestJson) as com.fasterxml.jackson.databind.node.ObjectNode
        val derived = s.symptoms.withIndex().filter { it.value.kind == SymptomKind.INCIDENT }.flatMap { (i, sym) ->
            deriveUnknowns(Snapshot(manifest, listOf(sym.line), emptyList())).filterNot { u ->
                run.confirmations.any { (subject, what, covers) -> subject == u.subject && what == u.what && i < covers }
            }
        }
        return derived + s.episodeUnknowns
    }

    private fun check(before: EpisodeState?, event: EpisodeEvent?, step: Step, lastSeq: Long, run: Run, stats: Stats): Long {
        val s = step.state
        stats.phases += s.phase
        val keys = run.keys
        val counts = run.counts
        if (event is EpisodeEvent.ConfirmedUnknown && step.reply == Reply.ACCEPTED && before != null)
            run.confirmations += Triple(event.subject, event.what, before.symptoms.size)
        val unknownNow = oracle(s, run)
        assertEquals(unknownNow, s.currentUnknowns(), "currentUnknowns differs from the test's account after $event")
        // Every Update gets a value back (design §7.2); a duplicated eventId changes nothing (§6).
        if (event != null && event.isUpdateForTest()) assertNotNull(step.reply, "no reply to $event")
        if (event is EpisodeEvent.SymptomJoined && before != null && event.symptom.eventId in before.seenEventIds)
            assertEquals(before.symptoms, s.symptoms, "a duplicated eventId was joined")
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
            if (d.intent.candidate.executionClass) assertTrue(unknownNow.isEmpty(), "execution Dispatch while unknown: $unknownNow")
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
    }
}
