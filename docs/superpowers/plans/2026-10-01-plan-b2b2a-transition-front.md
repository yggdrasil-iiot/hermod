# 계획 B2b-2a — 전이 함수 앞 절반 (시작 · 진단 · 승인 · 전제 재조회) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 에피소드 상태 · 사건 · 명령 타입과 순수 전이 함수를 만든다. 첫 증상부터 실행 의도 기록(DISPATCH_PENDING 진입)까지를 설계 v0.5 §5 · §6 대로 다룬다.

**Architecture:** 순수 모듈 `:episode` 에 파일 일곱을 더한다. `transition(state, event, read, now)` 는 사건 하나를 받아 다음 상태, 구동부가 순서대로 실행할 명령 목록, Update 이면 답(값)을 돌려준다. 사건이 맞지 않아도 예외를 던지지 않는다 — 기록하고 무시하거나 값으로 거절한다. 판단 규칙은 이미 있는 순수 함수(`resolvePolicy`, `judgeDiagnosis`, `diagnosisRequest`, `waitUntil`, `approvalStillValid`)를 조합한다. 명령형 껍질(Temporal)은 계획 B3 다.

**Tech Stack:** Kotlin 2.2 / JDK 21, Jackson databind 2.17 (트리), JUnit 5 / kotlin-test

**작업 위치:** worktree `C:\Users\Eisen\Desktop\Labs\[projects] koshchei\koshei-episode-core`, 브랜치 `feat/episode-core`. 원래 체크아웃(`koshei/`)은 건드리지 않는다. 파일은 Write/Edit 로만 쓴다(heredoc 금지). bare `git stash` 금지.

**커밋 규칙:** 모든 커밋은 `git commit -m "<제목>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`.

**B2b-2b 에 넘기는 의무:** `Recorded` 를 받아 `Dispatch` 를 내기 직전에 이 계획의 마지막 관문(`proceedToDispatch`)과 같은 검사를 다시 한다 —
`currentUnknowns()` 를 다시 계산하고(계산 못 하면 모르는 것이 있다고 본다), 승인 유효 기간과 POLICY 승인의 현재 표 판정을 다시 본다.
DISPATCH_PENDING 에서도 증상이 합류하기 때문이다(§6 고정 규칙).

**범위 밖 (계획 B2b-2b):** DISPATCH_PENDING 에서 나가는 모든 전이(`Recorded` · `RecordFailed` · `Dispatch` · `DispatchReturned`), AWAITING_EVIDENCE · UNKNOWN(OUTCOME) · 근거 판정, `unknowns` 목록 항목의 사람 확인(§7.2 `confirm` 의 둘째 · 셋째 경우), 이력의 `dispatch` · `evidence` 칸 채우기, 실행 횟수(`dispatchCounts`) 세기, §17 의 무작위 사건 열 속성 시험. **범위 밖 (B3):** 운영자 카드 그리기(§9.5), 정책 `source` 를 저장했다가 규칙이 조여진 뒤 다시 읽을 때의 대비, YAML 읽개.

---

## 설계에서 코드로 옮길 때 정한 것

| 설계 | 이 계획의 코드 |
|---|---|
| §6 `transition(state, event, policy: PolicyTable)` | `startEpisode(Detected, read: PolicyRead, now)` 와 `transition(state, event, read: PolicyRead?, now)`. `read` 는 구동부가 이 결정을 위해 `readPolicy` 를 부른 결과이고, `null` 이면 지금 표를 그대로 쓴다. 해석은 `resolvePolicy(read, state.policy, now)` 가 한다. 시각은 인자로 받는다(워크플로의 `Workflow.currentTimeMillis()`) |
| §5.1 PROPOSED (순간 상태) | `Phase` 에 없다. 진단 결과를 받은 한 단계 안에서 정책 판정까지 끝나고, 지난 것은 `RecordKind.PROPOSED` 기록으로 남는다 |
| §5.1 UNKNOWN 두 종류 | `Phase.UNKNOWN_PRECONDITION`, `Phase.UNKNOWN_OUTCOME` (둘째는 B2b-2b) |
| 기한 (`AwaitApproval(deadline)` 등) | `Command.SetTimer(which, at, token)`. 상태에 들어갈 때마다 `token` 이 1 늘고, 지난 상태의 타이머는 `token` 이 달라 무시된다. `EPISODE` 타이머는 `EPISODE_TOKEN` 고정. 액티비티를 기다리는 상태(DIAGNOSING · REVALIDATING)는 타이머 없이 액티비티의 ScheduleToClose 가 기한이다(§5.2 표 아래) |
| §7.2 Update 의 거절 값 | `Step.reply: Reply?` — `REFUSED_NOT_AWAITING` · `REFUSED_STALE` · `REFUSED_SUBJECT_NOT_UNKNOWN` · `REFUSED_NOT_ESCALATED` · `REFUSED_CLOSED` |
| §7.2 제안 id | `"<instanceId>#<attempt>"` |
| §11 멱등 열쇠 | `"<instanceId>:<attempt>:<kind>"` |
| §5.2 시작 행의 "정책이 꺼짐 · 없음 · 만료" | 시작에 `resolvePolicy(read, null, now)` 가 `Off` 면 곧바로 ESCALATED, 보존 기한은 `Off.policy`(없으면 24시간) |
| §5.2 `*` 행 "결정 시점에 읽은 정책이 꺼짐" | `read` 가 주어지고 `Off` 면 어떤 상태든(ESCALATED · 종료 제외) ESCALATED(그 사유). `POLICY_MISSING` · `POLICY_EXPIRED` 도 같다 |
| §8.2 `POLICY_REJECTED` 알림 | 거절 오류 목록이 지난번과 달라질 때만 한 번 |
| 후보 상한 26 | `MAX_OFFERED_CANDIDATES = 26` — narrator 가 후보에 A..Z 별칭을 붙인다(narrator `diagnose/context.py` `aliases`) |
| §9.1 실행 차단의 이중 방어 | DISPATCH_PENDING 에 들어가기 직전에, 진단 뒤에 합류한 증상까지 넣어 `unknowns` 를 다시 계산한다. 실행 계열이면 ESCALATED(`UNKNOWN_BLOCKS_EXECUTION`) |
| §6 `Revalidated(result)` | `Revalidated(result, token)` — `Command.Revalidate` 가 실은 상태 `token` 을 되돌려준다. 지난 상태에 대한 늦은 결과는 무시한다(재진단 뒤 새 후보의 전제로 읽히지 않게) |
| §6 `reason: RejectReason?` (값은 정의된 적 없음) | `RejectReason { WRONG_TARGET, WRONG_ACTION, PRECONDITION_NOT_MET, NOT_NOW, OTHER }` 와 자유 글 `note`. 이력(계약 §3.4, narrator 가 LLM 자료 칸에 옮김)에는 열거값만, `note` 는 감사 기록에만 간다 — 인증 없는 운영자의 글이 프롬프트로 가지 않게 |
| §5.2 PROPOSED 의 POLICY 자동 승인 | 승인한 뒤에도 실행 의도 직전에 **지금 표**로 다시 판정한다. 그 사이 읽은 표가 자동 승인을 끄거나(`allowed: false`, R6) 거절돼 멈췄으면(§8.2) 승인을 거두고 AWAITING_APPROVAL 로 간다. UNKNOWN(PRECONDITION)을 지나는 동안(최대 `unknownMs`) 정책이 바뀔 수 있고, 사람의 전제 확인은 세계의 사실을 확인한 것이지 조치를 승인한 것이 아니다 |
| §8.2 만료 | `read` 없이 결정할 때도 지금 표의 `expiresAt <= now` 면 ESCALATED(`POLICY_EXPIRED`) |
| §5.2 사람 과업 행의 "기록 `PERSON_TASK`" | PROPOSED 기록의 `route` 칸: `PERSON_TASK` · `POLICY` · `AWAITING_APPROVAL` · `REPEATED_REMEDY` |

B2a 검토에서 넘어온 메모 가운데 이 계획이 하는 것: 끄기 스위치와 만료의 앞뒤를 유효한 표 · 거절된 표에서 같게(끄기가 먼저), 거절된 표의 읽을 수 없는 `expiresAt` 이 마지막 유효 판으로 간다는 것을 KDoc 과 시험으로 고정, `PolicyTable.source` 를 꺼낼 때마다 사본으로. `Off.policy` 는 좁히지 않는다 — 보존 기한의 출처로만 쓰이고(`escalatedUntil`), 그 뜻은 이미 KDoc 에 적혀 있다.

---

## 파일 구조

| 파일 | 할 일 | 책임 |
|---|---|---|
| `episode/src/main/kotlin/koshei/episode/PolicyResolution.kt` | 수정 | 끄기 스위치를 만료보다 먼저 |
| `episode/src/main/kotlin/koshei/episode/Policy.kt` | 수정 | `source` 를 사본으로 |
| `episode/src/main/kotlin/koshei/episode/EpisodeModel.kt` | 생성 | 작은 값 타입과 열거값 |
| `episode/src/main/kotlin/koshei/episode/EpisodeEvents.kt` | 생성 | 사건과 명령 |
| `episode/src/main/kotlin/koshei/episode/EpisodeHistory.kt` | 생성 | 이력 항목 (계약 §3.4) |
| `episode/src/main/kotlin/koshei/episode/EpisodeState.kt` | 생성 | 시도와 에피소드 상태 |
| `episode/src/main/kotlin/koshei/episode/Transition.kt` | 생성 | 시작, 전역 행, ESCALATED, 도우미 |
| `episode/src/main/kotlin/koshei/episode/PhaseStubs.kt` | 생성 후 삭제 | Task 3 의 자리표시 (Task 4 · 5 가 지운다) |
| `episode/src/main/kotlin/koshei/episode/Diagnosing.kt` | 생성 | CORRELATING · DIAGNOSING · 제안 · 승인 |
| `episode/src/main/kotlin/koshei/episode/Revalidating.kt` | 생성 | REVALIDATING · UNKNOWN(PRECONDITION) · 실행 의도 |
| `episode/src/test/kotlin/koshei/episode/PolicyHardeningTest.kt` | 생성 | Task 1 |
| `episode/src/test/kotlin/koshei/episode/Episodes.kt` | 생성 | 시험용 에피소드 몰개 |
| `episode/src/test/kotlin/koshei/episode/EpisodeLifecycleTest.kt` | 생성 | Task 3 |
| `episode/src/test/kotlin/koshei/episode/DiagnosingTest.kt` | 생성 | Task 4 |
| `episode/src/test/kotlin/koshei/episode/RevalidatingTest.kt` | 생성 | Task 5 |
| `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md` | 수정 | Task 6: §6 에 구현 모양 한 단락 |

---

## Chunk 1: B2a 메모와 타입

### Task 1: 정책 해석의 앞뒤와 `source` 사본

**Files:**
- Modify: `episode/src/main/kotlin/koshei/episode/PolicyResolution.kt`
- Modify: `episode/src/main/kotlin/koshei/episode/Policy.kt`
- Test: `episode/src/test/kotlin/koshei/episode/PolicyHardeningTest.kt`

- [ ] **Step 1: 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/PolicyHardeningTest.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Review notes carried over from plan B2a. */
class PolicyHardeningTest {
    private val now = Instant.parse("2026-10-01T00:00:00Z")

    @Test fun `the off switch wins over expiry in a valid table, as it does in a rejected one`() {
        val tree = Policies.node { put("agentLayerEnabled", false); put("expiresAt", "2026-09-01T00:00:00Z") }
        val r = resolvePolicy(PolicyRead.Found(tree), null, now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.AGENT_LAYER_OFF, r.reason)
    }

    @Test fun `the source tree is handed out as a copy`() {
        val p = Policies.valid()
        (p.source as ObjectNode).put("version", "tampered")
        assertEquals("2026-09-30.1", p.source.get("version").textValue())
    }

    @Test fun `an unparsable expiresAt in a rejected table falls back to the last valid table`() {
        val r = resolvePolicy(PolicyRead.Found(Policies.node { put("expiresAt", "not a time"); put("bogus", 1) }), Policies.valid(), now)
        assertIs<PolicyResolution.Active>(r)
        assertTrue(r.autoApproveSuspended)
    }
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew --no-daemon :episode:test --tests 'koshei.episode.PolicyHardeningTest'`
Expected: 3개 중 2개 FAIL — 첫째는 `POLICY_EXPIRED` 가 나와서, 둘째는 `tampered` 가 보여서. 셋째(읽을 수 없는 `expiresAt`)는 지금 동작을 고정하는 시험이라 이미 통과한다.

- [ ] **Step 3: 구현한다**

`PolicyResolution.kt` 의 끝 세 줄

```kotlin
    val expiresAt = inForce.expiresAt
    if (expiresAt != null && !now.isBefore(expiresAt)) return PolicyResolution.Off(PolicyOffReason.POLICY_EXPIRED, inForce, rejected, adopt)
    if (!inForce.agentLayerEnabled) return PolicyResolution.Off(PolicyOffReason.AGENT_LAYER_OFF, inForce, rejected, adopt)
```

를 이 순서로 바꾼다:

```kotlin
    // The off switch before expiry, in the same order as for a rejected tree above: an explicit switch is the stronger intent.
    if (!inForce.agentLayerEnabled) return PolicyResolution.Off(PolicyOffReason.AGENT_LAYER_OFF, inForce, rejected, adopt)
    val expiresAt = inForce.expiresAt
    if (expiresAt != null && !now.isBefore(expiresAt)) return PolicyResolution.Off(PolicyOffReason.POLICY_EXPIRED, inForce, rejected, adopt)
```

같은 파일의 `resolvePolicy` KDoc 에서

```
 * - the table in force is finally checked for expiry (`expiresAt <= now`) and for the agent-layer switch.
```

를

```
 * - an `expiresAt` in a rejected tree that is not a readable instant is ignored: the read falls back like any other
 *   rejection (last valid table, auto-approval suspended);
 * - the table in force is finally checked for the agent-layer switch, then for expiry (`expiresAt <= now`) — the same
 *   order as for a rejected tree.
```

로 바꾼다.

`Policy.kt` 의 `PolicyTable` 에서

```kotlin
    /**
     * The tree this table was parsed from (a private copy). Persist it and re-parse with [parsePolicy] to restore the
     * table, e.g. across a Temporal continue-as-new.
     */
    val source: JsonNode,
) {
```

를

```kotlin
    source: JsonNode,
) {
    private val sourceCopy: JsonNode = source.deepCopy()

    /**
     * The tree this table was parsed from, as a fresh copy on every read. Persist it and re-parse with [parsePolicy] to
     * restore the table, e.g. across a Temporal continue-as-new.
     */
    val source: JsonNode get() = sourceCopy.deepCopy()

```

로 바꾼다(`override fun toString()` 은 그 아래 그대로).

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew --no-daemon :episode:test`
Expected: `BUILD SUCCESSFUL`, 161 + 3 = 164 초록. 기존 `PolicyResolutionTest` 가 이 앞뒤 변경으로 깨지면 멈추고 보고한다(끄기와 만료가 함께 있는 유효한 표를 시험하는 기존 시험은 없다).

- [ ] **Step 5: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/PolicyResolution.kt episode/src/main/kotlin/koshei/episode/Policy.kt episode/src/test/kotlin/koshei/episode/PolicyHardeningTest.kt
git commit -m "fix(episode): off switch before expiry in every table; policy source read as a copy" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: 에피소드 타입

이 과제는 타입만 만든다. 행동이 없으므로 시험은 Task 3 부터다. 이 과제의 확인은 컴파일이다.

**Files:**
- Create: `episode/src/main/kotlin/koshei/episode/EpisodeModel.kt`
- Create: `episode/src/main/kotlin/koshei/episode/EpisodeEvents.kt`
- Create: `episode/src/main/kotlin/koshei/episode/EpisodeHistory.kt`
- Create: `episode/src/main/kotlin/koshei/episode/EpisodeState.kt`

- [ ] **Step 1: `EpisodeModel.kt` 를 쓴다**

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * Where an episode is (design §5.1). PROPOSED is momentary — the policy decision happens inside the same step — so it
 * is never a resting phase; its passage is recorded ([RecordKind.PROPOSED]). The two UNKNOWN kinds are separate phases.
 */
enum class Phase {
    CORRELATING, DIAGNOSING, AWAITING_APPROVAL, REVALIDATING, UNKNOWN_PRECONDITION,
    DISPATCH_PENDING, DISPATCHED, AWAITING_EVIDENCE, UNKNOWN_OUTCOME,
    ESCALATED, RESOLVED, SUPERSEDED, CLOSED;

    val terminal: Boolean get() = this == RESOLVED || this == SUPERSEDED || this == CLOSED
}

/** Why an episode went to a person (design §5.3, the values that lead to ESCALATED). */
enum class EscalationReason {
    APPROVAL_EXPIRED, EVIDENCE_EXPIRED, EPISODE_EXPIRED, DIAGNOSIS_EXPIRED, DIAGNOSIS_FAILED,
    NO_GROUNDS, UNCITED, ESCALATE_RECOMMENDED, OUT_OF_CANDIDATES, CANDIDATES_VERSION_MISMATCH,
    UNKNOWN_BLOCKS_EXECUTION, UNKNOWN_UNRESOLVED, ATTEMPTS_EXHAUSTED, REPEATED_REMEDY,
    SNAPSHOT_TOO_LARGE, CANDIDATES_TOO_MANY, AGENT_LAYER_OFF, POLICY_MISSING, POLICY_EXPIRED,
    TAKEN_OVER, DISPATCH_REFUSED, DISPATCH_ANSWER_UNKNOWN, CHILD_OVERDUE, RECORD_FAILED, WITHHELD_HUMAN_FIRST,
}

data class Escalation(val reason: EscalationReason, val detail: String? = null)

/** Why an attempt ended in a new diagnosis instead of a person (design §5.1, §5.2). Recorded, not an escalation. */
enum class RediagnoseReason { APPROVAL_REJECTED, APPROVAL_LAPSED, PRECONDITION_BROKEN }

/**
 * Why a person rejected a proposal (design §5.3 `APPROVAL_REJECTED(reason)`, contract 0.6 §3.4 "koshei 거절 열거값").
 * Only this value reaches the diagnosis history — narrator copies it into the model's context — while the operator's
 * free text stays in the audit record.
 */
enum class RejectReason { WRONG_TARGET, WRONG_ACTION, PRECONDITION_NOT_MET, NOT_NOW, OTHER }

/** How far an approver's identity is proven. The PoC has no authentication (design §7.2), and the record says so. */
enum class IdentityAssurance { SELF_ASSERTED }

data class Approver(val id: String, val assurance: IdentityAssurance = IdentityAssurance.SELF_ASSERTED)

/** Who approved (design §9.4 `approval.by`). Person-task kinds have no approval at all. */
enum class ApprovalBy { PERSON, POLICY }

/** [approver] is null for POLICY. */
data class Approval(val by: ApprovalBy, val approver: Approver?, val at: Instant)

enum class SymptomKind { INCIDENT, SEARCH }

/**
 * One picasso export line the watcher carried in (design §12): an incident line (keyed by `digest`) or a remedy-search
 * line (keyed by `searchId`). [eventId] is the watcher's dedup key. The line is kept verbatim; each read is a fresh copy.
 */
class Symptom(val eventId: String, val kind: SymptomKind, line: ObjectNode, val observedAt: Instant) {
    private val lineCopy: ObjectNode = line.deepCopy()
    val line: ObjectNode get() = lineCopy.deepCopy()

    /** Why this line cannot go into a snapshot (the contract's request-reading rules, see [Snapshot]), or null. */
    internal fun problem(): String? = when (kind) {
        SymptomKind.INCIDENT -> if (lineCopy.text("digest").isNullOrBlank()) "incident line without digest" else null
        SymptomKind.SEARCH -> if (lineCopy.text("searchId").isNullOrBlank()) "search line without searchId" else null
    }

    override fun toString(): String = "Symptom($eventId, $kind)"
}

/** A three-valued observation (design §6): never folded into true/false. */
enum class TriState { TRUE, FALSE, UNKNOWN }

/**
 * Timers the runtime sets from [Command.SetTimer]. STATE and RECHECK belong to one phase and carry its token, so a timer
 * left over from an earlier phase is ignored. EPISODE is set once. RETENTION belongs to ESCALATED.
 */
enum class Timer { STATE, RECHECK, EPISODE, RETENTION }

/** What an Update handler returns (design §7.2): refusals are values, recorded, never exceptions. */
enum class Reply { ACCEPTED, REFUSED_NOT_AWAITING, REFUSED_STALE, REFUSED_SUBJECT_NOT_UNKNOWN, REFUSED_NOT_ESCALATED, REFUSED_CLOSED }

enum class NoticeKind { APPROVAL_NEEDED, CONFIRM_PRECONDITION, ESCALATED, POLICY_REJECTED }

/** What goes to the operator channel (design §8.5). The card itself (§9.5) is drawn by the runtime from the state. */
data class Notice(val kind: NoticeKind, val phase: Phase, val reason: EscalationReason? = null, val detail: String? = null)

enum class RecordKind {
    OPENED, TRANSITION, SYMPTOM_JOINED, DIAGNOSIS_REQUESTED, DIAGNOSIS_RESULT, PROPOSED, APPROVAL, REDIAGNOSE,
    REVALIDATION, RECHECK, CONFIRMATION, POLICY_REJECTED, IGNORED,
}

/** One append-only record (design §13). The payload is a private copy; each read is a fresh copy. */
class RecordEntry(val kind: RecordKind, payload: ObjectNode) {
    private val payloadCopy: ObjectNode = payload.deepCopy()
    val payload: ObjectNode get() = payloadCopy.deepCopy()
    override fun toString(): String = "RecordEntry($kind, $payloadCopy)"
}

/** What the DISPATCH_PENDING intent record says will be dispatched (design §5.2, §11). */
data class DispatchIntent(val candidate: Candidate, val idempotencyKey: String, val approval: Approval?)
```

- [ ] **Step 2: `EpisodeEvents.kt` 를 쓴다**

```kotlin
package koshei.episode

import java.time.Instant

/** What the runtime feeds into [transition] (design §6). Plan B2b-2b adds the dispatch and evidence events. */
sealed interface EpisodeEvent {
    /** The first symptom, delivered by signalWithStart. [instanceId] = workflow id + runId (design §7.1). */
    data class Detected(val instanceId: String, val manifestJson: String, val symptom: Symptom) : EpisodeEvent

    data class SymptomJoined(val symptom: Symptom) : EpisodeEvent

    /** The `diagnose` activity result as raw JSON text, so duplicate keys are caught ([parseDiagnosisResponse]). */
    data class DiagnosisReturned(val json: String) : EpisodeEvent

    /** The activity failed after Temporal's retries; [expired] = its ScheduleToClose ran out. */
    data class DiagnosisFailed(val expired: Boolean, val message: String?) : EpisodeEvent

    /** `decide` Update (design §7.2). [reason] is required for a rejection by the control plane; [note] is free text for the audit record only. */
    data class ApprovalDecided(
        val proposalId: String,
        val sawCandidatesVersion: String,
        val approve: Boolean,
        val by: Approver,
        val reason: RejectReason?,
        val note: String?,
    ) : EpisodeEvent

    /**
     * `revalidate` result; an activity failure arrives as UNKNOWN. [token] is the one [Command.Revalidate] carried, so a
     * late result for an earlier phase is recognisably stale.
     */
    data class Revalidated(val result: TriState, val token: Long) : EpisodeEvent

    /** `confirm` Update on UNKNOWN(PRECONDITION): the person checked the proposition for [candidateId] (design §8.3). */
    data class ConfirmedPrecondition(val candidateId: String, val holds: Boolean, val by: Approver) : EpisodeEvent

    data class DeadlineExpired(val which: Timer, val token: Long) : EpisodeEvent

    /** `takeover` Update. */
    data class TakenOver(val by: Approver) : EpisodeEvent

    /** `agentOff` broadcast signal (design §10.4). */
    data object AgentOff : EpisodeEvent

    /** `close` Update, accepted only in ESCALATED. */
    data class Closed(val by: Approver, val outcome: String) : EpisodeEvent
}

/** What [transition] asks the runtime to do, in order (design §6). Results come back as events. */
sealed interface Command {
    data class Diagnose(val request: DiagnosisRequest, val budget: DiagnosisBudget) : Command

    data object CancelDiagnosis : Command

    /** The runtime returns [token] in [EpisodeEvent.Revalidated]. */
    data class Revalidate(val candidate: Candidate, val timeoutMs: Long, val token: Long) : Command

    data class SetTimer(val which: Timer, val at: Instant, val token: Long) : Command

    data class Notify(val notice: Notice) : Command

    /** Append-only record; the runtime does not wait for it (design §8.5). */
    data class Record(val seq: Long, val entry: RecordEntry) : Command

    /** The execution-intent record of DISPATCH_PENDING; its outcome comes back as `Recorded(seq)` (plan B2b-2b). */
    data class RecordIntent(val seq: Long, val intent: DispatchIntent, val timeoutMs: Long) : Command
}
```

- [ ] **Step 3: `EpisodeHistory.kt` 를 쓴다**

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/** How an attempt ended (design §9.4 `closedAs`). */
enum class ClosedAs { REDIAGNOSE, RESOLVED, SUPERSEDED, UNKNOWN, ESCALATED }

enum class ApprovalResult { APPROVED, REJECTED, EXPIRED }

data class DiagnosisSummary(val outcome: DiagnosisOutcome, val candidateId: String?, val picked: String?)

/** [by] is null for EXPIRED; [reason] only for REJECTED. */
data class ApprovalSummary(val result: ApprovalResult, val by: ApprovalBy?, val reason: RejectReason?, val at: Instant)

/** Filled by plan B2b-2b. */
data class DispatchSummary(val result: String, val idempotencyKey: String, val approverKind: String?, val delivered: Boolean?)

/** Filled by plan B2b-2b. */
data class EvidenceSummary(val grade: String, val outcome: String)

/**
 * One closed attempt, as the diagnosis request's `history` carries it (contract 0.6 §3.4, design §9.4). All eight keys
 * are always written, in this order; a stage that was never reached is `null`, never omitted (contract §2).
 */
data class HistoryEntry(
    val attempt: Int,
    val candidatesVersion: String,
    val diagnosis: DiagnosisSummary?,
    val approval: ApprovalSummary?,
    val dispatch: DispatchSummary?,
    val evidence: EvidenceSummary?,
    val closedAs: ClosedAs,
    val at: Instant,
) {
    fun toJson(): ObjectNode = mapper.createObjectNode().apply {
        put("attempt", attempt)
        put("candidatesVersion", candidatesVersion)
        if (diagnosis == null) putNull("diagnosis") else putObject("diagnosis").apply {
            put("outcome", diagnosis.outcome.name)
            put("candidateId", diagnosis.candidateId)
            put("picked", diagnosis.picked)
        }
        if (approval == null) putNull("approval") else putObject("approval").apply {
            put("result", approval.result.name)
            put("by", approval.by?.name)
            put("reason", approval.reason?.name)
            put("at", approval.at.toString())
        }
        if (dispatch == null) putNull("dispatch") else putObject("dispatch").apply {
            put("result", dispatch.result)
            put("idempotencyKey", dispatch.idempotencyKey)
            put("approverKind", dispatch.approverKind)
            if (dispatch.delivered == null) putNull("delivered") else put("delivered", dispatch.delivered)
        }
        if (evidence == null) putNull("evidence") else putObject("evidence").apply {
            put("grade", evidence.grade)
            put("outcome", evidence.outcome)
        }
        put("closedAs", closedAs.name)
        put("at", at.toString())
    }
}
```

- [ ] **Step 4: `EpisodeState.kt` 를 쓴다**

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/** The attempt in progress (design §9.4). Closed attempts live in [EpisodeState.history]. */
@ConsistentCopyVisibility
data class Attempt internal constructor(
    val number: Int,
    val request: DiagnosisRequest,
    /** How many symptoms this attempt's snapshot saw; any later one "joined after the diagnosis" (design §5.2). */
    val symptomCount: Int,
    val verdict: DiagnosisVerdict? = null,
    val proposedAt: Instant? = null,
    val approval: Approval? = null,
    /** The seq of this attempt's execution-intent record, once DISPATCH_PENDING was entered. */
    val intentSeq: Long? = null,
) {
    /** design §7.2: instance id + attempt number. */
    val proposalId: String get() = "${request.episodeId}#$number"

    val candidate: Candidate? get() = (verdict as? DiagnosisVerdict.Proposed)?.candidate
}

/**
 * An episode between two events (design §5). Only [startEpisode] and [transition] make one. Every phase entry takes a
 * new [token]; STATE and RECHECK timers carry it, so a timer from an earlier phase is recognisably stale.
 */
@ConsistentCopyVisibility
data class EpisodeState internal constructor(
    val instanceId: String,
    val startedAt: Instant,
    /** Null only when the policy was off at the start and the episode escalated before any deadline existed. */
    val episodeDeadline: Instant?,
    val phase: Phase,
    val enteredAt: Instant,
    val token: Long,
    /** The current phase's own deadline (its STATE timer), if it has one. */
    val stateDeadline: Instant?,
    val escalation: Escalation?,
    val manifestJson: String,
    /** Opening symptom first, then joined ones in arrival order (design §9.0). */
    val symptoms: List<Symptom>,
    val seenEventIds: Set<String>,
    val attempt: Attempt?,
    val history: List<HistoryEntry>,
    /** Dispatches per candidateId in this episode (design §5.2 REPEATED_REMEDY). Plan B2b-2b counts them. */
    val dispatchCounts: Map<String, Int>,
    /** The table in force — which is also the last valid one. In ESCALATED, only the source of the retention time. */
    val policy: PolicyTable?,
    val autoApproveSuspended: Boolean,
    val lastRejected: List<String>,
    val recordSeq: Long,
) {
    /** Every symptom so far as a snapshot (design §9.0). Throws on a malformed manifest; callers escalate. */
    internal fun snapshot(): Snapshot = Snapshot(
        mapper.readTree(manifestJson) as? ObjectNode ?: throw IllegalArgumentException("manifest is not a JSON object"),
        symptoms.filter { it.kind == SymptomKind.INCIDENT }.map { it.line },
        symptoms.filter { it.kind == SymptomKind.SEARCH }.map { it.line },
    )

    /** Unknowns right now (design §9.1), from every symptom so far. Plan B2b-2b adds episode-level ones and confirmations. */
    internal fun currentUnknowns(): List<Unknown> = deriveUnknowns(snapshot())
}
```

- [ ] **Step 5: 컴파일을 본다**

Run: `./gradlew --no-daemon :episode:compileKotlin`
Expected: `BUILD SUCCESSFUL`. (`startEpisode` · `transition` 은 아직 없어 KDoc 링크만 가리킨다 — 컴파일에는 영향이 없다.)

- [ ] **Step 6: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/EpisodeModel.kt episode/src/main/kotlin/koshei/episode/EpisodeEvents.kt episode/src/main/kotlin/koshei/episode/EpisodeHistory.kt episode/src/main/kotlin/koshei/episode/EpisodeState.kt
git commit -m "feat(episode): episode states, events, commands and history entries" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Chunk 2: 전이 함수

### Task 3: 시작, 전역 행, ESCALATED

**Files:**
- Create: `episode/src/main/kotlin/koshei/episode/Transition.kt`
- Create: `episode/src/main/kotlin/koshei/episode/PhaseStubs.kt`
- Create: `episode/src/test/kotlin/koshei/episode/Episodes.kt`
- Test: `episode/src/test/kotlin/koshei/episode/EpisodeLifecycleTest.kt`

- [ ] **Step 1: 시험 몰개를 쓴다**

`episode/src/test/kotlin/koshei/episode/Episodes.kt`:

```kotlin
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
```

- [ ] **Step 2: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/EpisodeLifecycleTest.kt`:

```kotlin
package koshei.episode

import koshei.episode.Episodes.INSTANCE
import koshei.episode.Episodes.OPERATOR
import koshei.episode.Episodes.T0
import koshei.episode.Episodes.incident
import koshei.episode.Episodes.open
import koshei.episode.Episodes.policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeLifecycleTest {
    @Test fun `a first symptom under a valid policy correlates, with the episode and merge-window timers`() {
        val s = open()
        assertEquals(Phase.CORRELATING, s.state.phase)
        assertEquals(T0.plusMillis(3_600_000), s.timers(Timer.EPISODE).single().at)
        assertEquals(T0.plusMillis(5_000), s.timers(Timer.STATE).single().at)
        assertEquals(s.state.token, s.timers(Timer.STATE).single().token)
        val records = s.commands.filterIsInstance<Command.Record>()
        assertEquals(listOf(RecordKind.OPENED, RecordKind.TRANSITION), records.map { it.entry.kind })
        assertEquals(listOf(1L, 2L), records.map { it.seq })
        assertTrue(s.records(RecordKind.TRANSITION).single().entry.payload.get("from").isNull)
        assertNull(s.reply)
    }

    @Test fun `no policy, an expired policy or the agent layer off escalate at once and still close in time`() {
        val missing = open(read = PolicyRead.Missing)
        assertEquals(Phase.ESCALATED, missing.state.phase)
        assertEquals(EscalationReason.POLICY_MISSING, missing.reason)
        assertEquals(T0.plusMillis(FALLBACK_ESCALATED_RETENTION_MS), missing.timers(Timer.RETENTION).single().at)
        assertTrue(missing.timers(Timer.EPISODE).isEmpty())
        assertEquals(NoticeKind.ESCALATED, missing.notices().single().kind)

        val off = open(read = policy { put("agentLayerEnabled", false) })
        assertEquals(EscalationReason.AGENT_LAYER_OFF, off.reason)
        assertEquals(T0.plusMillis(86_400_000), off.timers(Timer.RETENTION).single().at)

        assertEquals(EscalationReason.POLICY_EXPIRED, open(read = policy { put("expiresAt", "2026-09-30T00:00:00Z") }).reason)

        // A rejected first read with no earlier table: POLICY_MISSING, and the rejection is announced from ESCALATED.
        val rejected = open(read = policy { put("bogus", 1) })
        assertEquals(EscalationReason.POLICY_MISSING, rejected.reason)
        assertEquals(Phase.ESCALATED, rejected.notices().single { it.kind == NoticeKind.POLICY_REJECTED }.phase)
    }

    @Test fun `a malformed opening line or manifest escalates at once`() {
        val bad = Symptom("search:bad", SymptomKind.SEARCH, mapper.createObjectNode().put("outcome", "FOUND"), T0)
        val line = open(bad)
        assertEquals(Escalation(EscalationReason.DIAGNOSIS_FAILED, "opening symptom: search line without searchId"), line.state.escalation)
        val manifest = startEpisode(EpisodeEvent.Detected(INSTANCE, "{\"schemaVersion\":\"4\"}", Episodes.search("search-1")), policy(), T0)
        assertEquals(EscalationReason.DIAGNOSIS_FAILED, manifest.reason)
        assertTrue(manifest.state.escalation!!.detail!!.startsWith("snapshot:"))
    }

    @Test fun `a table that expires mid-episode stops decisions even without a fresh read`() {
        val s = open(read = policy { put("expiresAt", "2026-10-01T00:00:10Z") })
        assertEquals(Phase.CORRELATING, s.state.phase)
        val later = s.on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(10))
        assertEquals(EscalationReason.POLICY_EXPIRED, later.reason)
    }

    @Test fun `a joined symptom is kept once and a duplicate eventId is only recorded`() {
        val joined = open().on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(1))
        assertEquals(listOf("search:search-1", "incident:incident-1"), joined.state.symptoms.map { it.eventId })
        assertEquals(1, joined.records(RecordKind.SYMPTOM_JOINED).size)
        val again = joined.on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(2))
        assertEquals(2, again.state.symptoms.size)
        assertEquals(1, again.records(RecordKind.IGNORED).size)
        assertEquals(Phase.CORRELATING, again.state.phase)
    }

    @Test fun `a line without its key is not joined`() {
        val bad = Symptom("search:bad", SymptomKind.SEARCH, mapper.createObjectNode().put("outcome", "FOUND"), T0)
        val s = open().on(EpisodeEvent.SymptomJoined(bad), T0.plusSeconds(1))
        assertEquals(1, s.state.symptoms.size)
        assertEquals("search line without searchId", s.records(RecordKind.IGNORED).single().entry.payload.get("why").textValue())
    }

    @Test fun `takeover escalates and is accepted`() {
        val s = open().on(EpisodeEvent.TakenOver(OPERATOR), T0.plusSeconds(1))
        assertEquals(Phase.ESCALATED, s.state.phase)
        assertEquals(Escalation(EscalationReason.TAKEN_OVER, "by op-1"), s.state.escalation)
        assertEquals(Reply.ACCEPTED, s.reply)
        assertEquals(T0.plusSeconds(1).plusMillis(86_400_000), s.timers(Timer.RETENTION).single().at)
    }

    @Test fun `the episode deadline and the agent-off broadcast escalate`() {
        val expired = open().on(EpisodeEvent.DeadlineExpired(Timer.EPISODE, EPISODE_TOKEN), T0.plusMillis(3_600_000))
        assertEquals(EscalationReason.EPISODE_EXPIRED, expired.reason)
        assertEquals(EscalationReason.AGENT_LAYER_OFF, open().on(EpisodeEvent.AgentOff, T0.plusSeconds(1)).reason)
    }

    @Test fun `a timer from an earlier phase is ignored`() {
        val s = open()
        val stale = s.on(EpisodeEvent.DeadlineExpired(Timer.STATE, s.state.token - 1), T0.plusSeconds(5))
        assertEquals(Phase.CORRELATING, stale.state.phase)
        assertEquals("stale timer", stale.records(RecordKind.IGNORED).single().entry.payload.get("why").textValue())
    }

    @Test fun `a policy read that is off, missing or expired escalates mid-episode`() {
        val off = open().on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(1), read = policy { put("agentLayerEnabled", false) })
        assertEquals(EscalationReason.AGENT_LAYER_OFF, off.reason)
        assertEquals(2, off.state.symptoms.size)   // the symptom is still kept
        val missing = open().on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(1), read = PolicyRead.Missing)
        assertEquals(EscalationReason.POLICY_MISSING, missing.reason)
    }

    @Test fun `a rejected policy read is announced once and suspends auto-approval until a valid read`() {
        val bad = policy { put("bogus", 1) }
        val first = open().on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(1), read = bad)
        assertEquals(Phase.CORRELATING, first.state.phase)
        assertTrue(first.state.autoApproveSuspended)
        assertEquals(NoticeKind.POLICY_REJECTED, first.notices().single().kind)
        val second = first.on(EpisodeEvent.SymptomJoined(incident("incident-2")), T0.plusSeconds(2), read = bad)
        assertTrue(second.notices().isEmpty())
        val healed = second.on(EpisodeEvent.SymptomJoined(incident("incident-4")), T0.plusSeconds(3), read = policy())
        assertFalse(healed.state.autoApproveSuspended)
    }

    @Test fun `escalated - the agent does nothing, a person closes, retention closes unattended`() {
        val escalated = open().on(EpisodeEvent.TakenOver(OPERATOR), T0.plusSeconds(1))
        val decide = escalated.on(EpisodeEvent.ApprovalDecided("$INSTANCE#1", "sha256:x", true, OPERATOR, null, null), T0.plusSeconds(2))
        assertEquals(Reply.REFUSED_NOT_AWAITING, decide.reply)
        assertEquals(Phase.ESCALATED, decide.state.phase)
        val joined = decide.on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(3))
        assertEquals(2, joined.state.symptoms.size)
        val closed = joined.on(EpisodeEvent.Closed(OPERATOR, "handled on site"), T0.plusSeconds(4))
        assertEquals(Phase.CLOSED, closed.state.phase)
        assertEquals(Reply.ACCEPTED, closed.reply)
        assertEquals(EscalationReason.TAKEN_OVER, closed.reason)   // the reason survives the close

        val retention = escalated.timers(Timer.RETENTION).single()
        val stale = escalated.on(EpisodeEvent.DeadlineExpired(Timer.RETENTION, retention.token - 1), retention.at)
        assertEquals(Phase.ESCALATED, stale.state.phase)
        val unattended = escalated.on(EpisodeEvent.DeadlineExpired(Timer.RETENTION, retention.token), retention.at)
        assertEquals(Phase.CLOSED, unattended.state.phase)
        assertEquals("UNATTENDED", unattended.records(RecordKind.TRANSITION).single().entry.payload.get("detail").textValue())
    }

    @Test fun `close outside ESCALATED and anything after the end are refused as values`() {
        assertEquals(Reply.REFUSED_NOT_ESCALATED, open().on(EpisodeEvent.Closed(OPERATOR, "x"), T0.plusSeconds(1)).reply)
        val closed = open().on(EpisodeEvent.TakenOver(OPERATOR), T0.plusSeconds(1)).on(EpisodeEvent.Closed(OPERATOR, "x"), T0.plusSeconds(2))
        assertEquals(Reply.REFUSED_CLOSED, closed.on(EpisodeEvent.TakenOver(OPERATOR), T0.plusSeconds(3)).reply)
        assertEquals(Reply.REFUSED_CLOSED, closed.on(EpisodeEvent.Closed(OPERATOR, "y"), T0.plusSeconds(3)).reply)
        val late = closed.on(EpisodeEvent.SymptomJoined(incident("incident-1")), T0.plusSeconds(3))
        assertNull(late.reply)
        assertEquals(1, late.state.symptoms.size)
        assertEquals(1, late.records(RecordKind.IGNORED).size)
    }
}
```

- [ ] **Step 3: 실패를 본다**

Run: `./gradlew --no-daemon :episode:test --tests 'koshei.episode.EpisodeLifecycleTest'`
Expected: 컴파일 실패 — `Unresolved reference 'startEpisode'` (그리고 `transition`, `Step`, `EPISODE_TOKEN`).

- [ ] **Step 4: `Transition.kt` 를 쓴다**

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/** One transition's result: the next state, the commands to run in order, and the reply if the event was an Update. */
data class Step(val state: EpisodeState, val commands: List<Command>, val reply: Reply? = null)

/** EPISODE timers are set once and never go stale. */
const val EPISODE_TOKEN: Long = -1

/**
 * Opens an episode on its first symptom (design §5.2 start rows, §7.1). [read] is what `readPolicy` found at the start.
 * The policy off, missing or expired → ESCALATED at once, with a retention timer; otherwise CORRELATING for the merge
 * window, with the whole-episode timer.
 */
fun startEpisode(event: EpisodeEvent.Detected, read: PolicyRead, now: Instant): Step {
    val out = Out(
        EpisodeState(
            instanceId = event.instanceId, startedAt = now, episodeDeadline = null, phase = Phase.CORRELATING,
            enteredAt = now, token = 0, stateDeadline = null, escalation = null, manifestJson = event.manifestJson,
            symptoms = listOf(event.symptom), seenEventIds = setOf(event.symptom.eventId), attempt = null,
            history = emptyList(), dispatchCounts = emptyMap(), policy = null, autoApproveSuspended = false,
            lastRejected = emptyList(), recordSeq = 0,
        ),
    )
    out.record(RecordKind.OPENED) {
        put("instanceId", event.instanceId)
        put("eventId", event.symptom.eventId)
        put("kind", event.symptom.kind.name)
    }
    val resolution = resolvePolicy(read, null, now)
    out.applyPolicy(resolution, announce = false)
    when (resolution) {
        is PolicyResolution.Off -> out.escalate(now, Escalation(resolution.reason.escalation()))
        is PolicyResolution.Active -> {
            // A line or manifest that can never become a snapshot fails now, not after the merge window.
            val problem = event.symptom.problem()?.let { "opening symptom: $it" }
                ?: try { out.state.snapshot(); null } catch (e: Exception) { "snapshot: ${e.message}" }
            if (problem != null) out.escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, problem))
            else {
                val deadline = episodeDeadline(now, resolution.policy)
                out.state = out.state.copy(episodeDeadline = deadline)
                out.commands += Command.SetTimer(Timer.EPISODE, deadline, EPISODE_TOKEN)
                out.enter(Phase.CORRELATING, now)
                out.stateTimer(now, resolution.policy.deadlines.correlatingMs)
            }
        }
    }
    // Announced once the phase is settled, so the notice names the phase the episode is actually in.
    out.announceRejected(resolution.rejectedErrors)
    return out.step()
}

/**
 * The pure transition function (design §5.2, §6). [read] is a fresh `readPolicy` result when the runtime read the
 * policy for this decision, or null to keep the table in force. It never throws on an event that does not apply: the
 * event is recorded and, for an Update, refused with a value.
 */
fun transition(state: EpisodeState, event: EpisodeEvent, read: PolicyRead?, now: Instant): Step {
    val out = Out(state, opening = false)
    if (state.phase.terminal) {
        out.ignore(event, "episode is ${state.phase}")
        out.reply = if (event.isUpdate()) Reply.REFUSED_CLOSED else null
        return out.step()
    }
    if (read != null && state.phase != Phase.ESCALATED) {
        val resolution = resolvePolicy(read, state.policy, now)
        out.applyPolicy(resolution)
        if (resolution is PolicyResolution.Off) out.escalate(now, Escalation(resolution.reason.escalation()))
    }
    // Without a fresh read the table in force still expires (design §8.2): no decision on an expired table.
    val expiresAt = out.state.policy?.expiresAt
    if (read == null && out.state.phase != Phase.ESCALATED && expiresAt != null && !now.isBefore(expiresAt))
        out.escalate(now, Escalation(EscalationReason.POLICY_EXPIRED))
    when {
        event is EpisodeEvent.SymptomJoined -> out.join(event)
        event is EpisodeEvent.Detected -> out.ignore(event, "already open")
        out.state.phase == Phase.ESCALATED -> out.onEscalated(event, now)
        event is EpisodeEvent.TakenOver -> {
            out.reply = Reply.ACCEPTED
            out.escalate(now, Escalation(EscalationReason.TAKEN_OVER, "by ${event.by.id}"))
        }
        event is EpisodeEvent.AgentOff -> out.escalate(now, Escalation(EscalationReason.AGENT_LAYER_OFF))
        event is EpisodeEvent.DeadlineExpired && event.which == Timer.EPISODE ->
            out.escalate(now, Escalation(EscalationReason.EPISODE_EXPIRED))
        event is EpisodeEvent.DeadlineExpired && event.token != out.state.token -> out.ignore(event, "stale timer")
        else -> {
            val policy = out.state.policy
            if (policy == null) out.escalate(now, Escalation(EscalationReason.POLICY_MISSING, "no table in force"))
            else out.onPhase(event, policy, now)
        }
    }
    return out.step()
}

private fun Out.onPhase(event: EpisodeEvent, policy: PolicyTable, now: Instant) = when (state.phase) {
    Phase.CORRELATING -> onCorrelating(event, policy, now)
    Phase.DIAGNOSING -> onDiagnosing(event, policy, now)
    Phase.AWAITING_APPROVAL -> onAwaitingApproval(event, policy, now)
    Phase.REVALIDATING -> onRevalidating(event, policy, now)
    Phase.UNKNOWN_PRECONDITION -> onUnknownPrecondition(event, policy, now)
    // Plan B2b-2b: DISPATCH_PENDING, DISPATCHED, AWAITING_EVIDENCE, UNKNOWN_OUTCOME.
    else -> ignore(event, "not handled in ${state.phase} yet")
}

/** ESCALATED (design §5.1): the agent takes no action; a person closes it, or its retention does. */
private fun Out.onEscalated(event: EpisodeEvent, now: Instant) {
    when {
        event is EpisodeEvent.Closed -> {
            reply = Reply.ACCEPTED
            enter(Phase.CLOSED, now, state.escalation, detail = "by ${event.by.id}: ${event.outcome}")
        }
        event is EpisodeEvent.DeadlineExpired && event.which == Timer.RETENTION && event.token == state.token ->
            enter(Phase.CLOSED, now, state.escalation, detail = "UNATTENDED")
        event is EpisodeEvent.TakenOver -> {
            reply = Reply.ACCEPTED
            ignore(event, "already with a person")
        }
        else -> ignore(event, "escalated: the agent takes no action")
    }
}

private fun Out.join(event: EpisodeEvent.SymptomJoined) {
    val symptom = event.symptom
    if (symptom.eventId in state.seenEventIds) return ignore(event, "duplicate eventId ${symptom.eventId}")
    symptom.problem()?.let { return ignore(event, it) }
    state = state.copy(symptoms = state.symptoms + symptom, seenEventIds = state.seenEventIds + symptom.eventId)
    record(RecordKind.SYMPTOM_JOINED) {
        put("eventId", symptom.eventId)
        put("kind", symptom.kind.name)
        put("observedAt", symptom.observedAt.toString())
    }
}

/**
 * Accumulates one transition. [opening] is true only inside [startEpisode], so the first TRANSITION record says it
 * came from nowhere.
 */
internal class Out(var state: EpisodeState, private var opening: Boolean = true) {
    val commands = mutableListOf<Command>()
    var reply: Reply? = null

    fun nextSeq(): Long = (state.recordSeq + 1).also { state = state.copy(recordSeq = it) }

    fun record(kind: RecordKind, payload: ObjectNode.() -> Unit = {}) {
        commands += Command.Record(nextSeq(), RecordEntry(kind, mapper.createObjectNode().apply(payload)))
    }

    fun notify(kind: NoticeKind, reason: EscalationReason? = null, detail: String? = null) {
        commands += Command.Notify(Notice(kind, state.phase, reason, detail))
    }

    /**
     * Enters [phase]: a new token (older timers go stale), no state deadline until [stateTimer] sets one. [escalation]
     * is kept on the state (ESCALATED, and CLOSED after it); [detail] overrides its detail in the record.
     */
    fun enter(phase: Phase, now: Instant, escalation: Escalation? = null, detail: String? = null) {
        val from = if (opening) null else state.phase
        opening = false
        state = state.copy(phase = phase, enteredAt = now, token = state.token + 1, stateDeadline = null, escalation = escalation)
        record(RecordKind.TRANSITION) {
            put("from", from?.name)
            put("to", phase.name)
            put("reason", escalation?.reason?.name)
            put("detail", detail ?: escalation?.detail)
        }
    }

    /** The current phase's own deadline: `min(now + budget, episode deadline)` (design §5.1). */
    fun stateTimer(now: Instant, budgetMs: Long) {
        val until = state.episodeDeadline?.let { waitUntil(now, budgetMs, it) } ?: now.plusMillis(budgetMs)
        state = state.copy(stateDeadline = until)
        commands += Command.SetTimer(Timer.STATE, until, state.token)
    }

    fun step(): Step = Step(state, commands.toList(), reply)
}

internal fun Out.ignore(event: EpisodeEvent, why: String) {
    record(RecordKind.IGNORED) {
        put("event", event::class.simpleName)
        put("phase", state.phase.name)
        put("why", why)
        // A late diagnosis is still the model's answer: keep it verbatim (design §13).
        if (event is EpisodeEvent.DiagnosisReturned) put("json", event.json)
    }
    if (reply == null) reply = event.refusal()
}

/**
 * ESCALATED (design §5.1): cancels a diagnosis in flight unless the escalation IS the diagnosis result, closes the
 * attempt into the history, and starts the retention clock from now (not from the episode deadline).
 */
internal fun Out.escalate(now: Instant, escalation: Escalation, approval: ApprovalSummary? = null, cancelDiagnosis: Boolean = true) {
    if (cancelDiagnosis && state.phase == Phase.DIAGNOSING) commands += Command.CancelDiagnosis
    closeAttempt(ClosedAs.ESCALATED, now, approval)
    enter(Phase.ESCALATED, now, escalation)
    commands += Command.SetTimer(Timer.RETENTION, escalatedUntil(now, state.policy), state.token)
    notify(NoticeKind.ESCALATED, escalation.reason, escalation.detail)
}

/** Moves the attempt in progress into the history (design §9.4). [approval] overrides what the attempt itself knows. */
internal fun Out.closeAttempt(closedAs: ClosedAs, now: Instant, approval: ApprovalSummary? = null) {
    val a = state.attempt ?: return
    val approvalSummary = approval ?: a.approval?.let { ApprovalSummary(ApprovalResult.APPROVED, it.by, null, it.at) }
    val entry = HistoryEntry(a.number, a.request.candidatesVersion, a.diagnosisSummary(), approvalSummary, null, null, closedAs, now)
    state = state.copy(attempt = null, history = state.history + entry)
}

/** Takes the resolved table into the state. [announce] = false defers the POLICY_REJECTED notice to [announceRejected]. */
internal fun Out.applyPolicy(resolution: PolicyResolution, announce: Boolean = true) {
    state = when (resolution) {
        is PolicyResolution.Active -> state.copy(policy = resolution.policy, autoApproveSuspended = resolution.autoApproveSuspended)
        is PolicyResolution.Off -> state.copy(policy = resolution.policy ?: state.policy)
    }
    if (announce) announceRejected(resolution.rejectedErrors)
}

/** Records and announces a rejected policy read once per distinct error list (design §8.2 POLICY_REJECTED). */
internal fun Out.announceRejected(errors: List<String>) {
    if (errors.isNotEmpty() && errors != state.lastRejected) {
        record(RecordKind.POLICY_REJECTED) { putArray("errors").apply { errors.forEach { add(it) } } }
        notify(NoticeKind.POLICY_REJECTED, detail = errors.joinToString("; "))
    }
    state = state.copy(lastRejected = errors)
}

private fun Attempt.diagnosisSummary(): DiagnosisSummary? = when (val v = verdict) {
    null -> null
    is DiagnosisVerdict.Proposed -> DiagnosisSummary(DiagnosisOutcome.RECOMMENDED, v.candidate.candidateId, null)
    is DiagnosisVerdict.Escalate -> v.response?.let { DiagnosisSummary(it.outcome, it.candidateId, it.picked) }
}

private fun PolicyOffReason.escalation(): EscalationReason = when (this) {
    PolicyOffReason.POLICY_MISSING -> EscalationReason.POLICY_MISSING
    PolicyOffReason.POLICY_EXPIRED -> EscalationReason.POLICY_EXPIRED
    PolicyOffReason.AGENT_LAYER_OFF -> EscalationReason.AGENT_LAYER_OFF
}

private fun EpisodeEvent.isUpdate(): Boolean =
    this is EpisodeEvent.ApprovalDecided || this is EpisodeEvent.ConfirmedPrecondition ||
        this is EpisodeEvent.TakenOver || this is EpisodeEvent.Closed

/** The value an Update gets when its event does not apply here (design §7.2). Signals get none. */
private fun EpisodeEvent.refusal(): Reply? = when (this) {
    is EpisodeEvent.ApprovalDecided -> Reply.REFUSED_NOT_AWAITING
    is EpisodeEvent.ConfirmedPrecondition -> Reply.REFUSED_SUBJECT_NOT_UNKNOWN
    is EpisodeEvent.Closed -> Reply.REFUSED_NOT_ESCALATED
    else -> null
}
```

- [ ] **Step 5: `PhaseStubs.kt` 를 쓴다**

```kotlin
package koshei.episode

import java.time.Instant

// Placeholders: Task 4 of plan B2b-2a replaces the first three, Task 5 the last two and deletes this file.
internal fun Out.onCorrelating(event: EpisodeEvent, policy: PolicyTable, now: Instant) = ignore(event, "not implemented")
internal fun Out.onDiagnosing(event: EpisodeEvent, policy: PolicyTable, now: Instant) = ignore(event, "not implemented")
internal fun Out.onAwaitingApproval(event: EpisodeEvent, policy: PolicyTable, now: Instant) = ignore(event, "not implemented")
internal fun Out.onRevalidating(event: EpisodeEvent, policy: PolicyTable, now: Instant) = ignore(event, "not implemented")
internal fun Out.onUnknownPrecondition(event: EpisodeEvent, policy: PolicyTable, now: Instant) = ignore(event, "not implemented")
```

- [ ] **Step 6: 통과를 본다**

Run: `./gradlew --no-daemon :episode:test --tests 'koshei.episode.EpisodeLifecycleTest'`
Expected: `BUILD SUCCESSFUL`, 13 tests. (쓰지 않는 인자 경고는 자리표시 때문이고 Task 5 에서 사라진다.)

- [ ] **Step 7: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/Transition.kt episode/src/main/kotlin/koshei/episode/PhaseStubs.kt episode/src/test/kotlin/koshei/episode/Episodes.kt episode/src/test/kotlin/koshei/episode/EpisodeLifecycleTest.kt
git commit -m "feat(episode): transition function - start, global rows and ESCALATED" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 4: 진단 · 제안 · 승인

**Files:**
- Create: `episode/src/main/kotlin/koshei/episode/Diagnosing.kt`
- Modify: `episode/src/main/kotlin/koshei/episode/PhaseStubs.kt` (첫 세 줄 삭제)
- Test: `episode/src/test/kotlin/koshei/episode/DiagnosingTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/DiagnosingTest.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import koshei.episode.Episodes.CONFIRM_DONE
import koshei.episode.Episodes.INSTANCE
import koshei.episode.Episodes.OPERATOR
import koshei.episode.Episodes.REMEDY
import koshei.episode.Episodes.T0
import koshei.episode.Episodes.incident
import koshei.episode.Episodes.open
import koshei.episode.Episodes.policy
import koshei.episode.Episodes.policyV1
import koshei.episode.Episodes.search
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosingTest {
    @Test fun `the merge window closes into the first diagnosis`() {
        val s = open().expireState()
        assertEquals(Phase.DIAGNOSING, s.state.phase)
        val d = s.commands.filterIsInstance<Command.Diagnose>().single()
        assertEquals(1, d.request.attempt)
        assertEquals(INSTANCE, d.request.episodeId)
        assertEquals(listOf(REMEDY, "ESCALATE"), d.request.candidates.map { it.candidateId })
        assertEquals(1_200_000, d.budget.scheduleToCloseMs)
        assertTrue(s.timers(Timer.STATE).isEmpty())   // the activity's own ScheduleToClose is DIAGNOSING's deadline
        assertEquals(1, s.records(RecordKind.DIAGNOSIS_REQUESTED).size)
    }

    @Test fun `a WITHHELD search goes to a person without a diagnosis`() {
        val s = open(search("search-3")).expireState()
        assertEquals(EscalationReason.WITHHELD_HUMAN_FIRST, s.reason)
        assertTrue(s.commands.none { it is Command.Diagnose })
        assertTrue(s.state.history.isEmpty())
    }

    @Test fun `a snapshot over the policy limit is not sent`() {
        val s = open(read = policy { put("snapshotMaxBytes", 100) }).expireState()
        assertEquals(EscalationReason.SNAPSHOT_TOO_LARGE, s.reason)
        assertTrue(s.commands.none { it is Command.Diagnose })
    }

    @Test fun `more candidates than narrator can letter are not sent`() {
        val line = mapper.readTree(PicassoRun1.search("search-4")) as ObjectNode
        line.putArray("alternatives").apply { (1..27).forEach { add("SEQ-IN-%02d.BIN-Z".format(it)) } }
        val s = open(Symptom("search:wide", SymptomKind.SEARCH, line, T0)).expireState()
        assertEquals(Escalation(EscalationReason.CANDIDATES_TOO_MANY, "27 candidates besides ESCALATE"), s.state.escalation)
        assertTrue(s.commands.none { it is Command.Diagnose })
    }

    @Test fun `a clean recommendation under an auto-approving policy goes to revalidation`() {
        val s = open().expireState().answer()
        assertEquals(Phase.REVALIDATING, s.state.phase)
        assertEquals("POLICY", s.records(RecordKind.PROPOSED).single().entry.payload.get("route").textValue())
        assertEquals(ApprovalBy.POLICY, s.state.attempt!!.approval!!.by)
        val revalidate = s.commands.filterIsInstance<Command.Revalidate>().single()
        assertEquals(REMEDY, revalidate.candidate.candidateId)
        assertEquals(30_000, revalidate.timeoutMs)
        assertEquals(1, s.records(RecordKind.PROPOSED).size)
        assertEquals(1, s.records(RecordKind.APPROVAL).size)
    }

    @Test fun `policy v1 sends every recommendation to a person`() {
        val diagnosing = open(read = policyV1()).expireState()
        val s = diagnosing.answer()
        assertEquals(Phase.AWAITING_APPROVAL, s.state.phase)
        assertEquals("AWAITING_APPROVAL", s.records(RecordKind.PROPOSED).single().entry.payload.get("route").textValue())
        assertEquals(diagnosing.state.enteredAt.plusSeconds(60).plusMillis(300_000), s.timers(Timer.STATE).single().at)
        assertEquals(Notice(NoticeKind.APPROVAL_NEEDED, Phase.AWAITING_APPROVAL, null, REMEDY), s.notices().single())
    }

    @Test fun `no auto-approval while suspended, for an unclean answer, or for a skill off the allowlist`() {
        assertEquals(Phase.AWAITING_APPROVAL, open().expireState().answer(read = policy { put("bogus", 1) }).state.phase)
        assertEquals(Phase.AWAITING_APPROVAL, open().expireState().answer { putNull("rationale") }.state.phase)
        val otherSkill = policy { ((get("autoApprove") as ObjectNode).get("APPROVE_REMEDY") as ObjectNode).putArray("skills").add("weld") }
        assertEquals(Phase.AWAITING_APPROVAL, open(read = otherSkill).expireState().answer().state.phase)
    }

    @Test fun `every non-proposal verdict escalates with its own reason and closes the attempt`() {
        val cases: Map<EscalationReason, Step.() -> Step> = mapOf(
            EscalationReason.NO_GROUNDS to { answer("NO_GROUNDS", candidateId = null) },
            EscalationReason.UNCITED to { answer("UNCITED", candidateId = null) },
            EscalationReason.ESCALATE_RECOMMENDED to { answer(candidateId = "ESCALATE") },
            EscalationReason.OUT_OF_CANDIDATES to { answer("OUT_OF_CANDIDATES", candidateId = null, edit = { put("picked", "B-07") }) },
            EscalationReason.DIAGNOSIS_FAILED to { answer { putArray("citations") } },   // RECOMMENDED with no verified citation
        )
        for ((reason, act) in cases) {
            val s = open().expireState().act()
            assertEquals(reason, s.reason, "$reason")
            assertTrue(s.commands.none { it is Command.CancelDiagnosis }, "$reason")
            val entry = s.state.history.single().toJson()
            assertEquals("ESCALATED", entry.get("closedAs").textValue(), "$reason")
            assertTrue(entry.get("dispatch").isNull, "$reason")
        }
        val out = open().expireState().answer("OUT_OF_CANDIDATES", candidateId = null, edit = { put("picked", "B-07") })
        assertEquals("picked: B-07", out.state.escalation!!.detail)
        assertEquals("B-07", out.state.history.single().toJson().get("diagnosis").get("picked").textValue())
    }

    @Test fun `an unreadable answer and a failed or expired activity escalate`() {
        val d = open().expireState()
        val unreadable = d.on(EpisodeEvent.DiagnosisReturned("{"), d.state.enteredAt.plusSeconds(1))
        assertEquals(EscalationReason.DIAGNOSIS_FAILED, unreadable.reason)
        assertTrue(unreadable.state.history.single().toJson().get("diagnosis").isNull)
        val expired = d.on(EpisodeEvent.DiagnosisFailed(expired = true, message = null), d.state.enteredAt.plusSeconds(1200))
        assertEquals(EscalationReason.DIAGNOSIS_EXPIRED, expired.reason)
        val quota = d.on(EpisodeEvent.DiagnosisFailed(expired = false, message = "quota"), d.state.enteredAt.plusSeconds(1))
        assertEquals(Escalation(EscalationReason.DIAGNOSIS_FAILED, "quota"), quota.state.escalation)
        assertTrue(quota.commands.none { it is Command.CancelDiagnosis })
    }

    @Test fun `takeover during a diagnosis cancels it, and a late answer is kept verbatim`() {
        val d = open().expireState()
        val s = d.on(EpisodeEvent.TakenOver(OPERATOR), T0.plusSeconds(10))
        assertTrue(s.commands.first() is Command.CancelDiagnosis)
        assertEquals(EscalationReason.TAKEN_OVER, s.reason)
        val json = Responses.forRequest(d.request).toString()
        val late = s.on(EpisodeEvent.DiagnosisReturned(json), T0.plusSeconds(20))
        assertEquals(Phase.ESCALATED, late.state.phase)
        assertEquals(json, late.records(RecordKind.IGNORED).single().entry.payload.get("json").textValue())
    }

    @Test fun `an operator decision is a person task without an approval step`() {
        val s = open(incident("incident-6")).expireState().answer()
        assertEquals(CONFIRM_DONE, s.state.attempt!!.candidate!!.candidateId)
        assertEquals(Phase.REVALIDATING, s.state.phase)
        assertNull(s.state.attempt!!.approval)
        assertTrue(s.records(RecordKind.APPROVAL).isEmpty())
        assertEquals("PERSON_TASK", s.records(RecordKind.PROPOSED).single().entry.payload.get("route").textValue())
    }

    @Test fun `a remedy dispatched as often as the threshold escalates as REPEATED_REMEDY`() {
        val d = open().expireState()
        val primed = Step(d.state.copy(dispatchCounts = mapOf(REMEDY to 2)), emptyList())
        assertEquals(EscalationReason.REPEATED_REMEDY, primed.answer().reason)
    }

    @Test fun `the proposal a person saw is approved - a stale one is refused as a value`() {
        val waiting = open(read = policyV1()).expireState().answer()
        val version = waiting.request.candidatesVersion
        val staleId = waiting.on(EpisodeEvent.ApprovalDecided("$INSTANCE#0", version, true, OPERATOR, null, null), T0.plusSeconds(90))
        assertEquals(Reply.REFUSED_STALE, staleId.reply)
        assertEquals(Phase.AWAITING_APPROVAL, staleId.state.phase)
        val staleVersion = waiting.on(EpisodeEvent.ApprovalDecided("$INSTANCE#1", "sha256:" + "0".repeat(64), true, OPERATOR, null, null), T0.plusSeconds(90))
        assertEquals(Reply.REFUSED_STALE, staleVersion.reply)
        val ok = waiting.on(EpisodeEvent.ApprovalDecided("$INSTANCE#1", version, true, OPERATOR, null, null), T0.plusSeconds(90))
        assertEquals(Reply.ACCEPTED, ok.reply)
        assertEquals(Phase.REVALIDATING, ok.state.phase)
        assertEquals(Approval(ApprovalBy.PERSON, OPERATOR, T0.plusSeconds(90)), ok.state.attempt!!.approval)
    }

    @Test fun `a rejection re-diagnoses and hands narrator the closed attempt`() {
        val waiting = open(read = policyV1()).expireState().answer()
        val s = waiting.on(
            EpisodeEvent.ApprovalDecided("$INSTANCE#1", waiting.request.candidatesVersion, false, OPERATOR, RejectReason.WRONG_TARGET, "wrong robot"),
            T0.plusSeconds(90),
        )
        assertEquals(Phase.DIAGNOSING, s.state.phase)
        val request = s.commands.filterIsInstance<Command.Diagnose>().single().request
        assertEquals(2, request.attempt)
        val entry = request.history.single()
        assertEquals(
            listOf("attempt", "candidatesVersion", "diagnosis", "approval", "dispatch", "evidence", "closedAs", "at"),
            entry.fieldNames().asSequence().toList(),
        )
        assertEquals("RECOMMENDED", entry.get("diagnosis").get("outcome").textValue())
        assertEquals(REMEDY, entry.get("diagnosis").get("candidateId").textValue())
        assertEquals("REJECTED", entry.get("approval").get("result").textValue())
        assertEquals("PERSON", entry.get("approval").get("by").textValue())
        assertEquals("WRONG_TARGET", entry.get("approval").get("reason").textValue())
        assertEquals("REDIAGNOSE", entry.get("closedAs").textValue())
        assertTrue(entry.get("dispatch").isNull && entry.get("evidence").isNull)
        assertTrue("wrong robot" !in request.toJson().toString())   // the operator's free text never reaches narrator
        val rediagnose = s.records(RecordKind.REDIAGNOSE).single().entry.payload
        assertEquals("APPROVAL_REJECTED", rediagnose.get("reason").textValue())
        assertEquals("by op-1: wrong robot", rediagnose.get("detail").textValue())
    }

    @Test fun `the approval deadline escalates with the approval recorded as expired`() {
        val s = open(read = policyV1()).expireState().answer().expireState()
        assertEquals(EscalationReason.APPROVAL_EXPIRED, s.reason)
        val approval = s.state.history.single().toJson().get("approval")
        assertEquals("EXPIRED", approval.get("result").textValue())
        assertTrue(approval.get("by").isNull)
    }

    @Test fun `attempts beyond the policy maximum escalate instead of diagnosing`() {
        val one = policyV1 { put("maxAttemptsPerEpisode", 1); put("repeatedRemedyThreshold", 1) }
        val waiting = open(read = one).expireState().answer()
        val s = waiting.on(EpisodeEvent.ApprovalDecided("$INSTANCE#1", waiting.request.candidatesVersion, false, OPERATOR, RejectReason.NOT_NOW, null), T0.plusSeconds(90))
        assertEquals(EscalationReason.ATTEMPTS_EXHAUSTED, s.reason)
        assertTrue(s.commands.none { it is Command.Diagnose })
        assertEquals(ClosedAs.REDIAGNOSE, s.state.history.single().closedAs)
    }

    @Test fun `a decision outside the approval wait is refused as not awaiting`() {
        val s = open().expireState().on(EpisodeEvent.ApprovalDecided("$INSTANCE#1", "sha256:x", true, OPERATOR, null, null), T0.plusSeconds(6))
        assertEquals(Reply.REFUSED_NOT_AWAITING, s.reply)
        assertEquals(Phase.DIAGNOSING, s.state.phase)
    }
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew --no-daemon :episode:test --tests 'koshei.episode.DiagnosingTest'`
Expected: 17개 모두 FAIL — 자리표시 때문에 CORRELATING 이 끝나지 않는다(DIAGNOSING 을 기대하는 단언이 틀리거나, `request` 를 읽다가 `no attempt in CORRELATING` 로 멈춘다). 이미 통과하는 시험이 있으면 멈추고 그 시험이 무엇을 보는지 보고한다.

- [ ] **Step 3: `Diagnosing.kt` 를 쓰고 자리표시 셋을 지운다**

`PhaseStubs.kt` 에서 이 세 줄을 지운다:

```kotlin
internal fun Out.onCorrelating(event: EpisodeEvent, policy: PolicyTable, now: Instant) = ignore(event, "not implemented")
internal fun Out.onDiagnosing(event: EpisodeEvent, policy: PolicyTable, now: Instant) = ignore(event, "not implemented")
internal fun Out.onAwaitingApproval(event: EpisodeEvent, policy: PolicyTable, now: Instant) = ignore(event, "not implemented")
```

`episode/src/main/kotlin/koshei/episode/Diagnosing.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/** narrator letters the offered candidates A..Z (narrator `diagnose/context.py` `aliases`); more cannot be offered. */
const val MAX_OFFERED_CANDIDATES = 26

internal fun Out.onCorrelating(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    if (event is EpisodeEvent.DeadlineExpired && event.which == Timer.STATE) enterDiagnosing(policy, now)
    else ignore(event, "correlating")
}

/**
 * Enters DIAGNOSING for the next attempt (design §5.2). On every entry — joined symptoms can change each of them — the
 * attempt count, a WITHHELD search line, the snapshot size and the number of offered candidates are checked; any
 * failing check escalates without calling `diagnose`.
 */
internal fun Out.enterDiagnosing(policy: PolicyTable, now: Instant) {
    val number = (state.history.maxOfOrNull { it.attempt } ?: 0) + 1
    if (number > policy.maxAttemptsPerEpisode)
        return escalate(now, Escalation(EscalationReason.ATTEMPTS_EXHAUSTED, "attempt $number > ${policy.maxAttemptsPerEpisode}"))
    val snapshot = try {
        state.snapshot()
    } catch (e: Exception) {
        return escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, "snapshot: ${e.message}"))
    }
    if (snapshot.searches.any { it.text("outcome") == "WITHHELD" })
        return escalate(now, Escalation(EscalationReason.WITHHELD_HUMAN_FIRST))
    val bytes = mapper.writeValueAsBytes(snapshot.toJson()).size
    if (bytes > policy.snapshotMaxBytes)
        return escalate(now, Escalation(EscalationReason.SNAPSHOT_TOO_LARGE, "$bytes bytes > ${policy.snapshotMaxBytes}"))
    val request = try {
        diagnosisRequest(state.instanceId, number, snapshot, history = state.history.map { it.toJson() })
    } catch (e: Exception) {
        return escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, "request: ${e.message}"))
    }
    val offered = request.candidates.count { it.kind != CandidateKind.ESCALATE }
    if (offered > MAX_OFFERED_CANDIDATES)
        return escalate(now, Escalation(EscalationReason.CANDIDATES_TOO_MANY, "$offered candidates besides ESCALATE"))
    state = state.copy(attempt = Attempt(number, request, state.symptoms.size))
    enter(Phase.DIAGNOSING, now)
    record(RecordKind.DIAGNOSIS_REQUESTED) {
        put("attempt", number)
        put("candidatesVersion", request.candidatesVersion)
    }
    commands += Command.Diagnose(request, policy.diagnosis)
}

internal fun Out.onDiagnosing(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    val attempt = state.attempt ?: return ignore(event, "no attempt in progress")
    when (event) {
        is EpisodeEvent.DiagnosisReturned -> {
            val verdict = judgeDiagnosis(attempt.request, parseDiagnosisResponse(event.json))
            state = state.copy(attempt = attempt.copy(verdict = verdict))
            record(RecordKind.DIAGNOSIS_RESULT) {
                put("attempt", attempt.number)
                put("verdict", verdict.toString())
                when (verdict) {
                    is DiagnosisVerdict.Proposed -> set<JsonNode>("response", verdict.response.raw)
                    is DiagnosisVerdict.Escalate -> {
                        set<JsonNode>("response", verdict.response?.raw ?: verdict.raw)
                        put("rawText", verdict.rawText)
                    }
                }
            }
            when (verdict) {
                is DiagnosisVerdict.Proposed -> propose(verdict, policy, now)
                is DiagnosisVerdict.Escalate -> escalate(
                    now,
                    Escalation(verdict.reason.escalation(), verdict.detail ?: verdict.picked?.let { "picked: $it" }),
                    cancelDiagnosis = false,
                )
            }
        }
        is EpisodeEvent.DiagnosisFailed -> escalate(
            now,
            Escalation(if (event.expired) EscalationReason.DIAGNOSIS_EXPIRED else EscalationReason.DIAGNOSIS_FAILED, event.message),
            cancelDiagnosis = false,
        )
        else -> ignore(event, "diagnosing")
    }
}

/**
 * PROPOSED (design §5.2), decided within the same step: a remedy dispatched too often escalates; person-task kinds
 * have no approval step; the policy auto-approves only a clean APPROVE_REMEDY on its skill allowlist, and never while
 * its auto-approval is suspended; everything else waits for a person.
 */
private fun Out.propose(proposed: DiagnosisVerdict.Proposed, policy: PolicyTable, now: Instant) {
    val candidate = proposed.candidate
    state = state.copy(attempt = state.attempt?.copy(proposedAt = now))
    val dispatched = state.dispatchCounts[candidate.candidateId] ?: 0
    val route = when {
        dispatched >= policy.repeatedRemedyThreshold -> "REPEATED_REMEDY"
        candidate.kind == CandidateKind.CHOOSE_SOURCE || candidate.kind == CandidateKind.OPERATOR_DECISION -> "PERSON_TASK"
        autoApproves(candidate, proposed.clean, policy) -> "POLICY"
        else -> "AWAITING_APPROVAL"
    }
    record(RecordKind.PROPOSED) {
        put("candidateId", candidate.candidateId)
        put("clean", proposed.clean)
        put("route", route)
    }
    when (route) {
        "REPEATED_REMEDY" -> escalate(
            now,
            Escalation(EscalationReason.REPEATED_REMEDY, "${candidate.candidateId} dispatched $dispatched times"),
            cancelDiagnosis = false,
        )
        "PERSON_TASK" -> enterRevalidating(null, policy, now)
        "POLICY" -> enterRevalidating(Approval(ApprovalBy.POLICY, null, now), policy, now)
        else -> awaitApproval(candidate, policy, now)
    }
}

internal fun Out.awaitApproval(candidate: Candidate, policy: PolicyTable, now: Instant) {
    enter(Phase.AWAITING_APPROVAL, now)
    stateTimer(now, policy.deadlines.approvalMs)
    notify(NoticeKind.APPROVAL_NEEDED, detail = candidate.candidateId)
}

/** Whether the policy in force auto-approves [candidate] right now (design §5.2 PROPOSED, §9.5, §10.1). */
internal fun Out.autoApproves(candidate: Candidate, clean: Boolean, policy: PolicyTable): Boolean {
    if (state.autoApproveSuspended || candidate.kind != CandidateKind.APPROVE_REMEDY) return false
    val rule = policy.autoApprove[candidate.kind] ?: return false
    val skills = candidate.sawSkillTypes.orEmpty()
    return rule.allowed && skills.isNotEmpty() && rule.skills.containsAll(skills) && (!rule.requireClean || clean)
}

internal fun Out.onAwaitingApproval(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    val attempt = state.attempt ?: return ignore(event, "no attempt in progress")
    when {
        event is EpisodeEvent.ApprovalDecided -> {
            if (event.proposalId != attempt.proposalId || event.sawCandidatesVersion != attempt.request.candidatesVersion) {
                reply = Reply.REFUSED_STALE
                return ignore(event, "stale: ${event.proposalId} / ${event.sawCandidatesVersion}")
            }
            reply = Reply.ACCEPTED
            if (event.approve) enterRevalidating(Approval(ApprovalBy.PERSON, event.by, now), policy, now)
            else rediagnose(
                RediagnoseReason.APPROVAL_REJECTED, policy, now,
                ApprovalSummary(ApprovalResult.REJECTED, ApprovalBy.PERSON, event.reason ?: RejectReason.OTHER, now),
                detail = listOfNotNull("by ${event.by.id}", event.note).joinToString(": "),
            )
        }
        event is EpisodeEvent.DeadlineExpired && event.which == Timer.STATE -> escalate(
            now,
            Escalation(EscalationReason.APPROVAL_EXPIRED),
            approval = ApprovalSummary(ApprovalResult.EXPIRED, null, null, now),
        )
        else -> ignore(event, "awaiting approval")
    }
}

/** Closes the attempt as REDIAGNOSE and diagnoses again (design §5.1, §5.2). */
internal fun Out.rediagnose(
    reason: RediagnoseReason,
    policy: PolicyTable,
    now: Instant,
    approval: ApprovalSummary? = null,
    detail: String? = null,
) {
    closeAttempt(ClosedAs.REDIAGNOSE, now, approval)
    record(RecordKind.REDIAGNOSE) {
        put("reason", reason.name)
        put("detail", detail)
    }
    enterDiagnosing(policy, now)
}

/** REVALIDATING: re-reads the source before anything is dispatched (design §8.3). [approval] null = person task. */
internal fun Out.enterRevalidating(approval: Approval?, policy: PolicyTable, now: Instant) {
    val attempt = state.attempt
    val candidate = attempt?.candidate
        ?: return escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, "internal: no proposed candidate"))
    state = state.copy(attempt = attempt.copy(approval = approval))
    if (approval != null) record(RecordKind.APPROVAL) {
        put("candidateId", candidate.candidateId)
        put("by", approval.by.name)
        put("approver", approval.approver?.id)
        put("assurance", approval.approver?.assurance?.name)
    }
    enter(Phase.REVALIDATING, now)
    commands += Command.Revalidate(candidate, policy.deadlines.revalidateMs, state.token)
}

private fun DiagnosisEscalation.escalation(): EscalationReason = when (this) {
    DiagnosisEscalation.ESCALATE_RECOMMENDED -> EscalationReason.ESCALATE_RECOMMENDED
    DiagnosisEscalation.NO_GROUNDS -> EscalationReason.NO_GROUNDS
    DiagnosisEscalation.UNCITED -> EscalationReason.UNCITED
    DiagnosisEscalation.OUT_OF_CANDIDATES -> EscalationReason.OUT_OF_CANDIDATES
    DiagnosisEscalation.CANDIDATES_VERSION_MISMATCH -> EscalationReason.CANDIDATES_VERSION_MISMATCH
    DiagnosisEscalation.UNKNOWN_BLOCKS_EXECUTION -> EscalationReason.UNKNOWN_BLOCKS_EXECUTION
    DiagnosisEscalation.DIAGNOSIS_FAILED -> EscalationReason.DIAGNOSIS_FAILED
}
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew --no-daemon :episode:test`
Expected: `BUILD SUCCESSFUL` — `DiagnosingTest` 17, `EpisodeLifecycleTest` 13, 모듈 전체 164 + 13 + 17 = 194.

- [ ] **Step 5: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/Diagnosing.kt episode/src/main/kotlin/koshei/episode/PhaseStubs.kt episode/src/test/kotlin/koshei/episode/DiagnosingTest.kt
git commit -m "feat(episode): diagnosing, proposal and approval transitions" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 5: 전제 재조회와 실행 의도

**Files:**
- Create: `episode/src/main/kotlin/koshei/episode/Revalidating.kt`
- Delete: `episode/src/main/kotlin/koshei/episode/PhaseStubs.kt`
- Test: `episode/src/test/kotlin/koshei/episode/RevalidatingTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/RevalidatingTest.kt`:

```kotlin
package koshei.episode

import koshei.episode.Episodes.CONFIRM_DONE
import koshei.episode.Episodes.INSTANCE
import koshei.episode.Episodes.OPERATOR
import koshei.episode.Episodes.REMEDY
import koshei.episode.Episodes.incident
import koshei.episode.Episodes.open
import koshei.episode.Episodes.search
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevalidatingTest {
    /** Auto-approved by the test policy (or a person task), revalidating one minute after DIAGNOSING began. */
    private fun revalidating(first: Symptom = search("search-1")): Step = open(first).expireState().answer()

    /** REVALIDATING was entered at the approval (or proposal) time. */
    private val Step.at: Instant get() = state.enteredAt

    private fun unknown(): Step = revalidating().let { it.revalidated(TriState.UNKNOWN, it.at.plusSeconds(1)) }

    @Test fun `a precondition that holds records the dispatch intent and nothing else`() {
        val r = revalidating()
        val s = r.revalidated(TriState.TRUE, r.at.plusSeconds(1))
        assertEquals(Phase.DISPATCH_PENDING, s.state.phase)
        val intent = s.commands.filterIsInstance<Command.RecordIntent>().single()
        assertEquals("$INSTANCE:1:APPROVE_REMEDY", intent.intent.idempotencyKey)
        assertEquals(REMEDY, intent.intent.candidate.candidateId)
        assertEquals(ApprovalBy.POLICY, intent.intent.approval!!.by)
        assertEquals(10_000, intent.timeoutMs)
        assertEquals(intent.seq, s.state.attempt!!.intentSeq)
        assertTrue(s.commands.none { it is Command.Revalidate || it is Command.Diagnose })
    }

    @Test fun `an approval past its validity is not acted on - a new diagnosis`() {
        val r = revalidating()
        val s = r.revalidated(TriState.TRUE, r.at.plusMillis(600_000))
        assertEquals(Phase.DIAGNOSING, s.state.phase)
        assertEquals(2, s.request.attempt)
        assertEquals("APPROVED", s.state.history.single().toJson().get("approval").get("result").textValue())
        assertEquals("APPROVAL_LAPSED", s.records(RecordKind.REDIAGNOSE).single().entry.payload.get("reason").textValue())
    }

    @Test fun `an unknown that appeared after the diagnosis blocks an execution candidate`() {
        val r = revalidating().let { it.on(EpisodeEvent.SymptomJoined(incident("incident-6")), it.at.plusSeconds(1)) }
        val s = r.revalidated(TriState.TRUE, r.at.plusSeconds(2))
        assertEquals(EscalationReason.UNKNOWN_BLOCKS_EXECUTION, s.reason)
        assertTrue(s.commands.none { it is Command.RecordIntent })
    }

    @Test fun `a broken precondition supersedes, unless a symptom joined since the diagnosis`() {
        val r = revalidating()
        val superseded = r.revalidated(TriState.FALSE, r.at.plusSeconds(1))
        assertEquals(Phase.SUPERSEDED, superseded.state.phase)
        assertEquals(ClosedAs.SUPERSEDED, superseded.state.history.single().closedAs)
        val joined = r.on(EpisodeEvent.SymptomJoined(search("search-2")), r.at.plusSeconds(1))
        val again = joined.revalidated(TriState.FALSE, r.at.plusSeconds(2))
        assertEquals(Phase.DIAGNOSING, again.state.phase)
        assertEquals(2, again.request.attempt)
        assertEquals(2, again.request.snapshot.searches.size)
    }

    @Test fun `an undecidable precondition waits for a recheck or a person`() {
        val r = revalidating()
        val s = r.revalidated(TriState.UNKNOWN, r.at.plusSeconds(1))
        assertEquals(Phase.UNKNOWN_PRECONDITION, s.state.phase)
        assertEquals(r.at.plusSeconds(1).plusMillis(900_000), s.timers(Timer.STATE).single().at)
        assertEquals(r.at.plusSeconds(1).plusMillis(30_000), s.timers(Timer.RECHECK).single().at)
        assertEquals(Notice(NoticeKind.CONFIRM_PRECONDITION, Phase.UNKNOWN_PRECONDITION, null, REMEDY), s.notices().single())
    }

    @Test fun `a recheck revalidates again, reschedules on UNKNOWN and records the intent on TRUE`() {
        val u = unknown()
        val recheck = u.timers(Timer.RECHECK).single()
        val asked = u.on(EpisodeEvent.DeadlineExpired(Timer.RECHECK, recheck.token), recheck.at)
        val again = asked.commands.filterIsInstance<Command.Revalidate>().single()
        assertEquals(REMEDY, again.candidate.candidateId)
        assertEquals(asked.state.token, again.token)
        assertEquals(Phase.UNKNOWN_PRECONDITION, asked.state.phase)
        val still = asked.revalidated(TriState.UNKNOWN, recheck.at.plusSeconds(1))
        assertEquals(recheck.at.plusSeconds(1).plusMillis(30_000), still.timers(Timer.RECHECK).single().at)
        val holds = still.revalidated(TriState.TRUE, recheck.at.plusSeconds(2))
        assertEquals(Phase.DISPATCH_PENDING, holds.state.phase)
    }

    @Test fun `a person's confirmation decides the precondition - another subject is refused`() {
        val u = unknown()
        val t = u.state.enteredAt.plusSeconds(10)
        val other = u.on(EpisodeEvent.ConfirmedPrecondition(CONFIRM_DONE, true, OPERATOR), t)
        assertEquals(Reply.REFUSED_SUBJECT_NOT_UNKNOWN, other.reply)
        assertEquals(Phase.UNKNOWN_PRECONDITION, other.state.phase)
        val holds = u.on(EpisodeEvent.ConfirmedPrecondition(REMEDY, true, OPERATOR), t)
        assertEquals(Reply.ACCEPTED, holds.reply)
        assertEquals(Phase.DISPATCH_PENDING, holds.state.phase)
        assertEquals("op-1", holds.records(RecordKind.CONFIRMATION).single().entry.payload.get("by").textValue())
        assertEquals("PERSON", holds.records(RecordKind.REVALIDATION).single().entry.payload.get("source").textValue())
        val broken = u.on(EpisodeEvent.ConfirmedPrecondition(REMEDY, false, OPERATOR), t)
        assertEquals(Phase.SUPERSEDED, broken.state.phase)
    }

    @Test fun `an unresolved precondition escalates at its deadline`() {
        assertEquals(EscalationReason.UNKNOWN_UNRESOLVED, unknown().expireState().reason)
    }

    @Test fun `a person task carries no approval and lapses from the proposal time`() {
        val r = revalidating(incident("incident-6"))
        val s = r.revalidated(TriState.TRUE, r.at.plusSeconds(1))
        assertEquals(Phase.DISPATCH_PENDING, s.state.phase)
        val intent = s.commands.filterIsInstance<Command.RecordIntent>().single().intent
        assertNull(intent.approval)
        assertEquals("$INSTANCE:1:OPERATOR_DECISION", intent.idempotencyKey)
        assertEquals(Phase.DIAGNOSING, r.revalidated(TriState.TRUE, r.at.plusMillis(600_000)).state.phase)
    }

    @Test fun `a person's approval counts from the approval time, not the proposal time`() {
        val waiting = open(read = Episodes.policyV1()).expireState().answer()          // proposed at T0+65s
        val approvedAt = waiting.state.enteredAt.plusSeconds(25)                       // T0+90s
        val approved = waiting.on(EpisodeEvent.ApprovalDecided("$INSTANCE#1", waiting.request.candidatesVersion, true, OPERATOR, null, null), approvedAt)
        val s = approved.revalidated(TriState.TRUE, waiting.state.enteredAt.plusMillis(600_000))   // past the proposal's window
        assertEquals(Phase.DISPATCH_PENDING, s.state.phase)
    }

    @Test fun `a late revalidation result from an earlier phase is ignored`() {
        val r = revalidating()
        val stale = r.on(EpisodeEvent.Revalidated(TriState.TRUE, r.state.token - 1), r.at.plusSeconds(1))
        assertEquals(Phase.REVALIDATING, stale.state.phase)
        assertEquals("stale revalidation", stale.records(RecordKind.IGNORED).single().entry.payload.get("why").textValue())
    }

    @Test fun `a policy approval the table no longer allows goes to a person before any intent`() {
        for (read in listOf(Episodes.policyV1(), Episodes.policy { put("bogus", 1) })) {
            val u = unknown()
            val s = u.on(EpisodeEvent.ConfirmedPrecondition(REMEDY, true, OPERATOR), u.state.enteredAt.plusSeconds(10), read)
            assertEquals(Phase.AWAITING_APPROVAL, s.state.phase)
            assertNull(s.state.attempt!!.approval)
            assertTrue(s.commands.none { it is Command.RecordIntent })
            assertTrue(s.records(RecordKind.APPROVAL).single().entry.payload.get("revoked").booleanValue())
            assertTrue(s.notices().any { it.kind == NoticeKind.APPROVAL_NEEDED })
        }
    }
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew --no-daemon :episode:test --tests 'koshei.episode.RevalidatingTest'`
Expected: 12개 모두 FAIL — 자리표시가 `Revalidated` 와 `ConfirmedPrecondition` 을 무시해 REVALIDATING 에 머문다(늦은 결과 시험은 무시 사유 `not implemented` 가 달라 FAIL).

- [ ] **Step 3: `Revalidating.kt` 를 쓰고 `PhaseStubs.kt` 를 지운다**

Run: `git rm episode/src/main/kotlin/koshei/episode/PhaseStubs.kt`

`episode/src/main/kotlin/koshei/episode/Revalidating.kt`:

```kotlin
package koshei.episode

import java.time.Instant

internal fun Out.onRevalidating(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    when {
        event is EpisodeEvent.Revalidated && event.token == state.token -> onPrecondition(event.result, "REVALIDATE", policy, now)
        event is EpisodeEvent.Revalidated -> ignore(event, "stale revalidation")
        else -> ignore(event, "revalidating")
    }
}

/**
 * UNKNOWN(PRECONDITION) (design §5.1): execution stays blocked; a periodic recheck, a person's confirmation of the
 * proposition (§8.3), or `unknownMs` ends it.
 */
internal fun Out.onUnknownPrecondition(event: EpisodeEvent, policy: PolicyTable, now: Instant) {
    val candidate = state.attempt?.candidate
        ?: return escalate(now, Escalation(EscalationReason.DIAGNOSIS_FAILED, "internal: no proposed candidate"))
    when {
        event is EpisodeEvent.Revalidated && event.token == state.token -> onPrecondition(event.result, "REVALIDATE", policy, now)
        event is EpisodeEvent.Revalidated -> ignore(event, "stale revalidation")
        event is EpisodeEvent.ConfirmedPrecondition && event.candidateId == candidate.candidateId -> {
            reply = Reply.ACCEPTED
            record(RecordKind.CONFIRMATION) {
                put("candidateId", candidate.candidateId)
                put("holds", event.holds)
                put("by", event.by.id)
                put("assurance", event.by.assurance.name)
            }
            onPrecondition(if (event.holds) TriState.TRUE else TriState.FALSE, "PERSON", policy, now)
        }
        event is EpisodeEvent.DeadlineExpired && event.which == Timer.RECHECK -> {
            record(RecordKind.RECHECK) { put("candidateId", candidate.candidateId) }
            commands += Command.Revalidate(candidate, policy.deadlines.revalidateMs, state.token)
        }
        event is EpisodeEvent.DeadlineExpired && event.which == Timer.STATE ->
            escalate(now, Escalation(EscalationReason.UNKNOWN_UNRESOLVED, "precondition of ${candidate.candidateId}"))
        else -> ignore(event, "precondition unknown")
    }
}

/**
 * One precondition verdict (design §5.2 REVALIDATING rows, §5.1 UNKNOWN(PRECONDITION) row): TRUE records the dispatch
 * intent — a person's TRUE does not revalidate again; FALSE re-diagnoses if a symptom joined after this attempt's
 * snapshot, otherwise the proposal is superseded; UNKNOWN waits.
 */
private fun Out.onPrecondition(result: TriState, source: String, policy: PolicyTable, now: Instant) {
    record(RecordKind.REVALIDATION) {
        put("result", result.name)
        put("source", source)   // REVALIDATE (the activity) or PERSON (a confirmation)
    }
    when (result) {
        TriState.TRUE -> proceedToDispatch(policy, now)
        TriState.FALSE -> {
            val attempt = state.attempt
            if (attempt != null && state.symptoms.size > attempt.symptomCount)
                rediagnose(RediagnoseReason.PRECONDITION_BROKEN, policy, now, detail = "a symptom joined after the diagnosis")
            else {
                closeAttempt(ClosedAs.SUPERSEDED, now)
                enter(Phase.SUPERSEDED, now)
            }
        }
        TriState.UNKNOWN ->
            if (state.phase == Phase.UNKNOWN_PRECONDITION) recheckTimer(policy, now)
            else {
                enter(Phase.UNKNOWN_PRECONDITION, now)
                stateTimer(now, policy.deadlines.unknownMs)
                recheckTimer(policy, now)
                notify(NoticeKind.CONFIRM_PRECONDITION, detail = state.attempt?.candidate?.candidateId)
            }
    }
}

private fun Out.recheckTimer(policy: PolicyTable, now: Instant) {
    val next = now.plusMillis(policy.deadlines.unknownRecheckMs)
    val at = state.stateDeadline?.let { minOf(next, it) } ?: next
    commands += Command.SetTimer(Timer.RECHECK, at, state.token)
}

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
    if (!approvalStillValid(basis, now, policy))
        return rediagnose(RediagnoseReason.APPROVAL_LAPSED, policy, now, detail = "valid until ${basis.plusMillis(policy.deadlines.approvalValidityMs)}")
    val clean = (attempt.verdict as? DiagnosisVerdict.Proposed)?.clean ?: false
    if (attempt.approval?.by == ApprovalBy.POLICY && !autoApproves(candidate, clean, policy)) {
        record(RecordKind.APPROVAL) {
            put("candidateId", candidate.candidateId)
            put("by", ApprovalBy.POLICY.name)
            put("revoked", true)
        }
        state = state.copy(attempt = attempt.copy(approval = null))
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

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew --no-daemon :episode:test`
Expected: `BUILD SUCCESSFUL` — 모듈 전체 194 + 12 = 206. `git status --short -uall episode/src/test/resources` 는 비어 있어야 한다(계약 예제가 바뀌지 않았다).

- [ ] **Step 5: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/Revalidating.kt episode/src/test/kotlin/koshei/episode/RevalidatingTest.kt
git commit -m "feat(episode): precondition revalidation, UNKNOWN(PRECONDITION) and the execution intent" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

(`git rm` 한 `PhaseStubs.kt` 는 이미 스테이지되어 이 커밋에 들어간다.)

### Task 6: 설계 §6 에 구현 모양을 적는다

**Files:**
- Modify: `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md`

- [ ] **Step 1: 한 단락을 더한다**

§6 의 코드 블록 바로 아래, `고정 규칙(정책 표로 못 바꾼다):` 줄 **바로 위에** 이 단락을 넣는다:

```markdown
**구현 모양 (계획 B2b-2a, 2026-10-01).** 위 블록은 설계의 스케치이고, 코드는 이렇게 섰다. 시작은
`startEpisode(Detected, read: PolicyRead, now)`, 그 뒤는 `transition(state, event, read: PolicyRead?, now)` — `read` 는 구동부가 이
결정을 위해 `readPolicy` 를 부른 결과이고 `null` 이면 지금 표를 쓴다(해석은 `resolvePolicy`). 결과 `Step` 은 다음 상태 · 명령 목록 ·
Update 의 답(`Reply`, 거절도 값)이다. PROPOSED 는 순간 상태라 `Phase` 에 없고 기록(`PROPOSED`)으로만 남는다. 기한 명령은
`SetTimer(which, at, token)` 하나로 모았다 — 상태에 들어갈 때마다 `token` 이 늘어 지난 상태의 타이머는 무시된다. 실행 의도 기록은
`RecordIntent(seq, intent, timeoutMs)` 이고, `Dispatch` 는 그 `Recorded(seq)` 뒤에만 나온다(B2b-2b). `Revalidate` 도 상태 `token` 을
싣고 `Revalidated` 가 그것을 돌려준다 — 재진단 뒤에 도착한 앞 후보의 늦은 결과가 새 후보의 전제로 읽히지 않게. 거절 사유는
`RejectReason { WRONG_TARGET, WRONG_ACTION, PRECONDITION_NOT_MET, NOT_NOW, OTHER }` 이고 이력(계약 §3.4)에는 이 값만 간다. 운영자의
자유 글(`note`)은 감사 기록에만 남는다 — narrator 가 이력을 모델의 자료 칸에 옮기므로 인증 없는 글이 프롬프트로 가지 않게 한다.
재진단 사유(기록 전용)는 `APPROVAL_REJECTED` · `APPROVAL_LAPSED` · `PRECONDITION_BROKEN`. POLICY 자동 승인은 실행 의도 직전에 지금 표로
다시 판정하고, 그 사이 자동 승인이 꺼졌거나 표가 거절돼 멈췄으면 승인을 거두고 AWAITING_APPROVAL 로 간다.
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md
git commit -m "docs: design §6 records the transition function's implemented shape" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
