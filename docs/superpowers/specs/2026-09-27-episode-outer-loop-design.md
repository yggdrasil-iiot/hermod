# 에피소드 바깥 루프 설계 — koshei 를 예외 대응의 결정적 상태기계로

- 작성 2026-09-27 · 상태: **설계 초안 0.5** (사용자 결정 넷 확정, 독립 검토 4회 반영, picasso 2026-09-30 답 반영, 열린 결정과 외부 답 대기 항목은 §19)
- 저장소 브랜치: `design/episode-outer-loop`
- 함께 읽을 것
  - 요건 점검표: `outer-loop-requirements.md` (R1~R15)
  - 구성 배경: `humanoid-ops-agent-architecture.md` (3~7절, 8.2~8.6절, 9절 표만 참고)
  - 진단 계약: narrator 저장소 `docs/superpowers/specs/2026-09-27-진단-계약-초안.md` (0.6, 제공자 narrator, 이 문서는 소비자)

---

## 1. 목적과 범위

koshei 에 **예외 한 건(에피소드)을 접수부터 종료까지 관리하는 바깥 루프**를 새로 얹는다. 바깥 루프는 결정적 상태기계이고,
LLM 진단(안쪽 루프)은 그 안의 한 상태(DIAGNOSING)에서 함수처럼 한 번 불리는 부작용 없는 액티비티다.

기존 koshei 의 계약 기반 사가(SagaWorkflow, 블록 계약, 역순 보상)는 버리지 않는다. **koshei 가 직접 하는 조치 유형의
실행체**로 자식 워크플로 자리에 남는다(§8.4, 계획 D).

### 하지 않는 것

| 하지 않는 것 | 누가 하나 |
|---|---|
| 안전 계통(긴급정지 등) | 루프 밖. 이 설계는 건드리지 않는다 |
| 즉시 대응(수 초, 결정적, LLM·승인 없음) | 앞 절반(멈추고 모른다고 말함, 운영자 보류, 자동 재실행 없음)은 picasso. 뒤 절반(수동 처리 전환, 현장 호출)은 라인 쪽 결정적 규칙, 주인 미정 (§19). 반응 시간은 담는 쪽의 `pump()` 주기 (§19 C) |
| 로봇에 직접 명령 | picasso. 바깥 루프는 picasso 승인 창구로 **이미 계산된 제안을 가리키기만** 한다 |
| picasso 를 세우는 것(담는 쪽: 인스턴스, `pump()`, 승인면) | 배치 호스트. 아직 없다(§19 C). koshei 는 소비자다 (§2) |
| 재할당(`reassign`) | 배정 정책이 부른다(picasso ADR 41). picasso 는 스스로 옮기지 않는다. 바깥 루프는 부르지 않는다 (§8.4) |
| 진단 내부(검색, 인용 검증, 모델 호출) | narrator + khala |
| 세계 상태(로봇, 재고, 서열) 보관 | 원천 시스템. 바깥 루프는 참조 id 와 관측 시각만 가진다 (R15) |
| 운영자가 절차를 그리는 것 | 하지 않는다. 절차는 플랫폼 코드, 현업은 정책 표(데이터)만 (R12) |

---

## 2. 바꾸지 않는 전제

1. **명령 원천은 picasso 하나.** 바깥 루프는 로봇을 직접 움직이지 않는다. picasso 승인 창구(`POST /approvals`, 루프백 전용)에
   제안 참조만 보낸다. 같은 (기체, 주문)의 두 번째 승인은 `NO_PROPOSAL`(같은 판이면 `REMEDY_NOT_APPLIED`)로 온다.
2. **LLM 은 조치를 기술하지 않는다.** 바깥 루프가 계산해 넘긴 후보 중 하나를 가리키거나 ESCALATE 를 고른다. 대상과 값은
   선언과 관측에서만 온다. 후보에 없는 것을 가리키면 버린다.
3. **안전 계통은 이 루프 밖이다.**
4. **koshei 는 picasso 의 소비자다. 담는 쪽이 아니다.** 소비자는 picasso 의 대장을 읽고 승인을 시도한다. picasso 는 소비자를
   부르지 않는다(picasso §3). 담는 쪽은 picasso 를 제 프로세스에 세우고 `pump()` 를 돌리고 승인면을 내며(picasso §6, §7.4), 결과
   통보도 담는 쪽이 꺼내 나른다(§12). koshei 워커 안에 picasso 를 세우지 않는다 —
   에이전트 층이 죽을 때 실행 보증도 같이 죽는다(picasso §6, R5). 담는 쪽은 아직 없고, 어디에 둘지는 배치 결정이다(§19 C).
   승인 창구는 루프백에만 붙고 신원을 인증하지 않는다(picasso §7.5 · §15.3, picasso 2026-09-30 답).

---

## 3. 확정된 결정

| # | 결정 | 근거 |
|---|---|---|
| D1 | 모듈 둘 신설: 순수 Kotlin `:episode` + Temporal 구동부 `:episode-runtime` | 판단(전이)을 시험 가능한 순수 함수로 두고, 구동부만 교체 가능하게 |
| D2 | 사람의 결정은 **Workflow Update** 로 받는다. validator 는 해독 불가·형식 오류만 막고, 낡음·거절은 handler 가 **값**으로 반환 | 값으로 돌려야 이력에 남는다 (§3.1 스파이크) |
| D3 | narrator 는 **Temporal Python 액티비티 워커**(큐 `narrator-tq`)로 붙는다 | HTTP 표면 불필요, 타임아웃·재시도·하트비트·취소를 Temporal 이 맡음, narrator 는 상태 없음 유지 |
| D4 | 자식 사가의 사람 관문(되돌릴 수 없음 **또는** `requireApprovalBefore`)은 **사람(PERSON)이 승인한 에피소드만** 대신한다. 정책 자동 승인(POLICY)은 대신하지 못한다 | "사람이 먼저 확인한다" 계약을 조용히 풀지 않기 위해 |
| D5 | drift → ot-safe-hold 경로의 `ApplyRecipe` 발사 결함(§14)은 **기록만 하고 구현 단계에서 처리** | 에피소드 경로에서는 drift 자동 디스패치를 끄므로 |

### 3.1 Update 스파이크 결과 (2026-09-27)

- 환경: koshei compose 의 `temporalio/auto-setup:1.25.2` + Java SDK 1.25.1. **별도 동적 설정 없이 동작.**
- 시험: `runtime/src/test/kotlin/koshei/runtime/spike/UpdateSpikeTest.kt` (서버가 없으면 건너뜀)
- 순서: 형식이 틀린 id(validator 거절) → 낡은 id(handler 가 `REFUSED_STALE` 반환) → 유효한 id(`APPROVED`)
- 결과: 이력 19개 이벤트 중 `UPDATE_ACCEPTED` 2, `UPDATE_COMPLETED` 2, `UPDATE_REJECTED` 0. 거절된 요청의 내용은 이력 어디에도
  남지 않았다. validator 거절은 호출자에게 `WorkflowUpdateException`(원인 `ApplicationFailure`)으로 즉시 돌아온다.
- 결론: **감사에 남아야 하는 거절은 전부 handler 가 값으로 반환한다.**

---

## 4. 구조

### 4.1 모듈

```
:episode            순수 Kotlin. Temporal·Spring·JDBC 의존 없음
  ├─ EpisodeState / EpisodeEvent / Command     상태, 사건, 구동부가 실행할 명령
  ├─ transition(state, event, policy)          결정적 전이 함수 (§6)
  ├─ project(snapshot, unknowns, catalog)      후보 투영 (§9)
  ├─ candidateId / candidatesVersion           식별자와 판 계산 (§9)
  ├─ PolicyTable + validate(policy)            정책 표 모양과 검증 (§10)
  ├─ judgeEvidence(observations, window)       값 + 시간창 판정 (R7)
  └─ correlate(event, rules) → CorrelationKey  병합 키 (R10)

:episode-runtime    Temporal 구동부. :episode, :runtime(SagaWorkflow 인터페이스, DataConverterSupport) 에 의존
  ├─ EpisodeWorkflow(Impl)                     transition 을 돌리는 얇은 구동부 (§7)
  ├─ activities: diagnose(원격, narrator-tq) · revalidate · dispatch · notify · readPolicy · record
  ├─ picasso 클라이언트 (승인 창구. 조회 표면은 없다 — 대장은 내보내기로 읽는다, §8.3)
  └─ EpisodeWatcherMain                        접수·근거 감시자, 별도 프로세스 (§12)

:app                Worker.kt 에서 EpisodeWorkflowImpl 과 액티비티 구현을 큐 koshei-episode-tq 에 등록
:authoring-api      에피소드 조회와 사람 결정 표면(Update 중계). 127.0.0.1 바인딩 (§7.2)
```

기존 경계 규칙을 그대로 따른다. `:conductor-runtime` 이 Temporal 을 모르게 분리된 것처럼, `:episode` 는 Temporal 을 모른다.
에피소드는 **Temporal 전용**이다. 운영자가 그리는 것이 아니므로 엔진 중립 IR 과 Conductor 를 거치지 않는다.

### 4.2 프로세스와 큐

| 프로세스 | 큐 / 표면 | 비고 |
|---|---|---|
| koshei 워커 (`:app`) | `koshei-episode-tq` (에피소드), `koshei-v0_1-tq` (자식 사가) | 두 큐 분리 |
| narrator 워커 (Python) | `narrator-tq` | 동시 실행은 설정값(khala 브리지 한도 이하) |
| 감시자 (`EpisodeWatcherMain`) | 접수: picasso 내보내기 디렉터리(개발용, §19 C) → signalWithStart / signal · 근거: 담는 쪽이 나른 결과 통보 → `evidence` signal (§12) | 커서는 Postgres. **운반만 한다** |
| 담는 쪽 (picasso 배치 호스트, **koshei 밖**, 아직 없음) | picasso 인스턴스와 `pump()`, 승인 창구 `POST /approvals`(루프백, 인증 없음), 결과 통보 `pending()` / `ack` | koshei 는 소비자(§2). 자리는 §19 C |
| 제어면 (`:authoring-api`) | 127.0.0.1:18090 | 조회·결정 중계만. 정책 편집은 하지 않는다 (R14) |
| 정책 편집 | git + CLI `policy validate` / `policy activate` | 런타임과 다른 프로세스·권한 (R14, 계획 D) |

---

## 5. 에피소드 상태기계

### 5.1 상태

| 상태 | 무엇을 기다리나 | 기한 (정책 표 `deadlines.*`) | 종료? |
|---|---|---|---|
| CORRELATING | 병합 창 동안 같은 원인의 증상 합류 | `correlatingMs` (병합 창) | |
| DIAGNOSING | `diagnose` 액티비티 | 액티비티 ScheduleToClose = `diagnosis.scheduleToCloseMs` | |
| PROPOSED | (즉시) 정책 판정 → AUTO_APPROVED 또는 AWAITING_APPROVAL | — (순간 상태) | |
| AWAITING_APPROVAL | 사람의 `decide` | `approvalMs` | |
| REVALIDATING | `revalidate` 액티비티 | 액티비티 ScheduleToClose = `revalidateMs` | |
| DISPATCH_PENDING | 실행 의도 `Record` 의 성공 확인 | 액티비티 ScheduleToClose = `recordMs` | |
| DISPATCHED | `dispatch` 액티비티 또는 자식 사가 결과 | 액티비티 = `dispatchMs`, 자식 = 부모 쪽 타이머 `childMaxMs` | |
| AWAITING_EVIDENCE | 완료 근거 signal — 결과 통보(JobResponse)에서 온다, 대장 스캔이 아니다 (§12). 먼저 와서 보관해 둔 근거는 들어올 때 다시 적용한다 | `evidenceMs` | |
| UNKNOWN | 재조회 판정 또는 사람의 `confirm` | 재조회 주기 `unknownRecheckMs`, 상한 `unknownMs` | |
| ESCALATED | 사람의 `close` (에이전트는 더 행동하지 않음) | `escalatedRetentionMs` | |
| RESOLVED · SUPERSEDED · CLOSED | — | — | 종료 |

ESCALATED 를 뺀 모든 대기는 `min(상태 기한, 에피소드 전체 기한 episodeMs)` 로 깨어난다. ESCALATED 는 에피소드 기한과 무관하게
들어간 순간부터 `escalatedRetentionMs` 를 센다 — `EPISODE_EXPIRED` 로 들어온 에피소드가 곧바로 닫히면 보존의 뜻이 없어지기 때문이다.
기한 없는 대기는 없다.

**ESCALATED 는 종료가 아니다.** "에이전트의 통제가 끝나고 사람이 맡음"이다. 워크플로는 열려 있고 에이전트 명령(Diagnose,
Dispatch, Revalidate)은 더 나오지 않는다. 합류 증상과 완료 근거는 **기록만** 받는다. 이미 나간 조치의 뒷정리 기록을 잇기
위해서다. 사람이 `close` 하거나 `escalatedRetentionMs` 가 지나면 CLOSED 로 끝난다(`CLOSED(UNATTENDED)`). 그래서 모든 사건 열은
유한 단계 안에 종료 상태에 닿는다(§17 속성 시험).

**UNKNOWN(확인 불가)** 은 실패와 구분되는 상태다. 실행 계열 명령을 **전부 막는다**(코드로 고정, 정책 표로 못 푼다). 무엇을
모르는지에 따라 둘로 나눈다.

| 종류 | 어디서 들어오나 | 해소 입력 (사건) | 해소 결과 |
|---|---|---|---|
| `UNKNOWN(PRECONDITION)` | REVALIDATING 이 전제를 판정 못 함(지금 기준으로 읽지 못함 포함, §8.3), `revalidate` 실패. 실시간 대장이 없는 동안 모든 실물 에피소드의 관문이다 | 주기 재조회(`Revalidated`), 사람 `ConfirmedPrecondition(holds: Boolean)` — 카드와 과업이 확인할 명제를 그대로 적는다(§8.3, §9.5) | TRUE → 승인이 유효하면 곧바로 DISPATCH_PENDING(재조회를 한 번 더 돌지 않는다), 아니면 DIAGNOSING · FALSE → 이 시도의 진단 스냅샷 뒤에 합류한 증상이 있으면 DIAGNOSING, 없으면 SUPERSEDED (§5.2 REVALIDATING FALSE 와 같은 분기) |
| `UNKNOWN(OUTCOME)` | 완료 근거가 UNKNOWN(판정 못 하는 결과 통보 포함, §12), `dispatch` 결과를 모름(재시도 소진, `NO_PROPOSAL` · `REMEDY_NOT_APPLIED` §11) | 근거 signal(`EvidenceArrived`, 결과 통보, 보관해 둔 것 포함, §5.2 의 판정 순서), 사람 `ConfirmedOutcome(DONE \| NOT_DONE)`, 상한 `unknownMs`. 주기 재조회(`OutcomeRechecked`)는 `APPROVE_REMEDY` 에 걸지 않는다 — 결과를 조회할 표면이 없다(§11). `NO_PROPOSAL` 은 재시도로 풀지 않는다 | DONE → RESOLVED · NOT_DONE → DIAGNOSING(결과를 붙여). `NO_PROPOSAL` · `REMEDY_NOT_APPLIED` 뒤의 DONE 은 「결과는 됨, 누가 했는지 모름」으로 기록한다 |

**승인이 유효하다** = 기준 시각 + `approvalValidityMs` 가 지나지 않았다. 기준 시각은 승인 시각이고, 승인 단계가 없는 사람 과업
종류는 제안 시각(PROPOSED 에 들어간 시각)이다. 후보 판은 DIAGNOSING 에서만 다시 계산되므로 승인 뒤에는 바뀌지 않는다. 유효하지
않으면 새로 진단한다.

`unknownMs` 가 지나면 ESCALATED(`UNKNOWN_UNRESOLVED`)로 넘긴다. 사람에게 넘겨도 실행 차단은 유지된다(ESCALATED 는 애초에
실행 명령을 내지 않는다).

### 5.2 전이 표

`*` 는 "종료 상태와 ESCALATED 를 뺀 모든 상태"다. 시도 번호는 DIAGNOSING 에 들어갈 때마다 1 늘고, `maxAttemptsPerEpisode` 를 넘으면
DIAGNOSING 대신 ESCALATED(`ATTEMPTS_EXHAUSTED`)로 간다.

| 현재 | 사건 | 다음 | 사유 / 비고 |
|---|---|---|---|
| (시작) | `Detected` + 정책이 꺼짐·없음·만료 | ESCALATED | `AGENT_LAYER_OFF` · `POLICY_MISSING` · `POLICY_EXPIRED` |
| (시작) | `Detected` | CORRELATING | |
| CORRELATING | 병합 창 만료 | DIAGNOSING | 스냅샷 크기 초과면 ESCALATED(`SNAPSHOT_TOO_LARGE`), ESCALATE 밖 후보가 계약 상한(26)을 넘으면 진단을 부르지 않고 ESCALATED(`CANDIDATES_TOO_MANY`). **DIAGNOSING 에 들어갈 때마다** 검사한다 — 합류 증상으로 커질 수 있다 |
| (DIAGNOSING 에 들어갈 때) | 스냅샷에 `WITHHELD` 탐색 줄이 있음 | ESCALATED | `WITHHELD_HUMAN_FIRST`. **`diagnose` 를 부르지 않고 곧바로.** 안쪽 루프의 진단이 없으므로 쿼리(§7.3)나 카드로 샐 것이 없다. 카드는 사실과 `ESCALATE` 과업만 (§9.5). §19 D 가 정해질 때까지. 들어가는 모든 경로(CORRELATING · 재진단)에서 크기·후보 수 검사와 함께 본다 |
| DIAGNOSING | `RECOMMENDED` + 후보 안 + 판 일치 + `verified` 인용 하나 이상 | PROPOSED | |
| DIAGNOSING | `RECOMMENDED` 인데 `unknowns` 있음 + 실행 계열 후보 | ESCALATED | `UNKNOWN_BLOCKS_EXECUTION` (투영 결함 신호, 알림) |
| DIAGNOSING | `RECOMMENDED` + 후보 밖 / `OUT_OF_CANDIDATES` | ESCALATED | `OUT_OF_CANDIDATES`, `picked` 기록(`null` 이어도 받는다 — 권고 표지가 없던 답), 재진단 없음. 전이는 `picked` 에 기대지 않는다 |
| DIAGNOSING | `sawCandidatesVersion` 불일치 | ESCALATED | `CANDIDATES_VERSION_MISMATCH` |
| DIAGNOSING | `RECOMMENDED(ESCALATE)` · `NO_GROUNDS` · `UNCITED` | ESCALATED | `ESCALATE_RECOMMENDED` · `NO_GROUNDS` · `UNCITED` |
| DIAGNOSING | 응답이 계약에 맞지 않음(읽기 위반 · `contractVersion` · `episodeId` · `attempt` 가 요청과 다름 · `verified` 인용 없는 `RECOMMENDED` — 계약 §4 규칙 3 은 `UNCITED` 를 요구한다) | ESCALATED | `DIAGNOSIS_FAILED`, 위반 목록과 응답 원문 기록. narrator 의 `UNCITED` 와 섞지 않는다 — 그쪽은 측정되는 결과, 이쪽은 계약 위반 |
| DIAGNOSING | 액티비티 기한 초과 / 비재시도 실패 | ESCALATED | `DIAGNOSIS_EXPIRED` / `DIAGNOSIS_FAILED` |
| DIAGNOSING | 위 행 어디에도 맞지 않는 `DiagnosisReturned` · `DiagnosisFailed` | ESCALATED | `DIAGNOSIS_FAILED`. 액티비티 결과는 표 아래의 「그대로」 규칙으로 삼키지 않는다 |
| PROPOSED | 같은 candidateId 의 **실행(Dispatch) 횟수** ≥ `repeatedRemedyThreshold` | ESCALATED | `REPEATED_REMEDY`. 승인이 아니라 실행을 센다 — 사람 과업 종류는 승인 단계가 없기 때문이다 |
| PROPOSED | 후보가 사람 과업 종류(`CHOOSE_SOURCE`, `OPERATOR_DECISION`) | REVALIDATING | 승인 단계 없음 — 과업을 받는 사람이 곧 결정한다. `approval.by = null`, 기록 `PERSON_TASK` |
| PROPOSED | 정책이 자동 승인 허용 ∧ 후보에 사람 관문 없음 ∧ (`requireClean` 이면 이유 있음 ∧ 확인 못 한 주장·인용 없는 문장 없음, §9.5) | AUTO_APPROVED → REVALIDATING | `approval.by = POLICY` |
| PROPOSED | 그 밖 | AWAITING_APPROVAL | 알림 |
| AWAITING_APPROVAL | `decide(APPROVE)` 유효 | REVALIDATING | `approval.by = PERSON` |
| AWAITING_APPROVAL | `decide(REJECT, reason)` 유효 | DIAGNOSING | `APPROVAL_REJECTED(reason)` |
| AWAITING_APPROVAL | 기한 만료 | ESCALATED | `APPROVAL_EXPIRED` |
| REVALIDATING | TRUE | DISPATCH_PENDING | 명령은 실행 의도 `Record` 하나 |
| REVALIDATING | FALSE + 이 시도의 진단 스냅샷 뒤에 합류한 증상(`SymptomJoined`) 있음 | DIAGNOSING | 전제가 깨졌고 새 증상이 있다 — 끝내지 않고 다시 본다 |
| REVALIDATING | FALSE + 합류한 증상 없음 | SUPERSEDED | 낡은 `APPROVE_REMEDY` 권고(같은 (기체, 주문)에 더 새 탐색 줄, §8.3)도 FALSE 다 |
| REVALIDATING | UNKNOWN / 액티비티 실패 | UNKNOWN(PRECONDITION) | 지금 기준으로 읽지 못한 실물 종류는 모두 여기다(§8.3) |
| REVALIDATING | 위 행 어디에도 맞지 않는 `Revalidated` | UNKNOWN(PRECONDITION) | 액티비티 실패와 같게 다룬다. 실행이 막히고(§6) 사람의 명제 확인이나 `unknownMs` 로만 나간다 — 사람 없이 조치가 나가는 길이 없는 쪽이라 이것을 고른다 |
| DISPATCH_PENDING | `Recorded` | DISPATCHED | 명령 `Dispatch`. 기록 없는 조치는 구조적으로 없다 |
| DISPATCH_PENDING | `RecordFailed` · `recordMs` 만료 | ESCALATED | `RECORD_FAILED`. 조치는 나가지 않았다 |
| DISPATCH_PENDING | 이 실행 의도와 맞지 않는 `Recorded` · `RecordFailed`(다른 `seq`) | ESCALATED | `RECORD_FAILED`. 조치를 내지 않는 쪽을 고른다 — `Dispatch` 는 이 의도의 `Recorded` 에서만 나온다 |
| DISPATCH_PENDING | `EvidenceArrived` | (그대로) | 기록만. 이 시도의 `Recorded` 앞에 처리한 것이라 근거로 치지 않는다 (§12) |
| DISPATCHED | `EvidenceArrived` | (그대로) | 기록하고 보관한다. 결과 통보는 수락과 같은 펌프에서 나오므로 답보다 먼저 올 수 있다. AWAITING_EVIDENCE · UNKNOWN(OUTCOME) 에 들어갈 때 다시 적용한다 — 내부 재적용이라 `eventId` 중복 제거(§6)에 걸리지 않는다 |
| DISPATCHED | 답의 `schemaVersion` 이 `"2"` 가 아님 · `refusal` 이 판 2 의 아는 값 넷(`NO_PROPOSAL` · `REMEDY_NOT_APPLIED` · `REFUSED_BY_GATE` · `NOT_DECLARED`) 밖 | ESCALATED | `DISPATCH_ANSWER_UNKNOWN`. **DISPATCHED 의 picasso 답 행 중 이 검사가 먼저다** (§8.4) |
| DISPATCHED | 수락(`APPROVE_REMEDY` 는 `delivered: true`) · 사람 과업 발행 · 자식 사가 완료 | AWAITING_EVIDENCE | `REMEDY_NOT_APPLIED` 는 수락이 아니다 (아래 행) |
| DISPATCHED | 승인이 수락됐는데 `delivered` 가 true 가 아님(false · null) | UNKNOWN(OUTCOME) | 전달됐는지 모른다. 재시도하지 않는다 |
| DISPATCHED | `NO_PROPOSAL` · `REMEDY_NOT_APPLIED` · 재시도 소진 · `dispatchMs` 만료 · 자식 사가 UNKNOWN 보고 | UNKNOWN(OUTCOME) | 나갔는지 모름 (§11). 둘 다 「내 앞선 시도가 이미 들어갔을 수 있음」이고 재시도로 풀지 않는다 |
| DISPATCHED | 재진단 목록(§8.4)에 있는 거절 열거값 | DIAGNOSING | `DISPATCH_REFUSED(enum)`. 목록은 지금 비어 있다 |
| DISPATCHED | 그 밖의 아는 거절 값(`REFUSED_BY_GATE` · `NOT_DECLARED`) | ESCALATED | `DISPATCH_REFUSED(enum)`. 기본값. `refusal` 열거값으로만 가르고 산문은 보지 않는다. 재진단으로 고칠 수 없는 거절을 다른 후보로 돌려 누르지 않게 한다 (§8.4) |
| DISPATCHED | 자식 사가 실패(보상 끝남) | DIAGNOSING | `CHILD_FAILED` |
| DISPATCHED | 위 행 어디에도 맞지 않는 `DispatchReturned` | ESCALATED | `DISPATCH_ANSWER_UNKNOWN`. 액티비티 결과는 「그대로」 규칙으로 삼키지 않는다 |
| AWAITING_EVIDENCE, UNKNOWN(OUTCOME) | ① `EvidenceArrived` 인데 대상과 이어지지 않음 · **이 시도의** `Recorded` 앞(앞선 시도의 늦은 통보 포함) · 이미 본 `jobResponseId` | (그대로) | 기록만. 기한(`evidenceMs` · `unknownMs`)이 출구를 지킨다 |
| AWAITING_EVIDENCE | ② `EvidenceArrived` 에 `inDoubtUnits` · `unverifiedUnits` 가 비지 않음 또는 `operatorRequired` 참 | UNKNOWN(OUTCOME) | NOT_DONE 으로 접지 않는다. 해당 단위를 `unknowns` 에도 넣는다 (§9.1) |
| AWAITING_EVIDENCE | ③ `EvidenceArrived` DONE (대상 단위가 `completedUnits` 에 있음) | RESOLVED | ① · ② 에 걸리지 않을 때만. `judgeEvidence` 가 판정 (§12) |
| AWAITING_EVIDENCE | ④ `EvidenceArrived` NOT_DONE | DIAGNOSING | 결과를 붙여. **지금 결과 통보는 이 값을 내지 않는다 — 사람만 낸다**(`ConfirmedOutcome`, 아래) |
| AWAITING_EVIDENCE, UNKNOWN(OUTCOME) | ⑤ `EvidenceArrived` 가 ① ~ ④ 어디에도 맞지 않음(예: 대상 단위가 아직 `completedUnits` 에 없음) | (그대로) | 기록만 |
| AWAITING_EVIDENCE | 기한 만료 | ESCALATED | `EVIDENCE_EXPIRED` |
| AWAITING_EVIDENCE | 실행한 대상에 대한 `ConfirmedOutcome(DONE \| NOT_DONE)` | RESOLVED / DIAGNOSING | 사람의 현물 확인은 근거로 친다. §7.2 `confirm` 이 이 상태의 실행 대상을 받는다. `CHOOSE_SOURCE` · `OPERATOR_DECISION` 이 끝나는 길이다 |
| DISPATCHED | 부모 쪽 `childMaxMs` 만료 | ESCALATED | `CHILD_OVERDUE`. 자식은 계속 돈다(ABANDON), 결과는 기록으로 받는다 |
| UNKNOWN | §5.1 표 | | |
| `*` | `takeover(by)` | ESCALATED | `TAKEN_OVER`. 진단 중이면 취소 |
| `*` | `agentOff` · 결정 시점에 읽은 정책이 꺼짐 | ESCALATED | `AGENT_LAYER_OFF`. DISPATCHED 면 결과 수신은 기록으로 계속 |
| `*` | 에피소드 기한 만료 | ESCALATED | `EPISODE_EXPIRED` |
| `*`, ESCALATED | `SymptomJoined` | (그대로) | 증상 목록에 붙이고 기록. 다음 진단의 스냅샷에 들어간다. 이미 나간 조치는 REVALIDATING 이 지킨다 |
| `*` | `ConfirmedPrecondition` · `ConfirmedOutcome` (UNKNOWN 상태가 아님) | (그대로) | 해당 subject 를 에피소드의 `unknowns` 에서 뺀다(§9.1). 사람의 판정으로 기록 |
| ESCALATED | `DispatchReturned` · `EvidenceArrived` · `OutcomeRechecked` · `Recorded` · `RecordFailed` | (그대로) | 기록만. 에이전트 명령 없음. DISPATCH_PENDING 중 인수되면 기록 결과가 늦게 올 수 있다 — `Dispatch` 는 DISPATCH_PENDING 에서만 나오므로 안전하다 |
| ESCALATED | `close(by, outcome)` · 보존 기한 | CLOSED | |

**결과 통보의 판정 순서.** AWAITING_EVIDENCE · UNKNOWN(OUTCOME) 의 `EvidenceArrived` 는 ① 기록만(이어지지 않음 · 이 시도의 `Recorded` 앞 ·
중복) → ② UNKNOWN(보류 · 미검증 단위, `operatorRequired`) → ③ DONE 순서로 본다. 한 통보가 DONE 과 UNKNOWN 을 함께 말하면 UNKNOWN 이다.
모호한 통보로 자동 RESOLVED 되는 일은 없다. UNKNOWN(OUTCOME) 에서는 ② 가 상태를 그대로 두고 `unknowns` 만 늘린다.

표에 없는 (상태, 사건) 짝은 상태를 바꾸지 않고 기록만 한다. **단 그 상태가 기다리는 액티비티의 결과는 이 규칙에 들지 않는다** —
DIAGNOSING 의 `DiagnosisReturned` · `DiagnosisFailed`, REVALIDATING 의 `Revalidated`, DISPATCH_PENDING 의 `Recorded` · `RecordFailed`,
DISPATCHED 의 `DispatchReturned`. 각 상태의 「위 행 어디에도 맞지 않는」 행이 받는다. 결과가 돌아오면 그 액티비티의 기한은 이미
다 쓴 것이라, 삼키면 `episodeMs` 까지 아무에게도 알리지 않고 멈춰 있게 된다.

종료 상태가 아닌 모든 상태에는 사건 · 사람 · 기한 중 하나로 나가는 출구가 있다: PROPOSED 는 순간 상태, ESCALATED 는 `close` ·
`escalatedRetentionMs`, 나머지는 사건 · 사람과 §5.1 의 상태 기한 · `episodeMs`. 액티비티를 기다리는 네 상태(DIAGNOSING ·
REVALIDATING · DISPATCH_PENDING · DISPATCHED)에서 상태 기한은 **액티비티가 끝내 돌아오지 않는 경우만** 덮는다 — 돌아온 결과는
반드시 표의 한 행이 받는다.

`abort` 는 따로 두지 않는다. 운영자의 "중단"은 `takeover` 다 — 에이전트의 행동을 멈추고 사람이 맡는다. 에피소드를 무효로 끝내려면
그 뒤에 `close` 한다.

### 5.3 사유 열거값

`APPROVAL_REJECTED(reason)` · `APPROVAL_EXPIRED` · `EVIDENCE_EXPIRED` · `EPISODE_EXPIRED` · `DIAGNOSIS_EXPIRED` · `DIAGNOSIS_FAILED` ·
`NO_GROUNDS` · `UNCITED` · `ESCALATE_RECOMMENDED` · `OUT_OF_CANDIDATES` · `CANDIDATES_VERSION_MISMATCH` · `UNKNOWN_BLOCKS_EXECUTION` ·
`UNKNOWN_UNRESOLVED` · `ATTEMPTS_EXHAUSTED` · `REPEATED_REMEDY` · `SNAPSHOT_TOO_LARGE` · `CANDIDATES_TOO_MANY` · `AGENT_LAYER_OFF` · `POLICY_MISSING` ·
`POLICY_EXPIRED` · `TAKEN_OVER` · `DISPATCH_REFUSED(enum)` · `DISPATCH_ANSWER_UNKNOWN` · `CHILD_FAILED` · `CHILD_OVERDUE` · `RECORD_FAILED` ·
`WITHHELD_HUMAN_FIRST`.
알림 전용(전이 없음): `POLICY_REJECTED` (§8.2), `RECORD_LAG` (실행 의도가 아닌 기록의 실패, §8.5).

- `UNCITED` 를 자동 재진단으로 바꾸는 조정은 khala 브리지 구조화 출력이 들어간 뒤로 미룬다. **2026-09-30 결정: 브리지 구조화 출력이 섰지만 `UNCITED` 는 계속 ESCALATED 다.** 재진단 한 번이 4~9분이라 라인 기한에
  부담이 크고, 첫 실물 진단에서 표지 오판독(`## 권고:`) 같은 다른 원인이 섞여 있었다. narrator 측정으로 `UNCITED` 의 비율과 원인을
  본 뒤 다시 정한다. 그 전에는 구독 한도 안내 문구가 답으로
  둔갑해 `UNCITED` 로 분류될 수 있다.
- `POLICY_MISSING` 과 `POLICY_EXPIRED` 는 같게 동작하지만 같은 값으로 접지 않는다.
- `DISPATCH_ANSWER_UNKNOWN` 은 `DISPATCH_REFUSED` 로 접지 않는다. 모르는 판·값으로 재진단하면 모르는 것을 아는 것처럼 다루게 된다.
- `WITHHELD_HUMAN_FIRST` 는 §19 D 가 정해질 때까지의 잠정 사유다. picasso 의 `WITHHELD` 는 사람이 먼저 진단하게 하려는 의도적
  비자동화라, 그런 에피소드에서는 안쪽 루프를 아예 부르지 않는다.

---

## 6. 전이 함수 (`:episode`)

구동부는 판단하지 않는다. 사건을 넣으면 **다음 상태와 실행할 명령 목록**이 나온다(함수형 코어, 명령형 껍질).

```kotlin
sealed interface EpisodeEvent {
    data class Detected(val symptom: SymptomRef, val observedAt: Instant) : EpisodeEvent
    data class SymptomJoined(val eventId: String, val symptom: SymptomRef, val observedAt: Instant) : EpisodeEvent
    data class DiagnosisReturned(val response: DiagnosisResponse) : EpisodeEvent
    data class DiagnosisFailed(val retryable: Boolean, val expired: Boolean) : EpisodeEvent
    data class ApprovalDecided(val proposalId: String, val sawCandidatesVersion: String,
                               val decision: Decision, val by: Approver, val reason: RejectReason?) : EpisodeEvent
    data class Revalidated(val result: TriState) : EpisodeEvent                  // TRUE / FALSE / UNKNOWN (실패도 UNKNOWN)
    data class DispatchReturned(val result: DispatchResult) : EpisodeEvent        // 수락 / picasso 열거값 / 모름 / 자식 결과
    data class EvidenceArrived(val eventId: String, val grade: EvidenceGrade, val outcome: TriOutcome) : EpisodeEvent  // 결과 통보(JobResponse)에서, §12
    data class OutcomeRechecked(val outcome: TriOutcome) : EpisodeEvent           // UNKNOWN(OUTCOME) 주기 재조회 결과
    data class ConfirmedPrecondition(val subject: SubjectRef, val holds: Boolean, val by: Approver) : EpisodeEvent
    data class ConfirmedOutcome(val subject: SubjectRef, val outcome: KnownOutcome, val by: Approver) : EpisodeEvent  // DONE / NOT_DONE
    data class Recorded(val seq: Long) : EpisodeEvent                            // 실행 의도 기록 성공
    data class RecordFailed(val seq: Long) : EpisodeEvent
    data class DeadlineExpired(val which: Deadline) : EpisodeEvent
    data class TakenOver(val by: Approver) : EpisodeEvent
    data class Closed(val by: Approver?, val outcome: CloseOutcome) : EpisodeEvent
    data class PolicyRead(val result: PolicyReadResult) : EpisodeEvent            // 유효 / 거절(마지막 유효 판 유지) / 없음 / 만료 / 꺼짐
}

sealed interface Command {  // 구동부가 실행한다
    data class Diagnose(val request: DiagnosisRequest) : Command
    data class AwaitApproval(val deadline: Instant) : Command
    data class Revalidate(val preconditions: List<Precondition>) : Command
    data class Dispatch(val action: ActionRef, val idempotencyKey: String) : Command
    data class AwaitEvidence(val deadline: Instant) : Command
    data class ScheduleRecheck(val at: Instant) : Command    // UNKNOWN 주기 재조회
    data class Notify(val card: OperatorCard) : Command      // 상태가 바뀔 때만 (R10)
    data class Record(val entry: EpisodeRecordEntry) : Command
}

data class Step(val next: EpisodeState, val commands: List<Command>)
fun transition(state: EpisodeState, event: EpisodeEvent, policy: PolicyTable): Step
```

고정 규칙(정책 표로 못 바꾼다):
- UNKNOWN 이거나 `unknowns` 가 비어 있지 않으면 **실행 계열**(§9.1 표의 "실행 계열" 열) `Dispatch` 명령이 나오지 않는다.
  `OPERATOR_DECISION/CONFIRM_DONE`(현물 확인 과업)과 `ESCALATE` 는 실행 계열이 아니므로 막히지 않는다 — 확인 불가를 푸는 길이
  확인 불가 때문에 막히면 안 된다.
- 같은 멱등 열쇠의 `Dispatch` 는 한 에피소드에서 한 번만 나온다.
- `Dispatch` 는 DISPATCH_PENDING 에서 `Recorded` 사건을 받은 전이에서만 나온다. 실행 의도 기록의 성공이 사건으로 들어오므로
  "기록 없는 조치 없음"을 순수 층에서 시험할 수 있다. 다른 `Record`(상태 변화, 진단 결과 등)는 결과를 기다리지 않는다.
- 구동부는 한 `Step` 의 명령을 순서대로 실행한다. 결과가 필요한 명령(진단, 재조회, 실행 의도 기록, 실행)의 결과는 다음 사건으로
  되먹인다.
- 모든 비종료 상태에는 기한이 있다(§5.1). 기한 없는 대기 명령은 만들 수 없다.
- 3값 관측(`TriState`, `TriOutcome`)을 참·거짓으로 접지 않는다. picasso 의 `null`("아직 못 봤다")은 UNKNOWN 이다.
- 같은 `eventId` 의 합류·근거는 두 번째부터 무시한다(감시자는 최소 한 번 전달). DISPATCHED 에서 보관한 근거를 다음 상태에 들어갈 때
  다시 적용하는 것은 전이 함수 안의 재적용이지 새 signal 이 아니므로 이 규칙에 걸리지 않는다(§5.2).

---

## 7. 워크플로 구동부 (`:episode-runtime`)

### 7.1 입력과 식별

- 워크플로 id = **병합 키**(`ep:<correlationKey>`). 시작은 `signalWithStart` 만. 같은 키로 두 번째 시작은 구조적으로 불가능하다
  (R1, R10). 닫힌 에피소드 뒤의 새 증상은 새 실행으로 연다(id 재사용 정책 `ALLOW_DUPLICATE`). ESCALATED 는 열려 있으므로 그동안
  같은 키의 새 증상은 그 에피소드에 기록으로 붙는다(§5.2).
- 에피소드 인스턴스 id(기록용) = 워크플로 id + Temporal runId.
- 입력: 첫 증상 참조, 관측 시각, **상태 스키마 판**(`schemaVersion`).
- **시작하자마자 `readPolicy`** 를 부른다. 꺼짐·없음·만료면 바로 ESCALATED. 감시자는 판정하지 않는다.
- DETECTED 의 발생원은 감시자(picasso 내보내기) 하나다. drift 감지를 발생원으로 붙이는 것은 계획 C 에서 감시자의 두 번째 입력
  (drift 감사 테이블 읽기)으로 한다.

### 7.2 사람과 감시자의 입력

| 입력 | 형태 | 보내는 쪽 |
|---|---|---|
| 승인·거절 `decide` | **Update**. validator: 해독·형식만. handler: 현재 상태가 AWAITING_APPROVAL 이 아니면 `REFUSED_NOT_AWAITING`, 제안 id·후보 판이 다르면 `REFUSED_STALE` 를 **값으로** 반환 | 제어면 (사람) |
| 현물 확인 `confirm(subject, result)` | Update, PERSON 만. 받는 subject: UNKNOWN 상태의 대상, `unknowns` 의 항목, **AWAITING_EVIDENCE 에서 실행한 대상**(결과 `DONE \| NOT_DONE` → `ConfirmedOutcome`, 사람 과업 종류가 끝나는 길, §5.2). 셋 어디에도 없으면 `REFUSED_SUBJECT_NOT_UNKNOWN`. UNKNOWN(PRECONDITION) 의 `holds` 는 카드가 보인 명제(§8.3)를 확인한 것으로 기록한다 | 제어면 |
| 인수 `takeover` | Update. **진행 중에도 먹는다**: 대기점은 조건에 포함, 진단 중이면 액티비티 취소 | 제어면 |
| 종료 `close(outcome)` | Update, ESCALATED 에서만 | 제어면 |
| 완료 근거 `evidence` | signal, `eventId` 로 중복 제거 | 감시자 |
| 새 증상 합류 `symptom` | signal (signalWithStart), `eventId` 로 중복 제거 | 감시자 |
| 에이전트 층 끄기 `agentOff` | signal, 열린 에피소드 전체에 방송 (계획 D) | 정책 활성화 CLI |

- 제안 id = 인스턴스 id + 시도 번호.
- 승인과 기한 만료가 겹치면 워크플로 단위 순차 처리로 하나만 먼저 먹는다. 기한이 먼저 먹었으면 뒤늦은 `decide` 는
  `REFUSED_NOT_AWAITING` 값을 받는다. R1 경합 시험의 "둘 중 하나"가 이 규칙으로 정의된다.
- 제어면 경로: `GET /api/episodes`, `GET /api/episodes/{id}`, `POST /api/episodes/{id}/decide|confirm|takeover|close`.
- **승인자 신원**: 요청 헤더 `X-Koshei-Operator` 의 값을 싣고, 기록에 `identityAssurance: SELF_ASSERTED` 를 함께 남긴다. 인증이 없는
  PoC 한계를 감사 기록에도 드러내기 위해서다(picasso 승인 창구도 같은 한계를 스스로 적어 둔다).

### 7.3 쿼리

현재 상태 · 시도 이력(§9.4 모양) · 현재 권고와 후보 목록 · 남은 기한들.

### 7.4 타이머

모든 대기는 `Workflow.await(Duration, 조건)` 이다. 기한 값은 정책 표에서 온다. 에피소드 전체 기한은 워크플로 안 타이머로 둔다.
Temporal execution timeout 으로 두면 강제 종료라 ESCALATED 가 **상태로** 남지 않는다. UNKNOWN 의 주기 재조회도 같은 방식의 타이머다.

### 7.5 결정성

- 워크플로 코드는 환경변수·파일·벽시계를 읽지 않는다. 시각은 `Workflow.currentTimeMillis()`.
- 코드 변경은 `Workflow.getVersion` 으로 분기한다. 상태에 `schemaVersion` 을 싣는다(R3).
- **정책 표는 고정하지 않는다.** 시작 때와 결정할 때마다(PROPOSED, 기한 설정 시점) `readPolicy` 액티비티로 읽는다. 결과가 이력에
  남으므로 재생에 안전하고, 운영 중 정책 조정이 진행 중 에피소드에도 먹는다(R6). `agentOff` 방송과 신규 에피소드 시작이 엇갈려도,
  신규 에피소드는 시작 때 정책을 읽으므로 놓치지 않는다.
- 자식 사가의 판 고정은 §8.4.

---

## 8. 액티비티

### 8.1 `diagnose` — narrator (원격, `narrator-tq`)

계약은 narrator 가 소유한다(진단 계약 초안 0.6, 예제의 `contractVersion` 은 `"0.6"`). koshei 쪽 약속만 적는다.

| 항목 | 값 |
|---|---|
| ScheduleToClose | 정책 표의 진단 예산. **큐 대기 포함** (narrator 동시 실행이 설정값이라 대기가 생긴다) |
| StartToClose | narrator 벽 + 여유. 계약 0.5 의 사슬: 브리지 420 → khala 450 → narrator 510 → **StartToClose 540초** (= khala 호출 문 대기 20 + narrator 벽 510 + 저장소·응답 조립 10). 브리지 벽이 바뀌면 사슬 전체가 같은 차로 따라간다. 숫자는 정책 표·설정으로만 받는다 |
| HeartbeatTimeout | **30초** (narrator 는 khala 호출 문을 기다리는 동안과 khala 를 기다리는 동안 10초마다 하트비트). 인수·끄기가 진단에 닿게 하려고 |
| 브리지 혼잡 | narrator 가 khala 호출 문(동시 실행 세마포어)을 20초 안에 못 얻으면 khala 를 부르지 않고 재시도할 예외(`unavailable`)를 던진다. 기다림은 StartToClose 가 아니라 큐, 곧 ScheduleToClose 쪽으로 간다. 이 시도도 한 시도로 센다 |
| 재시도 | 주인은 Temporal 하나. 재시도 예외: khala `rate_limit`·`unavailable`·`timeout`, 전송 시간 초과. 비재시도: `quota`·`auth`·`other`, 4xx, 해독 불가, 계약 판 불일치 |
| 값 | `RECOMMENDED` · `NO_GROUNDS` · `UNCITED` · `OUT_OF_CANDIDATES` — 넷 다 값. 예외로 바꾸지 않는다 |
| 멱등 | narrator 가 `(episodeId, attempt, candidatesVersion)` 로 첫 결과를 돌려준다. **같은 결정을 지키는 것은 이 첫 결과 저장소다.** 응답의 판 칸(§13)은 조사 재료일 뿐이다 |
| 취소 | `CancellationScope` 로 감싸 인수·끄기에서 취소 |

P0 동안 `diagnose` 는 **Mock** 이다. Mock 은 계약 고정 예제의 응답을 그대로 돌려준다(§17). 권고 모드는 khala 에 검색에 쓰지
않는 자료 칸이 생긴 뒤에 켠다. LLM 없이도 바깥 루프가 끝까지 도는 것이 R5 의 증명이기도 하다.

### 8.2 `readPolicy`

활성 정책 판을 읽어 `(version, table)` 을 돌려준다. 검증은 워크플로 안에서 순수 함수 `validate` 로 한다. 실패하면 **이력에 남은
마지막 유효 판을 계속 쓰고** `Notify(POLICY_REJECTED)` 를 낸다. 설정 하나로 모든 에피소드가 조용히 ESCALATED 로 가는 것을 막는다.
유효 판이 한 번도 없었으면 `POLICY_MISSING` 이다.
- 계획 B(P0): 정책 원천은 파일(`model/episode-policy/active.yaml`) 하나.
- 계획 D(P1): git 정본 + DB 활성 판 포인터 + 감사(§10.3).

v1 판단(계획 B2a): 활성 판이 **없다**고 읽히면(명시적 부재) 마지막 유효 판이 있어도 `POLICY_MISSING` 이다 — 판을 내린 것을
의도로 읽는다. 파일을 **읽을 수 없으면**(문법 · 입출력 오류) 무효 판과 같게 마지막 유효 판을 쓴다. 거절된 판에 `agentLayerEnabled:
false` 가 글자 그대로 있으면 마지막 유효 판으로 돌아가지 않고 끈다(R5). 모르는 키는 오류다(R14). `requireClean` 은 없으면 `true`.
유효한 판을 한 번도 읽지 못한 채 ESCALATED 로 가면 보존 기한은 고정 24시간이다(§5.1 의 "기한 없는 대기는 없다"). 모든 기한 칸은
365일 이하.
거절되거나 읽을 수 없는 판은 제한 쪽으로 푼다: 그 트리에 `agentLayerEnabled` 가 `true` 가 아닌 값으로 있으면 끄고, 지난 `expiresAt` 이 있으면 만료로 보고, 그 밖에는 마지막 유효 판으로 돌되 자동 승인을 멈춘다(사람이 승인한다). 활성 판이 없다고 읽혀도 ESCALATED 보존 기한은 마지막 유효 판의 것을 쓴다. B3 의 YAML 읽개는 중복 키를 거부해야 한다(끄기 스위치가 뒤의 키로 덮이지 않게).

### 8.3 `revalidate` — 원천 재조회 (R7, R15)

마지막 이벤트가 아니라 **원천(picasso 대장)을 다시 읽는다.** 판정은 `:episode` 의 값 + 시간창 함수. 결과는 3값. 액티비티가
재시도 예산 안에서 끝내 실패하면 UNKNOWN 으로 들어간다(모르면 안 한다).

**`APPROVE_REMEDY` 의 낡음** (picasso 2026-09-30 답, picasso 변경 없이):

| 항목 | 값 |
|---|---|
| 왜 따로 보나 | 승인 요청의 `sawSkillTypes` 는 조치 유형의 열만 대조한다. 같은 모양의 새 제안은 같은 것으로 지나간다 |
| 규칙 | 후보는 자기를 만든 탐색 줄의 `searchId` 를 `ref` 에 든다(§9.2). 원천에 그 (기체, 주문)의 **더 새 탐색 줄**이 있으면 낡음 → FALSE (§5.2 REVALIDATING FALSE). 「더 새」는 같은 `runId` 의 내보내기에서 그 줄보다 뒤에 적힌 줄이다. `searchId` 는 분해해 견주지 않고(§12) 그 줄을 찾는 데만 쓴다. 구동마다 되풀이되므로 `runId` 를 넘어 견주지 않는다 |
| 지금 기준 읽기 | 읽은 대장이 시간창 안(지금 기준)이 아니면 UNKNOWN 이다. **낡은 읽기로 TRUE 를 내지 않는다** — 더 새 줄이 아직 안 적혔을 수 있다. 이 규칙은 `APPROVE_REMEDY` 만이 아니라 원천을 다시 읽는 **모든 실물 종류**(`CHOOSE_SOURCE`, `OPERATOR_DECISION`, `SAGA_ACTION` 의 원천 재조회)에 걸린다 |
| 사람이 확인할 명제 | UNKNOWN(PRECONDITION) 의 카드와 과업은 사람이 확인할 명제를 후보의 `ref` 값으로 채워 그대로 적는다. 명제는 **거짓으로 드러날 수 있는 세계의 사실**이어야 한다 — 「줄이 그대로다」 같은 명제는 쓰지 않는다. 종류별: `APPROVE_REMEDY` 「(기체, 주문)에 `searchId` X 뒤로 더 새 탐색 줄이 없다」(위 규칙과 같은 판정, 예: (hum-02, PATROL-1)) + 스냅샷 뒤에 합류한 증상을 함께 보인다 · `CHOOSE_SOURCE` 「주문 O 의 자재 M 이 아직 결품이고 대체 위치 B 가 아직 쓸 수 있다」 · `OPERATOR_DECISION` 「실행 ex 의 단위 u 가 아직 운영자 보류이고 판단이 나지 않았다」 · `SAGA_ACTION` 조치 목록 항목에 선언한 전제(계획 D) — 여기서 확인하면 자식 사가가 자동으로 돈다. 사람도 그 명제를 관측할 수 없으면 카드가 그렇다고 적고, `unknownMs` 가 지나게 둔다. 명제 없이 「전제 맞음」만 누르게 하지 않는다 — `ConfirmedPrecondition(true)` 가 도장 찍기가 되지 않게 |
| 보수 쪽으로 틀림 | 탐색 줄은 제안 표가 아니다. NONE · SOURCE_MISSING 은 서 있던 FOUND 제안을 지우지 않는다(지우는 것은 접수 뒤 `settle` 하나). FOUND 뒤에 NONE 이 적히면 제안이 서 있어도 낡음으로 본다. 받아들인다 |
| FOUND 밖 후보 | picasso 쪽 낡음 검사가 없다. `resolve` · `release` 는 대상이 이미 그 상태가 아니면 `false` 뿐이라 「이미 누가 했다」와 「처음부터 아니었다」가 접힌다(`NO_PROPOSAL` 과 같은 접힘). koshei 는 둘 다 부르지 않는다(§8.4) |

대장을 실시간으로 읽는 표면은 아직 없다 — 파일 내보내기는 시나리오 끝에 한 번이다. 증분 조회 `after` 는 picasso 변경 후보이고
(§19 E ④), 실시간 읽기를 누가 담을지는 §19 C 다. **그래서 §19 C · E ④ 가 서기 전까지 모든 실물 에피소드는
UNKNOWN(PRECONDITION)을 지나고, 사람의 `ConfirmedPrecondition` 이 없으면 `unknownMs` 뒤 ESCALATED(`UNKNOWN_UNRESOLVED`)로 끝난다.**
그동안 UNKNOWN(PRECONDITION)이 실물 에피소드의 실제 관문이다. 재조회만으로 TRUE 까지 가는 길은 시험의 Mock picasso 뿐이다.

### 8.4 `dispatch` — 후보 종류별 대상

| kind | dispatch 대상 | 수락의 뜻 | 완료 근거 |
|---|---|---|---|
| `APPROVE_REMEDY` | picasso 승인 창구 (제안 참조). picasso 쪽 선언이 전제다(아래). 멱등 열쇠는 창구가 조용히 버리므로 기대지 않는다 (§11) | picasso 가 제안을 소모하고 전달. `REMEDY_NOT_APPLIED` 는 수락이 아니다 → UNKNOWN(OUTCOME) | 결과 통보(JobResponse), 담는 쪽이 나름 (§12). 사건 대장이 아니다 — 제대로 끝난 단위는 사건이 안 된다 |
| `CHOOSE_SOURCE` | 운영자 과업(주문을 고치는 사람에게 알림 + 과업 기록). picasso 쪽 문 없음 — 주문을 고쳐 다시 넣는 것은 상류 몫, 관문이 그때 다시 판정 | 과업 발행 | 사람의 `ConfirmedOutcome`(AWAITING_EVIDENCE 의 `confirm`, §7.2). 다시 넣은 주문의 결과 통보는 선택이다 — koshei 는 새 주문·실행 id 를 모른다 |
| `OPERATOR_DECISION` | 운영자 과업. koshei 는 `resolve` 를 부르지 않는다 | 과업 발행 | 사람의 `ConfirmedOutcome` 만(AWAITING_EVIDENCE 의 `confirm`, §7.2. 없으면 `EVIDENCE_EXPIRED`). 사건 줄의 `resolution` 은 아직 근거로 쓰지 않는다 — 실시간이 아니고, 판단이 아직 안 실린 가장 최근 사건에 붙는다(붙는 범위는 picasso 에 묻는다, §19) |
| `SAGA_ACTION` (계획 D) | koshei 자식 사가 | 자식 시작 | 자식 사가 결과 + 원천 재조회 |

- picasso 응답의 `approverKind`(PERSON/AGENT), `delivered` 를 그대로 기록한다. koshei 의 POLICY 자동 승인은 picasso 에 AGENT 로 간다.
- **멱등은 액티비티 안에서 지킨다(§11).** 자식 사가는 child workflow id 로 지킨다. `APPROVE_REMEDY` 는 열쇠로 결과를 조회할 표면이
  없다 — 두 번 나감은 picasso 가 막고, 재시도의 `NO_PROPOSAL` · `REMEDY_NOT_APPLIED` 는 UNKNOWN(OUTCOME)이다.

picasso 와의 경계 (picasso 2026-09-30 답):

| 항목 | 규칙 |
|---|---|
| 거절 열거값을 전이 입력으로 | picasso 동의, 조건 넷. ① `refusal` 열거값으로만 가르고 산문으로 가르지 않는다 ② 답의 `schemaVersion`(지금 `"2"`)을 **먼저** 본다. 판 2 의 아는 거절 값은 답이 이름을 댄 넷(`NO_PROPOSAL` · `REMEDY_NOT_APPLIED` · `REFUSED_BY_GATE` · `NOT_DECLARED`)뿐이다. 모르는 판이나 그 밖의 값이면 ESCALATED(`DISPATCH_ANSWER_UNKNOWN`). 판 2 의 전체 목록은 picasso 에 묻는다(§19) ③ `REFUSED_BY_GATE` 는 제안을 소모하지 않는다 ④ `NO_PROPOSAL` 은 「이미 소모됐을 수 있음」 → UNKNOWN(OUTCOME), 재시도로 풀지 않는다. 덧붙여 `REMEDY_NOT_APPLIED` 는 승인이 아니고, 같은 판의 둘째 시도에도 오므로(§11) `NO_PROPOSAL` 과 같이 UNKNOWN(OUTCOME) |
| 거절의 기본 갈래 | **모든 거절 값은 기본이 ESCALATED(`DISPATCH_REFUSED(enum)`)다.** DIAGNOSING 으로 가는 것은 「재진단으로 고칠 수 있다」고 답이 보여 준 값의 목록뿐이고, **그 목록은 지금 비어 있다.** picasso 2026-09-30 답에 나온 거절 값은 `NO_PROPOSAL` · `REMEDY_NOT_APPLIED`(나갔는지 모름 → UNKNOWN), `REFUSED_BY_GATE`(관문 판정 — 재진단이 관문을 바꾸지 않는다), `NOT_DECLARED`(picasso 쪽 선언 부재 — 재진단이 선언을 만들지 않는다)뿐이다 |
| 선언 | picasso 쪽 전제다. koshei 가 보내는 것이 아니다. 사람 승인도 창구를 지나면 선언이 있어야 하고, 없으면 `NOT_DECLARED` 로 온다(값 없는 문에서는 선언이 값의 출처). 이 선언이 자격 선언과 같은 표인지는 picasso 에 묻는다(§19). POLICY 자동 승인은 두 표(koshei 정책 ∧ picasso 자격)의 교집합이다(§10.3) |
| 다른 길로 누르지 않음 | picasso 가 거절한 것을 다른 길로 밀어 넣지 않는다. 거절이 ESCALATED 로 가므로 에이전트가 다른 후보로 다시 고를 틈이 없다 |
| 운영자 판단의 문 | `Middleware.resolve(executionId, unitId, decision)` 는 프로세스 안에만 있고 승인자를 받지 않으며 누가 눌렀는지 남기지 않는다. 그래서 `OPERATOR_DECISION` 은 사람 과업으로 남고 koshei 는 `resolve` 를 부르지 않는다. 사람만 누른다는 선은 코드 검사가 아니라 문이 없어서 선다 |
| 사건 줄의 운영자 판단 | `resolution` 이 널이 아니면 판단이 났다(`{decision, at, wallClockAt}`, 판 5). 누가 눌렀는지는 없다. `unresolved` · `verification` · `reachedEvidence` 는 봉인 시점 값이라 판단 뒤에 바뀌지 않는다 |
| 재할당 | **이 설계는 `reassign` 을 부르지 않는다.** 재할당은 배정 정책이 부른다(picasso ADR 41) — picasso 가 스스로 옮기지 않고, 바깥 루프의 후보도 명령도 아니다. 사용자가 달리 정하기 전까지 특히 결과 통보에 `inDoubtUnits` 가 있거나, `physicalState` 가 OPERATOR_HOLD 인데 `blockedBy` 가 빈 실행에는 부르지 않는다. picasso 는 보류로 간 단위를 도는 단위로 보지 않아 그 실행의 재할당을 막지 않고, 사람이 뒤에 `REWORK` 를 내면 새 기체에서 돈다(코드로 확인, 시험으로 밟지는 않음 — picasso 2026-09-30 답). **이 빈 곳은 §19 E ③ 전까지 막는 장치가 없다.** 배정 정책이 부르는 재할당은 koshei 밖이라, R8 의 「자동 재할당 0」은 koshei 만으로 검증할 수 없다 |

**`SAGA_ACTION` 과 자식 사가 (계획 D).** koshei 가 직접 하는 조치 유형(OT 쓰기, MES 반영 등)은 정책 표의 **조치 목록(action
catalog)** 에 선언한다. 항목: 사가 정의 `name@x.y.z`, 값의 출처(선언과 관측의 경로), `requiresPerson`, **전제(`preconditions`)** —
사람이 UNKNOWN(PRECONDITION) 에서 확인할 명제(§8.3)이자 `revalidate` 가 재조회할 세계 사실. 전제를 하나도 선언하지 않은 항목은
`validate`(§10.2)와 `policy activate` 가 거절한다 — 확인할 명제 없이 「전제 성립」을 누르게 되기 때문이다.
- `requiresPerson` 은 정책 활성화 때 CLI 가 사가 정의의 블록 계약을 풀어 계산해 박는다: 어느 블록이든 `requireApprovalBefore` 이거나
  IRREVERSIBLE 이면 true. 투영과 전이 함수는 이 값만 본다(순수).
- 활성화는 **모든 블록이 정확한 판으로 고정된 사가 정의만** 받는다(`^x`·`latest` 거절). 워커가 `workflow_def` 에서 `name@version` 을
  묶어도 `RuntimeAssembly.planFor` 가 블록 판 범위를 다시 풀기 때문이다(`Worker.kt:72-80`). 정확한 판이면 다시 풀어도 같은 계획이다.
- 자식 시작 옵션: child workflow id = 멱등 열쇠, `interactive = false` 고정(실패 노드가 기한 없이 멈추지 않게),
  **`autoApprove = true` 고정**, `ParentClosePolicy = ABANDON`. 자식에는 Temporal 실행·런 기한을 걸지 않는다 — 걸면 보상 도중에
  강제 종료된다(§7.4 가 에피소드에 execution timeout 을 쓰지 않는 이유와 같다).
- `autoApprove = true` 가 안전한 까닭: `requiresPerson` 후보는 PERSON 승인 없이는 DISPATCH 까지 오지 않는다(§5.2 — POLICY 는
  자동 승인하지 않고 AWAITING_APPROVAL 로 보낸다). 관문이 없는 자식에는 이 값이 쓰이지 않는다. 그래서 자식이 관문에서 기한 없이
  멈추는 경로가 없다.
- 부모 쪽 타이머 `childMaxMs` 가 지나면 에피소드는 ESCALATED(`CHILD_OVERDUE`)로 가되 자식을 죽이지 않는다(ABANDON). 자식 결과는
  기록으로 받는다. 보상 도중에 끊기는 일이 없다.
- 자식 사가가 확인 불가를 보고하는 경로(보상하지 않고 UNKNOWN)는 §14 의 현재 블록 동작을 바꾸는 일이라 계획 D 에서 한다.

### 8.5 `notify`, `record`

- `notify`: 운영자 채널. 에피소드 단위, 상태가 바뀔 때만(R10). 실패해도 전이를 막지 않는다.
- `record`: 추가 전용 기록(§13). `(episodeInstanceId, seq)` 로 `ON CONFLICT DO NOTHING` — 액티비티 최소 한 번 실행에 안전.
  - 실행 의도 기록(DISPATCH_PENDING): 결과를 `Recorded` / `RecordFailed` 사건으로 되먹인다. 실패하면 조치를 내지 않고
    ESCALATED(`RECORD_FAILED`).
  - 그 밖의 기록: 결과를 기다리지 않는다. 재시도 예산 안에서 끝내 실패하면 `Notify(RECORD_LAG)`. Temporal 이력에는 남아 있으므로
    보존 기간 안에 이력에서 메울 수 있다(메우는 작업은 계획 D).
- 계획 B(P0)부터 `episode_event` 테이블(§13)에 쓴다. 필수 항목 전체와 품질 이력 연결은 계획 D.

---

## 9. 후보, 식별자, 판

### 9.0 진단 스냅샷의 모양

- `snapshot.manifest` 는 picasso 가 쓴 `manifest.json` 의 칸을 **전부 그대로** 싣는다(`schemaVersion` · `runId` · `writtenAt` ·
  `virtualNow` · `contractSemver` · `counts`). 형도 바꾸지 않는다 — 예: `schemaVersion` 은 글자 `"5"` 다. narrator 는 아는 판만 읽고
  다른 판이면 비재시도 예외로 멈춘다(§8.1 의 "계약 판 불일치" 와 같은 갈래 → `DIAGNOSIS_FAILED`).
- `snapshot.incidents` · `snapshot.searches` 는 **에피소드를 연 줄을 각 목록의 첫째**에 둔다. narrator 는 v1 에서 incidents 의 첫째를
  주체, searches 의 첫째를 짝으로 질의에 옮기고(사건 줄이 없으면 searches 의 첫째만), 합류한 증상(둘째 줄부터)은 싣지 않는다.
  짝은 지어낸 것이 아니라 §12 의 결정적 병합 규칙이 묶은 것이다.
- 그래서 v1 에서는 투영(§9.1)이 합류 증상에서 만든 후보를 narrator 가 근거 없이 보게 될 수 있다. 그 결과는 `NO_GROUNDS` 나
  `OUT_OF_CANDIDATES` 로 나타나고 사람에게 간다. 합류 증상을 질의에 싣는 것은 narrator 쪽 다음 판의 일이다.

### 9.1 투영

`project(snapshot, unknowns, catalog) → Candidates` 는 `:episode` 의 순수 함수다. picasso 가 내보낸 줄(과 계획 D 의 조치 목록)에서
후보를 만든다. **후보 집합의 주인은 에피소드**다. narrator 는 받은 목록에서만 고르고, 고른 것을 에피소드가 다시 검증한다.

| kind | 무엇 | 누르는 쪽 | 실행 계열 | 사람 관문 |
|---|---|---|---|---|
| `APPROVE_REMEDY` | 탐색이 찾은 조치 승인 | 승인 창구. 자동 승인은 koshei 정책 ∧ picasso 자격 | 예 | 정책에 따름 |
| `CHOOSE_SOURCE` | 결품 줄의 대체 위치 선택 | 주문을 고치는 사람 | 예 | 사람 과업 (승인 단계 없음) |
| `OPERATOR_DECISION` | 보류 단위를 `CONFIRM_DONE` / `REWORK` — 사건 줄의 `verification` 이 `MATCHED` 일 때만 세운다(picasso `resolve` 는 그때만 근거를 올린다; 아니면 현물 확인이 권고이고 둘 다 세우지 않는다, picasso 2026-09-30 답) | 사람만 | `REWORK` 만 예 | 사람 과업 (승인 단계 없음) |
| `SAGA_ACTION` (계획 D) | 조치 목록의 koshei 조치 | 자식 사가 | 예 | `requiresPerson` |
| `ESCALATE` | 사람에게 넘김 | 사람 | 아니오 | — (항상 포함) |

탐색 결과가 `NONE` 이거나 `WITHHELD`(조치 열이 없음)면 그 탐색에서는 후보가 서지 않는다. 사람의 진단이 먼저다.
picasso 가 든 다섯째 후보(막는 결함의 감수, 결과 통보의 `blockedBy` · `operatorRequired`)는 두지 않는다(§19 A). 막힘 자체는 사건을
열지 않으므로, 결과 통보가 접수 입력이 되기 전까지 koshei 는 막는 결함을 보지 못한다 — 그동안은 담는 쪽이나 라인이 사람을 부른다.
이미 열린 에피소드 안에서 보이면 `ESCALATE` 가 맡는다.

**unknowns 가 비어 있지 않으면** 실행 계열(`APPROVE_REMEDY`, `CHOOSE_SOURCE`, `OPERATOR_DECISION/REWORK`, `SAGA_ACTION`)을 넣지
않는다. `OPERATOR_DECISION/CONFIRM_DONE` 은 남는다 — 사람의 현물 확인이 곧 해소 경로이기 때문이다. 권고 후보에서 뺀다고 사람이
못 하는 것은 아니다. 사람이 현물을 확인해 안 했다고 판정하면 확인 불가가 풀리고, 다음 진단에서 `REWORK` 가 다시 후보가 된다.
v1 은 에피소드 전체에 적용한다. 전이 함수도 같은 조건을 다시 검사한다(이중 방어, 사유 `UNKNOWN_BLOCKS_EXECUTION`).

`unknowns` 항목: `{ subject: ref, what: 열거값, since: 관측 시각, source: "picasso" | "revalidate" }`. `what` 목록은 picasso 사건 줄의
관측 신뢰 칸과 결과 미확정, 그리고 결과 통보(`inDoubtUnits` · `unverifiedUnits`, `source: "picasso"`)에서 가져온다 — UNVERIFIED 는
사건을 열지 않고 결과 통보의 `unverifiedUnits` 로만 보인다(picasso 2026-09-30 답). 열거값 확정은 §19.

**`unknowns` 는 UNKNOWN 상태와 별개인 에피소드의 목록이다.** 진단 스냅샷을 만들 때마다 다시 계산하고, 두 가지로만 빠진다.
- 원천이 더 새로운 관측(`observedAt` 이 더 늦음)으로 참·거짓을 준다.
- 사람이 `confirm` 으로 판정한다(어느 비종료 상태에서든, §7.2). 판정은 그 subject 에 대해 판정 시각 이후의 새 관측이 다시 모름을
  말하기 전까지 유효하다. `CONFIRM_DONE` 후보는 "했다"만 말하므로, "안 했다"는 `confirm(NOT_DONE)` 으로 들어온다.

### 9.2 후보 식별자

형식: **`<kind>:<재료1>:<재료2>:…`** — kind 접두사를 포함한다. 내용에서 결정적으로 만든다. 위치 번호를 쓰지 않는다(재진단 때 같은
번호가 다른 후보를 가리키게 된다). picasso 의 `searchId` · `incidentId` 는 같은 시드의 다른 구동에서 되풀이되므로 **재료로 쓰지
않는다**(추적과 낡음 판정(§8.3)용으로 `ref` 에만 둔다).

| kind | 재료 (순서 고정, 값 안의 `:` 는 퍼센트 인코딩) | 예 |
|---|---|---|
| `APPROVE_REMEDY` | 기체, 주문, 조치 열(`sawSkillTypes` 를 `+` 로 이음) | `APPROVE_REMEDY:hum-02:PATROL-1:pick_place` |
| `CHOOSE_SOURCE` | 주문, 자재, 대체 위치 | `CHOOSE_SOURCE:SEQ-RELOCATE:<자재>:B-03` |
| `OPERATOR_DECISION` | 실행, 단위, 판단 | `OPERATOR_DECISION:ex-9:u-2:REWORK` |
| `SAGA_ACTION` | 사가 정의 `name@x.y.z`, 대상 | (계획 D) |
| `ESCALATE` | 없음 | `ESCALATE` |

식별자는 비교에만 쓰고 분해하지 않는다. 같은 대상의 같은 조치는 시도가 달라도 같은 식별자라 `REPEATED_REMEDY` 를 셀 수 있다.

### 9.3 `candidatesVersion`

`"sha256:" + hex(sha256(JCS({ projectionVersion, candidates })))`. candidates 는 candidateId 순 정렬, 정규화는 RFC 8785(JCS), 64자
전체. `projectionVersion` 을 넣어 투영 코드가 바뀌면 판도 바뀐다. 스냅샷은 넣지 않는다 — 세계가 바뀌었는지는 REVALIDATING 이
판정하고, 이 판은 "승인자가 본 선택지 집합이 같은가"만 답한다. narrator 는 계산하지 않고 되돌려주기만 한다.

### 9.4 이력 항목 (진단 요청의 `history`, 쿼리의 시도 이력)

```json
{
  "attempt": 1,
  "candidatesVersion": "sha256:…",
  "diagnosis": { "outcome": "RECOMMENDED", "candidateId": "APPROVE_REMEDY:hum-02:PATROL-1:pick_place", "picked": null },
  "approval":  { "result": "REJECTED", "by": "PERSON", "reason": "…", "at": "…" },
  "dispatch":  { "result": "NO_PROPOSAL", "idempotencyKey": "…", "approverKind": "AGENT", "delivered": null },
  "evidence":  { "grade": "E1", "outcome": "UNKNOWN" },
  "closedAs":  "REDIAGNOSE",
  "at": "…"
}
```

- `approval.result`: `APPROVED` · `REJECTED` · `EXPIRED` · `null`. `approval.by`: `PERSON` · `POLICY` · `null`.
- `dispatch.result`: `ACCEPTED` · `PERSON_TASK`(사람 과업 발행, 이때 `approverKind`·`delivered` 는 `null`) · picasso 거절 열거값 그대로 ·
  `UNCERTAIN`(나갔는지 모름) · `ANSWER_UNKNOWN`(답의 `schemaVersion` 이나 `refusal` 값을 모름, `DISPATCH_ANSWER_UNKNOWN`) ·
  `CHILD_COMPLETED` · `CHILD_FAILED` · `null`. 사람 과업의 결과는 `evidence` 로 들어온다.
  `dispatch.approverKind` 는 picasso 가 기록한 PERSON / AGENT — `approval.by` 와 두 칸을 다 남겨야 두 시스템의 감사가 이어진다.
- `evidence.outcome`: `DONE` · `NOT_DONE` · `UNKNOWN` · `null`.
- 사람의 현물 확인(`confirm`)은 `evidence.grade = "E3"`(picasso 근거 등급의 작업자 확인)로 남기고, `identityAssurance` 를 함께 싣는다.
  인증이 없는 동안 E3 는 `SELF_ASSERTED` 신원 위의 E3 라는 것이 기록에서 보여야 한다.
- `NO_PROPOSAL` · `REMEDY_NOT_APPLIED` 로 들어간 UNKNOWN(OUTCOME) 은 `unknowns` 에 `{ what: "OUTCOME", source: "picasso" }` 항목으로도 남는다. 해소되면(§5.1)
  빠진다.
- `closedAs`(이 시도가 어떻게 끝났나): `REDIAGNOSE` · `RESOLVED` · `SUPERSEDED` · `UNKNOWN` · `ESCALATED` · `null`(진행 중).
- 도달하지 못한 단계는 키를 빼지 않고 `null`. `unknowns` 항목의 네 칸(subject · what · since · source)과 이력 항목의 여덟 칸도 같다 —
  빠진 키를 `null` 로 읽으면 "못 닿았다"와 "안 적혔다"가 같은 모양이 된다. 계약 0.6 은 키가 빠지면 비재시도 예외로 본다.
  구현 주의: 진단 요청을 싣는 Jackson 설정에 `NON_NULL`/`NON_EMPTY` 포함 규칙을 걸지 않는다(지금 `DataConverterSupport` 는 걸지 않음).
  고정 예제 대조 시험이 이것을 지킨다.

### 9.5 운영자 카드 (`OperatorCard`)

koshei 가 진단 응답(계약 0.6 §4)과 자기 상태로 그린다. narrator 응답의 `card`(표지 줄 목록)는 카드의 한 부분일 뿐이다.

| 카드 칸 | 출처 | 보이는 방식 |
|---|---|---|
| 제안 | koshei 후보(kind, ref) | 사람이 읽는 대상·조치. 식별자는 분해하지 않고 ref 로 그린다 |
| 이유 | 응답 `rationale` | `null` 이면 빈칸이 아니라 "이유 없음"을 명시 |
| 지침 | 응답 `card`(절차 · 먼저 · 금지 · 갈림 · 근거 세기) | 표지 순서대로, 있는 것만 |
| 인용 | 응답 `citations` | **"근거 꾸러미에 있던 문서"** 로 표시. 확정된 사실로 보이지 않게 (R13) |
| 인용 없는 문장 | 응답 `uncitedSentences` | 이유 안에서 따로 표시 |
| 확인 못 한 주장 | 응답 `unverifiedClaims` | `CITATION` / `NUMBER` 구분, `NUMBER` 는 `foundIn` 을 함께 (`[]` = 어디에도 없음, `["query"]` = 질의에만 있음). `NUMBER` 의 `text` 는 khala `numbers[]` 의 값이다. `text` 가 `null` 인 `NUMBER` 는 `numbers[]` 가 없는 옛 응답의 대비책이고, 그때만 "확인 못 한 숫자 n개(위치 모름)"로 묶어 그린다 |
| 확인 불가 | 에피소드 `unknowns` | 있으면 맨 위 |
| 확인할 전제 | UNKNOWN(PRECONDITION) 의 명제(§8.3, 종류별 세계의 사실), 후보 `ref` 의 값, `APPROVE_REMEDY` 면 스냅샷 뒤 합류 증상 | UNKNOWN(PRECONDITION) 일 때만. 명제를 그대로 적고 `confirm` 에 실어 보낸다. 사람도 관측할 수 없는 명제면 그렇다고 적는다(그때는 `unknownMs` 로 끝난다). `SAGA_ACTION` 이면 확인 뒤 자식 사가가 자동으로 돈다는 것을 함께 보인다 |
| 결정 칸 | `proposalId`, `candidatesVersion`, 남은 기한 | `decide` Update 에 그대로 실어 보낸다 (낡음 검사) |
| 신원 수준 | `identityAssurance` | PoC 동안 `SELF_ASSERTED` 표시 |

`cause`(원인 후보와 확실성)는 기록에만 남기고 카드에는 싣지 않는다.

카드 순서는 열려 있다(§19 D). picasso 는 안쪽 루프의 진단을 적어 두되 사람 진단 뒤에 대조 재료로만 보이기를 권한다. 그 전까지
카드는 이 표 그대로이되, **스냅샷에 `WITHHELD` 탐색 줄이 있는 에피소드**는 진단을 부르지 않는다(§5.2 `WITHHELD_HUMAN_FIRST`).
안쪽 루프의 진단이 없으므로 카드에도 쿼리(§7.3)에도 이유 · 지침 · 원인이 없다. 사실(사건·탐색 줄, `unknowns`)과 `ESCALATE` 과업만
보인다. 대조용으로 진단을 부르고 기록해 두었다가 사람 진단이 기록된 뒤에 보일지는 D 의 일부다.

**자동 승인은 "깨끗한" 권고에만.** 정책 `autoApprove.<kind>.requireClean` (기본 `true`)이 켜져 있으면, 다음을 모두 만족하는
권고만 자동 승인한다. 하나라도 어기면 AWAITING_APPROVAL 로 보낸다.
- `rationale != null` — 이유 줄이 없으면 셀 문장이 없어 `uncitedSentences` 가 저절로 `[]` 가 되므로, 비었다는 것만으로는 깨끗하지
  않다(계약 0.6 §4 에도 같은 문장).
- `uncitedSentences == []`, `unverifiedClaims == []` — 계약이 두 칸을 `null` 이 아니라 늘 목록으로 보장한다. 확인 안 된 숫자는 khala
  `numbers[]` 에서 `NUMBER` 항목으로 오고(변경 ④ 전에는 `grounded: false`, 뒤에는 `found_in` 에 근거가 없음), `numbers[]` 가 없는 옛
  응답이면 개수만큼 `text: null` 항목이 온다. 숫자가 있는데 목록이 비어 보이는 일은 없다.
- 알려진 한계: khala 는 유의미한 수(10 이상의 정수 · 소수 · 백분율)만 검사하고 0~9 의 맨 정수는 검사하지 않는다. 그런 수에 기댄
  이유는 깨끗해 보일 수 있다. 자동 승인 범위(정책의 kind·skill 한정)를 좁게 두는 까닭 가운데 하나다.

사람이 읽지 않는 경로일수록 확인 못 한 주장에 기대지 않게 하려는 것이다.

---

## 10. 정책 표

### 10.1 모양 (초안)

```yaml
version: 2026-09-27.1
expiresAt: null
agentLayerEnabled: true
autoApprove:                                               # 자동 승인이 가능한 종류만 칸이 있다
  APPROVE_REMEDY: { allowed: true, requireClean: true, skills: [pick_place] }
actionCatalog: []                                          # SAGA_ACTION 항목, 항목마다 autoApprove.allowed (계획 D)
deadlines:
  correlatingMs: …        # 병합 창
  approvalMs: …           # 라인 버퍼가 버티는 시간에서
  approvalValidityMs: …   # 승인 후 실행까지 유효한 시간 (§5.1)
  revalidateMs: …
  recordMs: …
  dispatchMs: …
  childMaxMs: …
  evidenceMs: …
  unknownRecheckMs: …
  unknownMs: …
  escalatedRetentionMs: …
  episodeMs: …
diagnosis: { scheduleToCloseMs: 1200000, startToCloseMs: 540000, heartbeatMs: 30000, maxAttempts: 2 }
# 예시값. 2회 × 540초 + 큐 대기 여유. 브리지 혼잡으로 돌아온 시도(≈20초)도 한 시도로 세므로, 혼잡이 잦으면
# maxAttempts 를 올리고 예산을 그만큼 늘린다(검증 규칙 §10.2 가 둘을 함께 강제한다).
maxAttemptsPerEpisode: 3
repeatedRemedyThreshold: 2
snapshotMaxBytes: 262144
correlation: [ …결정적 병합 규칙… ]
```

조치 유형 "끄기"(R6)는 `autoApprove.<kind>.allowed: false` 다. 후보에서 빠지지 않고 **자동 승인만 막힌다**(사람 승인으로 간다).
R6 검증("끈 유형은 자동 실행 없이 사람 승인으로")과 맞춘 것이다. `CHOOSE_SOURCE` · `OPERATOR_DECISION` 은 사람 과업이라 칸이
없고, 따라서 R6 의 "끄기" 대상도 아니다 — 원래 사람이 결정하는 과업이므로 끌 자동 실행이 없다. 과업 알림 자체를 줄이려면 병합
규칙과 알림 규칙(R10)으로 한다.

### 10.2 검증 (`validate`, 순수 함수)

- `diagnosis.scheduleToCloseMs ≥ maxAttempts × startToCloseMs`, `heartbeatMs < startToCloseMs`, `scheduleToCloseMs ≤ episodeMs` —
  어기면 거절. 기한이 겹치면 가장 작은 것이 조용히 이기므로, 예산이 벽보다 작으면 LLM 경로가 설정만으로 죽는다. 같은 까닭으로
  `maxAttempts` 를 적어 두고 예산이 그만큼의 시도를 담지 못하는 설정도 거절한다 — 적힌 시도 수와 실제 시도 수가 조용히 달라지기
  때문이다. 라인 예산이 두 시도를 담지 못하면 `maxAttempts: 1` 로 적는다.
- `snapshotMaxBytes` < Temporal 페이로드 경고선(512KB).
- `deadlines` 의 모든 칸 존재·양수, `unknownRecheckMs < unknownMs`, `escalatedRetentionMs` 를 뺀 상태 기한 ≤ `episodeMs`.
- `autoApprove` 에 알 수 없는 kind 나 `CHOOSE_SOURCE` · `OPERATOR_DECISION` 이 있으면 거절.
- `actionCatalog` 항목은 정확한 판의 사가 정의만(§8.4).
- **UNKNOWN 의 실행 차단은 스키마에 칸이 없다.** 정책으로 풀 수 있는 구조 자체가 없다(R8).

### 10.3 관리 (R6, R14 — 계획 D)

- git 정본(`model/episode-policy/*.yaml`) + DB 의 활성 판 포인터 + 추가 전용 감사. 모양은 이미 있는 `FsmDeploymentStore`
  (활성·이전 판, soak, 롤백, `fsm_deployment_audit`)를 따른다.
- 활성화는 CLI `policy validate` → `policy activate <version>`, 런타임과 다른 권한. 런타임은 읽기만 한다. 활성화 때 조치 목록의
  `requiresPerson` 을 계산해 박는다.
- **자동 승인 = koshei 정책 ∧ picasso 자격 선언.** koshei 는 자기 표만 검사하고, picasso 가 승인 창구에서 자기 자격을 검사해
  거절하면 그 열거값이 `DispatchReturned` 로 들어온다. 정책 표 모양은 picasso 자격 선언을 본으로 삼는다(판, 만료, 철회, 감사).

### 10.4 에이전트 층 끄기 (R5)

`agentLayerEnabled: false` 가 활성화되면:
- 새 에피소드: 시작 때 `readPolicy` 로 알고 바로 ESCALATED(`AGENT_LAYER_OFF`), 알림 한 번. 감시자는 판정하지 않는다.
- 진행 중: 다음 결정 시점의 `readPolicy` 로 안다. 대기 중인 에피소드가 곧바로 알게 하려고 활성화 CLI 가 열린 에피소드 전체에
  `agentOff` signal 을 방송한다(계획 D). DISPATCHED 이전이면 즉시 인계(진단 취소), DISPATCHED / AWAITING_EVIDENCE 면 ESCALATED 로
  가되 결과·근거 수신은 기록으로 계속한다 — 나간 조치의 뒷정리를 버리지 않는다.
- 계획 B(P0) 시험은 `agentOff` signal 을 직접 보내고, 파일 정책의 스위치를 바꿔 신규 에피소드를 확인한다.

---

## 11. 정확히 한 번 (R2)

- 멱등 열쇠 = `(에피소드 인스턴스 id, 시도 번호, 조치 유형)`, 워크플로 안에서 결정적으로 파생.
- 중복 위험은 워크플로 재생이 아니라(재생은 액티비티를 다시 보내지 않는다) **`dispatch` 액티비티의 재시도**다 — picasso 가 이미
  받았는데 응답 전에 워커가 죽은 경우. 열쇠는 koshei 쪽 기록과 자식 사가에만 쓴다. picasso 승인 창구에 실어도 소용이 없다(아래).
- 자식 사가: child workflow id = 열쇠(Temporal 이 중복 시작을 막는다).
- **picasso 2026-09-30 답:** 같은 제안이 두 번 나가는 일은 이미 막혀 있다(승인이 접수로 이어진 뒤 제안이 지워지고 창구는 구동 루프와
  같은 자물쇠를 지난다, 둘째 시도는 `NO_PROPOSAL`, 같은 판이면 `REMEDY_NOT_APPLIED`). 그래서 둘 다 「내 앞선 시도가 이미
  들어갔을 수 있음」이다. **모자란 것은 내 요청이 들어갔는지 아는 길이고 그 표면은 없다** — 승인 요청은 다섯
  칸만 읽고 나머지는 조용히 버린다(멱등 열쇠를 실어도 버려진다), 승인의 대장이 없다, `approvedBy` 는 모듈 안에서만 보인다. 그래서
  열쇠에 기대지 않고, **`NO_PROPOSAL` 과 `REMEDY_NOT_APPLIED` 는 「이미 소모됐을 수 있음」으로 UNKNOWN(OUTCOME)에 간다**(§5.2).
  거기서 DONE 에 닿으면 「결과는 됨, 누가 했는지 모름」으로 기록한다. 재시도로 풀지 않는다 — 모르면
  다시 하지 않는다. 부분 추론(그 (기체, 주문)에 FOUND · WITHHELD 줄이 한 번도 없으면 선 적이 없음)은 대장이 시나리오 끝에 한 번 나오므로
  쓰지 않는다. picasso §7.3 표가 `NO_PROPOSAL` 에 적은 「다시 읽고 다시 시도」는 따르지 않는다(picasso 도 문서 결함으로 올렸다). 값 분리
  (`CONSUMED`)는 사용자 결정이다(§19 B).
- 시험의 Mock picasso 는 실물과 같게 소모를 흉내 낸다: 열쇠를 버리고 결과 조회를 내지 않으며, 소모된 제안의 둘째 시도에는
  `NO_PROPOSAL`(같은 판이면 `REMEDY_NOT_APPLIED`)을 돌려준다. R2 시험은 그때 UNKNOWN(OUTCOME)을 기대한다.

---

## 12. 감시자 (`EpisodeWatcherMain`, 별도 프로세스, 계획 C)

narrator 의 "상태 없음, 부작용 없음" 계약을 지키려고 감시자는 koshei 쪽에 둔다. **운반만 하고 판정하지 않는다**(정책 판정도
워크플로가 한다).

- picasso 내보내기 한 벌은 **`manifest.json` 이 나타난 뒤에만** 읽는다(임시 파일 → 이름 바꾸기, manifest 가 마지막).
- 커서 = 마지막으로 읽은 식별자. `incident-N` 같은 형식을 분해하지 않는다. Postgres 에 둔다.
- 중복 제거 열쇠(`eventId`): 사건 줄 `(runId, digest)`, 탐색 줄 `(runId, searchId)`. 둘 다 없으면 추측하지 않고 멈춘다. `runId` 를
  빼면 같은 시드의 두 번째 구동이 이미 본 것으로 접혀 사라진다.
- `null` 과 `[]` 를 가른다(예: 결품 줄 `alternatives` 가 null = 셀에 못 물음, 빈 목록 = 자리가 없음).
- 새 증상: `correlate(event, rules)` 로 병합 키 → `signalWithStart(ep:<key>, symptom)`. 애매하면 **묶지 않고** 새 에피소드를 열고
  서로 참조를 붙인다. LLM 에 묻지 않는다.
- 완료 근거: **사건 대장을 스캔해 찾지 않는다** — 제대로 끝난 단위는 사건이 안 된다. 완료 근거는 picasso 의 결과 통보(JobResponse)다:
  `physicalState` · `requiredEvidence` · `reachedEvidence` · `completedUnits` · `unverifiedUnits` · `inDoubtUnits` · `operatorRequired` ·
  `blockedBy`. picasso 가 상태가 바뀐 그 펌프 안에서 아웃박스에 넣고, 담는 쪽이 `pending()` 으로 꺼내 나르고 `ack(jobResponseId)` 로
  닫는다. 담는 쪽이 상류에 나르는 결과 통보를 koshei 에도 나르고, 감시자는 그것을 `evidence` signal(`EvidenceArrived`)로 운반한다.
  펌프 단위로 실시간이고 picasso §6 승격과 무관하다(picasso 2026-09-30 답). 나르는 모양은 담는 쪽과 함께 정한다(§19 C).
- 결과 통보 → 에피소드: 대응(`(기체, 주문)` / `(실행, 단위)` → 에피소드)은 **실행 의도 `Record` 에 함께 적는다** — `Dispatch` 보다
  먼저 서므로 수락과 같은 펌프에서 나온 결과 통보도 찾아간다(§5.2 는 답보다 먼저 온 근거를 보관한다). 중복 제거 열쇠는
  `jobResponseId`. 사건 시각은 감시자가 받은 시각이다(`observedAt`). **워크플로가 이 시도의 `Recorded` 를 처리한 뒤에 처리한 결과
  통보만 친다** — 이력의 순서로 정하고 두 시계를 견주지 않는다. 앞선 시도의 늦은 통보는 지금 시도의 근거가 되지 않는다.
- 판정(워크플로 안의 순수 함수 `judgeEvidence`), **이 순서로**:
  1. 기록만: 대상과 이어지지 않음 · 이 시도의 `Recorded` 앞 · 이미 본 `jobResponseId`.
  2. UNKNOWN: `inDoubtUnits` · `unverifiedUnits` 가 비지 않았거나 `operatorRequired` 가 참 → UNKNOWN(OUTCOME), 해당 단위를
     `unknowns` 에도 넣는다(§9.1). NOT_DONE 으로 접지 않는다.
  3. DONE: 1 · 2 에 걸리지 않고 대상 단위가 `completedUnits` 에 있다.

  한 통보가 DONE 과 UNKNOWN 을 함께 말하면 UNKNOWN 이다. 모호한 통보로 자동 RESOLVED 하지 않는다. NOT_DONE 은 지금 결과 통보에서
  나오지 않는다 — 사람의 `ConfirmedOutcome` 만 낸다.
- 결과 통보와 (기체, 주문) · 대상 단위를 잇는 칸은 답에 없다 — picasso 에 묻는다(§19). **그 답이 오기 전까지 결과 통보로는 DONE 에
  닿을 수 없다.** 모든 실물 에피소드는 사람(`confirm`) 또는 기한으로 끝난다.
- **파일 내보내기는 결과 통보를 싣지 않는다.** 담는 쪽이 설 때까지 감시자는 개발용으로 파일 내보내기에서 접수만 한다. 그 길에서는
  완료 근거가 오지 않아 AWAITING_EVIDENCE 가 `EVIDENCE_EXPIRED` 로 끝난다(모르면 끝내지 않는다). 근거 경로 시험은 Mock 담는 쪽
  (계획 C)이 결과 통보 모양으로 넣는다 — 답보다 먼저 온 것, 관계없는 것, `jobResponseId` 가 겹치는 것을 섞어 낸다.

---

## 13. 기록 (R11)

- `episode_event(episode_instance_id, seq, kind, payload jsonb, at)` 추가 전용. 런타임 계정에서 `UPDATE`/`DELETE` 권한 회수.
  기존 `schema.sql`(DROP 포함)과 분리된 스크립트로 만든다.
- 필수 항목: 입력 스냅샷 · 판 칸 다섯(`promptVersion`, `corpusVersion`, `searchFingerprint`, `modelId`, `narratorCommit`) ·
  조치안 전문(이유, 인용, `uncitedSentences`, 검증 못 한 주장 분리) · 정책 판정(정책 판 포함) · 승인자(PERSON/POLICY, picasso 쪽
  PERSON/AGENT, `identityAssurance`) · 멱등 열쇠 · 완료 근거(등급, 3값).
- 판 칸은 **"왜 달라졌나"를 가르는 조사 재료**다. 같은 질문에 같은 결정을 지키는 것은 판 칸이 아니라 narrator 의 첫 결과
  저장소다(§8.1). 샘플링(온도 등)은 응답에 드러나지 않으므로 판 칸이 같아도 답이 다를 수 있다는 점을 조사 때 전제한다.
- 영향받은 작업 id · 차대번호 칸을 두어 품질 이력과 잇는다(계획 D).
- Temporal 이력은 보존 기간이 지나면 지워진다. **감사 정본은 이 테이블**이고, Temporal 이력은 재생·디버깅용이다.

---

## 14. 기존 코드와의 충돌과 처리

| 지금 코드 | 충돌 | 처리 |
|---|---|---|
| `/api/publish` 가 인증 없이 jar 를 받고 `Class.forName(fqcn, true, …)` 로 static 초기화를 실행 (`PublishController.kt:22`, `PluginLoader.kt:19`) | R12 목록 밖 부작용 경로, 원격 코드 실행 | **계획 A.** 에피소드 배포에서 publish 끄기, `initialize=false`, 제어면 127.0.0.1 바인딩 |
| 실행 전체 단일 `approved` 플래그 (`SagaWorkflowImpl.kt:32,76`) | 게이트별 승인 불가 | 에피소드는 Update(§7.2). 자식 사가는 `autoApprove=true` 고정으로만 시작(§8.4) |
| 타이머 전무, 게이트 무기한 대기 (`SagaWorkflowImpl.kt:76`) | R4 | 에피소드 대기는 전부 기한(§5.1). 자식은 `interactive=false`, `autoApprove=true`, 부모 쪽 타이머 `childMaxMs` |
| 워커 시작마다 YAML 재해석·맨 이름 바인딩 (`Worker.kt:110`), 배포 정의도 `planFor` 가 블록 `^x`/`latest` 를 다시 풂 (`Worker.kt:72-80`, `Registry.kt:70-76`) | R3, 재생 비결정성 | 조치 목록은 정확한 판으로 고정된 정의만 받는다(§8.4) |
| drift 자동 교정: 20초 스케줄, 먼저 시작 후 중복이면 abort (`AutoCorrectDispatcher.kt:72-79`), 승인에서 1시간 정체 (`:38`) | R1, R4, R5 | 에피소드 모드에서 자동 디스패치 끔. drift 는 감시자의 두 번째 입력으로 DETECTED 만(계획 C) |
| ot-safe-hold 의 `opcua.call` 이 항상 `Recipe/ApplyRecipe` 를 발사, 정책 검사 없음 (`OpcUaCallBlock.kt:51`, `model/ot-site.yaml:13`) | "안전 보류"가 레시피 적용 | D5: 기록만. 계획 D 에서 safe-hold 전용 명령 노드와 `opcua.call` 정책 검사 |
| 모름을 실패로 접음: NCMD 응답 없음 → `ok=false` (`SparkplugNcmdApplyPort.kt:77,96`), 완료 비트 시간 초과 → `ok=false` (`OpcUaApplyPort.kt:121-124`), 둘 다 `PermanentBlockFailure` → 역순 보상 | R8 | 계획 D: 블록이 "모름"을 따로 보고하고 보상하지 않는 경로. 그 전까지 `SAGA_ACTION` 은 쓰지 않는다 |
| 공유 `source_rows` 에 세계 상태 사본, `db.read` 가 테이블 전체 읽음 (`SourceRowSeeder.kt:15`, `DbReadBlock.kt:12`) | R15 | 에피소드 경로에서 쓰지 않음. 참조 id + 관측 시각만 |
| `schema.sql` 재실행 시 `comp_ledger`, `reconciliation_provenance` DROP (`schema.sql:3,52`), `RunStore` 가 행 DELETE (`RunStore.kt:92-94`) | R11 | 에피소드 기록은 별도 스크립트, 추가 전용 |
| 끄기 스위치가 기능별 환경변수, 재시작 필요 (`AutoCorrectBean.kt:17`, `RunReconciler.kt:60`, `SoakSupervisorBean.kt:15`) | R5, R6 | 정책 표의 데이터 스위치 (§10.4) |
| 제어면 한 프로세스에 승인·publish·폐기·스케줄러, 인증 없음 | R14 | 정책 편집을 CLI 로 분리, 제어면은 조회·결정 중계만. **인증 부재는 PoC 한계로 명시**, 기록에 `identityAssurance: SELF_ASSERTED` |

---

## 15. 요건 대응표

| 요건 | 우선 | 수단 (이 문서) | 검증 | 계획 | 의존 |
|---|---|---|---|---|---|
| R1 동시 재개 | P0 | 워크플로 id = 병합 키, signalWithStart, 순차 처리, 늦은 `decide` 는 값으로 거절 (§7.1, §7.2) | 승인 Update 와 기한 타이머 동시 발화 수천 회, 결과는 둘 중 하나, 섞임 0 | B | — |
| R2 정확히 한 번 | P0 | 결정적 열쇠(koshei 기록·자식 사가), 두 번째 승인은 picasso 가 막음, `NO_PROPOSAL` · `REMEDY_NOT_APPLIED` → UNKNOWN · 재시도 없음 (§11) | dispatch 직후·완료 보고 전 워커 강제 종료, Mock 로봇 반영 1회, 재시도는 UNKNOWN(OUTCOME) | C | picasso ★ |
| R3 진행 중 버전 | P1 | getVersion, schemaVersion, 조치 목록 판 고정 (§7.5, §8.4) | 상태별로 멈춘 이력을 저장해 새 코드로 재생 + 실배포 후 정상 종료 | D | — |
| R4 시간 예산 | P0 | 모든 상태 기한(§5.1), 만료 경로 하나(기본 동작 유지 + ESCALATED), ScheduleToClose | 승인 무응답·진단 지연 → ESCALATED, dispatch 0. 라인 정지 0 은 라인 쪽 결정적 규칙(수동 전환·호출)이 선 뒤 (§19) | B | picasso, 라인 쪽 규칙, narrator(Mock) |
| R5 에이전트 장애 | P0 | 시작 시 정책 읽기, `agentOff`, 인계 규칙, Mock 진단으로 LLM 없이 동작, koshei 워커에 picasso 를 세우지 않음 (§2, §8.1, §10.4) | narrator 워커 전멸 / Postgres 중단 / koshei 워커 전멸 / Temporal 중단 각각: dispatch 0, 복구 후 재개. 라인 가동은 picasso Mock 셀 | B (방송 CLI 는 D) | picasso |
| R6 배포 없이 대응 | P1 | 정책 표 판·감사·조치 유형별 on/off (§10) | 운영 중 조치 유형 하나 끔 → 이후 자동 실행 없이 사람 승인으로 | D | picasso(자격) |
| R7 신호의 한계 | P0 | 원천 재조회, 값 + 시간창 판정, 감시자 중복 제거, 완료 근거는 결과 통보(대장 스캔 아님), 낡은 권고는 `searchId` 로 (§8.3, §12) | 폴링보다 짧은 펄스, 순서 역전, 중복 주입 → 재확인·완료 판정 대조표 | C | picasso, 담는 쪽(§19 C) |
| R8 확인 불가 | P0 | UNKNOWN 두 종류, 3값 타입, 실행 차단 코드 고정, 투영 제외, `reassign` 부르지 않음 (§5.1, §6, §8.4, §9.1) | dispatch 후 통신 절단 → 자동 재할당 0(koshei 가 부르지 않음까지만. 배정 정책의 재할당은 koshei 만으로 검증 못 함, §8.4), 현물 확인 요청 1 | B (자식 사가 보상 0 은 D) | picasso |
| R9 모델 변경 관리 | P1 | 기록에 판 칸 다섯 (§13) | narrator 골든셋 재생 지표(유효 선택률, 후보 밖 비율, 확인 불가 중 실행 권고 비율 = 0) | D | narrator, khala |
| R10 알림 | P1 | 병합 키, 병합 창, 애매하면 상호 참조, 상태 전이 때만 알림 (§5.1, §12) | 같은 원인 증상 3개 동시 주입 → 에피소드 1, 알림 1 | C | picasso |
| R11 기록 | P1 | `episode_event` 추가 전용 (§13) | 임의 차대번호에서 에피소드와 근거 전체 역추적 | B(테이블)·D(항목 전체) | picasso, narrator |
| R12 조치 유형만 | P0 | 후보 종류 고정 + 조치 목록(판 고정 사가) + picasso 제안, publish 봉쇄 (§8.4, §14) | 부작용 호출 경로 정적 감사, 목록 밖 조치 주입 거부 | A·B | picasso |
| R13 LLM 출력 제약 | P0 | 후보 참조만, 이중 검증, 사유 분리 (§5.2, §9) | 형식 오류·후보 밖·확인 불가 중 실행 권고 주입 → 전부 실행 전 거부·기록 | B | narrator, khala |
| R14 정의·실행 분리 | P1 | 정책 편집 CLI 분리, narrator 별도 프로세스, 제어면 축소 (§4.2, §10.3) | 오타 정책이 검증 없이 활성화되는 경로 0, narrator 워커 강제 종료 시 다른 에피소드 영향 0 | D | narrator |
| R15 원천 비소유 | P1 | 참조 id + 관측 시각만, 판단 전 재조회 (§8.3, §14) | 저장소 감사, 원천 값 변경 직후 판단 반영 | C | picasso |

`outer-loop-requirements.md` 와 다르게 가져간 곳:
- R12 · R13: LLM 은 조치 유형·대상·값을 채우지 않는다. 계산된 후보 중 하나만 가리킨다. "존재하지 않는 대상" 시험은
  `OUT_OF_CANDIDATES` 하나로 줄어든다.
- R13: 인용의 `verified` 는 "그 문서가 근거 꾸러미에 있었다"는 뜻이지 그 문서가 주장을 뒷받침한다는 뜻이 아니다(어느 검증기도
  뒷받침 여부는 보지 않는다). 그래서 승인자 카드에는 인용이 "확정된 사실"이 아니라 **"근거 꾸러미에 있던 문서"**로 보인다.
  인용이 안 붙은 문장은 narrator 가 세어 `uncitedSentences` 로 싣고, 숫자는 khala 가 찾은 곳(`found_in`)으로 이유의 정책 값이
  근거에서 왔는지 narrator 가 본다.
- R10: 애매한 병합을 LLM 에 묻지 않는다.

---

## 16. 구현 계획 나누기

하나의 계획으로 묶기엔 독립된 부분이 넷이다. 계획마다 따로 쓰고, 이 순서로 진행한다.

| 계획 | 범위 | 선행 | 외부 대기 |
|---|---|---|---|
| **A. publish 봉쇄** | `/api/publish` 끄기 설정, `Class.forName(…, false, …)`, 제어면 127.0.0.1 바인딩. 기존 게이트로 퇴행 확인 | — | — |
| **B. 에피소드 코어** | `:episode`(전이·투영·식별자·판·정책 검증·판정 함수), `:episode-runtime` 구동부·타이머·Update·쿼리, Mock `diagnose`·Mock picasso(`revalidate`/`dispatch`), 파일 정책, `episode_event` 테이블, 제어면 경로, 고정 예제 요청 넷 | — (A 뒤에 하는 것은 순서 선호) | — (Mock 으로 진행) |
| **C. 현장 연결** | 감시자(접수·근거·병합, drift 입력), 에피소드 모드에서 drift 자동 디스패치 끄기, 실제 picasso 승인 창구 클라이언트, 결과 통보 수신, Mock 담는 쪽(먼저 온 · 관계없는 · `jobResponseId` 가 겹치는 결과 통보를 냄, §12) | B | picasso · 담는 쪽 (§19) |
| **D. 운영 관리 (P1)** | 정책 git+DB 포인터·CLI·`agentOff` 방송, `SAGA_ACTION`·조치 목록·자식 사가, 블록의 "모름" 보고, 기록 항목 전체·품질 연결, R3 재생 시험, D5 결함 | B | khala·narrator(판 칸) |

---

## 17. 시험 전략

| 층 | 무엇 | 도구 |
|---|---|---|
| `:episode` 순수 | §5.2 전이 표 전수, 정책 검증, 투영·식별자·판 | JUnit. 무작위 사건 열 속성 시험: UNKNOWN 중 Dispatch 0, 같은 열쇠 Dispatch ≤ 1, Record 없이 Dispatch 0, 모든 열이 유한 단계 안에 종료 상태 도달 |
| 구동부 | R1 경합, R4 기한, 인수(하트비트 취소), 끄기, 늦은 `decide` 의 값 거절 | `TestWorkflowEnvironment` 시간 건너뛰기 (기존 `SagaWorkflowTest` 방식) |
| 재생 | R3: 상태별 멈춘 이력 저장 → 새 코드 재생 | `WorkflowReplayer` (기존 `ReplayDeterminismTest` 방식) |
| 장애 주입 | R2 · R5: 워커·DB·Temporal·narrator 강제 종료 | 실제 프로세스. 기존 `run-crash-recovery.sh` 방식. 고정 sleep 대신 조건 대기 |
| 계약 | Mock 진단 ↔ narrator 워커 | 고정 예제 넷. **요청은 koshei 가 투영 함수로**, **응답은 narrator 가** 쓴다 |

고정 예제 요청 넷과 재료(picasso 인계본, 읽기만. 채점용 정답표는 쓰지 않는다):

| 예제 | 재료 | 모양 |
|---|---|---|
| 권고 | run-1 탐색 `FOUND` + 같은 기체의 사건 | `APPROVE_REMEDY` 후보가 선다 |
| 근거 없음 | run-1 탐색 `NONE` | 조치 후보 없음, `ESCALATE` 만 |
| 후보 밖 | run-1 결품 탐색(대체 위치 하나) | `CHOOSE_SOURCE` 후보, 응답은 목록 밖을 고름 |
| 확인 불가 | run-1 연결 끊김 사건 | 실행 계열 빠짐, `ESCALATE` 와 `CONFIRM_DONE` 만 |

곁들여 시험할 것: 진행 관측 가능 여부가 null 인 사건(3값 입력), 같은 시나리오를 두 번 돌린 run-1·run-2 쌍(`runId` 가 중복 제거 열쇠에
필요한 이유), 재발 시나리오 run-3(`OPERATOR_DECISION`, `REPEATED_REMEDY`), `WITHHELD` 탐색(진단을 부르지 않고 `WITHHELD_HUMAN_FIRST`).

---

## 18. 설계 판단 기록

- **새 워크플로 타입.** 사가는 DAG 를 한 번 통과한다. 에피소드는 되돌아가는 고리와 타이머가 있는 상태기계다. SagaWorkflow 를
  고치면 둘 다 망가진다.
- **Temporal 전용.** 에피소드는 운영자가 그리지 않으므로 IR·Conductor 중립이 필요 없다.
- **순수 전이 모듈.** 판단이 구동부에 들어가면 시험이 닿지 않는다. 또 `humanoid-ops-agent-architecture.md` 8.6절은 초기 운영에
  직접 만든 상태기계를, Temporal 은 확장 단계를 예측한다. 이 설계는 그와 다르게 처음부터 Temporal 을 쓴다 — koshei 가 이미
  Temporal 운영 비용(compose, 워커, 시험 환경)을 치르고 있기 때문이다. 전이 함수를 순수 모듈로 빼 두어, 운영 주체가 직접 만든
  상태기계를 요구하면 구동부만 바꿔 옮길 수 있게 한다(`outer-loop-requirements.md` 6절의 "누가 운영하느냐" 기준).
- **ESCALATED 를 종료로 두지 않음.** 사람에게 넘긴 뒤에도 이미 나간 조치의 결과와 근거가 들어온다. 닫아 버리면 그 기록을 받을 곳이
  사라진다. 대신 보존 기한으로 반드시 닫힌다.
- **`abort` 없이 `takeover` + `close`.** "중단"의 두 뜻(에이전트를 멈춤 / 에피소드를 무효로 끝냄)을 두 입력으로 가른다.
- **`NO_PROPOSAL` · `REMEDY_NOT_APPLIED` → UNKNOWN.** 나갔는지 모르면 다시 하지 않는다. picasso 2026-09-30 답도 같다(나누기 전까지
  「이미 소모됐을 수 있음」). `CONSUMED` 가 들어오면 좁힌다(§19 B).
- **거절은 기본 ESCALATED.** 재진단이 고칠 수 있다고 보인 거절 값만 DIAGNOSING 으로 보낸다(지금은 없음). 거절된 것을 다른 후보로
  돌려 누르는 길을 구조로 닫는다.
- **지금 기준이 아닌 읽기로 TRUE 를 내지 않음.** 실시간 대장이 없는 동안 모든 실물 에피소드는 UNKNOWN(PRECONDITION)에서 사람에게 간다(§8.3).
- **결과 통보는 UNKNOWN 을 먼저 본다.** 한 통보가 DONE 과 UNKNOWN 을 함께 말하면 UNKNOWN 이다. 모호한 통보로 자동 RESOLVED 하지
  않는다(§5.2, §12).
- **`WITHHELD` 면 진단을 부르지 않음.** 부르지 않은 진단은 샐 수 없다. 대조용 진단은 §19 D 에서 정한다.
- **Update.** 동기 응답으로 승인자가 결과를 바로 받고, 값으로 돌린 거절이 이력에 남는다(§3.1).
- **Python 액티비티 워커.** CLI 를 ProcessBuilder 로 부르는 안은 취소 시 자식 프로세스 정리, 표준출력 파싱, Windows 경로 문제가
  붙어 가장 취약하다. HTTP 는 표면과 운영이 하나 더 는다.
- **ScheduleToClose 로 진단 기한.** StartToClose 는 워커가 집은 뒤에야 시작하므로 큐 대기가 기한에서 빠진다.
- **정책은 판 고정하지 않음, 코드는 고정.** 운영 중 정책 조정이 진행 중 에피소드에도 먹어야 한다. 읽기 결과가 이력에 남아 재생은
  안전하다.
- **자식 사가는 사람 관문을 절대 기다리지 않게.** 관문이 있는 조치는 PERSON 승인 뒤에만 시작하고, 자식은 언제나 `autoApprove=true`
  로 연다. 단일 승인 플래그와 무기한 대기라는 기존 사가의 두 결함을 에피소드 경로에서 구조적으로 피한다.
- **사람 과업 종류(`CHOOSE_SOURCE`, `OPERATOR_DECISION`)에는 승인 단계가 없다.** 사람이 사람에게 줄 과업을 승인하는 것은 한 겹을 더할
  뿐이다. 과업을 받은 사람이 곧 결정한다.
- **실행 의도 기록을 사건으로 되먹임.** "기록 없는 조치 없음"을 구동부의 약속이 아니라 전이 표의 성질로 만들어 순수 층에서 시험한다.

---

## 19. 열린 것

**기다리는 것**

| 무엇 | 기다리는 곳 | 막는 항목 |
|---|---|---|
| 결정적 기본 동작 층의 뒤 절반(슬롯을 수동 처리로 넘기는 상태·명령, 현장 호출) | 라인 쪽 결정적 규칙, 주인 미정 (picasso 의 것이 아니고 에이전트 층과 뗀다). 반응 시간은 담는 쪽의 `pump()` 주기라 C 에 딸린다 | R4 · R5 의 라인 정지 0 검증 |
| `unknowns.what` 열거값 — koshei 의 열거값이다. picasso 는 대응만 확인한다(2026-09-30 답에는 없음). 검토 제안(결정 전): 진행 관측 가능 여부가 null → `OBSERVATION`(§17 이 시험한다, 유지), `inDoubtUnits` → `OUTCOME`, `unverifiedUnits` 또는 `verification ≠ MATCHED` → `VERIFICATION`, `reachedEvidence` 가 `requiredEvidence` 에 못 미침 → `EVIDENCE`. 코드와 narrator 계약 §3.3 을 바꾼다 | 사용자 결정 (대응 확인은 picasso) | R8 세부 |
| 판 2 의 거절 값 전체 목록 — 답이 이름을 댄 넷(`NO_PROPOSAL` · `REMEDY_NOT_APPLIED` · `REFUSED_BY_GATE` · `NOT_DECLARED`) 밖에 더 있는가, koshei 의 물음에 나온 `PROPOSAL_CHANGED` 가 그 하나인가 | picasso 에 물음 | 그 전까지 넷 밖의 값은 `DISPATCH_ANSWER_UNKNOWN` (§5.2, §8.4) |
| 사건 줄 `resolution` 이 붙는 범위 — 「판단이 아직 안 실린 가장 최근 사건」이 판단한 (실행, 단위)의 사건과 늘 같은가 | picasso 에 물음 | `OPERATOR_DECISION` 의 근거로 `resolution` 을 쓰는 것 (§8.4) |
| `NOT_DECLARED` 를 가르는 선언이 자격 선언과 같은 표인가 | picasso 에 물음 | §8.4 선언 행 |
| 결과 통보를 (기체, 주문) 과 대상 단위에 잇는 칸 — 승인 답에서 executionId 나 단위를 알 수 있는가 | picasso 에 물음 | §12 의 DONE 판정 (`completedUnits` 대조). 그 전까지 결과 통보로 DONE 에 못 닿고, 실물 에피소드는 사람이나 기한으로 끝난다 |
| khala 변경 여섯: 프롬프트 지문 결함과 `prompt_version`, 브리지 구조화 출력, `answer_context`, `numbers[].found_in`, `corpus_version`, `search_fingerprint` | khala | R9 · R11 판 칸, `UNCITED` 재진단 조정 |
| 개발용 동시 한도 | khala · narrator | narrator 워커 동시 실행 설정 (벽 순서는 계약 0.5 에서 정해짐) |
| 고정 예제의 응답 넷 | narrator | §17 계약 시험 |
| 검색에 쓰지 않는 자료 칸 (권고 모드 전제, khala `answer_context`) | khala | 진단 Mock → 실연결 전환. 그 전에는 실물 호출이 khala 422 → narrator `unreachable`(비재시도) → `DIAGNOSIS_FAILED` 로 끝난다. 배선 시험은 이 실패 길까지는 끝까지 볼 수 있다 |

narrator 쪽 현황(2026-09-28): 진단 워커가 섰다 — 큐 `narrator-tq`, 액티비티 `diagnose`, `python -m diagnose.worker`
(narrator 커밋 `f71ca2e`). 실물 Temporal 에 붙여 돌린 적은 아직 없다.

**picasso 2026-09-30 답으로 닫힌 것**

| 물음 | 답 | 반영 |
|---|---|---|
| 바깥 루프의 자리 | 라이브러리 밖이 맞다(picasso §6). 다만 koshei 는 담는 쪽이 아니라 소비자 | §1, §2 4, §4.2 |
| 후보 범위 | 다섯 — 편지의 넷 + 막는 결함의 감수. 우선순위 조정은 코드에 없다 | §9.1 (다섯째는 A) |
| `CONFIRM_DONE` 의 근거 | `verification: MATCHED` 일 때만 | §9.1 (7c7febd) |
| 권고를 후보 가리키기 + 본 후보 열로 | ADR 44 와 맞다. `sawSkillTypes` 는 같은 모양의 새 제안을 못 가른다 → `searchId` 낡음 규칙 | §8.3, §9.2 |
| 실시간 완료 근거 | 결과 통보(JobResponse), 담는 쪽의 `pending()` / `ack`. 대장에도 파일 내보내기에도 없다 | §5.1, §5.2, §8.4, §12 |
| `NO_PROPOSAL` 세 경우 · 열쇠로 결과 조회 | 두 번 나감은 이미 막힘(둘째 시도는 `NO_PROPOSAL`, 같은 판이면 `REMEDY_NOT_APPLIED`). 내 요청의 결과를 아는 표면은 없고 열쇠는 버려진다 | §11 (값 분리는 B) |
| 거절 열거값을 전이 입력으로 | 동의, 조건 넷. 이 설계는 거절을 기본 ESCALATED 로 둔다 | §5.2, §5.3, §8.4 |
| 운영자 보류는 사람만 | 코드 검사가 아니라 문이 없어서. `resolve` 는 승인자를 안 받는다. 판단은 사건 줄 `resolution`(판 5) — 근거로는 아직 쓰지 않는다 | §8.4 |
| 결정적 기본 동작 층 | 앞 절반(멈춤, 모른다고 말함, 운영자 보류)은 picasso 가 이미 한다. 뒤 절반은 라인 쪽. 재할당은 즉시 대응이 아니다 | §1, §15 R4, 위 표 |
| 재할당 | 배정 정책이 부른다(ADR 41). 보류 단위가 있는 실행은 막히지 않는다(코드로 확인, 시험으로 밟지는 않음) | §1, §8.4 |

**사용자가 정할 것** (설계는 정하지 않는다. 오른쪽 칸이 그때까지의 동작)

| # | 무엇 | 그 전까지 |
|---|---|---|
| A | 다섯째 후보 — 막는 결함을 감수하고 다음 단위로 갈지. 결과 통보의 `blockedBy` · `operatorRequired`(막힘 자체는 사건을 안 엶), 문은 `Middleware.release(executionId)` 로 프로세스 안에만 있다. 결과 통보를 접수 입력으로 삼을지(C 와 함께)도 여기서 정한다. 사람을 부르지만 문이 없는 경우 — `operatorRequired` 가 참이 되는 나머지(UNVERIFIED · 검증 불일치 · 출발 용기 불일치 · 취소 뒤 정리 실패)와 진행 정체 알림 — 도 같은 자리다. picasso 는 이것들을 NONE 과 같은 모양(누구에게 무엇을 확인시킬지)으로 권한다 | 후보로 내지 않는다. 결과 통보가 접수 입력이 아니므로 koshei 는 막는 결함과 위의 경우들을 보지 못한다 — 담는 쪽이나 라인이 사람을 부른다. 이미 열린 에피소드 안에서 보이면 `ESCALATE` 가 맡는다 |
| B | picasso 가 제안한 거절 `CONSUMED` — `settle` 이 (기체, 주문)마다 소모 기록(승인자 · 시각 · executionId · 나간 걸음과 값)을 남기고 서 있는 제안이 없을 때 돌려준다. 창구 스키마 판 2 → 3, ADR 44 개정. picasso ADR 9(소비자 존재 원칙)상 koshei 가 실제로 읽겠다고 해야 들어온다. 대가: 기록이 재시작에 진다, 남의 승인자 id 가 인증 없는 창구에서 보인다. **전제:** 「한 (기체, 주문)에 한 승인자 id 로 시도 하나만」이면 소모 기록의 승인자로 「내 요청」을 가를 수 있다. picasso 가 권하는 순서는 값을 먼저 가르고, 그 가정이 안 설 때만 열쇠를 더하는 것이다. **koshei 는 지금 그 가정을 깬다** — `repeatedRemedyThreshold: 2` 라 같은 후보를 두 번 낼 수 있고, POLICY 자동 승인은 모두 같은 AGENT 승인자 id 로 간다 | `NO_PROPOSAL` · `REMEDY_NOT_APPLIED` → UNKNOWN(OUTCOME) |
| C | 담는 쪽의 자리. picasso 는 picasso 를 세우는 배치 호스트가 실시간 읽기와 승인 쓰기를 둘 다 담기를 권한다(koshei 는 아님). 배치 결정이다. 승인 창구는 루프백에만 붙으므로 **koshei 는 담는 쪽과 같은 기계에서 돌아야 한다.** 반응 시간(담는 쪽의 `pump()` 주기)과 결과 통보를 koshei 에 나르는 모양(A 와 함께)도 여기 딸린다 | 개발용으로만 파일 내보내기 + 감시자. 실물 완료 근거 없음(§12), 모든 실물 에피소드는 UNKNOWN(PRECONDITION)을 지난다(§8.3) |
| D | 카드 순서. picasso 의 WITHHELD 는 의도적 비자동화다 — 사람이 안쪽 루프의 진단을 먼저 보면 그것은 진단이 아니라 동의가 된다. picasso 는 안쪽 루프 진단을 적어 두되 사람 진단 뒤에 대조 재료로만 보이기를 권한다. 그런 대조용 진단을 부르고 기록해 둘지, 사람 진단이 기록된 뒤에 보일지가 D 의 일이다 | §9.5 그대로. 다만 스냅샷에 `WITHHELD` 탐색 줄이 있으면 DIAGNOSING 에 들어가는 순간 `diagnose` 를 부르지 않고 ESCALATED(`WITHHELD_HUMAN_FIRST`). 카드는 사실과 `ESCALATE` 과업만 |
| E | picasso 쪽 변경 후보: ② `resolve` 가 승인자를 받아 AGENT 를 거절하고 누가 눌렀는지 남김(문을 여는 변경과 함께) ③ 보류 단위가 있는 실행의 재할당 막기 ④ 대장의 증분 조회 `after`. (① 은 B) | 셋 다 없다고 보고 짓는다 — `resolve` · `reassign` 을 부르지 않고(§8.4), 실시간 대장 읽기가 없다(§8.3). ③ 전까지 보류 단위의 재할당 빈 곳을 막는 장치는 없다 — koshei 가 부르지 않는 것뿐이다 |

계획 A 와 B 는 이 표들의 어느 답도 기다리지 않는다.
