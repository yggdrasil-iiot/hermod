package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.random.Random

/**
 * Random but plausible events for one episode (design §17). Most events fit the phase the episode is in, so runs get
 * deep; the rest are noise — stale timers and tokens, another attempt's proposal, duplicated or unlinked reports,
 * policy flips, takeovers — that the machine must survive without breaking a fixed rule. Seeded: every run repeats.
 */
internal class EpisodeFuzzer(seed: Long) {
    private val r = Random(seed)
    /** Whether a precondition confirmation comes without its proposition: drawn apart from [r], whose draws stay as they were. */
    private val bare = Random(seed xor 0x5EED_B3C2L)

    fun first(): Symptom = when (r.nextInt(12)) {
        0, 1, 2 -> Episodes.incident("incident-6")
        3, 4 -> Episodes.search("search-4")
        5 -> Episodes.search("search-2")
        6 -> Episodes.search("search-3")
        else -> Episodes.search("search-1")
    }

    fun startPolicy(): PolicyRead = when (r.nextInt(12)) {
        0 -> Episodes.policyV1()
        1 -> PolicyRead.Missing
        else -> Episodes.policy()
    }

    /** Now and then a fresh policy read: valid, v1, rejected, or the layer off. */
    fun read(): PolicyRead? = when (r.nextInt(150)) {
        0 -> Episodes.policyV1()
        1 -> Episodes.policy { put("bogus", 1) }
        2 -> Episodes.policy { put("agentLayerEnabled", false) }
        3 -> Episodes.policy()
        else -> null
    }

    /** Seconds between two events: often quick, sometimes long enough to pass a deadline by time. */
    fun gap(): Long = r.nextLong(0, 120)

    fun next(s: EpisodeState, n: Int): EpisodeEvent = (if (r.nextInt(10) < 6) fitting(s, n) else null) ?: noise(s, n)

    private fun fitting(s: EpisodeState, n: Int): EpisodeEvent? {
        // Where a person can confirm, now and then one does — so the confirmation path is walked, not just probed.
        // (1 in 6; 1 in 2 before the dispatch, where an unknown decides whether an execution candidate may go at all.)
        val odds = if (s.phase in BEFORE_DISPATCH) 2 else 6
        if (s.phase != Phase.ESCALATED && !s.phase.terminal && r.nextInt(odds) == 0) currentItem(s)?.let { return confirm(it) }
        // An unknown turning up before the dispatch: what a later confirmation has to clear for the gate to let go.
        if (s.phase in BEFORE_DISPATCH && r.nextInt(12) == 0) return EpisodeEvent.SymptomJoined(unknownLine(n))
        val a = s.attempt
        return when (s.phase) {
            Phase.CORRELATING -> EpisodeEvent.DeadlineExpired(Timer.STATE, s.token)
            Phase.DIAGNOSING -> a?.let {
                if (r.nextInt(8) == 0) EpisodeEvent.DiagnosisFailed(r.nextBoolean(), "fuzz") else EpisodeEvent.DiagnosisReturned(answer(it))
            }
            Phase.AWAITING_APPROVAL -> if (r.nextInt(4) == 0) EpisodeEvent.DeadlineExpired(Timer.STATE, s.token) else a?.let { decide(it, stale = false) }
            Phase.REVALIDATING -> EpisodeEvent.Revalidated(tri(), s.token)
            Phase.UNKNOWN_PRECONDITION -> when (r.nextInt(4)) {
                0 -> EpisodeEvent.DeadlineExpired(Timer.STATE, s.token)
                1 -> EpisodeEvent.DeadlineExpired(Timer.RECHECK, s.token)
                2 -> EpisodeEvent.Revalidated(tri(), s.token)
                else -> a?.let { confirmPrecondition(it, stale = false) }
            }
            Phase.DISPATCH_PENDING -> a?.let {
                when (r.nextInt(6)) {
                    0 -> EpisodeEvent.RecordFailed(it.intentSeq ?: 0, "fuzz")
                    // an unknown that appears while the intent is being recorded: the gate at Recorded must stop it
                    1 -> EpisodeEvent.SymptomJoined(unknownLine(n))
                    else -> EpisodeEvent.Recorded(it.intentSeq ?: 0)
                }
            }
            Phase.DISPATCHED -> if (r.nextInt(4) == 0) a?.let { evidence(it, n) } else EpisodeEvent.DispatchReturned(dispatchResult(), s.token)
            // Two items held and the person says it was not done: the attempt ends and the items are left to be confirmed.
            Phase.UNKNOWN_OUTCOME if s.episodeUnknowns.size >= 2 && r.nextBoolean() ->
                a?.let { EpisodeEvent.ConfirmedOutcome(it.candidate?.candidateId ?: "ESCALATE", it.proposalId, false, Episodes.OPERATOR) }
            Phase.AWAITING_EVIDENCE, Phase.UNKNOWN_OUTCOME -> when (r.nextInt(8)) {
                0 -> EpisodeEvent.DeadlineExpired(Timer.STATE, s.token)
                1, 2, 3, 4 -> a?.let { evidence(it, n) }
                else -> a?.let { confirmOutcome(it, stale = false) }
            }
            Phase.ESCALATED -> if (r.nextBoolean()) EpisodeEvent.Closed(Episodes.OPERATOR, "fuzz") else EpisodeEvent.DeadlineExpired(Timer.RETENTION, s.token)
            Phase.RESOLVED, Phase.SUPERSEDED, Phase.CLOSED -> null
        }
    }

    private fun noise(s: EpisodeState, n: Int): EpisodeEvent {
        val a = s.attempt
        val fallback = EpisodeEvent.DeadlineExpired(Timer.STATE, s.token - 1)
        return when (r.nextInt(14)) {
            0 -> EpisodeEvent.SymptomJoined(
                when {
                    r.nextBoolean() -> when {
                        r.nextInt(3) == 0 -> twoKinds(n)
                        r.nextBoolean() -> arrival(INCIDENTS.random(r), n)
                        else -> fixed(INCIDENTS.random(r))
                    }
                    else -> Episodes.search(SEARCHES.random(r))
                },
            )
            1 -> EpisodeEvent.DeadlineExpired(listOf(Timer.STATE, Timer.RECHECK, Timer.RETENTION).random(r), s.token - r.nextLong(0, 3))
            2 -> if (r.nextInt(4) == 0) EpisodeEvent.TakenOver(Episodes.OPERATOR) else EpisodeEvent.DeadlineExpired(Timer.RECHECK, s.token)
            3 -> if (r.nextInt(4) == 0) EpisodeEvent.AgentOff else fallback
            4 -> EpisodeEvent.Revalidated(tri(), s.token - r.nextLong(0, 2))
            5 -> EpisodeEvent.DispatchReturned(dispatchResult(), s.token - r.nextLong(0, 2))
            6 -> a?.let { decide(it, stale = r.nextBoolean()) } ?: fallback
            7 -> a?.let { confirmPrecondition(it, stale = r.nextBoolean()) } ?: fallback
            8 -> a?.let { confirmOutcome(it, stale = r.nextBoolean()) } ?: fallback
            9 -> a?.let { evidence(it, n) } ?: fallback
            10 -> EpisodeEvent.Recorded(a?.intentSeq?.let { it + r.nextLong(-1, 2) } ?: r.nextLong(1, 50))
            11 -> a?.let { EpisodeEvent.DiagnosisReturned(answer(it)) } ?: EpisodeEvent.DiagnosisReturned("{")
            12 -> confirmUnknown(s)
            else -> EpisodeEvent.Closed(Episodes.OPERATOR, "noise")
        }
    }

    /** incident-6 with its progress not observed: two kinds of unknown (LINK_BROKEN, PROGRESS_UNOBSERVED) on one unit. */
    private fun twoKinds(n: Int): Symptom = Episodes.incident("incident-6").let {
        val line = it.line
        (line.get("observation") as ObjectNode).putNull("progressObservable")
        Symptom("incident:two-kinds@$n", it.kind, line, it.observedAt)
    }

    /** A new observation that says unknown: incident-6 again, on its unit or the other one, or with two kinds on one unit. */
    private fun unknownLine(n: Int): Symptom = when (r.nextInt(3)) {
        0 -> twoKinds(n)
        1 -> arrival("incident-6", n)
        else -> arrival(OTHER_UNIT, n)
    }

    /** An incident line under its fixed eventId; [OTHER_UNIT] is incident-6 on another unit and robot (a second subject). */
    private fun fixed(incidentId: String): Symptom =
        if (incidentId == OTHER_UNIT) Episodes.otherUnitIncident() else Episodes.incident(incidentId)

    /** The same incident line arriving again under a fresh eventId — a new observation (design §9.1). */
    private fun arrival(incidentId: String, n: Int): Symptom =
        fixed(incidentId).let { Symptom("incident:$incidentId@$n", it.kind, it.line, it.observedAt) }

    private fun answer(a: Attempt): String {
        val offered = a.request.candidates.filter { it.kind != CandidateKind.ESCALATE }.map { it.candidateId }
            .ifEmpty { a.request.candidates.map { it.candidateId } }
        return when (r.nextInt(30)) {
            0 -> Responses.forRequest(a.request, "NO_GROUNDS", null).toString()
            1 -> Responses.forRequest(a.request, "UNCITED", null).toString()
            2 -> Responses.forRequest(a.request, "OUT_OF_CANDIDATES", null, picked = "elsewhere").toString()
            3 -> Responses.forRequest(a.request, candidateId = "APPROVE_REMEDY:hum-99:X:pick_place").toString()
            4 -> "{"
            5 -> Responses.forRequest(a.request, candidateId = offered.random(r)) { putArray("citations") }.toString()
            else -> Responses.forRequest(a.request, candidateId = offered.random(r)).toString()
        }
    }

    private fun decide(a: Attempt, stale: Boolean): EpisodeEvent = EpisodeEvent.ApprovalDecided(
        if (stale) "${a.request.episodeId}#0" else a.proposalId, a.request.candidatesVersion,
        r.nextInt(3) != 0, Episodes.OPERATOR, RejectReason.entries.random(r), null,
    )

    private fun confirmPrecondition(a: Attempt, stale: Boolean): EpisodeEvent {
        val holds = r.nextBoolean()
        // A confirmation that the precondition holds carries the card's proposition (§8.3) — except now and then, when the
        // core must refuse it.
        val proposition = if (holds && bare.nextInt(4) == 0) null else "fuzz proposition"
        return EpisodeEvent.ConfirmedPrecondition(
            a.candidate?.candidateId ?: "ESCALATE", if (stale) "${a.request.episodeId}#0" else a.proposalId, holds, Episodes.OPERATOR, proposition,
        )
    }

    private fun confirmOutcome(a: Attempt, stale: Boolean): EpisodeEvent = EpisodeEvent.ConfirmedOutcome(
        a.candidate?.candidateId ?: "ESCALATE", if (stale) "${a.request.episodeId}#0" else a.proposalId, r.nextBoolean(), Episodes.OPERATOR,
    )

    /** A JobResponse; now and then one that leaves two units in doubt at once (the episode then holds two items). */
    private fun evidence(a: Attempt, n: Int): EpisodeEvent {
        val twoInDoubt = r.nextBoolean()
        return EpisodeEvent.EvidenceArrived(
            jobResponse(
                "jr-${r.nextInt(n + 2)}",
                executionId = if (r.nextInt(4) == 0) "exec-other" else (a.executionId ?: "exec-1"),
                completed = listOf("u-1"),
                inDoubt = if (twoInDoubt) listOf("u-1") else listOf(emptyList<String>(), listOf("u-1"), null).random(r),
                unverified = if (twoInDoubt) listOf("u-2") else listOf(emptyList<String>(), listOf("u-2"), null).random(r),
                operatorRequired = listOf(false, true, null).random(r),
            ),
        )
    }

    private fun dispatchResult(): DispatchResult = when (r.nextInt(10)) {
        0, 1, 2, 3, 4 -> DispatchResult.Answer(PicassoAnswers.approved("exec-${r.nextInt(1, 3)}"))
        5 -> DispatchResult.Answer(PicassoAnswers.refused(PICASSO_REFUSALS.toList().random(r)))
        6 -> DispatchResult.Answer(PicassoAnswers.refused("NEW_VALUE"))
        7 -> DispatchResult.Uncertain("fuzz")
        8 -> DispatchResult.PersonTaskIssued
        else -> DispatchResult.Answer(PicassoAnswers.refused("REFUSED_BY_GATE", schema = "3"))
    }

    /** One of the unknowns the episode holds now, if any. */
    private fun currentItem(s: EpisodeState): Unknown? =
        (try { s.currentUnknowns() } catch (e: Exception) { emptyList() }).let { if (it.isEmpty()) null else it.random(r) }

    private fun confirm(subject: Map<String, String?>, what: UnknownWhat) = EpisodeEvent.ConfirmedUnknown(subject, what, Episodes.OPERATOR, null)

    private fun confirm(u: Unknown) = confirm(u.subject, u.what)

    /** A confirmation that is exact, or one that misses by a little: a key short, a key more, a null, the wrong kind, the candidate's own. */
    private fun confirmUnknown(s: EpisodeState): EpisodeEvent {
        val u = currentItem(s) ?: return confirm(mapOf("unitId" to "nobody"), UnknownWhat.OUTCOME)
        return when (r.nextInt(8)) {
            0 -> u.subject.keys.random(r).let { key -> confirm(u.subject.filterKeys { it != key }, u.what) }
            1 -> confirm(u.subject + ("extra" to "x"), u.what)
            2 -> u.subject.keys.random(r).let { key -> confirm(u.subject + (key to null), u.what) }
            3 -> confirm(u.subject, UnknownWhat.entries.filter { it != u.what }.random(r))
            4 -> confirm(mapOf("candidateId" to (s.attempt?.candidate?.candidateId ?: "ESCALATE")), UnknownWhat.OUTCOME)
            else -> confirm(u)
        }
    }

    private fun tri(): TriState = TriState.entries.random(r)

    private companion object {
        const val OTHER_UNIT = "incident-other-unit"
        val BEFORE_DISPATCH = setOf(Phase.CORRELATING, Phase.DIAGNOSING, Phase.AWAITING_APPROVAL, Phase.REVALIDATING, Phase.UNKNOWN_PRECONDITION)
        val INCIDENTS = listOf("incident-1", "incident-2", "incident-4", "incident-6", OTHER_UNIT)
        val SEARCHES = listOf("search-1", "search-2", "search-3", "search-4")
    }
}
