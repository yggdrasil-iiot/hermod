package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/** Drives episodes in tests: picasso run-1 lines as symptoms, the test policy table ([Policies]), a fixed clock. */
internal object Episodes {
    val T0: Instant = Instant.parse("2026-10-01T00:00:00Z")
    const val INSTANCE = "ep:test/run-a"
    val OPERATOR = Approver("op-1")
    const val REMEDY = "APPROVE_REMEDY:hum-02:PATROL-1:pick_place"
    const val CONFIRM_DONE = "OPERATOR_DECISION:exec-8:RACK-204.S06:CONFIRM_DONE"

    fun search(id: String, at: Instant = T0): Symptom =
        Symptom("search:$id", SymptomKind.SEARCH, mapper.readTree(PicassoRun1.search(id)) as ObjectNode, at)

    fun incident(id: String, at: Instant = T0): Symptom =
        Symptom("incident:$id", SymptomKind.INCIDENT, mapper.readTree(PicassoRun1.incident(id)) as ObjectNode, at)

    /** The test table: APPROVE_REMEDY auto-approval ON, so the auto path can be tested. */
    fun policy(edit: ObjectNode.() -> Unit = {}): PolicyRead = PolicyRead.Found(Policies.node(edit))

    /** Policy v1 (design §10.1): APPROVE_REMEDY auto-approval off. */
    fun policyV1(edit: ObjectNode.() -> Unit = {}): PolicyRead = policy {
        ((get("autoApprove") as ObjectNode).get("APPROVE_REMEDY") as ObjectNode).put("allowed", false)
        edit()
    }

    fun open(first: Symptom = search("search-1"), read: PolicyRead = policy(), now: Instant = T0): Step =
        startEpisode(EpisodeEvent.Detected(INSTANCE, PicassoRun1.manifest, first), read, now)
}

internal fun Step.on(event: EpisodeEvent, now: Instant, read: PolicyRead? = null): Step = transition(state, event, read, now)

/** Fires the current phase's STATE timer at its deadline. */
internal fun Step.expireState(read: PolicyRead? = null): Step =
    on(EpisodeEvent.DeadlineExpired(Timer.STATE, state.token), state.stateDeadline ?: error("no STATE timer in ${state.phase}"), read)

internal val Step.request: DiagnosisRequest get() = state.attempt?.request ?: error("no attempt in ${state.phase}")

/** A `revalidate` result for the current phase (the token its command carried). */
internal fun Step.revalidated(result: TriState, now: Instant): Step = on(EpisodeEvent.Revalidated(result, state.token), now)

/** narrator's answer to the current request, one minute after DIAGNOSING began. A clean RECOMMENDED by default. */
internal fun Step.answer(
    outcome: String = "RECOMMENDED",
    candidateId: String? = request.candidates.firstOrNull { it.kind != CandidateKind.ESCALATE }?.candidateId,
    read: PolicyRead? = null,
    edit: ObjectNode.() -> Unit = {},
): Step = on(
    EpisodeEvent.DiagnosisReturned(Responses.forRequest(request, outcome, candidateId, edit = edit).toString()),
    state.enteredAt.plusSeconds(60),
    read,
)

internal fun Step.timers(which: Timer): List<Command.SetTimer> = commands.filterIsInstance<Command.SetTimer>().filter { it.which == which }

internal fun Step.records(kind: RecordKind): List<Command.Record> = commands.filterIsInstance<Command.Record>().filter { it.entry.kind == kind }

internal fun Step.notices(): List<Notice> = commands.filterIsInstance<Command.Notify>().map { it.notice }

internal val Step.reason: EscalationReason? get() = state.escalation?.reason
