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

    /**
     * incident-6 on another unit and robot (RACK-204.S07, hum-08, exec-9), with a fresh digest: a second, unrelated
     * LINK_BROKEN subject.
     */
    fun otherUnitIncident(eventId: String = "incident:6-other", at: Instant = T0): Symptom {
        val line = mapper.readTree(PicassoRun1.incident("incident-6")) as ObjectNode
        line.put("unitId", "RACK-204.S07")
        line.put("robotId", "hum-08")
        line.put("executionId", "exec-9")
        line.put("digest", "digest-other-unit")
        return Symptom(eventId, SymptomKind.INCIDENT, line, at)
    }

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

/** picasso approval-window answers, shaped as picasso `ApprovalWire.encode` writes them (schema 4, picasso main 3d6530a). */
internal object PicassoAnswers {
    const val INSTANCE = "mw-1"
    const val EXECUTION = "exec-42"
    const val UNIT = "remedy-1-pick_place"

    /** One step per unit, in order, as picasso names them (`remedy-{n}-{skillType}`, ADR 48). */
    fun approved(executionId: String = EXECUTION, instanceId: String = INSTANCE, units: List<String> = listOf(UNIT)): String {
        val steps = units.joinToString(",") { """{"unitId":"$it","skillType":"pick_place","parameters":{"slot":"B-03"}}""" }
        return """{"schemaVersion":"4","contractSemver":"1.0.0","instanceId":"$instanceId","outcome":"APPROVED","executionId":"$executionId","steps":[$steps]}"""
    }

    /** [consumed] is the raw JSON of the `consumed` field: a consumption record for CONSUMED, `null` for every other refusal. */
    fun refused(refusal: String, schema: String = "4", consumed: String = "null"): String =
        """{"schemaVersion":"$schema","contractSemver":"1.0.0","instanceId":"$INSTANCE","outcome":"REFUSED","refusal":"$refusal","reason":"r","consumed":$consumed}"""

    /** A consumption record as picasso writes it (ADR 46): who, when, which execution, what went out. */
    fun consumedRecord(executionId: String = "exec-7"): String =
        """{"approverId":"op-9","approverKind":"PERSON","at":"2026-10-03T00:00:00Z","wallClockAt":"2026-10-03T00:00:00Z","executionId":"$executionId","steps":[{"unitId":"remedy-1-pick_place","skillType":"pick_place","parameters":{}}]}"""
}

/** A JobResponse for the default execution: everything completed, nothing in doubt, unless told otherwise. */
internal fun jobResponse(
    id: String,
    executionId: String? = PicassoAnswers.EXECUTION,
    completed: List<String>? = listOf("u-1"),
    inDoubt: List<String>? = emptyList(),
    unverified: List<String>? = emptyList(),
    operatorRequired: Boolean? = false,
    at: Instant = Episodes.T0,
    reached: String? = "E2",
): Evidence = Evidence(id, executionId, "DONE", reached, completed, inDoubt, unverified, operatorRequired, at)

/** DISPATCH_PENDING: auto-approved by the test table, revalidated TRUE one second later. */
internal fun pending(first: Symptom = Episodes.search("search-1"), read: PolicyRead = Episodes.policy()): Step =
    Episodes.open(first, read).expireState().answer().let { it.revalidated(TriState.TRUE, it.state.enteredAt.plusSeconds(1)) }

/** The intent's `Recorded`, one second after DISPATCH_PENDING began unless [now] says otherwise. */
internal fun Step.recorded(now: Instant = state.enteredAt.plusSeconds(1)): Step =
    on(EpisodeEvent.Recorded(state.attempt!!.intentSeq!!), now)

/** DISPATCHED: [pending] plus the intent's `Recorded`. */
internal fun dispatched(first: Symptom = Episodes.search("search-1"), read: PolicyRead = Episodes.policy()): Step =
    pending(first, read).recorded()

/** The dispatch result for the current phase (the token its command carried). */
internal fun Step.returned(result: DispatchResult, now: Instant = state.enteredAt.plusSeconds(2)): Step =
    on(EpisodeEvent.DispatchReturned(result, state.token), now)
