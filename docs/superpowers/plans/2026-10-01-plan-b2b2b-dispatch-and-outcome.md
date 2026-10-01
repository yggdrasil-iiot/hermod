# 계획 B2b-2b — 실행과 결과 (DISPATCH_PENDING 이후) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 실행 의도 기록의 결과부터 picasso 답, 완료 근거, 사람의 현물 확인, UNKNOWN(OUTCOME)까지 전이 함수의 뒤 절반을 만든다(설계 §5.1 · §5.2 · §8.4 · §11 · §12).

**Architecture:** B2b-2a 의 `transition` 에 상태 넷(DISPATCH_PENDING · DISPATCHED · AWAITING_EVIDENCE · UNKNOWN_OUTCOME)의 처리기를 붙인다. picasso 승인 창구의 답은 글자 그대로 받아 순수 함수 `judgePicassoAnswer` 가 판정한다. 결과 통보(JobResponse)는 담는 쪽이 나르는 형 있는 값 `Evidence` 로 받고, 설계 §12 의 판정 순서대로 다룬다. `Recorded` 를 받아 `Dispatch` 를 내기 직전에는 B2b-2a 의 마지막 관문을 다시 지난다 — B2b-2a 가 넘긴 의무다.

**Tech Stack:** Kotlin 2.2 / JDK 21, Jackson databind 2.17 (트리), JUnit 5 / kotlin-test

**작업 위치:** worktree `C:\Users\Eisen\Desktop\Labs\[projects] koshchei\koshei-episode-core`, 브랜치 `feat/episode-core` (지금 머리 `bb41aa5`, 시험 227개). 원래 체크아웃(`koshei/`)은 건드리지 않는다. 파일은 Write/Edit 로만 쓴다(heredoc 금지). bare `git stash` 금지. picasso 저장소는 읽기만 한다.

**커밋 규칙:** 모든 커밋은 `git commit -m "<제목>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`.

**범위 밖 (계획 B2b-2c):** `unknowns` 목록 항목의 사람 확인(§7.2 `confirm` 의 둘째 경우, §9.1 의 판정 유효 규칙), §17 의 무작위 사건 열 속성 시험. **범위 밖 (계획 D):** `SAGA_ACTION` 과 자식 사가 결과(`CHILD_COMPLETED` · `CHILD_FAILED` · `childMaxMs`). **범위 밖 (B3 · C):** 결과 통보 JSON 을 `Evidence` 로 읽는 일(담는 쪽과 모양을 정한 뒤), 운영자 과업 발행 자체.

---

## picasso 답의 실제 모양 — 설계 v0.5 를 바로잡는다

picasso main `584c6cd` 의 `picasso/src/main/kotlin/dev/picasso/middleware/Approval.kt`(`ApprovalWire.encode`, `ApprovalRefusal`)와 `docs/orchestration.md` §7.3 을 읽었다(읽기만).

| 설계 v0.5 | 실제 | 이 계획 |
|---|---|---|
| 답에 참 · 거짓 `delivered` 가 있고 `true` 가 아니면 UNKNOWN(OUTCOME) (§5.2 행, §8.4) | 그런 칸이 없다. 답은 `{"schemaVersion":"2","contractSemver":…,"outcome":"APPROVED","executionId":…,"steps":[…]}` 또는 `{…,"outcome":"REFUSED","refusal":…,"reason":…}`. picasso §7.3 의 「승인의 응답이 실제 전달된 값을 반환합니다」는 `steps` 를 말한다 | `APPROVED` 는 `executionId` 와 `steps` 가 있어야 수락이다(없으면 `DISPATCH_ANSWER_UNKNOWN`). 이력의 `dispatch.delivered` 는 `steps` 다(계약 §3.4 의 「승인 응답이 돌려준 실제 전달 값」과 같은 뜻). `delivered` 행은 지운다 |
| 판 2 의 아는 거절 값은 넷, 전체 목록은 picasso 에 묻는다(§8.4, §19) | `ApprovalRefusal` 은 열다섯: `WITHHELD` · `NO_PROPOSAL` · `PROPOSAL_CHANGED` · `NOT_DECLARED` · `REVOKED` · `EXPIRED` · `ROBOT_OUT_OF_SCOPE` · `SKILL_OUT_OF_SCOPE` · `VALUE_NOT_DECLARED` · `OBJECT_NOT_OBSERVED` · `DECLARED_CONTRADICTS_OBSERVED` · `CAPABILITY_UNKNOWN` · `VALUES_NOT_ACCEPTED` · `REFUSED_BY_GATE` · `REMEDY_NOT_APPLIED` | 열다섯이 아는 값. 판정 규칙은 그대로 — `NO_PROPOSAL` · `REMEDY_NOT_APPLIED` 는 UNKNOWN(OUTCOME), 나머지 열셋은 ESCALATED(`DISPATCH_REFUSED`), 그 밖은 `DISPATCH_ANSWER_UNKNOWN`. `PROPOSAL_CHANGED` 는 picasso 가 「다시 읽고 다시 시도」를 권하지만 재진단 목록은 사용자 결정 전까지 비어 있다(§8.4) — §19 에 적는다 |
| 결과 통보를 실행에 잇는 칸을 picasso 에 묻는다(§19) | `APPROVED` 답이 `executionId` 를 싣는다 | 결과 통보는 `executionId` 로 이 시도에 잇는다. **대상 단위**를 잇는 칸은 여전히 열려 있어 결과 통보로 DONE 에 닿지 못한다(§12) |

`approverKind` 는 답이 아니라 **koshei 가 보내는** 요청 칸이다(picasso §7.1): POLICY 승인은 `AGENT`, 사람의 승인은 `PERSON`, 사람 과업은 창구를 지나지 않으므로 `null`.

## 설계에서 코드로 옮길 때 정한 것

| 설계 | 이 계획의 코드 |
|---|---|
| §5.2 DISPATCH_PENDING `Recorded` → DISPATCHED | 이 시도의 `intentSeq` 와 같은 `Recorded` 만. 받으면 `passesDispatchGate` 를 다시 지나고(B2b-2a 가 넘긴 의무: 확인 불가 · 승인 유효 · POLICY 승인의 지금 표 판정), 통과하면 `Dispatch` 와 함께 실행 횟수(`dispatchCounts`)를 센다 |
| §6 `DispatchReturned(result)` | `DispatchReturned(result, token)` — `Revalidated` 와 같은 까닭으로 상태 `token` 을 돌려받는다 |
| §6 `EvidenceArrived(eventId, grade, outcome)` | `EvidenceArrived(evidence: Evidence)` — 결과 통보의 칸 그대로(`jobResponseId` · `executionId` · `physicalState` · `reachedEvidence` · `completedUnits` · `inDoubtUnits` · `unverifiedUnits` · `operatorRequired`). `null` 은 「보고 안 됨」이고 판정은 그것을 모름으로 읽는다 |
| §12 ① 「대상과 이어지지 않음」 | 결과 통보의 `executionId` 가 이 시도의 `APPROVED` 답 `executionId` 와 같아야 잇는다. 사람 과업에는 `executionId` 가 없어 결과 통보가 이어지지 않는다(§8.4: 사람의 확인만) |
| §12 ③ 「대상 단위가 `completedUnits` 에 있음」 | `DispatchIntent.targetUnit`. 지금은 모든 종류에서 `null` 이라 ③ 에 닿지 않는다(§19). 규칙은 구현하고, 시험은 대상 단위를 넣은 상태로 확인한다 |
| §7.2 `confirm` 의 셋째 경우 | `ConfirmedOutcome(candidateId, proposalId, done, by)` — B2b-2a 의 늦은 확인 교훈대로 `proposalId` 를 싣는다. 증거 등급 E3 |
| §9.4 `closedAs: UNKNOWN` | UNKNOWN(OUTCOME) 이 `unknownMs` 로 끝나 ESCALATED 로 갈 때 |
| §9.4 「`NO_PROPOSAL` 로 들어간 UNKNOWN(OUTCOME) 은 `unknowns` 에도 남는다」 | 에피소드 `unknowns` 에 `{subject: {candidateId}, what: OUTCOME, source: picasso}`. `Uncertain`(재시도 소진 · `dispatchMs`)도 같다. 사람의 DONE · NOT_DONE 이 지운다 — UNKNOWN(OUTCOME)에서 DIAGNOSING 으로 가는 길은 NOT_DONE 하나라, 이 항목이 다음 진단 요청에 실리는 일은 없다(그 상태에 머무는 동안의 실행 차단과 기록용). 다음 요청으로 넘어가는 것은 ② 의 단위 항목이다 |
| §9.4 「사람의 현물 확인은 E3 로 남기고 `identityAssurance` 를 함께 싣는다」 | 이력(계약 §3.4)의 `evidence` 는 `{grade, outcome}` 두 칸이라 `identityAssurance` 를 담지 않는다. 확인자와 신원 수준은 감사 기록 `OUTCOME_CONFIRMED` 에 남는다 |
| §12 ② 의 단위 항목이 거듭 오면 | 같은 `(subject, what)` 는 한 번만 넣는다 |
| §12 ② 「해당 단위를 `unknowns` 에도 넣는다」 | `{subject: {executionId, unitId}, what: OUTCOME, source: picasso}` — 열거값 확장(`VERIFICATION` 등)은 §19 의 사용자 결정이라 지금은 `OUTCOME` 하나로 적는다 |

---

## 파일 구조

| 파일 | 할 일 | 책임 |
|---|---|---|
| `episode/src/main/kotlin/koshei/episode/EpisodeEvents.kt` | 수정 | 사건 다섯, `Command.Dispatch`, `DispatchResult`, `Evidence` |
| `episode/src/main/kotlin/koshei/episode/EpisodeModel.kt` | 수정 | 열거값 추가, `DispatchIntent.targetUnit` |
| `episode/src/main/kotlin/koshei/episode/EpisodeHistory.kt` | 수정 | `DispatchSummary.delivered` 를 `steps` 로 |
| `episode/src/main/kotlin/koshei/episode/EpisodeState.kt` | 수정 | 시도 · 에피소드 칸 추가, `currentUnknowns` |
| `episode/src/main/kotlin/koshei/episode/Diagnosing.kt` | 수정 | 진단 요청에 에피소드 `unknowns` |
| `episode/src/main/kotlin/koshei/episode/Revalidating.kt` | 수정 | 관문을 `passesDispatchGate` 로 뽑음 |
| `episode/src/main/kotlin/koshei/episode/Transition.kt` | 수정 | 상태 넷 연결, 이력에 실행 · 근거, `ConfirmedOutcome` 의 답 |
| `episode/src/main/kotlin/koshei/episode/Dispatching.kt` | 생성 | picasso 답 판정, DISPATCH_PENDING · DISPATCHED |
| `episode/src/main/kotlin/koshei/episode/Outcome.kt` | 생성 | AWAITING_EVIDENCE · UNKNOWN(OUTCOME), 근거 판정 |
| `episode/src/test/kotlin/koshei/episode/Episodes.kt` | 수정 | 몰개 추가 |
| `episode/src/test/kotlin/koshei/episode/PicassoAnswersTest.kt` | 생성 | Task 2 |
| `episode/src/test/kotlin/koshei/episode/DispatchingTest.kt` | 생성 | Task 3 |
| `episode/src/test/kotlin/koshei/episode/EvidenceTest.kt` | 생성 | Task 4 |
| `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md` | 수정 | Task 5: picasso 답 정정 |

---

## Chunk 1: 타입과 picasso 답

### Task 1: 타입을 넓힌다

행동을 바꾸지 않는 타입 변경이다. 확인은 컴파일과 기존 시험 227개다.

**Files:**
- Modify: `episode/src/main/kotlin/koshei/episode/EpisodeEvents.kt`
- Modify: `episode/src/main/kotlin/koshei/episode/EpisodeModel.kt`
- Modify: `episode/src/main/kotlin/koshei/episode/EpisodeHistory.kt`
- Modify: `episode/src/main/kotlin/koshei/episode/EpisodeState.kt`
- Modify: `episode/src/main/kotlin/koshei/episode/Diagnosing.kt`
- Modify: `episode/src/main/kotlin/koshei/episode/Transition.kt`

- [ ] **Step 1: `EpisodeEvents.kt` — 사건 다섯을 더한다**

```kotlin
    /** `close` Update, accepted only in ESCALATED. */
    data class Closed(val by: Approver, val outcome: String) : EpisodeEvent
}
```

를

```kotlin
    /** `close` Update, accepted only in ESCALATED. */
    data class Closed(val by: Approver, val outcome: String) : EpisodeEvent

    /** The execution-intent record ([Command.RecordIntent]) was stored. */
    data class Recorded(val seq: Long) : EpisodeEvent

    /** The execution-intent record could not be stored, or its activity ran out of time. Nothing was dispatched. */
    data class RecordFailed(val seq: Long, val message: String?) : EpisodeEvent

    /** `dispatch` activity result. [token] is the one [Command.Dispatch] carried, so a late result is recognisably stale. */
    data class DispatchReturned(val result: DispatchResult, val token: Long) : EpisodeEvent

    /** `evidence` signal: a JobResponse the host carried (design §12), deduplicated by its `jobResponseId`. */
    data class EvidenceArrived(val evidence: Evidence) : EpisodeEvent

    /**
     * `confirm` Update on the executed target (AWAITING_EVIDENCE, UNKNOWN(OUTCOME)): the person checked on site whether
     * the action of [proposalId] for [candidateId] was done (design §7.2). Counted as evidence grade E3.
     */
    data class ConfirmedOutcome(val candidateId: String, val proposalId: String, val done: Boolean, val by: Approver) : EpisodeEvent
}
```

로 바꾼다.

- [ ] **Step 2: `EpisodeEvents.kt` — `Dispatch` 명령과 두 타입을 더한다**

```kotlin
    /** The execution-intent record of DISPATCH_PENDING; its outcome comes back as `Recorded(seq)` (plan B2b-2b). */
    data class RecordIntent(val seq: Long, val intent: DispatchIntent, val timeoutMs: Long) : Command
}
```

를

```kotlin
    /** The execution-intent record of DISPATCH_PENDING; its outcome comes back as [EpisodeEvent.Recorded] or [EpisodeEvent.RecordFailed]. */
    data class RecordIntent(val seq: Long, val intent: DispatchIntent, val timeoutMs: Long) : Command

    /**
     * Dispatches the intent (design §8.4): the picasso approval window for APPROVE_REMEDY, sent as [approverKind]
     * (`AGENT` for a POLICY approval, `PERSON` for a person's); an operator task for the person-task kinds
     * ([approverKind] null). The runtime returns [token] in [EpisodeEvent.DispatchReturned]; when its retries run out it
     * returns [DispatchResult.Uncertain] — it never decides to dispatch again.
     */
    data class Dispatch(val intent: DispatchIntent, val approverKind: String?, val timeoutMs: Long, val token: Long) : Command
}

/** What the `dispatch` activity came back with (design §8.4). */
sealed interface DispatchResult {
    /** The picasso approval window's answer as raw JSON text (picasso `ApprovalWire.encode`). */
    data class Answer(val json: String) : DispatchResult

    /** The operator task was issued (CHOOSE_SOURCE, OPERATOR_DECISION). */
    data object PersonTaskIssued : DispatchResult

    /** The activity gave up — retries exhausted, or `dispatchMs` ran out: whether it went out is not known (design §11). */
    data class Uncertain(val message: String?) : DispatchResult
}

/**
 * A picasso result notification (JobResponse) the host carried in (design §12), field by field as delivered. A `null`
 * list or flag means "not reported", which [transition] reads as unknown — never as empty or false.
 */
class Evidence(
    val jobResponseId: String,
    val executionId: String?,
    val physicalState: String?,
    /** The evidence grade picasso reached for the execution (E0..E2). */
    val reachedEvidence: String?,
    completedUnits: List<String>?,
    inDoubtUnits: List<String>?,
    unverifiedUnits: List<String>?,
    val operatorRequired: Boolean?,
    val receivedAt: Instant,
) {
    val completedUnits: List<String>? = completedUnits?.toList()
    val inDoubtUnits: List<String>? = inDoubtUnits?.toList()
    val unverifiedUnits: List<String>? = unverifiedUnits?.toList()

    override fun toString(): String = "Evidence($jobResponseId, $executionId)"
}
```

로 바꾼다.

- [ ] **Step 3: `EpisodeModel.kt` — 열거값과 `DispatchIntent`**

`enum class RediagnoseReason { APPROVAL_REJECTED, APPROVAL_LAPSED, PRECONDITION_BROKEN }` 를
`enum class RediagnoseReason { APPROVAL_REJECTED, APPROVAL_LAPSED, PRECONDITION_BROKEN, NOT_DONE }` 로.

`enum class NoticeKind { APPROVAL_NEEDED, CONFIRM_PRECONDITION, ESCALATED, POLICY_REJECTED }` 를
`enum class NoticeKind { APPROVAL_NEEDED, CONFIRM_PRECONDITION, ESCALATED, POLICY_REJECTED, CONFIRM_OUTCOME }` 로.

```kotlin
    REVALIDATION, RECHECK, CONFIRMATION, POLICY_REJECTED, IGNORED, POLICY_ADOPTED, ATTEMPT_CLOSED,
}
```

를

```kotlin
    REVALIDATION, RECHECK, CONFIRMATION, POLICY_REJECTED, IGNORED, POLICY_ADOPTED, ATTEMPT_CLOSED,
    DISPATCH_SENT, DISPATCH_RESULT, EVIDENCE, OUTCOME_CONFIRMED,
}
```

로.

```kotlin
/** What the DISPATCH_PENDING intent record says will be dispatched (design §5.2, §11). */
@ConsistentCopyVisibility
data class DispatchIntent internal constructor(val candidate: Candidate, val idempotencyKey: String, val approval: Approval?)
```

를

```kotlin
/**
 * What the DISPATCH_PENDING intent record says will be dispatched (design §5.2, §11). [targetUnit] is the unit whose
 * presence in a JobResponse's `completedUnits` counts as DONE (design §12). It is null for every kind today: picasso has
 * not said how a JobResponse names the target's unit (design §19), so no JobResponse can complete an attempt yet.
 */
@ConsistentCopyVisibility
data class DispatchIntent internal constructor(
    val candidate: Candidate,
    val idempotencyKey: String,
    val approval: Approval?,
    val targetUnit: String? = null,
)
```

로.

- [ ] **Step 4: `EpisodeHistory.kt` — `delivered` 는 실제로 나간 `steps`**

`import com.fasterxml.jackson.databind.node.ObjectNode` 위에 `import com.fasterxml.jackson.databind.JsonNode` 를 더한다.

```kotlin
/** Filled by plan B2b-2b. */
data class DispatchSummary(val result: String, val idempotencyKey: String, val approverKind: String?, val delivered: Boolean?)

/** Filled by plan B2b-2b. */
data class EvidenceSummary(val grade: String, val outcome: String)
```

를

```kotlin
/**
 * [result]: `ACCEPTED` · `PERSON_TASK` · a picasso refusal value · `UNCERTAIN` · `ANSWER_UNKNOWN` (design §9.4).
 * [approverKind] is what koshei sent the approval window. [delivered] is what picasso says actually went out — its
 * answer's `steps` — and null when nothing was approved; kept as a private copy, each read a fresh copy.
 */
class DispatchSummary(val result: String, val idempotencyKey: String, val approverKind: String?, delivered: JsonNode?) {
    private val deliveredCopy: JsonNode? = delivered?.deepCopy()
    val delivered: JsonNode? get() = deliveredCopy?.deepCopy()

    override fun equals(other: Any?): Boolean =
        this === other || (other is DispatchSummary && result == other.result && idempotencyKey == other.idempotencyKey &&
            approverKind == other.approverKind && deliveredCopy == other.deliveredCopy)

    override fun hashCode(): Int = listOf(result, idempotencyKey, approverKind, deliveredCopy).hashCode()

    override fun toString(): String = "DispatchSummary($result, $idempotencyKey)"
}

/** [grade] E0..E3 (E3 = a person's on-site check); [outcome] `DONE` · `NOT_DONE` · `UNKNOWN`. */
data class EvidenceSummary(val grade: String, val outcome: String)
```

로, 그리고

```kotlin
            if (dispatch.delivered == null) putNull("delivered") else put("delivered", dispatch.delivered)
```

를

```kotlin
            set<JsonNode>("delivered", dispatch.delivered)
```

로 바꾼다.

- [ ] **Step 5: `EpisodeState.kt` — 시도와 에피소드의 칸**

```kotlin
    val validUntil: Instant? = null,
) {
    /** design §7.2: instance id + attempt number. */
```

를

```kotlin
    val validUntil: Instant? = null,
    /** The intent DISPATCH_PENDING recorded (under [intentSeq]). */
    val intent: DispatchIntent? = null,
    /** picasso's `executionId` from an APPROVED answer: what links a JobResponse to this attempt (design §12). */
    val executionId: String? = null,
    val dispatch: DispatchSummary? = null,
    val evidence: EvidenceSummary? = null,
    /** NO_PROPOSAL, REMEDY_NOT_APPLIED or an uncertain dispatch: a later DONE is "done, by whom unknown" (design §11). */
    val authorUnknown: Boolean = false,
) {
    /** design §7.2: instance id + attempt number. */
```

로.

```kotlin
    val recordSeq: Long,
) {
```

를

```kotlin
    val recordSeq: Long,
    /** The episode's own unknowns (design §9.1, §9.4), e.g. OUTCOME after NO_PROPOSAL; they reach the next diagnosis request. */
    val episodeUnknowns: List<Unknown> = emptyList(),
    /** JobResponses that arrived in DISPATCHED, before the answer; applied on entering AWAITING_EVIDENCE / UNKNOWN(OUTCOME). */
    val bufferedEvidence: List<Evidence> = emptyList(),
    val seenJobResponseIds: Set<String> = emptySet(),
) {
```

로.

```kotlin
    /** Unknowns right now (design §9.1), from every symptom so far. Plan B2b-2b adds episode-level ones and confirmations. */
    internal fun currentUnknowns(): List<Unknown> = deriveUnknowns(snapshot())
```

를

```kotlin
    /** Unknowns right now (design §9.1): from every symptom so far, plus the episode's own. A person's confirmations: plan B2b-2c. */
    internal fun currentUnknowns(): List<Unknown> = deriveUnknowns(snapshot()) + episodeUnknowns
```

로.

- [ ] **Step 6: `Diagnosing.kt` — 진단 요청에 에피소드 `unknowns`**

```kotlin
        diagnosisRequest(state.instanceId, number, snapshot, history = state.history.map { it.toJson() })
```

를

```kotlin
        diagnosisRequest(state.instanceId, number, snapshot, episodeUnknowns = state.episodeUnknowns, history = state.history.map { it.toJson() })
```

로.

- [ ] **Step 7: `Transition.kt` — 이력에 실행 · 근거, `ConfirmedOutcome` 의 답, `closedAs`**

```kotlin
internal fun Out.escalate(now: Instant, escalation: Escalation, approval: ApprovalSummary? = null, cancelDiagnosis: Boolean = true) {
    if (cancelDiagnosis && state.phase == Phase.DIAGNOSING) commands += Command.CancelDiagnosis
    closeAttempt(ClosedAs.ESCALATED, now, approval)
```

를

```kotlin
internal fun Out.escalate(
    now: Instant,
    escalation: Escalation,
    approval: ApprovalSummary? = null,
    cancelDiagnosis: Boolean = true,
    closedAs: ClosedAs = ClosedAs.ESCALATED,
) {
    if (cancelDiagnosis && state.phase == Phase.DIAGNOSING) commands += Command.CancelDiagnosis
    closeAttempt(closedAs, now, approval)
```

로.

```kotlin
    val entry = HistoryEntry(a.number, a.request.candidatesVersion, a.diagnosisSummary(), approvalSummary, null, null, closedAs, now)
```

를

```kotlin
    val entry = HistoryEntry(a.number, a.request.candidatesVersion, a.diagnosisSummary(), approvalSummary, a.dispatch, a.evidence, closedAs, now)
```

로.

```kotlin
    is EpisodeEvent.Closed -> by
    else -> null
}
```

을

```kotlin
    is EpisodeEvent.Closed -> by
    is EpisodeEvent.ConfirmedOutcome -> by
    else -> null
}
```

로.

```kotlin
    this is EpisodeEvent.ApprovalDecided || this is EpisodeEvent.ConfirmedPrecondition ||
        this is EpisodeEvent.TakenOver || this is EpisodeEvent.Closed
```

를

```kotlin
    this is EpisodeEvent.ApprovalDecided || this is EpisodeEvent.ConfirmedPrecondition ||
        this is EpisodeEvent.TakenOver || this is EpisodeEvent.Closed || this is EpisodeEvent.ConfirmedOutcome
```

로.

```kotlin
    is EpisodeEvent.ConfirmedPrecondition -> Reply.REFUSED_SUBJECT_NOT_UNKNOWN
```

를

```kotlin
    is EpisodeEvent.ConfirmedPrecondition, is EpisodeEvent.ConfirmedOutcome -> Reply.REFUSED_SUBJECT_NOT_UNKNOWN
```

로.

- [ ] **Step 8: 확인한다**

Run: `./gradlew --no-daemon :episode:test --rerun`
Expected: `BUILD SUCCESSFUL`, 227 초록. `HistoryEntry` 의 `delivered` 를 단언하는 기존 시험은 없다(B2b-2a 에서는 늘 `null`).

- [ ] **Step 9: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode
git commit -m "feat(episode): dispatch and evidence types; history carries what went out" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: picasso 답 판정

**Files:**
- Create: `episode/src/main/kotlin/koshei/episode/Dispatching.kt` (판정 부분만, 상태 처리기는 Task 3)
- Modify: `episode/src/test/kotlin/koshei/episode/Episodes.kt`
- Test: `episode/src/test/kotlin/koshei/episode/PicassoAnswersTest.kt`

- [ ] **Step 1: 시험 몰개를 더한다**

`episode/src/test/kotlin/koshei/episode/Episodes.kt` 끝에 붙인다:

```kotlin

/** picasso approval-window answers, shaped as picasso `ApprovalWire.encode` writes them (picasso main 584c6cd). */
internal object PicassoAnswers {
    const val EXECUTION = "exec-42"

    fun approved(executionId: String = EXECUTION): String =
        """{"schemaVersion":"2","contractSemver":"1.0.0","outcome":"APPROVED","executionId":"$executionId","steps":[{"skillType":"pick_place","parameters":{"slot":"B-03"}}]}"""

    fun refused(refusal: String, schema: String = "2"): String =
        """{"schemaVersion":"$schema","contractSemver":"1.0.0","outcome":"REFUSED","refusal":"$refusal","reason":"r"}"""
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
): Evidence = Evidence(id, executionId, "DONE", "E2", completed, inDoubt, unverified, operatorRequired, at)

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
```

- [ ] **Step 2: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/PicassoAnswersTest.kt`:

```kotlin
package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PicassoAnswersTest {
    @Test fun `an APPROVED answer carries the execution and what went out`() {
        val j = judgePicassoAnswer(PicassoAnswers.approved())
        assertIs<DispatchJudgement.Accepted>(j)
        assertEquals("exec-42", j.executionId)
        assertEquals("pick_place", j.delivered[0].get("skillType").textValue())
    }

    @Test fun `every refusal of schema 2 is understood - two may have landed, the rest are refusals`() {
        assertEquals(15, PICASSO_REFUSALS_V2.size)
        for (r in PICASSO_REFUSALS_V2) {
            val j = judgePicassoAnswer(PicassoAnswers.refused(r))
            if (r == "NO_PROPOSAL" || r == "REMEDY_NOT_APPLIED") assertEquals(DispatchJudgement.MayHaveLanded(r), j, r)
            else assertEquals(DispatchJudgement.Refused(r), j, r)
        }
    }

    @Test fun `anything not understood is an unknown answer`() {
        listOf(
            PicassoAnswers.refused("SOMETHING_NEW"),
            PicassoAnswers.refused("REFUSED_BY_GATE", schema = "3"),
            """{"outcome":"APPROVED","executionId":"e","steps":[]}""",
            """{"schemaVersion":"2","outcome":"APPROVED","steps":[]}""",
            """{"schemaVersion":"2","outcome":"APPROVED","executionId":"e"}""",
            """{"schemaVersion":"2","outcome":"MAYBE"}""",
            """{"schemaVersion":"2","outcome":"REFUSED","refusal":"NO_PROPOSAL","refusal":"REFUSED_BY_GATE"}""",
            "{",
            "[]",
        ).forEach { assertIs<DispatchJudgement.AnswerUnknown>(judgePicassoAnswer(it), it) }
    }

    @Test fun `an answer of the wrong kind for the candidate is unknown`() {
        assertIs<DispatchJudgement.AnswerUnknown>(judgeDispatch(CandidateKind.OPERATOR_DECISION, DispatchResult.Answer(PicassoAnswers.approved())))
        assertIs<DispatchJudgement.AnswerUnknown>(judgeDispatch(CandidateKind.APPROVE_REMEDY, DispatchResult.PersonTaskIssued))
        assertEquals(DispatchJudgement.PersonTask, judgeDispatch(CandidateKind.CHOOSE_SOURCE, DispatchResult.PersonTaskIssued))
        assertEquals(DispatchJudgement.Uncertain("timeout"), judgeDispatch(CandidateKind.APPROVE_REMEDY, DispatchResult.Uncertain("timeout")))
    }
}
```

- [ ] **Step 3: 실패를 본다**

Run: `./gradlew --no-daemon :episode:test --tests 'koshei.episode.PicassoAnswersTest'`
Expected: 컴파일 실패 — `Unresolved reference 'judgePicassoAnswer'` (그리고 `DispatchJudgement`, `PICASSO_REFUSALS_V2`, `judgeDispatch`). 몰개의 `pending` · `recorded` 등은 이미 컴파일된다.

- [ ] **Step 4: `Dispatching.kt` 의 판정 부분을 쓴다**

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/** The approval window's wire schema this module reads (picasso `ApprovalWire.SCHEMA_VERSION`). */
const val PICASSO_APPROVAL_SCHEMA = "2"

/**
 * Every refusal value of the approval window's schema "2" (picasso `ApprovalRefusal`, `docs/orchestration.md` §7.3, read
 * at picasso main 584c6cd). A value outside this set is not understood and goes to a person (design §8.4).
 */
val PICASSO_REFUSALS_V2: Set<String> = setOf(
    "WITHHELD", "NO_PROPOSAL", "PROPOSAL_CHANGED", "NOT_DECLARED", "REVOKED", "EXPIRED", "ROBOT_OUT_OF_SCOPE",
    "SKILL_OUT_OF_SCOPE", "VALUE_NOT_DECLARED", "OBJECT_NOT_OBSERVED", "DECLARED_CONTRADICTS_OBSERVED",
    "CAPABILITY_UNKNOWN", "VALUES_NOT_ACCEPTED", "REFUSED_BY_GATE", "REMEDY_NOT_APPLIED",
)

/** Refusals that fold "my earlier attempt may already have landed" (design §11): UNKNOWN(OUTCOME), never a retry. */
private val MAY_HAVE_LANDED = setOf("NO_PROPOSAL", "REMEDY_NOT_APPLIED")

/** What a dispatch result means for the episode (design §5.2 DISPATCHED rows). */
internal sealed interface DispatchJudgement {
    /** picasso approved; [delivered] is its `steps`, what actually went out (picasso §7.3). */
    class Accepted(val executionId: String, delivered: JsonNode) : DispatchJudgement {
        val delivered: JsonNode = delivered.deepCopy()
    }

    data object PersonTask : DispatchJudgement

    data class MayHaveLanded(val refusal: String) : DispatchJudgement

    data class Uncertain(val detail: String?) : DispatchJudgement

    data class Refused(val refusal: String) : DispatchJudgement

    data class AnswerUnknown(val detail: String) : DispatchJudgement
}

/** A picasso answer only for APPROVE_REMEDY, an issued task only for the person-task kinds; anything else is not understood. */
internal fun judgeDispatch(kind: CandidateKind, result: DispatchResult): DispatchJudgement = when (result) {
    is DispatchResult.Uncertain -> DispatchJudgement.Uncertain(result.message)
    DispatchResult.PersonTaskIssued ->
        if (kind == CandidateKind.CHOOSE_SOURCE || kind == CandidateKind.OPERATOR_DECISION) DispatchJudgement.PersonTask
        else DispatchJudgement.AnswerUnknown("a person-task answer for $kind")
    is DispatchResult.Answer ->
        if (kind == CandidateKind.APPROVE_REMEDY) judgePicassoAnswer(result.json)
        else DispatchJudgement.AnswerUnknown("a picasso answer for $kind")
}

/**
 * Reads the approval window's answer (design §8.4): `schemaVersion` first, then `outcome`; branches on the `refusal`
 * value only, never on the prose `reason`. Read with the module's strict mapper, so a duplicated key is not understood.
 */
internal fun judgePicassoAnswer(json: String): DispatchJudgement {
    val node = try {
        mapper.readTree(json)
    } catch (e: Exception) {
        return DispatchJudgement.AnswerUnknown("unreadable: ${e.message}")
    }
    if (node == null || !node.isObject) return DispatchJudgement.AnswerUnknown("not a JSON object")
    val schema = node.text("schemaVersion")
    if (schema != PICASSO_APPROVAL_SCHEMA) return DispatchJudgement.AnswerUnknown("schemaVersion $schema")
    return when (val outcome = node.text("outcome")) {
        "APPROVED" -> {
            val executionId = node.text("executionId")
            val steps = node.get("steps")
            if (executionId.isNullOrBlank() || steps == null || !steps.isArray) DispatchJudgement.AnswerUnknown("APPROVED without executionId or steps")
            else DispatchJudgement.Accepted(executionId, steps)
        }
        "REFUSED" -> {
            val refusal = node.text("refusal")
            when {
                refusal == null || refusal !in PICASSO_REFUSALS_V2 -> DispatchJudgement.AnswerUnknown("refusal $refusal")
                refusal in MAY_HAVE_LANDED -> DispatchJudgement.MayHaveLanded(refusal)
                else -> DispatchJudgement.Refused(refusal)
            }
        }
        else -> DispatchJudgement.AnswerUnknown("outcome $outcome")
    }
}
```

- [ ] **Step 5: 통과를 본다**

Run: `./gradlew --no-daemon :episode:test`
Expected: `BUILD SUCCESSFUL`, 227 + 4 = 231.

- [ ] **Step 6: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/Dispatching.kt episode/src/test/kotlin/koshei/episode/Episodes.kt episode/src/test/kotlin/koshei/episode/PicassoAnswersTest.kt
git commit -m "feat(episode): judge picasso approval-window answers (schema 2, fifteen refusals)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Chunk 2: 상태 처리기

### Task 3: DISPATCH_PENDING 과 DISPATCHED

**Files:**
- Modify: `episode/src/main/kotlin/koshei/episode/Revalidating.kt` (관문을 뽑음)
- Modify: `episode/src/main/kotlin/koshei/episode/Dispatching.kt` (처리기 추가)
- Create: `episode/src/main/kotlin/koshei/episode/Outcome.kt` (Task 3 에 필요한 진입 함수와 근거 기록 — 판정은 Task 4)
- Modify: `episode/src/main/kotlin/koshei/episode/Transition.kt` (상태 넷 연결)
- Test: `episode/src/test/kotlin/koshei/episode/DispatchingTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/DispatchingTest.kt`:

```kotlin
package koshei.episode

import koshei.episode.Episodes.INSTANCE
import koshei.episode.Episodes.OPERATOR
import koshei.episode.Episodes.REMEDY
import koshei.episode.Episodes.incident
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DispatchingTest {
    @Test fun `the intent's Recorded dispatches once, counted`() {
        val s = pending().recorded()
        assertEquals(Phase.DISPATCHED, s.state.phase)
        val d = s.commands.filterIsInstance<Command.Dispatch>().single()
        assertEquals(REMEDY, d.intent.candidate.candidateId)
        assertEquals("AGENT", d.approverKind)
        assertEquals(30_000, d.timeoutMs)
        assertEquals(s.state.token, d.token)
        assertEquals(mapOf(REMEDY to 1), s.state.dispatchCounts)
        assertEquals(1, s.records(RecordKind.DISPATCH_SENT).size)
    }

    @Test fun `another seq or a failed record never dispatches`() {
        val p = pending()
        val seq = p.state.attempt!!.intentSeq!!
        val other = p.on(EpisodeEvent.Recorded(seq + 100), p.state.enteredAt.plusSeconds(1))
        assertEquals(EscalationReason.RECORD_FAILED, other.reason)
        assertTrue(other.commands.none { it is Command.Dispatch })
        val failed = p.on(EpisodeEvent.RecordFailed(seq, "db down"), p.state.enteredAt.plusSeconds(1))
        assertEquals(Escalation(EscalationReason.RECORD_FAILED, "db down"), failed.state.escalation)
        assertTrue(failed.commands.none { it is Command.Dispatch })
    }

    @Test fun `the gate runs again at Recorded`() {
        // an unknown joined while the intent was being recorded
        val joined = pending().let { it.on(EpisodeEvent.SymptomJoined(incident("incident-6")), it.state.enteredAt.plusMillis(500)) }
        val blocked = joined.recorded()
        assertEquals(EscalationReason.UNKNOWN_BLOCKS_EXECUTION, blocked.reason)
        assertTrue(blocked.commands.none { it is Command.Dispatch })
        // the table stopped auto-approving
        val p = pending()
        val revoked = p.on(EpisodeEvent.Recorded(p.state.attempt!!.intentSeq!!), p.state.enteredAt.plusSeconds(1), read = Episodes.policyV1())
        assertEquals(Phase.AWAITING_APPROVAL, revoked.state.phase)
        assertTrue(revoked.commands.none { it is Command.Dispatch })
        // the approval lapsed
        val lapsed = p.recorded(now = p.state.attempt!!.approval!!.at.plusMillis(600_000))
        assertEquals(Phase.DIAGNOSING, lapsed.state.phase)
        assertTrue(lapsed.commands.none { it is Command.Dispatch })
    }

    @Test fun `a JobResponse while the intent is being recorded is only recorded`() {
        val p = pending()
        val s = p.on(EpisodeEvent.EvidenceArrived(jobResponse("jr-early", inDoubt = listOf("u-9"))), p.state.enteredAt.plusMillis(500))
        assertEquals(Phase.DISPATCH_PENDING, s.state.phase)
        assertTrue(s.state.bufferedEvidence.isEmpty())
        assertFalse(s.records(RecordKind.EVIDENCE).single().entry.payload.get("counted").booleanValue())
        val accepted = s.recorded().returned(DispatchResult.Answer(PicassoAnswers.approved()))
        assertEquals(Phase.AWAITING_EVIDENCE, accepted.state.phase)   // it was not kept for later
        assertTrue("jr-early" in accepted.state.seenJobResponseIds)     // a redelivered copy is a duplicate (EvidenceTest)
    }

    @Test fun `an approved answer waits for evidence and keeps what went out`() {
        val s = dispatched().returned(DispatchResult.Answer(PicassoAnswers.approved()))
        assertEquals(Phase.AWAITING_EVIDENCE, s.state.phase)
        val a = s.state.attempt!!
        assertEquals("exec-42", a.executionId)
        assertEquals("ACCEPTED", a.dispatch!!.result)
        assertEquals("AGENT", a.dispatch!!.approverKind)
        assertEquals("pick_place", a.dispatch!!.delivered!![0].get("skillType").textValue())
        assertEquals(s.state.enteredAt.plusMillis(600_000), s.timers(Timer.STATE).single().at)
    }

    @Test fun `a person task is issued without an approver kind`() {
        val d = dispatched(incident("incident-6"))
        assertNull(d.commands.filterIsInstance<Command.Dispatch>().single().approverKind)
        val s = d.returned(DispatchResult.PersonTaskIssued)
        assertEquals(Phase.AWAITING_EVIDENCE, s.state.phase)
        assertEquals("PERSON_TASK", s.state.attempt!!.dispatch!!.result)
    }

    @Test fun `NO_PROPOSAL, REMEDY_NOT_APPLIED and an uncertain dispatch leave the outcome unknown, never retried`() {
        val results = listOf(
            DispatchResult.Answer(PicassoAnswers.refused("NO_PROPOSAL")),
            DispatchResult.Answer(PicassoAnswers.refused("REMEDY_NOT_APPLIED")),
            DispatchResult.Uncertain("retries exhausted"),
        )
        for (result in results) {
            val s = dispatched().returned(result)
            assertEquals(Phase.UNKNOWN_OUTCOME, s.state.phase, "$result")
            assertTrue(s.commands.none { it is Command.Dispatch || it is Command.RecordIntent }, "$result")
            assertTrue(s.state.attempt!!.authorUnknown, "$result")
            assertEquals(listOf(UnknownWhat.OUTCOME), s.state.episodeUnknowns.map { it.what }, "$result")
            assertEquals(NoticeKind.CONFIRM_OUTCOME, s.notices().single().kind, "$result")
            assertEquals(s.state.enteredAt.plusMillis(900_000), s.timers(Timer.STATE).single().at, "$result")
        }
    }

    @Test fun `a refusal goes to a person with its value, also in the history`() {
        for (refusal in listOf("REFUSED_BY_GATE", "NOT_DECLARED", "PROPOSAL_CHANGED", "WITHHELD")) {
            val s = dispatched().returned(DispatchResult.Answer(PicassoAnswers.refused(refusal)))
            assertEquals(Escalation(EscalationReason.DISPATCH_REFUSED, refusal), s.state.escalation)
            assertEquals(refusal, s.state.history.single().toJson().get("dispatch").get("result").textValue())
        }
    }

    @Test fun `an answer koshei does not understand goes to a person`() {
        val s = dispatched().returned(DispatchResult.Answer(PicassoAnswers.refused("REFUSED_BY_GATE", schema = "3")))
        assertEquals(EscalationReason.DISPATCH_ANSWER_UNKNOWN, s.reason)
        assertEquals("ANSWER_UNKNOWN", s.state.history.single().toJson().get("dispatch").get("result").textValue())
    }

    @Test fun `a dispatch result from an earlier phase is ignored`() {
        val d = dispatched()
        val s = d.on(EpisodeEvent.DispatchReturned(DispatchResult.Answer(PicassoAnswers.approved()), d.state.token - 1), d.state.enteredAt.plusSeconds(1))
        assertEquals(Phase.DISPATCHED, s.state.phase)
        assertEquals("stale dispatch result", s.records(RecordKind.IGNORED).single().entry.payload.get("why").textValue())
    }

    @Test fun `after escalation the answer and JobResponses are still recorded in full`() {
        val taken = dispatched().on(EpisodeEvent.TakenOver(OPERATOR), Episodes.T0.plusSeconds(80))
        assertEquals(Phase.ESCALATED, taken.state.phase)
        val answer = taken.returned(DispatchResult.Answer(PicassoAnswers.approved()))
        assertTrue(answer.records(RecordKind.IGNORED).single().entry.payload.get("answer").textValue().contains("exec-42"))
        val report = answer.on(EpisodeEvent.EvidenceArrived(jobResponse("jr-late")), Episodes.T0.plusSeconds(90))
        assertEquals("jr-late", report.records(RecordKind.IGNORED).single().entry.payload.get("evidence").get("jobResponseId").textValue())
    }

    @Test fun `a person's approval is sent as PERSON`() {
        val waiting = Episodes.open(read = Episodes.policyV1()).expireState().answer()
        val approved = waiting.on(
            EpisodeEvent.ApprovalDecided("$INSTANCE#1", waiting.request.candidatesVersion, true, OPERATOR, null, null),
            waiting.state.enteredAt.plusSeconds(10),
        )
        val s = approved.revalidated(TriState.TRUE, approved.state.enteredAt.plusSeconds(1)).recorded()
        assertEquals("PERSON", s.commands.filterIsInstance<Command.Dispatch>().single().approverKind)
    }
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew --no-daemon :episode:test --tests 'koshei.episode.DispatchingTest'`
Expected: 12개 모두 FAIL — DISPATCH_PENDING 이 아직 모든 사건을 무시한다(`not handled in DISPATCH_PENDING yet`). 마지막에서 둘째 시험도 DISPATCHED 에 닿지 못해 FAIL 이다.

- [ ] **Step 3: `Revalidating.kt` — 관문을 뽑는다**

이 블록 전체(KDoc 부터 함수 끝까지)를

```kotlin
/**
 * The last gate before DISPATCH_PENDING. The approval (or, for a person task, the proposal) must still be valid
 * (design §5.1); a POLICY approval must still be one the table in force would give — the table may have turned
 * auto-approval off or been rejected meanwhile (R6, §8.2), and then a person approves instead; anything unknown right
 * now — including symptoms that joined after the diagnosis — blocks an execution-class candidate (§6, the second line
 * of defence after the projection). Then the execution intent is recorded; `Dispatch` follows only its `Recorded`
 * (plan B2b-2b, which repeats these checks there).
 */
private fun Out.proceedToDispatch(policy: PolicyTable, now: Instant) {
    val attempt = state.attempt
    val candidate = attempt?.candidate
    val basis = attempt?.approval?.at ?: attempt?.proposedAt
    if (attempt == null || candidate == null || basis == null)
        return escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, "internal: no proposed candidate"))
    // Valid only if both the window fixed when the approval was given and the window of the table in force now allow it.
    val fixedUntil = attempt.validUntil
    if (!approvalStillValid(basis, now, policy) || (fixedUntil != null && !now.isBefore(fixedUntil))) {
        val until = listOfNotNull(basis.plusMillis(policy.deadlines.approvalValidityMs), fixedUntil).min()
        return rediagnose(RediagnoseReason.APPROVAL_LAPSED, policy, now, detail = "valid until $until")
    }
    val clean = (attempt.verdict as? DiagnosisVerdict.Proposed)?.clean ?: false
    if (attempt.approval?.by == ApprovalBy.POLICY && !autoApproves(candidate, clean, policy)) {
        record(RecordKind.APPROVAL) {
            put("candidateId", candidate.candidateId)
            put("result", ApprovalRecordResult.REVOKED.name)
            put("by", ApprovalBy.POLICY.name)
            put("policyVersion", policy.version)
        }
        state = state.copy(attempt = attempt.copy(approval = null, validUntil = null))
        return awaitApproval(candidate, policy, now)
    }
    // Fail-safe: if the unknowns cannot even be computed, treat something as unknown.
    val somethingUnknown = try { state.currentUnknowns().isNotEmpty() } catch (e: Exception) { true }
    if (candidate.executionClass && somethingUnknown)
        return escalate(now, Escalation(EscalationReason.UNKNOWN_BLOCKS_EXECUTION, "unknown after the diagnosis: ${candidate.candidateId}"))
    enter(Phase.DISPATCH_PENDING, now)
    val seq = nextSeq()
    val intent = DispatchIntent(candidate, "${state.instanceId}:${attempt.number}:${candidate.kind.name}", attempt.approval)
    state = state.copy(attempt = attempt.copy(intentSeq = seq))
    commands += Command.RecordIntent(seq, intent, policy.deadlines.recordMs)
}
```

이것으로 바꾼다:

```kotlin
/**
 * The gate before the execution intent, and again before `Dispatch` (design §5.1, §6). The approval (or, for a person
 * task, the proposal) must still be valid — both the window fixed when it was given and the window of the table in
 * force; a POLICY approval must still be one the table in force would give — the table may have turned auto-approval
 * off or been rejected meanwhile (R6, §8.2), and then a person approves instead; anything unknown right now — symptoms
 * that joined after the diagnosis, the episode's own unknowns — blocks an execution-class candidate (§6, the second line
 * of defence after the projection). Returns true when the attempt may go on; otherwise the episode has already moved
 * (a new diagnosis, back to a person, or ESCALATED).
 */
internal fun Out.passesDispatchGate(policy: PolicyTable, now: Instant): Boolean {
    val attempt = state.attempt
    val candidate = attempt?.candidate
    val basis = attempt?.approval?.at ?: attempt?.proposedAt
    if (attempt == null || candidate == null || basis == null) {
        escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, "internal: no proposed candidate"))
        return false
    }
    // Valid only if both the window fixed when the approval was given and the window of the table in force now allow it.
    val fixedUntil = attempt.validUntil
    if (!approvalStillValid(basis, now, policy) || (fixedUntil != null && !now.isBefore(fixedUntil))) {
        val until = listOfNotNull(basis.plusMillis(policy.deadlines.approvalValidityMs), fixedUntil).min()
        rediagnose(RediagnoseReason.APPROVAL_LAPSED, policy, now, detail = "valid until $until")
        return false
    }
    val clean = (attempt.verdict as? DiagnosisVerdict.Proposed)?.clean ?: false
    if (attempt.approval?.by == ApprovalBy.POLICY && !autoApproves(candidate, clean, policy)) {
        record(RecordKind.APPROVAL) {
            put("candidateId", candidate.candidateId)
            put("result", ApprovalRecordResult.REVOKED.name)
            put("by", ApprovalBy.POLICY.name)
            put("policyVersion", policy.version)
        }
        state = state.copy(attempt = attempt.copy(approval = null, validUntil = null))
        awaitApproval(candidate, policy, now)
        return false
    }
    // Fail-safe: if the unknowns cannot even be computed, treat something as unknown.
    val somethingUnknown = try { state.currentUnknowns().isNotEmpty() } catch (e: Exception) { true }
    if (candidate.executionClass && somethingUnknown) {
        escalate(now, Escalation(EscalationReason.UNKNOWN_BLOCKS_EXECUTION, "unknown after the diagnosis: ${candidate.candidateId}"))
        return false
    }
    return true
}

/** REVALIDATING / UNKNOWN(PRECONDITION) → DISPATCH_PENDING: records the execution intent; `Dispatch` follows only its `Recorded`. */
private fun Out.proceedToDispatch(policy: PolicyTable, now: Instant) {
    if (!passesDispatchGate(policy, now)) return
    val attempt = state.attempt ?: return
    val candidate = attempt.candidate ?: return
    enter(Phase.DISPATCH_PENDING, now)
    val seq = nextSeq()
    val intent = DispatchIntent(candidate, "${state.instanceId}:${attempt.number}:${candidate.kind.name}", attempt.approval)
    state = state.copy(attempt = attempt.copy(intentSeq = seq, intent = intent))
    commands += Command.RecordIntent(seq, intent, policy.deadlines.recordMs)
}
```

- [ ] **Step 4: `Dispatching.kt` 끝에 상태 처리기를 붙인다**

```kotlin

/** What koshei tells the approval window (picasso §7.1): AGENT for a POLICY approval, PERSON for a person's; none for a task. */
internal fun approverKindOf(intent: DispatchIntent): String? = when {
    intent.candidate.kind != CandidateKind.APPROVE_REMEDY -> null
    intent.approval?.by == ApprovalBy.POLICY -> "AGENT"
    else -> "PERSON"
}

/**
 * DISPATCH_PENDING (design §5.2): only this attempt's intent `Recorded` may dispatch; any other record result escalates
 * without dispatching. A JobResponse handled here came before this attempt's `Recorded` and never counts (§12).
 */
internal fun Out.onDispatchPending(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    val expected = state.attempt?.intentSeq
        ?: return escalate(now, Escalation(EscalationReason.RECORD_FAILED, "internal: no intent in DISPATCH_PENDING"))
    when (event) {
        is EpisodeEvent.Recorded ->
            if (event.seq == expected) dispatch(policy, now)
            else escalate(now, Escalation(EscalationReason.RECORD_FAILED, "Recorded(${event.seq}), expected $expected"))
        is EpisodeEvent.RecordFailed -> escalate(
            now,
            Escalation(EscalationReason.RECORD_FAILED, if (event.seq == expected) event.message else "RecordFailed(${event.seq}), expected $expected"),
        )
        is EpisodeEvent.EvidenceArrived -> {
            val e = event.evidence
            if (e.jobResponseId in state.seenJobResponseIds) recordEvidence(e, counted = false, why = "duplicate jobResponseId")
            else {
                // Seen now, so a redelivered copy after the answer cannot count either.
                state = state.copy(seenJobResponseIds = state.seenJobResponseIds + e.jobResponseId)
                recordEvidence(e, counted = false, why = "before this attempt's Recorded")
            }
        }
        else -> ignore(event, "dispatch pending")
    }
}

/** `Recorded` → `Dispatch`, after the same gate as before the intent (design §6); counts the dispatch (REPEATED_REMEDY). */
private fun Out.dispatch(policy: PolicyTable, now: Instant) {
    if (!passesDispatchGate(policy, now)) return
    val intent = state.attempt?.intent
        ?: return escalate(now, Escalation(EscalationReason.RECORD_FAILED, "internal: no intent to dispatch"))
    val id = intent.candidate.candidateId
    val count = (state.dispatchCounts[id] ?: 0) + 1
    state = state.copy(dispatchCounts = state.dispatchCounts + (id to count))
    enter(Phase.DISPATCHED, now)
    val approverKind = approverKindOf(intent)
    record(RecordKind.DISPATCH_SENT) {
        put("candidateId", id)
        put("idempotencyKey", intent.idempotencyKey)
        put("approverKind", approverKind)
        put("count", count)
    }
    commands += Command.Dispatch(intent, approverKind, policy.deadlines.dispatchMs, state.token)
}

/** DISPATCHED (design §5.2): the answer decides; a JobResponse that comes first is kept for the next phase. */
internal fun Out.onDispatched(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    val attempt = state.attempt
    val intent = attempt?.intent
        ?: return escalate(now, Escalation(EscalationReason.DISPATCH_ANSWER_UNKNOWN, "internal: no intent in DISPATCHED"))
    when {
        event is EpisodeEvent.DispatchReturned && event.token != state.token -> ignore(event, "stale dispatch result")
        event is EpisodeEvent.DispatchReturned -> onDispatchResult(attempt, intent, event.result, policy, now)
        event is EpisodeEvent.EvidenceArrived -> bufferEvidence(event.evidence)
        else -> ignore(event, "dispatched")
    }
}

private fun Out.onDispatchResult(attempt: Attempt, intent: DispatchIntent, result: DispatchResult, policy: PolicyTable, now: Instant) {
    val judgement = judgeDispatch(intent.candidate.kind, result)
    record(RecordKind.DISPATCH_RESULT) {
        put("judgement", judgement::class.simpleName)
        if (result is DispatchResult.Answer) put("answer", result.json)
        if (result is DispatchResult.Uncertain) put("detail", result.message)
    }
    fun summary(value: String, delivered: JsonNode? = null) = DispatchSummary(value, intent.idempotencyKey, approverKindOf(intent), delivered)
    when (judgement) {
        is DispatchJudgement.Accepted -> {
            state = state.copy(attempt = attempt.copy(executionId = judgement.executionId, dispatch = summary("ACCEPTED", judgement.delivered)))
            enterAwaitingEvidence(policy, now)
        }
        DispatchJudgement.PersonTask -> {
            state = state.copy(attempt = attempt.copy(dispatch = summary("PERSON_TASK")))
            enterAwaitingEvidence(policy, now)
        }
        is DispatchJudgement.MayHaveLanded -> {
            state = state.copy(attempt = attempt.copy(dispatch = summary(judgement.refusal), authorUnknown = true))
            enterUnknownOutcome(policy, now, outcomeUnknown = true, detail = judgement.refusal)
        }
        is DispatchJudgement.Uncertain -> {
            state = state.copy(attempt = attempt.copy(dispatch = summary("UNCERTAIN"), authorUnknown = true))
            enterUnknownOutcome(policy, now, outcomeUnknown = true, detail = judgement.detail)
        }
        is DispatchJudgement.Refused -> {
            state = state.copy(attempt = attempt.copy(dispatch = summary(judgement.refusal)))
            escalate(now, Escalation(EscalationReason.DISPATCH_REFUSED, judgement.refusal))
        }
        is DispatchJudgement.AnswerUnknown -> {
            state = state.copy(attempt = attempt.copy(dispatch = summary("ANSWER_UNKNOWN")))
            escalate(now, Escalation(EscalationReason.DISPATCH_ANSWER_UNKNOWN, judgement.detail))
        }
    }
}
```

- [ ] **Step 5: `Outcome.kt` 를 쓴다 (Task 3 이 부르는 부분)**

`episode/src/main/kotlin/koshei/episode/Outcome.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/** AWAITING_EVIDENCE: a JobResponse or a person's check decides; `evidenceMs` ends it (design §5.1, §5.2). */
internal fun Out.enterAwaitingEvidence(policy: PolicyTable, now: Instant) {
    enter(Phase.AWAITING_EVIDENCE, now)
    stateTimer(now, policy.deadlines.evidenceMs)
    replayBuffered(policy, now)
}

/**
 * UNKNOWN(OUTCOME) (design §5.1): execution stays blocked; a JobResponse, a person's check or `unknownMs` ends it — no
 * periodic recheck, picasso has no surface to ask (§11). [outcomeUnknown] adds the episode-level OUTCOME unknown for the
 * candidate (§9.4), which blocks execution candidates in the next diagnosis until a person knows the outcome.
 */
internal fun Out.enterUnknownOutcome(policy: PolicyTable, now: Instant, outcomeUnknown: Boolean, detail: String?) {
    val candidate = state.attempt?.candidate
    if (outcomeUnknown && candidate != null) state = state.copy(
        episodeUnknowns = state.episodeUnknowns +
            Unknown(mapOf("candidateId" to candidate.candidateId), UnknownWhat.OUTCOME, now.toString(), "picasso"),
    )
    enter(Phase.UNKNOWN_OUTCOME, now, detail = detail)
    stateTimer(now, policy.deadlines.unknownMs)
    notify(NoticeKind.CONFIRM_OUTCOME, detail = candidate?.candidateId)
    replayBuffered(policy, now)
}

/** DISPATCHED keeps JobResponses that come before the answer (they leave the same pump as the acceptance, design §5.2). */
internal fun Out.bufferEvidence(e: Evidence) {
    if (e.jobResponseId in state.seenJobResponseIds) return recordEvidence(e, counted = false, why = "duplicate jobResponseId")
    state = state.copy(bufferedEvidence = state.bufferedEvidence + e, seenJobResponseIds = state.seenJobResponseIds + e.jobResponseId)
    recordEvidence(e, counted = false, why = "kept until the answer")
}

/** Re-applies what DISPATCHED kept — an internal step, not a new signal, so the jobResponseId dedup does not apply (§6). */
private fun Out.replayBuffered(policy: PolicyTable, now: Instant) {
    val buffered = state.bufferedEvidence
    if (buffered.isEmpty()) return
    state = state.copy(bufferedEvidence = emptyList())
    for (e in buffered) {
        if (state.phase == Phase.AWAITING_EVIDENCE || state.phase == Phase.UNKNOWN_OUTCOME) takeEvidence(e, replayed = true, policy, now)
        else recordEvidence(e, counted = false, why = "kept, but the episode left for ${state.phase}")
    }
}

/** Task 4 replaces this placeholder with the design §12 judgement. */
internal fun Out.takeEvidence(e: Evidence, replayed: Boolean, policy: PolicyTable, now: Instant) =
    recordEvidence(e, counted = false, why = "not judged yet")

/** The audit record of a JobResponse (design §13), whether or not it counted. */
internal fun Out.recordEvidence(e: Evidence, counted: Boolean, why: String) = record(RecordKind.EVIDENCE) {
    putEvidence(e)
    put("counted", counted)
    put("why", why)
}

/** Every field of a JobResponse, as delivered — also used when one arrives where it cannot count (ESCALATED, §13). */
internal fun ObjectNode.putEvidence(e: Evidence) {
    put("jobResponseId", e.jobResponseId)
    put("executionId", e.executionId)
    put("physicalState", e.physicalState)
    put("reachedEvidence", e.reachedEvidence)
    putStrings("completedUnits", e.completedUnits)
    putStrings("inDoubtUnits", e.inDoubtUnits)
    putStrings("unverifiedUnits", e.unverifiedUnits)
    put("operatorRequired", e.operatorRequired)
    put("receivedAt", e.receivedAt.toString())
}

private fun ObjectNode.putStrings(key: String, values: List<String>?) {
    if (values == null) putNull(key) else putArray(key).apply { values.forEach { add(it) } }
}
```

- [ ] **Step 6: `Transition.kt` — 상태 넷을 잇는다**

```kotlin
    Phase.UNKNOWN_PRECONDITION -> onUnknownPrecondition(event, policy, now)
    // Plan B2b-2b: DISPATCH_PENDING, DISPATCHED, AWAITING_EVIDENCE, UNKNOWN_OUTCOME.
    else -> ignore(event, "not handled in ${state.phase} yet")
}
```

를

```kotlin
    Phase.UNKNOWN_PRECONDITION -> onUnknownPrecondition(event, policy, now)
    Phase.DISPATCH_PENDING -> onDispatchPending(event, policy, now)
    Phase.DISPATCHED -> onDispatched(event, policy, now)
    Phase.AWAITING_EVIDENCE, Phase.UNKNOWN_OUTCOME -> onOutcome(event, policy, now)
    Phase.ESCALATED, Phase.RESOLVED, Phase.SUPERSEDED, Phase.CLOSED -> ignore(event, "not an agent phase")
}
```

로 바꾼다. 같은 파일의 `ignore` 에서

```kotlin
        // A late diagnosis is still the model's answer: keep it verbatim (design §13).
        if (event is EpisodeEvent.DiagnosisReturned) put("json", event.json)
```

를

```kotlin
        // A late diagnosis is still the model's answer: keep it verbatim (design §13).
        if (event is EpisodeEvent.DiagnosisReturned) put("json", event.json)
        // So is a late dispatch result — what went out under this episode's name — and any JobResponse (§5.1, §13).
        if (event is EpisodeEvent.DispatchReturned) when (val r = event.result) {
            is DispatchResult.Answer -> put("answer", r.json)
            is DispatchResult.Uncertain -> put("detail", r.message)
            DispatchResult.PersonTaskIssued -> put("result", "PersonTaskIssued")
        }
        if (event is EpisodeEvent.EvidenceArrived) putObject("evidence").putEvidence(event.evidence)
```

로 바꾼다. 그리고 `Outcome.kt` 끝에 Task 4 가 바꿀 자리표시를 하나 더 붙인다:

```kotlin

/** Task 4 replaces this placeholder: JobResponses, a person's check, and the two deadlines. */
internal fun Out.onOutcome(event: EpisodeEvent, policy: PolicyTable, now: Instant) = ignore(event, "awaiting the outcome")
```

- [ ] **Step 7: 통과를 본다**

Run: `./gradlew --no-daemon :episode:test`
Expected: `BUILD SUCCESSFUL` — `DispatchingTest` 12, 모듈 전체 231 + 12 = 243. B2b-2a 의 `RevalidatingTest` 는 `intentSeq` 만 보므로 그대로 초록이다.

- [ ] **Step 8: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode episode/src/test/kotlin/koshei/episode/DispatchingTest.kt
git commit -m "feat(episode): dispatch after the intent's Recorded, through the same gate; picasso answers decide" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 4: 완료 근거와 사람의 확인

**Files:**
- Modify: `episode/src/main/kotlin/koshei/episode/Outcome.kt` (자리표시 둘을 바꾼다)
- Test: `episode/src/test/kotlin/koshei/episode/EvidenceTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/EvidenceTest.kt`:

```kotlin
package koshei.episode

import koshei.episode.Episodes.CONFIRM_DONE
import koshei.episode.Episodes.INSTANCE
import koshei.episode.Episodes.OPERATOR
import koshei.episode.Episodes.REMEDY
import koshei.episode.Episodes.incident
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvidenceTest {
    private fun accepted(): Step = dispatched().returned(DispatchResult.Answer(PicassoAnswers.approved()))

    private fun Step.evidence(e: Evidence, now: Instant = state.enteredAt.plusSeconds(5)): Step = on(EpisodeEvent.EvidenceArrived(e), now)

    /** [s] with an intent that names its target unit "u-1" — no kind does that yet (design §19). */
    private fun withTarget(s: Step): Step {
        val a = s.state.attempt!!
        return Step(s.state.copy(attempt = a.copy(intent = a.intent!!.copy(targetUnit = "u-1"))), emptyList())
    }

    private fun confirm(s: Step, done: Boolean, attempt: Int = 1, candidateId: String = REMEDY): Step =
        s.on(EpisodeEvent.ConfirmedOutcome(candidateId, "$INSTANCE#$attempt", done, OPERATOR), s.state.enteredAt.plusSeconds(10))

    @Test fun `a JobResponse that came before the answer is applied when the answer arrives`() {
        val d = dispatched()
        val early = d.evidence(jobResponse("jr-1", inDoubt = listOf("u-1")), d.state.enteredAt.plusMillis(500))
        assertEquals(Phase.DISPATCHED, early.state.phase)
        assertEquals(1, early.state.bufferedEvidence.size)
        val s = early.returned(DispatchResult.Answer(PicassoAnswers.approved()))
        assertEquals(Phase.UNKNOWN_OUTCOME, s.state.phase)   // the kept report was in doubt
        assertTrue(s.state.bufferedEvidence.isEmpty())
        assertTrue(s.state.episodeUnknowns.any { it.subject == mapOf("executionId" to "exec-42", "unitId" to "u-1") })
    }

    @Test fun `an unlinked or duplicate JobResponse is only recorded`() {
        val a = accepted()
        val other = a.evidence(jobResponse("jr-x", executionId = "exec-other", inDoubt = listOf("u-1")))
        assertEquals(Phase.AWAITING_EVIDENCE, other.state.phase)
        val first = a.evidence(jobResponse("jr-1", completed = listOf("u-2")))
        val dup = first.evidence(jobResponse("jr-1", inDoubt = listOf("u-1")))
        assertEquals(Phase.AWAITING_EVIDENCE, dup.state.phase)
        assertEquals("duplicate jobResponseId", dup.records(RecordKind.EVIDENCE).single().entry.payload.get("why").textValue())
        // a report first seen while the intent was being recorded is a duplicate after the answer too
        val p = pending()
        val early = p.on(EpisodeEvent.EvidenceArrived(jobResponse("jr-early", inDoubt = listOf("u-9"))), p.state.enteredAt.plusMillis(500))
        val answered = early.recorded().returned(DispatchResult.Answer(PicassoAnswers.approved()))
        val again = answered.evidence(jobResponse("jr-early", inDoubt = listOf("u-9")))
        assertEquals(Phase.AWAITING_EVIDENCE, again.state.phase)
        assertEquals("duplicate jobResponseId", again.records(RecordKind.EVIDENCE).single().entry.payload.get("why").textValue())
    }

    @Test fun `anything in doubt or not reported is UNKNOWN, even when the target completed`() {
        val cases = listOf(
            jobResponse("a", inDoubt = listOf("u-1")),
            jobResponse("b", unverified = listOf("u-1")),
            jobResponse("c", operatorRequired = true),
            jobResponse("d", operatorRequired = null),
            jobResponse("e", inDoubt = null),
        )
        for (e in cases) {
            val s = withTarget(accepted()).evidence(e)
            assertEquals(Phase.UNKNOWN_OUTCOME, s.state.phase, e.jobResponseId)
            assertEquals("UNKNOWN", s.state.attempt!!.evidence!!.outcome, e.jobResponseId)
        }
    }

    @Test fun `in UNKNOWN(OUTCOME) a doubtful report adds unknowns and stays`() {
        val u = accepted().evidence(jobResponse("a", inDoubt = listOf("u-1")))
        val s = u.evidence(jobResponse("b", inDoubt = listOf("u-2", "u-1")))
        assertEquals(Phase.UNKNOWN_OUTCOME, s.state.phase)
        assertEquals(listOf("u-1", "u-2"), s.state.episodeUnknowns.mapNotNull { it.subject["unitId"] })   // u-1 once
    }

    @Test fun `the target unit completed with nothing in doubt resolves`() {
        val s = withTarget(accepted()).evidence(jobResponse("jr-1"))
        assertEquals(Phase.RESOLVED, s.state.phase)
        val entry = s.state.history.single().toJson()
        assertEquals("RESOLVED", entry.get("closedAs").textValue())
        assertEquals("DONE", entry.get("evidence").get("outcome").textValue())
        assertEquals("E2", entry.get("evidence").get("grade").textValue())
        assertEquals("ACCEPTED", entry.get("dispatch").get("result").textValue())
        assertEquals("pick_place", entry.get("dispatch").get("delivered")[0].get("skillType").textValue())
    }

    @Test fun `no JobResponse completes an attempt while the target unit is not known`() {
        val s = accepted().evidence(jobResponse("jr-1"))
        assertEquals(Phase.AWAITING_EVIDENCE, s.state.phase)
        assertEquals("target unit not known (design §19)", s.records(RecordKind.EVIDENCE).single().entry.payload.get("why").textValue())
    }

    @Test fun `a person's DONE resolves with E3 - NOT_DONE re-diagnoses without the outcome unknown`() {
        val u = dispatched().returned(DispatchResult.Answer(PicassoAnswers.refused("NO_PROPOSAL")))
        val done = confirm(u, done = true)
        assertEquals(Reply.ACCEPTED, done.reply)
        assertEquals(Phase.RESOLVED, done.state.phase)
        assertEquals("E3", done.state.history.single().evidence!!.grade)
        assertEquals("done; by whom unknown", done.records(RecordKind.TRANSITION).last().entry.payload.get("detail").textValue())
        assertTrue(done.state.episodeUnknowns.isEmpty())
        val notDone = confirm(u, done = false)
        assertEquals(Phase.DIAGNOSING, notDone.state.phase)
        assertEquals(EvidenceSummary("E3", "NOT_DONE"), notDone.state.history.single().evidence)
        assertTrue(notDone.request.unknowns.isEmpty())
        assertEquals(listOf(REMEDY, "ESCALATE"), notDone.request.candidates.map { it.candidateId })   // proposable again
    }

    @Test fun `a confirmation for another attempt, another target or another phase is refused`() {
        val a = accepted()
        assertEquals(Reply.REFUSED_STALE, confirm(a, done = true, attempt = 0).reply)
        assertEquals(Reply.REFUSED_SUBJECT_NOT_UNKNOWN, confirm(a, done = true, candidateId = CONFIRM_DONE).reply)
        val p = pending()
        assertEquals(Reply.REFUSED_SUBJECT_NOT_UNKNOWN, confirm(p, done = true).reply)
    }

    @Test fun `the deadlines end both waits`() {
        assertEquals(EscalationReason.EVIDENCE_EXPIRED, accepted().expireState().reason)
        val s = dispatched().returned(DispatchResult.Uncertain(null)).expireState()
        assertEquals(EscalationReason.UNKNOWN_UNRESOLVED, s.reason)
        assertEquals(ClosedAs.UNKNOWN, s.state.history.single().closedAs)
    }

    @Test fun `a person task completes only by a person`() {
        val t = dispatched(incident("incident-6")).returned(DispatchResult.PersonTaskIssued)
        val report = t.evidence(jobResponse("jr-1", executionId = null))
        assertEquals(Phase.AWAITING_EVIDENCE, report.state.phase)
        val done = confirm(t, done = true, candidateId = CONFIRM_DONE)
        assertEquals(Phase.RESOLVED, done.state.phase)
        assertEquals("PERSON_TASK", done.state.history.single().dispatch!!.result)
    }

    @Test fun `the same remedy dispatched up to the threshold escalates as REPEATED_REMEDY`() {
        val first = confirm(accepted(), done = false)                       // attempt 2 diagnosing
        val second = first.answer()
            .let { it.revalidated(TriState.TRUE, it.state.enteredAt.plusSeconds(1)) }
            .recorded()
            .returned(DispatchResult.Answer(PicassoAnswers.approved()))
        assertEquals(mapOf(REMEDY to 2), second.state.dispatchCounts)
        val third = confirm(second, done = false, attempt = 2).answer()      // attempt 3 proposes the same remedy
        assertEquals(EscalationReason.REPEATED_REMEDY, third.reason)
    }
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew --no-daemon :episode:test --tests 'koshei.episode.EvidenceTest'`
Expected: 11개 모두 FAIL — 자리표시가 근거를 판정하지 않고 확인과 기한을 무시한다. 이미 통과하는 시험이 있으면 멈추고 보고한다.

- [ ] **Step 3: `Outcome.kt` 의 자리표시 둘을 바꾼다**

```kotlin
/** Task 4 replaces this placeholder with the design §12 judgement. */
internal fun Out.takeEvidence(e: Evidence, replayed: Boolean, policy: PolicyTable, now: Instant) =
    recordEvidence(e, counted = false, why = "not judged yet")
```

를

```kotlin
/**
 * One JobResponse, in the design §12 order: ① record only (duplicate, not this attempt's execution) → ② UNKNOWN (anything
 * in doubt or not reported) → ③ DONE (the target unit completed). A report that says DONE and UNKNOWN at once is
 * UNKNOWN. JobResponses never say NOT_DONE (④ — only a person does). Anything else is recorded (⑤).
 */
internal fun Out.takeEvidence(e: Evidence, replayed: Boolean, policy: PolicyTable, now: Instant) {
    val attempt = state.attempt ?: return recordEvidence(e, counted = false, why = "no attempt in progress")
    if (!replayed) {
        if (e.jobResponseId in state.seenJobResponseIds) return recordEvidence(e, counted = false, why = "duplicate jobResponseId")
        state = state.copy(seenJobResponseIds = state.seenJobResponseIds + e.jobResponseId)
    }
    // ① Linked only through the executionId picasso returned for this attempt's approval. Person tasks have none (§8.4).
    if (attempt.executionId == null || e.executionId != attempt.executionId)
        return recordEvidence(e, counted = false, why = "not linked to this attempt's execution")
    // ② In doubt, unverified, a person needed, or simply not reported: UNKNOWN — never DONE, never folded into NOT_DONE.
    val inDoubt = e.inDoubtUnits
    val unverified = e.unverifiedUnits
    if (inDoubt == null || unverified == null || e.operatorRequired != false || inDoubt.isNotEmpty() || unverified.isNotEmpty()) {
        recordEvidence(e, counted = true, why = "in doubt")
        val units = (inDoubt.orEmpty() + unverified.orEmpty()).distinct()
        val subjects: List<Map<String, String?>> =
            if (units.isEmpty()) listOf(mapOf("executionId" to e.executionId))
            else units.map { mapOf("executionId" to e.executionId, "unitId" to it) }
        // Once per (subject, what): a repeated report does not grow the next request.
        val fresh = subjects.filterNot { s -> state.episodeUnknowns.any { it.subject == s && it.what == UnknownWhat.OUTCOME } }
        state = state.copy(
            attempt = attempt.copy(evidence = EvidenceSummary(e.reachedEvidence ?: "E0", "UNKNOWN")),
            episodeUnknowns = state.episodeUnknowns + fresh.map { Unknown(it, UnknownWhat.OUTCOME, e.receivedAt.toString(), "picasso") },
        )
        if (state.phase == Phase.AWAITING_EVIDENCE)
            enterUnknownOutcome(policy, now, outcomeUnknown = false, detail = "JobResponse ${e.jobResponseId} in doubt")
        return
    }
    // ③ DONE: the target unit completed.
    val unit = attempt.intent?.targetUnit
    if (unit != null && e.completedUnits?.contains(unit) == true) {
        recordEvidence(e, counted = true, why = "target completed")
        return resolve(EvidenceSummary(e.reachedEvidence ?: "E0", "DONE"), now)
    }
    recordEvidence(e, counted = false, why = if (unit == null) "target unit not known (design §19)" else "target not completed yet")
}

/** RESOLVED (design §5.2): the attempt closes with its evidence; after NO_PROPOSAL the record says "done; by whom unknown" (§11). */
private fun Out.resolve(evidence: EvidenceSummary, now: Instant) {
    val attempt = state.attempt ?: return
    attempt.candidate?.let { forgetOutcomeUnknown(it) }
    state = state.copy(attempt = attempt.copy(evidence = evidence))
    closeAttempt(ClosedAs.RESOLVED, now)
    enter(Phase.RESOLVED, now, detail = if (attempt.authorUnknown) "done; by whom unknown" else "done")
}

/** The candidate's own OUTCOME unknown (§9.4) goes once a person or a report knows the outcome. Unit-level ones stay. */
private fun Out.forgetOutcomeUnknown(candidate: Candidate) {
    val subject = mapOf("candidateId" to candidate.candidateId)
    state = state.copy(episodeUnknowns = state.episodeUnknowns.filterNot { it.what == UnknownWhat.OUTCOME && it.subject == subject })
}
```

로, 그리고

```kotlin
/** Task 4 replaces this placeholder: JobResponses, a person's check, and the two deadlines. */
internal fun Out.onOutcome(event: EpisodeEvent, policy: PolicyTable, now: Instant) = ignore(event, "awaiting the outcome")
```

를

```kotlin
/**
 * AWAITING_EVIDENCE and UNKNOWN(OUTCOME) (design §5.1, §5.2, §7.2, §12). A person's on-site check of the executed target
 * is evidence grade E3: DONE resolves, NOT_DONE re-diagnoses. A check from another attempt's card is refused as stale.
 */
internal fun Out.onOutcome(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    val attempt = state.attempt
    val candidate = attempt?.candidate
        ?: return escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, "internal: no proposed candidate"))
    when {
        event is EpisodeEvent.EvidenceArrived -> takeEvidence(event.evidence, replayed = false, policy, now)
        event is EpisodeEvent.ConfirmedOutcome && event.candidateId == candidate.candidateId && event.proposalId != attempt.proposalId -> {
            reply = Reply.REFUSED_STALE
            ignore(event, "stale confirmation: ${event.proposalId}")
        }
        event is EpisodeEvent.ConfirmedOutcome && event.candidateId == candidate.candidateId -> {
            reply = Reply.ACCEPTED
            record(RecordKind.OUTCOME_CONFIRMED) {
                put("candidateId", candidate.candidateId)
                put("proposalId", event.proposalId)
                put("done", event.done)
                put("by", event.by.id)
                put("assurance", event.by.assurance.name)
            }
            val evidence = EvidenceSummary("E3", if (event.done) "DONE" else "NOT_DONE")
            if (event.done) resolve(evidence, now)
            else {
                state = state.copy(attempt = attempt.copy(evidence = evidence))
                forgetOutcomeUnknown(candidate)
                rediagnose(RediagnoseReason.NOT_DONE, policy, now, detail = "by ${event.by.id}")
            }
        }
        event is EpisodeEvent.DeadlineExpired && event.which == Timer.STATE ->
            if (state.phase == Phase.AWAITING_EVIDENCE) escalate(now, Escalation(EscalationReason.EVIDENCE_EXPIRED, candidate.candidateId))
            else escalate(now, Escalation(EscalationReason.UNKNOWN_UNRESOLVED, "outcome of ${candidate.candidateId}"), closedAs = ClosedAs.UNKNOWN)
        else -> ignore(event, "awaiting the outcome")
    }
}
```

로 바꾼다.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew --no-daemon :episode:test --rerun`
Expected: `BUILD SUCCESSFUL` — `EvidenceTest` 11, 모듈 전체 243 + 11 = 254. `git status --short -uall episode/src/test/resources` 는 비어 있다.

- [ ] **Step 5: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/Outcome.kt episode/src/test/kotlin/koshei/episode/EvidenceTest.kt
git commit -m "feat(episode): JobResponse judgement and a person's outcome check (AWAITING_EVIDENCE, UNKNOWN(OUTCOME))" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 5: 설계를 picasso 의 실제 답에 맞춘다

**Files:**
- Modify: `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md`

Edit 로 아래를 차례로 바꾼다(각 옛 글은 파일에 한 번만 있다).

- [ ] **Step 1: §5.2 DISPATCHED 행 셋**

옛:
```
| DISPATCHED | 답의 `schemaVersion` 이 `"2"` 가 아님 · `refusal` 이 판 2 의 아는 값 넷(`NO_PROPOSAL` · `REMEDY_NOT_APPLIED` · `REFUSED_BY_GATE` · `NOT_DECLARED`) 밖 | ESCALATED | `DISPATCH_ANSWER_UNKNOWN`. **DISPATCHED 의 picasso 답 행 중 이 검사가 먼저다** (§8.4) |
| DISPATCHED | 수락(`APPROVE_REMEDY` 는 `delivered: true`) · 사람 과업 발행 · 자식 사가 완료 | AWAITING_EVIDENCE | `REMEDY_NOT_APPLIED` 는 수락이 아니다 (아래 행) |
| DISPATCHED | 승인이 수락됐는데 `delivered` 가 true 가 아님(false · null) | UNKNOWN(OUTCOME) | 전달됐는지 모른다. 재시도하지 않는다 |
```
새:
```
| DISPATCHED | 답의 `schemaVersion` 이 `"2"` 가 아님 · `outcome` 이 `APPROVED` · `REFUSED` 밖 · `refusal` 이 판 2 의 열다섯(§8.4) 밖 · `APPROVED` 인데 `executionId` 나 `steps` 가 없음 · 후보 종류와 맞지 않는 답 | ESCALATED | `DISPATCH_ANSWER_UNKNOWN`. **DISPATCHED 의 picasso 답 행 중 이 검사가 먼저다** (§8.4) |
| DISPATCHED | 수락(`APPROVE_REMEDY` 는 `outcome: APPROVED` — `executionId` 와 실제로 나간 `steps` 를 싣는다) · 사람 과업 발행 · 자식 사가 완료 | AWAITING_EVIDENCE | `executionId` 가 결과 통보를 이 시도에 잇는다(§12). `REMEDY_NOT_APPLIED` 는 수락이 아니다 (아래 행) |
```
(셋째 행 `delivered` 는 지운다 — picasso 답에 그런 칸이 없다.)

- [ ] **Step 2: §5.2 「그 밖의 아는 거절 값」 행**

옛: `| DISPATCHED | 그 밖의 아는 거절 값(`REFUSED_BY_GATE` · `NOT_DECLARED`) | ESCALATED |`
새: `| DISPATCHED | 그 밖의 아는 거절 값(판 2 의 열다섯 가운데 `NO_PROPOSAL` · `REMEDY_NOT_APPLIED` 를 뺀 열셋) | ESCALATED |`

- [ ] **Step 3: §8.4 기록 문장**

옛: ``- picasso 응답의 `approverKind`(PERSON/AGENT), `delivered` 를 그대로 기록한다. koshei 의 POLICY 자동 승인은 picasso 에 AGENT 로 간다.``
새: ``- koshei 가 승인 창구에 보낸 `approverKind`(PERSON/AGENT — POLICY 자동 승인은 AGENT, picasso §7.1)와 답의 `executionId` · `steps` 를 기록한다. `steps` 가 「실제로 나간 값」이다(picasso §7.3) — 이력의 `dispatch.delivered` 가 이것이다(§9.4). 답에 참 · 거짓 `delivered` 칸은 없다(v0.5 는 그렇게 잘못 읽었다, 2026-10-01 picasso main `584c6cd` 의 `ApprovalWire` 로 바로잡음).``

- [ ] **Step 4: §8.4 경계 표의 ② 조건**

옛: ``판 2 의 아는 거절 값은 답이 이름을 댄 넷(`NO_PROPOSAL` · `REMEDY_NOT_APPLIED` · `REFUSED_BY_GATE` · `NOT_DECLARED`)뿐이다. 모르는 판이나 그 밖의 값이면 ESCALATED(`DISPATCH_ANSWER_UNKNOWN`). 판 2 의 전체 목록은 picasso 에 묻는다(§19)``
새: ``판 2 의 거절 값은 picasso `ApprovalRefusal` 의 열다섯이다(picasso main `584c6cd`, `docs/orchestration.md` §7.3): `WITHHELD` · `NO_PROPOSAL` · `PROPOSAL_CHANGED` · `NOT_DECLARED` · `REVOKED` · `EXPIRED` · `ROBOT_OUT_OF_SCOPE` · `SKILL_OUT_OF_SCOPE` · `VALUE_NOT_DECLARED` · `OBJECT_NOT_OBSERVED` · `DECLARED_CONTRADICTS_OBSERVED` · `CAPABILITY_UNKNOWN` · `VALUES_NOT_ACCEPTED` · `REFUSED_BY_GATE` · `REMEDY_NOT_APPLIED`. 모르는 판이나 그 밖의 값이면 ESCALATED(`DISPATCH_ANSWER_UNKNOWN`)``

- [ ] **Step 5: §9.4 `approverKind` 문장**

옛: ``  `dispatch.approverKind` 는 picasso 가 기록한 PERSON / AGENT — `approval.by` 와 두 칸을 다 남겨야 두 시스템의 감사가 이어진다.``
새: ``  `dispatch.approverKind` 는 koshei 가 승인 창구에 보낸 PERSON / AGENT(picasso 가 감사에 남긴다) — `approval.by` 와 두 칸을 다 남겨야 두 시스템의 감사가 이어진다. `dispatch.delivered` 는 `APPROVED` 답의 `steps`(실제로 나간 걸음과 값)이고, 승인되지 않았으면 `null` 이다.``

- [ ] **Step 6: §19 두 행**

옛: ``| 판 2 의 거절 값 전체 목록 — 답이 이름을 댄 넷(`NO_PROPOSAL` · `REMEDY_NOT_APPLIED` · `REFUSED_BY_GATE` · `NOT_DECLARED`) 밖에 더 있는가, koshei 의 물음에 나온 `PROPOSAL_CHANGED` 가 그 하나인가 | picasso 에 물음 | 그 전까지 넷 밖의 값은 `DISPATCH_ANSWER_UNKNOWN` (§5.2, §8.4) |``
새: ``| `PROPOSAL_CHANGED` 를 재진단 목록에 넣을지 — picasso 는 「다시 읽고 다시 시도」를 권한다(§7.3). 판 2 의 거절 값 열다섯은 picasso 코드로 확인했다(`584c6cd`, §8.4) | 사용자 결정 | 그 전까지 ESCALATED(`DISPATCH_REFUSED`) (§8.4 기본 갈래) |``

옛: ``| 결과 통보를 (기체, 주문) 과 대상 단위에 잇는 칸 — 승인 답에서 executionId 나 단위를 알 수 있는가 | picasso 에 물음 |``
새: ``| 결과 통보를 대상 **단위**에 잇는 칸 — 실행은 `APPROVED` 답의 `executionId` 로 잇는다(picasso `584c6cd`). 결과 통보의 어느 단위가 이 조치의 대상인가 | picasso 에 물음 |``

- [ ] **Step 7: §6 구현 모양 단락**

옛: ``재진단 사유(기록 전용)는 `APPROVAL_REJECTED` · `APPROVAL_LAPSED` · `PRECONDITION_BROKEN`. POLICY 자동 승인은 실행 의도 직전에 지금 표로``
새: ``재진단 사유(기록 전용)는 `APPROVAL_REJECTED` · `APPROVAL_LAPSED` · `PRECONDITION_BROKEN` · `NOT_DONE`. 실행 뒤의 모양(계획 B2b-2b): `Dispatch(intent, approverKind, timeoutMs, token)` 과 `DispatchReturned(result, token)`, `EvidenceArrived(Evidence)`(결과 통보의 칸 그대로), `ConfirmedOutcome(candidateId, proposalId, done, by)`. `Recorded` 를 받으면 실행 의도 직전과 같은 관문을 다시 지나고 나서야 `Dispatch` 를 낸다. UNKNOWN(OUTCOME)이 `unknownMs` 로 끝나면 이력의 `closedAs` 는 `UNKNOWN` 이다. POLICY 자동 승인은 실행 의도 직전에 지금 표로``

- [ ] **Step 8: §12 잇는 칸 문장**

옛: ``- 결과 통보와 (기체, 주문) · 대상 단위를 잇는 칸은 답에 없다 — picasso 에 묻는다(§19). **그 답이 오기 전까지 결과 통보로는 DONE 에``
새: ``- 결과 통보는 `APPROVED` 답의 `executionId` 로 이 시도에 잇는다(picasso main `584c6cd`). 결과 통보의 어느 단위가 이 조치의 대상인지는 답에 없다 — picasso 에 묻는다(§19). **그 답이 오기 전까지 결과 통보로는 DONE 에``

- [ ] **Step 9: Commit**

```bash
git add docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md
git commit -m "docs: design follows picasso's actual approval answer (steps, executionId, fifteen refusals)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
