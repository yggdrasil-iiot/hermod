package koshei.episode

import kotlin.random.Random

/**
 * Random but plausible events for one episode (design §17). Most events fit the phase the episode is in, so runs get
 * deep; the rest are noise — stale timers and tokens, another attempt's proposal, duplicated or unlinked reports,
 * policy flips, takeovers — that the machine must survive without breaking a fixed rule. Seeded: every run repeats.
 */
internal class EpisodeFuzzer(seed: Long) {
    private val r = Random(seed)

    fun first(): Symptom = when (r.nextInt(12)) {
        0 -> Episodes.incident("incident-6")
        1, 2 -> Episodes.search("search-4")
        3 -> Episodes.search("search-2")
        4 -> Episodes.search("search-3")
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
                    1 -> EpisodeEvent.SymptomJoined(if (r.nextBoolean()) twoKinds(n) else arrival("incident-6", n))
                    else -> EpisodeEvent.Recorded(it.intentSeq ?: 0)
                }
            }
            Phase.DISPATCHED -> if (r.nextInt(4) == 0) a?.let { evidence(it, n) } else EpisodeEvent.DispatchReturned(dispatchResult(), s.token)
            Phase.AWAITING_EVIDENCE, Phase.UNKNOWN_OUTCOME -> when (r.nextInt(4)) {
                0 -> EpisodeEvent.DeadlineExpired(Timer.STATE, s.token)
                1 -> a?.let { evidence(it, n) }
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
                if (r.nextBoolean()) (if (r.nextInt(3) == 0) twoKinds(n) else if (r.nextBoolean()) arrival(INCIDENTS.random(r), n) else Episodes.incident(INCIDENTS.random(r)))
                else Episodes.search(SEARCHES.random(r)),
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
        (line.get("observation") as com.fasterxml.jackson.databind.node.ObjectNode).putNull("progressObservable")
        Symptom("incident:two-kinds@$n", it.kind, line, it.observedAt)
    }

    /** The same incident line arriving again under a fresh eventId — a new observation (design §9.1). */
    private fun arrival(incidentId: String, n: Int): Symptom =
        Episodes.incident(incidentId).let { Symptom("incident:$incidentId@$n", it.kind, it.line, it.observedAt) }

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

    private fun confirmPrecondition(a: Attempt, stale: Boolean): EpisodeEvent = EpisodeEvent.ConfirmedPrecondition(
        a.candidate?.candidateId ?: "ESCALATE", if (stale) "${a.request.episodeId}#0" else a.proposalId, r.nextBoolean(), Episodes.OPERATOR,
    )

    private fun confirmOutcome(a: Attempt, stale: Boolean): EpisodeEvent = EpisodeEvent.ConfirmedOutcome(
        a.candidate?.candidateId ?: "ESCALATE", if (stale) "${a.request.episodeId}#0" else a.proposalId, r.nextBoolean(), Episodes.OPERATOR,
    )

    private fun evidence(a: Attempt, n: Int): EpisodeEvent = EpisodeEvent.EvidenceArrived(
        jobResponse(
            "jr-${r.nextInt(n + 2)}",
            executionId = if (r.nextInt(4) == 0) "exec-other" else (a.executionId ?: "exec-1"),
            completed = listOf("u-1"),
            inDoubt = listOf(emptyList<String>(), listOf("u-1"), null).random(r),
            unverified = listOf(emptyList<String>(), listOf("u-2"), null).random(r),
            operatorRequired = listOf(false, true, null).random(r),
        ),
    )

    private fun dispatchResult(): DispatchResult = when (r.nextInt(8)) {
        0, 1, 2 -> DispatchResult.Answer(PicassoAnswers.approved("exec-${r.nextInt(1, 3)}"))
        3 -> DispatchResult.Answer(PicassoAnswers.refused(PICASSO_REFUSALS_V2.toList().random(r)))
        4 -> DispatchResult.Answer(PicassoAnswers.refused("NEW_VALUE"))
        5 -> DispatchResult.Uncertain("fuzz")
        6 -> DispatchResult.PersonTaskIssued
        else -> DispatchResult.Answer(PicassoAnswers.refused("REFUSED_BY_GATE", schema = "3"))
    }

    private fun confirmUnknown(s: EpisodeState): EpisodeEvent {
        val unknowns = try { s.currentUnknowns() } catch (e: Exception) { emptyList() }
        if (unknowns.isEmpty()) return EpisodeEvent.ConfirmedUnknown(mapOf("unitId" to "nobody"), UnknownWhat.OUTCOME, Episodes.OPERATOR, null)
        val u = unknowns.random(r)
        return EpisodeEvent.ConfirmedUnknown(u.subject, u.what, Episodes.OPERATOR, null)
    }

    private fun tri(): TriState = TriState.entries.random(r)

    private companion object {
        val INCIDENTS = listOf("incident-1", "incident-2", "incident-4", "incident-6")
        val SEARCHES = listOf("search-1", "search-2", "search-3", "search-4")
    }
}
