# 계획 B3b — 실제 액티비티와 워커 배선 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** B3a 의 워크플로 뒤에 실제 액티비티를 붙이고 `:app` 워커에 등록한다. 대상은 다음과 같다.
- 겹친 키를 거부하는 YAML 정책 읽개
- 추가 전용 `episode_event` 기록
- 알림 표
- 소모를 흉내 내는 Mock picasso
- 멱등 `dispatch`
- `RECORD_LAG`
- Mock narrator 와 실물 narrator 전환
- 재생 시험

함께 B3a 재검토에서 남은 지적 다섯을 닫는다(설계 §7.1 · §8.2 · §8.4 · §8.5 · §11 · §13).

**워크플로 판 관리:** Task 2 는 명령 순서를 바꾼다. `RECORD_LAG` 알림, 이어 시작할 때의 manifest, `dispatch` 인자 수가 그것이다. 그래도 `Workflow.getVersion` 을 쓰지 않는다. 배포된 에피소드 이력이 아직 하나도 없기 때문이다. 첫 배포(계획 C 의 감시자가 증상을 넣기 시작할 때)부터는 바꿀 때마다 판을 건다.

**Architecture:**
- **판단은 여전히 순수 코어가 한다.** 액티비티는 읽고 쓰고 나르기만 한다. 정책이 유효한지는 `parsePolicy` 가 보고, picasso 답이 무슨 뜻인지는 `judgePicassoAnswer` 가 본다. 액티비티는 YAML 을 JSON 글자로 바꾸고 picasso 의 답을 글자 그대로 돌려줄 뿐이다.
- **기록은 Postgres 의 추가 전용 표에 쓴다.** 쓰기는 `(episode_instance_id, seq)` 에 대해 `ON CONFLICT DO NOTHING` 이다. 같은 자리에 다른 내용이 있으면 조용히 넘기지 않고 재시도하지 않는 실패로 낸다. 워크플로는 이 실패를 `RECORD_LAG` 알림으로 바꾼다.
- **`dispatch` 의 멱등은 koshei 쪽 표가 지킨다(§8.4, §11).**
  - 같은 멱등 열쇠로 저장된 결과가 있으면 picasso 를 다시 부르지 않고 그 결과를 돌려준다.
  - 사람 과업은 과업 행과 알림 행을 한 트랜잭션으로 쓴다. 그래서 두 번 발행되지 않는다.
  - picasso 를 부른 뒤 저장 전에 죽으면 재시도가 다시 부르고, Mock 도 실물처럼 `REMEDY_NOT_APPLIED` 또는 `NO_PROPOSAL` 을 낸다. 코어는 이것을 UNKNOWN(OUTCOME)으로 받는다(R2).
- **배선은 `episode-runtime` 의 한 함수가 한다.** `:app` 의 `Worker.kt` 는 설정을 환경변수에서 읽어 그 함수를 부를 뿐이다. 워크플로 코드는 여전히 환경변수 · 파일 · 벽시계를 읽지 않는다(§7.5).

**Tech Stack:** Kotlin 2.2 / JDK 21, Temporal Java SDK 1.25.1(`temporal-testing`), Jackson 2.17(+ `jackson-dataformat-yaml`), PostgreSQL 16(plain JDBC), Testcontainers 1.20.1

**작업 위치:** worktree `C:\Users\Eisen\Desktop\Labs\[projects] koshchei\koshei-episode-core`, 브랜치 `feat/episode-core`(머리 `c17df8c`). 지금 시험은 `:episode` 291개, `:episode-runtime` 26개, `:runtime` `RawJsonConverterTest` 3개다.
- 원래 체크아웃(`koshei/`)은 건드리지 않는다.
- 파일은 Write/Edit 로만 쓴다(heredoc 금지).
- bare `git stash` 를 쓰지 않는다.
- 푸시하지 않는다.
- Docker 가 돌아야 한다(Testcontainers). 돌지 않으면 BLOCKED 로 보고한다.

**커밋 규칙:** 모든 커밋은 `git commit -m "<제목>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`.

**범위 밖:**
- **실물 picasso 승인 창구 클라이언트:** `POST /approvals` 를 부르는 쪽이다. 담는 쪽 위치(§19 C)가 서야 한다.
  - 이번에는 `KOSHEI_PICASSO` 를 `mock` 으로 **명시했을 때만** 에피소드 워커를 띄운다. 기본값은 `off` 다.
  - 설계 §8.3 · §11 은 Mock 을 「시험의」 Mock 이라 부른다. 실제 워커가 말없이 Mock 으로 돌면, 아무것도 picasso 에 닿지 않았는데도 지어낸 `APPROVED` 를 받는다. Mock 은 프로세스 메모리라서 워커가 죽으면 소모 기록도 잊는다.
- **계획 B3c:** 제어면 `/api/episodes…`, 알림 표를 읽는 화면.
- **계획 C:** 감시자, 담는 쪽.
- **계획 D:** 정책 활성화 CLI, DB 활성 판 포인터, 런타임 계정의 `UPDATE`/`DELETE` 권한 회수, Temporal 이력에서 기록 메우기, 상태별로 멈춘 이력을 커밋해 재생하는 R3 시험.
- 외부 취소(cancel)와 강제 종료(terminate)는 다루지 않는다. 운영자는 `takeover` → `close` 를 쓴다. B3c 에서 제어면이 이 길만 열어 둔다.

---

## 설계에서 코드로 옮길 때 정한 것

| 설계 | 이 계획의 코드 |
|---|---|
| §8.2 정책 원천은 파일 `model/episode-policy/active.yaml` 하나 | 경로는 `KOSHEI_EPISODE_POLICY`, 없으면 작업 디렉터리 기준 `model/episode-policy/active.yaml`. `:app:run` 은 저장소 뿌리의 절대경로를 넣어 준다. 파일이 없으면 `MISSING`, 읽거나 풀 수 없으면 `UNREADABLE`, 풀리면 JSON 글자로 `FOUND` |
| §8.2 「B3 의 YAML 읽개는 중복 키를 거부해야 한다」 | Jackson 의 트리 읽기에 기대지 않는다. 두 번 걷는다. 먼저 SnakeYAML 의 이벤트를 걸어 앵커와 별칭(`&a` / `*a`: 별칭은 다른 값을 몰래 끌어오는 길이다), 태그(`!!str`, `!foo`, 비특정 `!` 포함), `%YAML` · `%TAG` 지시문을 거부한다. Jackson 파서는 스칼라의 앵커를 드러내지 않기 때문이다. 다음에 Jackson 파서의 토큰을 직접 걸어 트리를 만들며 중복 키, 정규 JSON 꼴이 아닌 스칼라(`010` · `0x10` · `0b1` · `1_000` · `+1` · `1000.` · `.5`, `yes` · `on` · `off` · `True`: YAML 1.1 은 이들을 다른 값으로 읽는다), 둘째 문서(`---`), 빈 파일, 64 단을 넘는 중첩을 거부한다. 모두 `UNREADABLE`. 파일이 1 MiB 를 넘어도 읽기 전에 `UNREADABLE` |
| §10.1 v1 은 자동 승인을 끈다 | 커밋하는 `active.yaml` 은 `autoApprove.APPROVE_REMEDY.allowed: false`. 값은 `:episode` 시험 표와 같다. 시험이 이 파일을 직접 읽어 유효한지와 자동 승인이 꺼졌는지를 지킨다 |
| §13 `episode_event(episode_instance_id, seq, kind, payload jsonb, at)` 추가 전용, `schema.sql` 과 분리된 스크립트 | `episode-runtime/src/main/resources/episode-schema.sql`(`CREATE … IF NOT EXISTS`, 몇 번이든 돌려도 같다). `EpisodeStore.ensureSchema()` 가 워커 시작 때 돌린다. `at` 은 쓴 시각(DB `now()`)이다. 권한 회수는 스크립트 주석으로 적고 계획 D 에서 한다 |
| §8.5 `record` 는 `(instance, seq)` 로 `ON CONFLICT DO NOTHING` | 같은 내용이면 조용히 넘긴다(재시도). 다른 내용이면 `RecordConflict` → 재시도하지 않는 실패. 실행 의도는 같은 seq 공간에 `kind = DISPATCH_INTENT` 로 쓴다 |
| §8.5 기록이 끝내 실패하면 `Notify(RECORD_LAG)` | 워크플로가 `record` 액티비티의 실패를 받으면 run 마다 한 번 `RECORD_LAG` 알림을 낸다. 코어 열거형에 `NoticeKind.RECORD_LAG` 를 더하되 코어는 내지 않는다. 알림이 실패한 것에는 다시 알리지 않는다(알림이 채널이다) |
| §8.5 `notify` = 운영자 채널 | P0 의 채널은 `episode_notice` 표(추가 전용) + 로그 한 줄이다. B3c 화면이 이 표를 읽는다. 최소 한 번이다: 커밋 뒤 응답 전에 죽으면 같은 알림이 두 줄 될 수 있다. 문서에 적는다 |
| §8.4 `CHOOSE_SOURCE` · `OPERATOR_DECISION` = 운영자 과업(알림 + 과업 기록) | `episode_dispatch` 행(`PERSON_TASK`)과 `episode_notice` 행(`kind: PERSON_TASK`)을 한 트랜잭션으로 쓴다. 이미 행이 있으면 아무것도 쓰지 않고 `PERSON_TASK` 를 돌려준다. 과업은 정확히 한 번 발행된다 |
| §8.4 · §11 멱등은 액티비티 안에서, picasso 는 열쇠를 버린다 | `episode_dispatch(idempotency_key)` 에 답을 글자 그대로(`text`) 저장한다. 먼저 쓴 쪽이 이긴다. 저장된 답이 있으면 picasso 를 부르지 않는다. 답을 받고 저장하기 전에 죽은 경우는 막을 수 없다. picasso 가 막고, 코어가 UNKNOWN(OUTCOME)으로 받는다 |
| §11 Mock picasso 는 소모를 흉내 낸다 | `MockPicasso`(main 코드, 프로세스 메모리). (기체, 주문) 제안은 한 번 승인되면 소모된다. 같은 `searchId` 의 둘째 승인은 `REMEDY_NOT_APPLIED`, 다른 `searchId` 는 `NO_PROPOSAL`. `revalidate` 는 소모된 제안이면 `FALSE`, 아니면 `TRUE`(§8.3: 재조회만으로 TRUE 까지 가는 길은 Mock 뿐이다) |
| `dispatch` 는 사람 과업 알림에 인스턴스 id 가 있어야 한다 | 액티비티 서명에 `instanceId` 를 더한다: `dispatch(instanceId, intentJson, approverKind)`. 배포된 이력이 아직 없으므로 서명을 바꿔도 깨지는 재생이 없다 |
| §4.1 `:app` Worker.kt 가 `koshei-episode-tq` 에 등록 | `EpisodeWorkers.register(factory, config, store)`. 워커 옵션은 기존 사가 워커와 같다(교착 감지 10초). `KOSHEI_PICASSO` 가 없거나 `off` 면 에피소드 워커를 띄우지 않고 표도 만들지 않는다. 그래서 기존 사가 워커와 관문 스크립트는 그대로다. `mock` 이면 띄우되 시작 때 경고 한 줄을 남긴다 |
| §8.3 · §11 Mock picasso 는 「시험의」 Mock | `KOSHEI_PICASSO` 기본값은 `off` 다. 이때는 에피소드 워커를 띄우지 않는다. `mock` 은 이름으로 골라야 하고, 시작 때 경고를 남긴다. Mock 은 프로세스 메모리라서 워커가 죽으면 소모 기록을 잊고, 그 뒤 재시도는 다시 승인된다. 끝에서 끝까지 시험의 R2 는 같은 프로세스 안의 실패만 보인다 |
| §8.1 P0 동안 diagnose 는 Mock | `KOSHEI_NARRATOR=mock`(기본)이면 koshei 워커가 `MockNarratorActivities` 를 `narrator-tq` 에 등록한다. `remote` 면 등록하지 않고 narrator 의 Python 워커가 그 큐를 맡는다. 둘이 한 큐를 함께 폴링하면 어느 쪽이 답할지 모르므로 하나만 켠다 |
| B3a 재검토 1 — 이어진 run 이 옛 manifest 를 쓴다 | `SymptomSignal.manifestJson`(널 가능)을 더한다. 감시자는 signalWithStart 마다 그 줄의 manifest 를 함께 싣는다(계획 C). 이어 시작할 때는 첫 증상의 manifest 를 쓰고, 없을 때만 옛 것을 쓴다. 열린 에피소드에 합류하는 증상의 manifest 는 여전히 쓰지 않는다. 이것은 알려진 한계로 문서에 적는다 |
| B3a 재검토 2 — 늦은 `SymptomJoined` 의 IGNORED 에 eventId 가 없다 | 코어 `ignore()` 가 `SymptomJoined` 에 `eventId` 와 `symptomKind` 를 적는다 |
| B3a 재검토 3 — 이전 run 의 결과를 기다리면 다음 에피소드 결과를 받는다 | 문서(§7.1)에 B3c 에 줄 메모로 적는다: runId 를 고정하거나 `CONTINUED_AS_NEW` 를 끝으로 본다 |
| B3a 재검토 4 — 정리 중 Update 시험 없음 | 시험을 더한다 |
| B3a 재검토 5 — 재생 시험은 `DataConverterSupport` 변환기로 | `DataConverterSupport.clientOptions()` 로 만든 시험 환경의 `Worker` 를 재생기에 준다. 기본 변환기로 재생하면 실패하는지도 함께 본다(변환기가 실제로 필요하다는 대조군) |

---

## 파일 구조

| 파일 | 할 일 | 책임 |
|---|---|---|
| `episode/src/main/kotlin/koshei/episode/EpisodeModel.kt` | 수정 | `NoticeKind.RECORD_LAG` |
| `episode/src/main/kotlin/koshei/episode/Transition.kt` | 수정 | `ignore()` 가 `SymptomJoined` 의 eventId 를 적음 |
| `episode/src/test/kotlin/koshei/episode/EpisodeLifecycleTest.kt` | 수정 | Task 1 |
| `episode-runtime/build.gradle.kts` | 수정 | yaml, postgres, testcontainers |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/Wire.kt` | 수정 | `SymptomSignal.manifestJson` |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/Activities.kt` | 수정 | `dispatch` 서명 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkflow.kt` | 수정 | manifest 이어 주기, `RECORD_LAG`, `dispatch` 호출 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/StrictYaml.kt` | 생성 | 겹친 키 · 별칭 · 둘째 문서를 거부하는 YAML → JSON |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/PolicyFileReader.kt` | 생성 | 파일 → `PolicyReadResult` |
| `model/episode-policy/active.yaml` | 생성 | 정책 v1 |
| `episode-runtime/src/main/resources/episode-schema.sql` | 생성 | 표 셋 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeStore.kt` | 생성 | 기록 · 알림 · dispatch 결과 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/MockPicasso.kt` | 생성 | 소모를 흉내 내는 승인 창구 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeActivitiesImpl.kt` | 생성 | 실제 액티비티 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkers.kt` | 생성 | 설정과 등록 |
| `app/build.gradle.kts` | 수정 | `:episode-runtime` 의존, 환경변수 |
| `app/src/main/kotlin/koshei/app/Worker.kt` | 수정 | 에피소드 워커 등록 |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/Fixtures.kt` | 수정 | 공용 시험 보조 함수, `ProgrammableNarrator`(옮겨 옴), 가짜 액티비티의 `dispatch` 서명 · `recordFault` · `noticeFault` · `notices` |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWorkflowTest.kt` | 수정 | Task 2 |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/StrictYamlTest.kt` | 생성 | Task 3 |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/PolicyFileReaderTest.kt` | 생성 | Task 3 · 4 |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeDb.kt` | 생성 | Testcontainers 지원 |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeStoreTest.kt` | 생성 | Task 5 |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/MockPicassoTest.kt` | 생성 | Task 6 |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeActivitiesImplTest.kt` | 생성 | Task 7 |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWorkersTest.kt` | 생성 | Task 8 |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeEndToEndTest.kt` | 생성 | Task 9 |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeReplayTest.kt` | 생성 | Task 10 |
| `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md` | 수정 | Task 11 |

---

## Chunk 1: 남은 지적과 워크플로 손질

### Task 1: 코어 — 늦은 증상의 eventId, `RECORD_LAG` 열거값

**Files:**
- Modify: `episode/src/main/kotlin/koshei/episode/Transition.kt`(`ignore()`, 207행 부근)
- Modify: `episode/src/main/kotlin/koshei/episode/EpisodeModel.kt:89`(`NoticeKind`)
- Test: `episode/src/test/kotlin/koshei/episode/EpisodeLifecycleTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`EpisodeLifecycleTest.kt` 의 기존 IGNORED 시험(161행 부근, `phaseAtReceipt` 를 보는 것)을 먼저 읽는다. 같은 보조 함수(에피소드를 열고 끝내는 것, 기록을 꺼내는 것)를 그대로 써서 다음 시험을 더한다.

```kotlin
@Test fun `a symptom ignored after the end names its eventId and kind`() {
    // 끝난 에피소드(예: CLOSED 까지 간 것)를 기존 보조 함수로 만든다.
    // 그 상태에 SymptomJoined(eventId = "search:late-1", SEARCH) 를 넣는다.
    // 나온 Record 중 IGNORED 의 payload 에서:
    //   payload["event"] == "SymptomJoined"
    //   payload["eventId"] == "search:late-1"
    //   payload["symptomKind"] == "SEARCH"
}
```

주석 자리는 그 파일의 보조 함수로 채운다. 끝난 상태를 만드는 보조 함수가 없으면, `agentOff` → `takeover` → `close` 처럼 이미 다른 시험이 쓰는 사건 순서로 만든다.

`NoticeKind` 는 시험이 필요 없다. 아래 Step 3 에서 값만 더하고, 컴파일로 확인한다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode:test --tests "koshei.episode.EpisodeLifecycleTest"`
Expected: 새 시험 FAIL(`eventId` 없음)

- [ ] **Step 3: 구현한다**

`Transition.kt` 의 `ignore()`(207행)에서, `record(RecordKind.IGNORED) { … }` 블록 **안**의 `if (event is EpisodeEvent.EvidenceArrived) putObject("evidence")…` 줄 바로 위에 다음을 더한다. 블록 밖에 같은 조건의 줄이 또 있다. 그쪽에는 `put` 이 없으므로 넣지 않는다.

```kotlin
        // Which line the ended episode (or a duplicate or keyless line) did not take: a forwarding run can be traced (§7.1).
        if (event is EpisodeEvent.SymptomJoined) {
            put("eventId", event.symptom.eventId)
            put("symptomKind", event.symptom.kind.name)
        }
```

`EpisodeModel.kt` 에서는 다음을 바꾼다.

```kotlin
/** RECORD_LAG is raised by the runtime when a record keeps failing (design §8.5); the core never emits it. */
enum class NoticeKind { APPROVAL_NEEDED, CONFIRM_PRECONDITION, ESCALATED, POLICY_REJECTED, CONFIRM_OUTCOME, RECORD_LAG }
```

`NoticeKind` 에 대한 망라 `when` 이 코어나 시험(퍼저 포함)에 있으면 컴파일이 알려 준다. 거기에 `RECORD_LAG` 갈래를 「코어는 내지 않는다」로 더한다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode:test`
Expected: 292개 PASS(291 + 1)

- [ ] **Step 5: 커밋한다**

```bash
git add episode/src/main/kotlin/koshei/episode/Transition.kt episode/src/main/kotlin/koshei/episode/EpisodeModel.kt episode/src/test/kotlin/koshei/episode/EpisodeLifecycleTest.kt
git commit -m "feat(episode): an ignored late symptom names its eventId; RECORD_LAG notice kind for the runtime" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: 워크플로 — manifest 이어 주기, `RECORD_LAG`, `dispatch` 의 인스턴스 id, 정리 중 Update

**Files:**
- Modify: `episode-runtime/src/main/kotlin/koshei/episode/runtime/Wire.kt:20`
- Modify: `episode-runtime/src/main/kotlin/koshei/episode/runtime/Activities.kt`
- Modify: `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkflow.kt`
- Modify: `episode-runtime/src/test/kotlin/koshei/episode/runtime/Fixtures.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWorkflowTest.kt`

- [ ] **Step 1: 시험 보조 함수를 `Fixtures.kt` 로 끌어올리고, 가짜 액티비티를 새 서명과 결함 주입에 맞춘다**

Task 9 · 10 의 새 시험 파일도 같은 보조 함수를 쓴다. 그래서 지금 `EpisodeWorkflowTest.kt` 의 보조 함수를 최상위 함수로 `Fixtures.kt` 에 옮긴다. `ProgrammableNarrator` 클래스(`EpisodeWorkflowTest.kt` 327행 부근)도 그대로 `Fixtures.kt` 로 옮긴다. 몸통은 지금 코드와 같고, 바뀌는 것은 `env` · `client` · `acts` 를 인자로 받는다는 점뿐이다.

```kotlin
/** A test environment with koshei's converter (as every worker and client in production), workers not yet started. */
internal fun episodeEnvironment(register: TestWorkflowEnvironment.() -> Unit): TestWorkflowEnvironment =
    TestWorkflowEnvironment.newInstance(
        TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(DataConverterSupport.clientOptions()).build(),
    ).apply(register)

/** The workflow with [acts] on the episode queue and [narrator] on narrator-tq. */
internal fun TestWorkflowEnvironment.fakeWorkers(acts: EpisodeActivities, narrator: NarratorActivities) {
    newWorker(EPISODE_TASK_QUEUE).apply {
        registerWorkflowImplementationTypes(EpisodeWorkflowImpl::class.java)
        registerActivitiesImplementations(acts)
    }
    newWorker(NARRATOR_TASK_QUEUE).registerActivitiesImplementations(narrator)
}

internal fun episodeOptions(id: String): WorkflowOptions = WorkflowOptions.newBuilder()
    .setTaskQueue(EPISODE_TASK_QUEUE)
    .setWorkflowId(id)
    .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
    .build()

internal fun searchSignal(id: String) = SymptomSignal("search:$id", "SEARCH", Picasso.search(id), 0)

/** signalWithStart, as the watcher will (design §7.1). */
internal fun WorkflowClient.openEpisode(id: String = "ep:test", symptom: SymptomSignal = searchSignal("search-1")): EpisodeWorkflow {
    val stub = newWorkflowStub(EpisodeWorkflow::class.java, episodeOptions(id))
    WorkflowStub.fromTyped(stub).signalWithStart("symptom", arrayOf(symptom), arrayOf(EpisodeStart(Picasso.manifest)))
    return stub
}

/** Advances test time a second at a time until [cond] holds on the view (the workflow runs between steps). */
internal fun EpisodeWorkflow.until(env: TestWorkflowEnvironment, seconds: Int = 120, cond: (EpisodeView) -> Boolean): EpisodeView {
    repeat(seconds) {
        val v = view()
        if (cond(v)) return v
        env.sleep(Duration.ofSeconds(1))
    }
    fail("condition not reached; last view ${view()}")
}

internal fun EpisodeWorkflow.result(): EpisodeResult = WorkflowStub.fromTyped(this).getResult(EpisodeResult::class.java)

/** Polls in wall-clock time (activities run on worker threads, outside test time) until [probe] yields a value. */
internal fun <T : Any> eventually(seconds: Int = 20, describe: () -> String = { "" }, probe: () -> T?): T {
    repeat(seconds * 10) {
        probe()?.let { return it }
        Thread.sleep(100)
    }
    return probe() ?: fail("not reached within ${seconds}s; ${describe()}")
}
```

`EpisodeWorkflowTest.kt` 의 기존 21개 시험 몸통은 손대지 않는다. 그 파일의 private 보조 함수만 한 줄 위임으로 바꾼다.
- `environment()` → `episodeEnvironment { fakeWorkers(acts, narrator) }`
- `open(id, symptom)` → `client.openEpisode(id, symptom)`
- `EpisodeWorkflow.until(seconds, cond)` → `until(env, seconds, cond)`
- `eventually(seconds, probe)` → `eventually(seconds, { "records ${acts.records}" }, probe)`
- `options(id)` → `episodeOptions(id)`(한 줄 위임으로 남긴다. 기존 시험 `signals that arrive before the episode opens…` 가 부른다)
- `searchSignal`, `result` 는 지운다(최상위 것을 쓴다).

private 확장 `until(seconds, cond)` 와 최상위 `until(env, seconds, cond)` 는 인자 모양이 달라서 겹치지 않을 것이다. 컴파일러가 모호하다고 하면 최상위 쪽 이름을 `untilView` 로 바꾸고, Task 9 · 10 의 코드에서도 그 이름을 쓴다. `eventually` 가 모호하면 위임 쪽에서 `koshei.episode.runtime.eventually(…)` 로 전체 이름을 쓴다.

`FakeEpisodeActivities` 를 다음과 같이 바꾼다.
- `dispatch` 는 `override fun dispatch(instanceId: String, intentJson: String, approverKind: String?): DispatchOutcome?` 로 바꾼다. 몸통은 그대로 두고 `calls += "dispatch:$approverKind"` 도 그대로 둔다.
- 다음 칸 둘을 더한다.

```kotlin
    /** Whether a `record` call for this kind throws (every attempt, so the activity fails for good). */
    @Volatile var recordFault: (String) -> Boolean = { false }
    /** Whether every `sendNotice` throws (after the notice is remembered as attempted). */
    @Volatile var noticeFault: Boolean = false
    /** Every notice attempted, in order, retries included. */
    val notices: MutableList<String> = Collections.synchronizedList(mutableListOf())
```

- `record` 의 첫 줄에 `if (recordFault(kind)) { calls += "record-failed:$kind"; throw IllegalStateException("store down for $kind") }` 를 더한다. `calls += "record:$kind"` 와 `records` 에 더하는 것보다 앞에 둔다.
- `sendNotice` 의 몸통을 `calls += "notice"; notices += noticeJson; if (noticeFault) throw IllegalStateException("channel down")` 으로 바꾼다.

`Activities.kt` 의 인터페이스도 다음과 같이 바꾼다.

```kotlin
    /**
     * Obligation: MUST be idempotent on the intent's idempotencyKey (design §8.4, §11) — a person task must not be issued
     * twice. [instanceId] names the episode a person task or its notice belongs to.
     */
    @ActivityMethod(name = "episodeDispatch")
    fun dispatch(instanceId: String, intentJson: String, approverKind: String?): DispatchOutcome?
```

- [ ] **Step 2: 실패하는 시험 넷을 쓴다**

`EpisodeWorkflowTest.kt` 에 더한다. 보조 함수 `open`, `until`, `eventually`, `records`, `settledRecords`, `result` 는 이미 있다.

```kotlin
    private val json = com.fasterxml.jackson.databind.ObjectMapper()

    /** Notices attempted so far whose `kind` is [kind] (retries included). */
    private fun notices(kind: String): List<String> =
        synchronized(acts.notices) { acts.notices.filter { json.readTree(it)["kind"]?.textValue() == kind } }

    @Test fun `a record that keeps failing raises RECORD_LAG once, and the episode goes on`() {
        acts.recordFault = { it == "PROPOSED" || it == "APPROVAL" }
        val ep = open()
        ep.until { it.phase == "AWAITING_EVIDENCE" }
        // "Once" means something only after BOTH failing records have used up their three attempts.
        eventually {
            acts.calls.toList().takeIf { c -> c.count { it == "record-failed:PROPOSED" } >= 3 && c.count { it == "record-failed:APPROVAL" } >= 3 }
        }
        val lag = eventually { notices("RECORD_LAG").takeIf { it.isNotEmpty() } }
        Thread.sleep(2_000)   // without the once-per-run flag, the second failure's notice would have landed by now
        assertEquals(1, notices("RECORD_LAG").size, "one RECORD_LAG per run: ${acts.notices}")
        val detail = json.readTree(lag.first())["detail"].textValue()
        assertTrue("PROPOSED" in detail || "APPROVAL" in detail, "the notice names the failed record: $detail")
    }

    @Test fun `a failing notice does not raise RECORD_LAG`() {
        acts.noticeFault = true                                  // every notice fails, after being remembered as attempted
        acts.policyJson = TestPolicies.table(autoApprove = false)
        val ep = open()
        ep.until { it.phase == "AWAITING_APPROVAL" }
        eventually { notices("APPROVAL_NEEDED").takeIf { it.size >= 3 } }   // three failed attempts of APPROVAL_NEEDED
        // A later notice: anything the failures above would have raised is attempted before it.
        assertEquals("ACCEPTED", ep.takeover(TakeoverRequest("op-1")))
        eventually { notices("ESCALATED").takeIf { it.isNotEmpty() } }
        Thread.sleep(2_000)
        assertTrue(notices("RECORD_LAG").isEmpty(), "a failed notice is not a lagging record: ${acts.notices}")
    }

    @Test fun `an Update during the drain is refused as a value`() {
        val gate = CountDownLatch(1)
        acts.dispatchGate = gate
        val ep = open()
        ep.until { it.phase == "DISPATCHED" }
        assertEquals("ACCEPTED", ep.takeover(TakeoverRequest("op-1")))
        assertEquals("ACCEPTED", ep.close(CloseRequest("op-1", "handled on site")))
        assertEquals("REFUSED_CLOSED", ep.close(CloseRequest("op-2", "again")))   // the run is still draining
        gate.countDown()
        assertEquals(EpisodeResult("CLOSED", "TAKEN_OVER"), ep.result())
    }

    @Test fun `a run continued for a late symptom opens with that symptom's manifest`() {
        val gate = CountDownLatch(1)
        acts.dispatchGate = gate
        val ep = open(id = "ep:manifest")
        val first = ep.until { it.phase == "DISPATCHED" }.instanceId
        assertEquals("ACCEPTED", ep.takeover(TakeoverRequest("op-1")))
        assertEquals("ACCEPTED", ep.close(CloseRequest("op-1", "handled on site")))
        val later = Picasso.manifest.replace("\"schemaVersion\"", "\"marker\":\"run-later\",\"schemaVersion\"")
        ep.symptom(searchSignal("search-2").copy(manifestJson = later))
        gate.countDown()
        val next = client.newWorkflowStub(EpisodeWorkflow::class.java, "ep:manifest").until { it.instanceId != first && it.phase != "OPENING" }
        val opened = eventually { records(next.instanceId).firstOrNull { it.kind == "OPENED" } }
        assertTrue("run-later" in opened.payload, "the new run opened with the late symptom's manifest: ${opened.payload}")
        val ignored = records(first).first { it.kind == "IGNORED" && "SymptomJoined" in it.payload }
        assertTrue("search:search-2" in ignored.payload, "the ended run names what it forwarded: ${ignored.payload}")
    }
```

마지막 시험의 `val later = …` 바로 다음 줄에 `assertTrue(later != Picasso.manifest)` 를 넣는다. `replace` 가 아무것도 바꾸지 않아 시험이 헛도는 것을 막는다. `Picasso.manifest` 의 첫 키는 `schemaVersion` 이고, `Snapshot` 은 모르는 manifest 키를 그대로 둔다. 둘 다 리뷰에서 확인했다.

`an Update during the drain is refused as a value` 는 옛 코드에서도 통과한다. 동작은 이미 맞고, 이 시험은 그것을 고정하는 시험이다.

- [ ] **Step 3: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeWorkflowTest"`
Expected: 컴파일 오류. `SymptomSignal.copy(manifestJson=…)` 가 없고, 워크플로의 `dispatch` 호출이 옛 서명이다.

- [ ] **Step 4: 구현한다**

`Wire.kt:20` 을 다음과 같이 바꾼다.

```kotlin
/**
 * `symptom` signal. [manifestJson] is the manifest of the export the line came from; the watcher sends it with every
 * signal (plan C). Only the symptom that opens an episode uses it — a joined symptom's manifest is not read (design §7.1).
 */
data class SymptomSignal(
    val eventId: String = "",
    val kind: String = "",
    val lineJson: String = "",
    val observedAtMillis: Long = 0,
    val manifestJson: String? = null,
)
```

`EpisodeWorkflow.kt` 의 `run` 에서 이어 시작하는 줄을 다음과 같이 바꾼다.

```kotlin
        if (fresh.isNotEmpty()) {
            // The new episode opens on the first fresh line, so it takes that line's manifest when the watcher sent one.
            val manifest = fresh.first().manifestJson?.takeUnless { it.isBlank() } ?: start.manifestJson
            Workflow.continueAsNew(EpisodeStart(manifest, start.schemaVersion, fresh))
        }
```

첫 run 도 같은 규칙을 따라야 한다. `startEpisode(EpisodeEvent.Detected(instanceId(), first.line!!.manifestJson?.takeUnless { it.isBlank() } ?: start.manifestJson, …))` 로 바꾼다. 시작 인자와 첫 signal 이 둘 다 manifest 를 가지면 signal 쪽이 그 줄의 것이다.

`Command.Dispatch` 실행부를 다음과 같이 바꾼다.

```kotlin
            is Command.Dispatch -> {
                val id = instanceId()
                report(
                    Async.function { activities(command.timeoutMs).dispatch(id, intentJson(command.intent), command.approverKind) },
                    failed = { EpisodeEvent.DispatchReturned(DispatchResult.Uncertain(it.message), command.token) },
                ) { outcome -> EpisodeEvent.DispatchReturned(checkNotNull(outcome) { "dispatch answered null" }.toResult(), command.token) }
            }
```

`Command.Record` 실행부와 `TODO(B3b)` 주석을 다음으로 바꾼다. 클래스에 `private var recordLagRaised = false` 를 더한다.

```kotlin
            // Records run concurrently and may land out of seq order; the store keys them by (instance, seq).
            is Command.Notify -> notice(noticeJson(command.notice))
            is Command.Record -> {
                val id = instanceId()
                val seq = command.seq
                val kind = command.entry.kind.name
                val payload = command.entry.payload.toString()
                keep(
                    unsettled,
                    Async.procedure { activities(30_000).record(id, seq, kind, payload) }.handle { _, failure ->
                        // A record that failed for good: say so once per run (design §8.5). Temporal history still has it.
                        if (failure != null && !recordLagRaised) {
                            recordLagRaised = true
                            notice(noticeJson(Notice(NoticeKind.RECORD_LAG, current().phase, detail = "record $seq $kind failed: ${failure.message}")))
                        }
                    },
                )
            }
```

보조 함수를 더한다.

```kotlin
    /** A notice is never awaited and its failure is never reported further: the channel is what failed (design §8.5). */
    private fun notice(json: String) {
        val id = instanceId()
        keep(unsettled, Async.procedure { activities(30_000).sendNotice(id, json) }.handle { _, _ -> })
    }
```

(이름이 `notify` 가 아닌 것은 `java.lang.Object.notify()` 와 겹치지 않게 하려는 것이다.)

`handle` 콜백 안에서 액티비티를 띄우고 `keep(unsettled, …)` 를 불러도 된다. 콜백은 promise 가 끝난 뒤 워크플로 스레드에서 돌고, 그때 `unsettled` 를 순회하는 코드는 없다.

`settled()` 는 `unsettled` 를 보므로, 정리 중에 나온 `RECORD_LAG` 알림도 끝까지 기다린다. `import koshei.episode.Notice` 와 `import koshei.episode.NoticeKind` 를 더한다.

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test`
Expected: 30개 PASS(26 + 4). 이것은 `ConversionsTest` 5 + `EpisodeWorkflowTest` 25 를 더한 수다.

- [ ] **Step 6: 커밋한다**

```bash
git add episode-runtime/src
git commit -m "feat(episode-runtime): RECORD_LAG once per run, a continued run takes the late symptom's manifest, dispatch names its episode" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Chunk 2: 정책 파일

### Task 3: 엄격한 YAML 읽개와 `PolicyFileReader`

**Files:**
- Modify: `episode-runtime/build.gradle.kts`
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/StrictYaml.kt`
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/PolicyFileReader.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/StrictYamlTest.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/PolicyFileReaderTest.kt`

- [ ] **Step 1: 의존성을 더한다**

`episode-runtime/build.gradle.kts` 의 `dependencies` 에 다음을 더한다. Chunk 3 에서 쓸 DB 의존성도 여기서 함께 더한다.

```kotlin
    implementation(libs.jackson.yaml)
    implementation(libs.postgresql)
    testImplementation(libs.testcontainers.postgresql)
```

`tasks.test` 를 다음으로 바꾼다. 시험이 저장소의 `model/` 을 읽게 하기 위해서다.

```kotlin
tasks.test {
    useJUnitPlatform()
    systemProperty("koshei.repoRoot", rootProject.projectDir.absolutePath)
}
```

- [ ] **Step 2: 실패하는 시험을 쓴다 — `StrictYamlTest`**

```kotlin
package koshei.episode.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StrictYamlTest {
    private val json = ObjectMapper()

    private fun rejects(yaml: String, mention: String) {
        val e = assertThrows<IllegalArgumentException> { StrictYaml.toJson(yaml) }
        assertTrue(mention in (e.message ?: ""), "message names '$mention': ${e.message}")
    }

    @Test fun `a mapping becomes the same JSON tree`() {
        val out = StrictYaml.toJson(
            """
            version: "2026-10-01.1"
            expiresAt: null
            agentLayerEnabled: true
            autoApprove:
              APPROVE_REMEDY: { allowed: false, requireClean: true, skills: [pick_place] }
            deadlines: { episodeMs: 3600000 }
            ratio: 0.5
            """.trimIndent(),
        )
        assertEquals(
            json.readTree(
                """{"version":"2026-10-01.1","expiresAt":null,"agentLayerEnabled":true,
                   "autoApprove":{"APPROVE_REMEDY":{"allowed":false,"requireClean":true,"skills":["pick_place"]}},
                   "deadlines":{"episodeMs":3600000},"ratio":0.5}""",
            ),
            json.readTree(out),
        )
    }

    @Test fun `a repeated top-level key is refused, so a later switch cannot override an earlier one`() =
        rejects("agentLayerEnabled: false\nversion: \"1\"\nagentLayerEnabled: true\n", "agentLayerEnabled")

    @Test fun `a repeated nested key is refused`() =
        rejects("autoApprove:\n  APPROVE_REMEDY:\n    allowed: false\n    allowed: true\n", "allowed")

    @Test fun `a repeated key in a flow mapping is refused`() =
        rejects("autoApprove: { APPROVE_REMEDY: { allowed: false, allowed: true } }\n", "allowed")

    @Test fun `an anchor is refused`() = rejects("base: &b { allowed: false }\nother: 1\n", "anchor")

    @Test fun `an alias is refused`() = rejects("a: &x 1\nb: *x\n", "anchor")   // the anchor is met first

    @Test fun `an alias alone is refused`() = rejects("b: *x\n", "alias")   // an undefined alias arrives as a string token

    @Test fun `an anchor on a key is refused`() = rejects("&k allowed: true\n", "key")

    @Test fun `a second document is refused`() = rejects("version: \"1\"\n---\nversion: \"2\"\n", "document")

    @Test fun `an empty file is refused`() = rejects("", "empty")

    @Test fun `a comment-only file is refused`() = rejects("# nothing\n", "empty")

    @Test fun `a syntax error is refused`() = rejects("a: [1, 2\n", "")

    @Test fun `a list root passes through - the core decides what a table is`() =
        assertEquals("[1,2]", StrictYaml.toJson("- 1\n- 2\n"))
}
```

`a syntax error` 에서는 메시지 내용을 보지 않는다(`mention = ""`). Jackson 이나 SnakeYAML 의 예외를 `toJson` 이 `IllegalArgumentException` 으로 감싸서 던진다.

`---` 한 줄뿐인 파일은 빈 파일이 아니다. 값이 `null` 인 문서이므로 `"null"` 로 FOUND 가 되고, 코어가 표가 아니라고 거절한다. 그것으로 충분하다.

`an anchor on a key` 의 YAML(`&k allowed: true`)을 파서가 키의 앵커가 아니라 값의 앵커로 읽을 수도 있다. 그 경우에도 앵커 거절은 일어나므로, 실패하면 기대 문구를 `"anchor"` 로 바꾼다. 거절이 일어나지 않을 때만 보고한다.

- [ ] **Step 3: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.StrictYamlTest"`
Expected: 컴파일 오류(`StrictYaml` 없음)

- [ ] **Step 4: 구현한다 — `StrictYaml.kt`**

```kotlin
package koshei.episode.runtime

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLParser

/**
 * YAML to JSON text for the policy file (design §8.2). The tree is built from the parser's tokens rather than by
 * Jackson's tree reader, so the refusals do not depend on a parser feature: a repeated key (a later `agentLayerEnabled`
 * must not silently override an earlier one), an anchor or alias (a value pulled in from elsewhere), a second
 * document, and an empty file are all refused with [IllegalArgumentException].
 */
internal object StrictYaml {
    private val factory = YAMLFactory()
    private val nodes = JsonNodeFactory.instance

    fun toJson(text: String): String = try {
        (factory.createParser(text) as YAMLParser).use { p ->
            if (p.nextToken() == null) throw IllegalArgumentException("empty document")
            val root = value(p)
            if (p.nextToken() != null) throw IllegalArgumentException("more than one document")
            root.toString()
        }
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("not readable YAML: ${e.message}", e)
    }

    private fun value(p: YAMLParser): JsonNode {
        if (p.isCurrentAlias) throw IllegalArgumentException("alias *${p.text} is not allowed (no anchors in the policy)")
        p.objectId?.let { throw IllegalArgumentException("anchor &$it is not allowed") }
        return when (p.currentToken()) {
            JsonToken.START_OBJECT -> nodes.objectNode().also { o ->
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    if (p.isCurrentAlias || p.objectId != null) throw IllegalArgumentException("anchor or alias on a key is not allowed")
                    val name = p.currentName()
                    if (o.has(name)) throw IllegalArgumentException("duplicate key '$name'")
                    p.nextToken()
                    o.set<JsonNode>(name, value(p))
                }
            }
            JsonToken.START_ARRAY -> nodes.arrayNode().also { a ->
                while (p.nextToken() != JsonToken.END_ARRAY) a.add(value(p))
            }
            JsonToken.VALUE_STRING -> nodes.textNode(p.text)
            JsonToken.VALUE_NUMBER_INT -> when (p.numberType) {
                JsonParser.NumberType.INT, JsonParser.NumberType.LONG -> nodes.numberNode(p.longValue)
                else -> nodes.numberNode(p.bigIntegerValue)
            }
            JsonToken.VALUE_NUMBER_FLOAT -> nodes.numberNode(p.decimalValue)
            JsonToken.VALUE_TRUE -> nodes.booleanNode(true)
            JsonToken.VALUE_FALSE -> nodes.booleanNode(false)
            JsonToken.VALUE_NULL -> nodes.nullNode()
            else -> throw IllegalArgumentException("unexpected ${p.currentToken()}")
        }
    }
}
```

`YAMLParser.isCurrentAlias()` 와 `getObjectId()` 가 2.17.2 에 있는 것은 리뷰에서 `javap` 로 확인했다. (정정, 청크 2 리뷰) 위 코드의 앵커 검사는 컬렉션에만 듣는다. Jackson 2.17.2 의 `getObjectId()` 는 스칼라에 대해 늘 `null` 이라 `a: &x 1` 이 통과했다. `an alias is refused` 가 통과한 것도 앵커를 먼저 만나서가 아니라 별칭 메시지에 우연히 `anchors` 라는 낱말이 들어 있었기 때문이다. 고친 코드는 SnakeYAML 이벤트를 먼저 걸어 앵커 · 별칭 · 태그 · 지시문을 거부하고, 시험은 `anchor &x` · `alias *x` 처럼 이름까지 본다. 앵커와 그 별칭이 함께 있으면 이벤트 순서상 앵커에서 거부된다.

- [ ] **Step 5: `StrictYamlTest` 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.StrictYamlTest"`
Expected: 13개 PASS

- [ ] **Step 6: 실패하는 시험을 쓴다 — `PolicyFileReaderTest`(파일 쪽)**

```kotlin
package koshei.episode.runtime

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolicyFileReaderTest {
    @TempDir lateinit var dir: Path

    @Test fun `no file is MISSING - the table was taken down on purpose`() {
        assertEquals(PolicyReadResult("MISSING"), PolicyFileReader(dir.resolve("active.yaml")).read())
    }

    @Test fun `a readable file is FOUND as JSON text`() {
        val file = dir.resolve("active.yaml")
        Files.writeString(file, "version: \"1\"\n")
        val read = PolicyFileReader(file).read()
        assertEquals("FOUND", read.kind)
        assertEquals("""{"version":"1"}""", read.json)
    }

    @Test fun `a repeated key is UNREADABLE and names the file`() {
        val file = dir.resolve("active.yaml")
        Files.writeString(file, "agentLayerEnabled: false\nagentLayerEnabled: true\n")
        val read = PolicyFileReader(file).read()
        assertEquals("UNREADABLE", read.kind)
        assertTrue("agentLayerEnabled" in read.reason!! && "active.yaml" in read.reason!!, read.reason)
    }

    @Test fun `a directory in its place is UNREADABLE`() {
        val read = PolicyFileReader(Files.createDirectory(dir.resolve("active.yaml"))).read()
        assertEquals("UNREADABLE", read.kind)
        assertNotNull(read.reason)
    }

    @Test fun `the file is read afresh on every call`() {
        val file = dir.resolve("active.yaml")
        val reader = PolicyFileReader(file)
        Files.writeString(file, "version: \"1\"\n")
        assertEquals("""{"version":"1"}""", reader.read().json)
        Files.writeString(file, "version: \"2\"\n")
        assertEquals("""{"version":"2"}""", reader.read().json)
    }
}
```

- [ ] **Step 7: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.PolicyFileReaderTest"`
Expected: 컴파일 오류

- [ ] **Step 8: 구현한다 — `PolicyFileReader.kt`**

```kotlin
package koshei.episode.runtime

import java.nio.file.Files
import java.nio.file.Path

/**
 * The plan-B policy source (design §8.2): one YAML file, read afresh on every call. No file is MISSING (the table was
 * taken down on purpose); a file that cannot be read or is not strict YAML is UNREADABLE (the core keeps the last valid
 * table); anything else is FOUND as JSON text, and the core decides whether it is a valid table.
 */
class PolicyFileReader(private val path: Path) {
    fun read(): PolicyReadResult {
        if (!Files.exists(path)) return PolicyReadResult("MISSING")
        val text = try {
            Files.readString(path)
        } catch (e: Exception) {
            return PolicyReadResult("UNREADABLE", reason = "$path: cannot read: ${e.message}")
        }
        return try {
            PolicyReadResult("FOUND", json = StrictYaml.toJson(text))
        } catch (e: IllegalArgumentException) {
            PolicyReadResult("UNREADABLE", reason = "$path: ${e.message}")
        }
    }
}
```

- [ ] **Step 9: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.PolicyFileReaderTest"`
Expected: 5개 PASS

- [ ] **Step 10: 커밋한다**

```bash
git add episode-runtime/build.gradle.kts episode-runtime/src
git commit -m "feat(episode-runtime): strict YAML policy reader - repeated keys, anchors and second documents are unreadable" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 4: 정책 v1 파일

**Files:**
- Create: `model/episode-policy/active.yaml`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/PolicyFileReaderTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`PolicyFileReaderTest` 에 더한다.

```kotlin
    @Test fun `the committed v1 table is valid and keeps auto-approval off`() {
        val file = Path.of(System.getProperty("koshei.repoRoot"), "model", "episode-policy", "active.yaml")
        val read = PolicyFileReader(file).read()
        assertEquals("FOUND", read.kind, read.reason)
        val parsed = koshei.episode.parsePolicy(read.toRead().let { (it as koshei.episode.PolicyRead.Found).node })
        val table = (parsed as? koshei.episode.PolicyParse.Valid)?.policy ?: kotlin.test.fail("invalid: $parsed")
        assertTrue(table.agentLayerEnabled)
        assertEquals(false, table.autoApprove[koshei.episode.CandidateKind.APPROVE_REMEDY]?.allowed, "policy v1 (design §10.1, §18)")
    }
```

`toRead()` 는 `Conversions.kt` 의 확장 함수다(`internal` 이면 같은 모듈 시험에서 보인다). import 는 파일 머리에서 정리한다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.PolicyFileReaderTest"`
Expected: FAIL(`MISSING`)

- [ ] **Step 3: 파일을 쓴다 — `model/episode-policy/active.yaml`**

```yaml
# Episode policy table v1 (design §10.1). The runtime reads this file afresh at every decision (plan B; plan D moves
# the active table to git + a DB pointer + audit, §10.3). Repeated keys, anchors and a second document make the file
# unreadable; unknown keys or bad values make the table rejected (POLICY_REJECTED). Either way the episode keeps the
# last valid table and suspends auto-approval (§8.2); with no valid table yet it is POLICY_MISSING.
version: "2026-10-03.1"
expiresAt: null
agentLayerEnabled: true                     # false: every episode hands over to a person (R5, §10.4)
autoApprove:
  # v1 keeps auto-approval OFF (design §18, 2026-10-01): a clean diagnosis is not a correct one. A person approves.
  APPROVE_REMEDY: { allowed: false, requireClean: true, skills: [pick_place] }
actionCatalog: []                           # SAGA_ACTION entries (plan D)
deadlines:
  correlatingMs: 5000
  approvalMs: 300000
  approvalValidityMs: 600000
  revalidateMs: 30000
  recordMs: 10000
  dispatchMs: 30000
  childMaxMs: 600000
  evidenceMs: 600000
  unknownRecheckMs: 30000
  unknownMs: 900000
  escalatedRetentionMs: 86400000
  episodeMs: 3600000
# narrator contract 0.6: two attempts of 540 s plus queue slack; heartbeat 30 s (design §8.1).
diagnosis: { scheduleToCloseMs: 1200000, startToCloseMs: 540000, heartbeatMs: 30000, maxAttempts: 2 }
maxAttemptsPerEpisode: 3
repeatedRemedyThreshold: 2
snapshotMaxBytes: 262144
correlation: []
```

값을 `:episode` 시험 표(`episode/src/test/kotlin/koshei/episode/Policies.kt`)와 대조한다. 다른 칸이 있으면 시험 표 쪽 값을 따르고 보고한다. `model/README.md` 에 파일 목록이 있으면 한 줄을 더한다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.PolicyFileReaderTest"`
Expected: 6개 PASS

- [ ] **Step 5: 커밋한다**

```bash
git add model/episode-policy/active.yaml model/README.md episode-runtime/src/test
git commit -m "feat(model): episode policy v1 - auto-approval off, values of the tested table" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

(`model/README.md` 를 바꾸지 않았으면 `git add` 에서 뺀다.)

---

## Chunk 3: 기록 저장소

### Task 5: `episode_event` · `episode_notice` · `episode_dispatch` 와 `EpisodeStore`

**Files:**
- Create: `episode-runtime/src/main/resources/episode-schema.sql`
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeStore.kt`
- Create: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeDb.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeStoreTest.kt`

- [ ] **Step 1: 시험 지원을 쓴다 — `EpisodeDb.kt`**

`registry/src/test/kotlin/koshei/registry/RegistryDbSupport.kt` 와 같은 방식이다.

```kotlin
package koshei.episode.runtime

import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** One Postgres for the module's tests, started on first use; every test starts from empty episode tables. */
internal object EpisodeDb {
    private val container = PostgreSQLContainer("postgres:16")
    private var started = false

    fun connection(): Connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    @Synchronized fun reset(): EpisodeStore {
        val store = EpisodeStore(::connection)
        if (!started) {
            container.start()
            store.ensureSchema()
            store.ensureSchema()   // the script must be safe to run twice (it runs at every worker start)
            started = true
            Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
        }
        connection().use { it.createStatement().execute("TRUNCATE episode_event, episode_notice, episode_dispatch") }
        return store
    }
}
```

- [ ] **Step 2: 실패하는 시험을 쓴다 — `EpisodeStoreTest`**

```kotlin
package koshei.episode.runtime

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeStoreTest {
    private lateinit var store: EpisodeStore

    @BeforeEach fun up() { store = EpisodeDb.reset() }

    @Test fun `records come back in seq order whatever order they landed in`() {
        assertTrue(store.append("ep:a/r1", 2, "TRANSITION", """{"to":"DIAGNOSING"}"""))
        assertTrue(store.append("ep:a/r1", 1, "OPENED", """{"eventId":"search:s-1"}"""))
        assertTrue(store.append("ep:b/r1", 1, "OPENED", """{}"""))
        assertEquals(listOf(1L to "OPENED", 2L to "TRANSITION"), store.events("ep:a/r1").map { it.seq to it.kind })
    }

    @Test fun `the same record again is a quiet no-op - an activity retry`() {
        assertTrue(store.append("ep:a/r1", 1, "OPENED", """{"a":1,"b":2}"""))
        assertFalse(store.append("ep:a/r1", 1, "OPENED", """{"b":2,"a":1}"""))   // the same JSON value
        assertEquals(1, store.events("ep:a/r1").size)
    }

    @Test fun `a different record on a taken seq is a conflict, never overwritten`() {
        store.append("ep:a/r1", 1, "OPENED", """{"a":1}""")
        assertThrows<RecordConflict> { store.append("ep:a/r1", 1, "OPENED", """{"a":2}""") }
        assertThrows<RecordConflict> { store.append("ep:a/r1", 1, "TRANSITION", """{"a":1}""") }
        assertEquals("""{"a": 1}""", store.events("ep:a/r1").single().payloadJson)
    }

    @Test fun `notices are kept in arrival order per episode`() {
        store.notice("ep:a/r1", """{"kind":"APPROVAL_NEEDED"}""")
        store.notice("ep:a/r1", """{"kind":"ESCALATED"}""")
        assertEquals(listOf("APPROVAL_NEEDED", "ESCALATED"), store.notices("ep:a/r1").map { it.substringAfter("\"kind\": \"").substringBefore('"') })
    }

    @Test fun `a dispatch outcome is kept verbatim and the first writer wins`() {
        assertNull(store.outcome("k1"))
        val answer = """{"schemaVersion":"2",  "outcome":"APPROVED"}"""   // odd spacing must survive
        assertEquals(DispatchOutcome("ANSWER", answer), store.saveOutcome("ep:a/r1", "k1", DispatchOutcome("ANSWER", answer)))
        assertEquals(DispatchOutcome("ANSWER", answer), store.saveOutcome("ep:a/r1", "k1", DispatchOutcome("ANSWER", "{}")))
        assertEquals(DispatchOutcome("ANSWER", answer), store.outcome("k1"))
    }

    @Test fun `a person task is issued once - the task and its notice together`() {
        val intent = """{"idempotencyKey":"ep:a/r1:1:CHOOSE_SOURCE","candidate":{"kind":"CHOOSE_SOURCE"}}"""
        assertEquals(DispatchOutcome("PERSON_TASK"), store.issueTask("ep:a/r1", "ep:a/r1:1:CHOOSE_SOURCE", intent))
        assertEquals(DispatchOutcome("PERSON_TASK"), store.issueTask("ep:a/r1", "ep:a/r1:1:CHOOSE_SOURCE", intent))
        assertEquals(1, store.notices("ep:a/r1").size, "one notice for one task")
        assertTrue("PERSON_TASK" in store.notices("ep:a/r1").single() && "CHOOSE_SOURCE" in store.notices("ep:a/r1").single())
        assertEquals(DispatchOutcome("PERSON_TASK"), store.outcome("ep:a/r1:1:CHOOSE_SOURCE"))
    }
}
```

`jsonb` 는 돌려줄 때 키 사이에 공백을 넣어(`{"a": 1}`) 다시 쓴다. 시험의 문자열 비교가 이 모양에 기댄다. `payloadJson` 이 `jsonb::text` 를 그대로 돌려주기 때문이다. 모양이 다르면 시험 쪽을 JSON 트리 비교로 바꾼다. 답 글자(`answer`)는 `text` 칸이라 바뀌지 않아야 한다.

- [ ] **Step 3: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeStoreTest"`
Expected: 컴파일 오류

- [ ] **Step 4: 스키마를 쓴다 — `episode-schema.sql`**

```sql
-- Episode audit and channel tables (design §8.5, §13). Separate from app/src/main/resources/schema.sql, which DROPs.
-- Safe to run at every worker start. Append-only by use: production revokes UPDATE and DELETE on these tables from
-- the runtime account (plan D); nothing in koshei updates or deletes a row.

-- The audit record of an episode instance (workflow id + run id). Written at least once per (instance, seq).
CREATE TABLE IF NOT EXISTS episode_event (
    episode_instance_id text        NOT NULL,
    seq                 bigint      NOT NULL,
    kind                text        NOT NULL,
    payload             jsonb       NOT NULL,
    at                  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (episode_instance_id, seq)
);

-- The operator channel of plan B (design §8.5): one row per notice, read by the control plane (plan B3c).
CREATE TABLE IF NOT EXISTS episode_notice (
    id                  bigserial   PRIMARY KEY,
    episode_instance_id text        NOT NULL,
    notice              jsonb       NOT NULL,
    at                  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS episode_notice_instance ON episode_notice (episode_instance_id, id);

-- What a dispatch came back with, by idempotency key (design §8.4, §11). The answer is picasso's text, verbatim.
CREATE TABLE IF NOT EXISTS episode_dispatch (
    idempotency_key     text        PRIMARY KEY,
    episode_instance_id text        NOT NULL,
    outcome_kind        text        NOT NULL,
    answer              text,
    at                  timestamptz NOT NULL DEFAULT now()
);
```

- [ ] **Step 5: 구현한다 — `EpisodeStore.kt`**

```kotlin
package koshei.episode.runtime

import java.sql.Connection

/** One stored record of an episode. [payloadJson] is the stored JSON value (jsonb's own spacing). */
data class StoredEvent(val seq: Long, val kind: String, val payloadJson: String)

/** A different record already holds this (instance, seq): never overwritten, never retried. */
class RecordConflict(message: String) : RuntimeException(message)

/**
 * The episode tables (design §8.5, §13). Plain JDBC, one connection per call. Open so tests can inject faults.
 */
open class EpisodeStore(private val connect: () -> Connection) {

    fun ensureSchema() {
        val sql = checkNotNull(javaClass.getResourceAsStream("/episode-schema.sql")) { "episode-schema.sql missing" }
            .bufferedReader().use { it.readText() }
        connect().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    /**
     * Appends one record. True when written; false when the same record is already there (an activity retry);
     * [RecordConflict] when a different record holds the seq.
     */
    open fun append(instanceId: String, seq: Long, kind: String, payloadJson: String): Boolean = connect().use { c ->
        val written = c.prepareStatement(
            "INSERT INTO episode_event (episode_instance_id, seq, kind, payload) VALUES (?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING",
        ).use { s ->
            s.setString(1, instanceId); s.setLong(2, seq); s.setString(3, kind); s.setString(4, payloadJson)
            s.executeUpdate() == 1
        }
        if (written) return true
        c.prepareStatement(
            "SELECT kind = ? AND payload = ?::jsonb FROM episode_event WHERE episode_instance_id = ? AND seq = ?",
        ).use { s ->
            s.setString(1, kind); s.setString(2, payloadJson); s.setString(3, instanceId); s.setLong(4, seq)
            s.executeQuery().use { rs ->
                check(rs.next()) { "episode_event $instanceId #$seq vanished" }
                if (!rs.getBoolean(1)) throw RecordConflict("episode_event $instanceId #$seq already holds a different record")
            }
        }
        false
    }

    fun events(instanceId: String): List<StoredEvent> = connect().use { c ->
        c.prepareStatement("SELECT seq, kind, payload::text FROM episode_event WHERE episode_instance_id = ? ORDER BY seq").use { s ->
            s.setString(1, instanceId)
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) StoredEvent(rs.getLong(1), rs.getString(2), rs.getString(3)) else null }.toList() }
        }
    }

    open fun notice(instanceId: String, noticeJson: String) {
        connect().use { c -> insertNotice(c, instanceId, noticeJson) }
    }

    fun notices(instanceId: String): List<String> = connect().use { c ->
        c.prepareStatement("SELECT notice::text FROM episode_notice WHERE episode_instance_id = ? ORDER BY id").use { s ->
            s.setString(1, instanceId)
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }
    }

    /** The outcome already stored for this key, or null. */
    open fun outcome(key: String): DispatchOutcome? = connect().use { c ->
        c.prepareStatement("SELECT outcome_kind, answer FROM episode_dispatch WHERE idempotency_key = ?").use { s ->
            s.setString(1, key)
            s.executeQuery().use { rs -> if (rs.next()) DispatchOutcome(rs.getString(1), rs.getString(2)) else null }
        }
    }

    /** Stores [outcome] unless the key already has one; returns whichever is stored (the first writer wins). */
    open fun saveOutcome(instanceId: String, key: String, outcome: DispatchOutcome): DispatchOutcome {
        connect().use { c -> insertOutcome(c, instanceId, key, outcome) }
        return checkNotNull(outcome(key)) { "episode_dispatch $key vanished" }
    }

    /**
     * Issues a person task exactly once (design §8.4): the task row and its notice are written in one transaction, and
     * nothing is written when the key already has a task.
     */
    open fun issueTask(instanceId: String, key: String, intentJson: String): DispatchOutcome {
        val task = DispatchOutcome("PERSON_TASK")
        connect().use { c ->
            c.autoCommit = false
            try {
                if (insertOutcome(c, instanceId, key, task)) {
                    val notice = mapper.createObjectNode().apply {
                        put("kind", "PERSON_TASK")
                        put("idempotencyKey", key)
                        set<com.fasterxml.jackson.databind.JsonNode>("intent", strictJson.readTree(intentJson))
                    }
                    insertNotice(c, instanceId, notice.toString())
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
        return checkNotNull(outcome(key)) { "episode_dispatch $key vanished" }
    }

    private fun insertOutcome(c: Connection, instanceId: String, key: String, outcome: DispatchOutcome): Boolean =
        c.prepareStatement(
            "INSERT INTO episode_dispatch (idempotency_key, episode_instance_id, outcome_kind, answer) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        ).use { s ->
            s.setString(1, key); s.setString(2, instanceId); s.setString(3, outcome.kind); s.setString(4, outcome.answerJson)
            s.executeUpdate() == 1
        }

    private fun insertNotice(c: Connection, instanceId: String, noticeJson: String) {
        c.prepareStatement("INSERT INTO episode_notice (episode_instance_id, notice) VALUES (?, ?::jsonb)").use { s ->
            s.setString(1, instanceId); s.setString(2, noticeJson)
            s.executeUpdate()
        }
    }

    private companion object {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
    }
}
```

`strictJson` 은 `Conversions.kt` 에 있는 것을 쓴다. 이름과 가시성을 확인한다. `DispatchOutcome` 의 칸 이름(`kind`, `answerJson`)은 `Wire.kt` 와 맞춘다. import 는 파일 머리로 올려 정리한다.

- [ ] **Step 6: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeStoreTest"`
Expected: 6개 PASS

- [ ] **Step 7: 커밋한다**

```bash
git add episode-runtime/src
git commit -m "feat(episode-runtime): episode_event, episode_notice, episode_dispatch - append-only records, verbatim answers, a person task issued once" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Chunk 4: Mock picasso 와 실제 액티비티

### Task 6: `MockPicasso`

**Files:**
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/MockPicasso.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/MockPicassoTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package koshei.episode.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals

class MockPicassoTest {
    private val json = ObjectMapper()

    private fun remedy(robot: String = "hum-02", order: String = "PATROL-1", search: String = "search-1") =
        """{"candidateId":"APPROVE_REMEDY:$robot:$order:pick_place","kind":"APPROVE_REMEDY",
            "ref":{"robotId":"$robot","jobOrderId":"$order","searchId":"$search"},"sawSkillTypes":["pick_place"]}"""

    private fun intent(candidate: String) = json.readTree("""{"candidate":$candidate,"idempotencyKey":"k","approval":null,"targetUnit":null}""") as ObjectNode

    private fun answer(text: String) = json.readTree(text)

    @Test fun `the first approval of a proposal is APPROVED in picasso's schema 2 with the steps that went out`() {
        val a = answer(MockPicasso().approve(intent(remedy()), "PERSON"))
        assertEquals("2", a["schemaVersion"].textValue())
        assertEquals("APPROVED", a["outcome"].textValue())
        assertEquals("mock-exec-1", a["executionId"].textValue())
        assertEquals("pick_place", a["steps"][0]["skillType"].textValue())
    }

    @Test fun `the same proposal approved again is REMEDY_NOT_APPLIED - it was consumed`() {
        val picasso = MockPicasso()
        picasso.approve(intent(remedy()), "PERSON")
        val again = answer(picasso.approve(intent(remedy()), "PERSON"))
        assertEquals("REFUSED", again["outcome"].textValue())
        assertEquals("REMEDY_NOT_APPLIED", again["refusal"].textValue())
    }

    @Test fun `a newer search for the consumed (robot, order) is NO_PROPOSAL`() {
        val picasso = MockPicasso()
        picasso.approve(intent(remedy(search = "search-1")), "PERSON")
        assertEquals("NO_PROPOSAL", answer(picasso.approve(intent(remedy(search = "search-2")), "AGENT"))["refusal"].textValue())
    }

    @Test fun `another (robot, order) is its own proposal`() {
        val picasso = MockPicasso()
        picasso.approve(intent(remedy()), "PERSON")
        val other = answer(picasso.approve(intent(remedy(order = "PATROL-2")), "PERSON"))
        assertEquals("APPROVED", other["outcome"].textValue())
        assertEquals("mock-exec-2", other["executionId"].textValue())
    }

    @Test fun `revalidation is TRUE until the proposal is consumed, then FALSE`() {
        val picasso = MockPicasso()
        assertEquals("TRUE", picasso.revalidate(remedy()))
        picasso.approve(intent(remedy()), "PERSON")
        assertEquals("FALSE", picasso.revalidate(remedy()))
    }

    @Test fun `revalidation of a person task is TRUE - the mock has no source to read`() =
        assertEquals("TRUE", MockPicasso().revalidate("""{"candidateId":"CHOOSE_SOURCE:x","kind":"CHOOSE_SOURCE","ref":{}}"""))

    @Test fun `a candidate it cannot read is UNKNOWN`() = assertEquals("UNKNOWN", MockPicasso().revalidate("{not json"))
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.MockPicassoTest"`
Expected: 컴파일 오류

- [ ] **Step 3: 구현한다**

```kotlin
package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * picasso's approval window as plan B needs it (design §8.3, §11), in this process's memory. Like the real one it
 * drops the idempotency key and offers no lookup of past approvals: a proposal — one (robot, order) — is consumed by
 * its first approval. Approving it again answers REMEDY_NOT_APPLIED for the same search line and NO_PROPOSAL for
 * another one, both "my earlier attempt may have landed" (UNKNOWN(OUTCOME) in the core). Revalidation is the only
 * way to TRUE without a person (§8.3): TRUE while the proposal stands, FALSE once consumed.
 */
class MockPicasso {
    private val consumedBy = HashMap<Pair<String, String>, String>()   // (robotId, jobOrderId) -> searchId that consumed it
    private var executions = 0

    @Synchronized fun revalidate(candidateJson: String): String {
        val candidate = try {
            strictJson.readTree(candidateJson)
        } catch (e: Exception) {
            return "UNKNOWN"
        }
        if (candidate?.path("kind")?.asText() != "APPROVE_REMEDY") return "TRUE"
        return if (proposal(candidate) in consumedBy) "FALSE" else "TRUE"
    }

    /** The answer text, picasso `ApprovalWire.encode` schema 2. [approverKind] is accepted and not checked here. */
    @Synchronized fun approve(intent: JsonNode, approverKind: String?): String {
        val candidate = intent.path("candidate")
        val key = proposal(candidate)
        val search = candidate.path("ref").path("searchId").asText()
        consumedBy[key]?.let { by ->
            val refusal = if (by == search) "REMEDY_NOT_APPLIED" else "NO_PROPOSAL"
            return refused(refusal, "mock: the proposal for ${key.first}/${key.second} was consumed by $by")
        }
        consumedBy[key] = search
        executions += 1
        return mapper.createObjectNode().apply {
            put("schemaVersion", "2")
            put("contractSemver", "1.0.0")
            put("outcome", "APPROVED")
            put("executionId", "mock-exec-$executions")
            putArray("steps").apply {
                candidate.path("sawSkillTypes").forEach { addObject().put("skillType", it.asText()).putObject("parameters") }
            }
        }.toString()
    }

    private fun proposal(candidate: JsonNode): Pair<String, String> =
        candidate.path("ref").path("robotId").asText() to candidate.path("ref").path("jobOrderId").asText()

    private fun refused(refusal: String, reason: String): String = mapper.createObjectNode().apply {
        put("schemaVersion", "2")
        put("contractSemver", "1.0.0")
        put("outcome", "REFUSED")
        put("refusal", refusal)
        put("reason", reason)
    }.toString()

    private companion object {
        val mapper = ObjectMapper()
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.MockPicassoTest"`
Expected: 7개 PASS

- [ ] **Step 5: 커밋한다**

```bash
git add episode-runtime/src
git commit -m "feat(episode-runtime): Mock picasso consumes a proposal on approval, as the real window does" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 7: `EpisodeActivitiesImpl`

**Files:**
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeActivitiesImpl.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeActivitiesImplTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

액티비티를 Temporal 없이 직접 부른다. `RecordConflict` 가 재시도 없는 `ApplicationFailure` 로 바뀌는지는 예외 형만 본다. Temporal 안에서의 동작은 Task 9 에서 본다.

```kotlin
package koshei.episode.runtime

import io.temporal.failure.ApplicationFailure
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpisodeActivitiesImplTest {
    @TempDir lateinit var dir: Path
    private lateinit var store: EpisodeStore
    private val picasso = MockPicasso()
    private val acts by lazy { EpisodeActivitiesImpl(PolicyFileReader(dir.resolve("active.yaml")), store, picasso) }

    @BeforeEach fun up() { store = EpisodeDb.reset() }

    private fun remedyIntent(key: String = "ep:a/r1:1:APPROVE_REMEDY") =
        """{"candidate":{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{"robotId":"hum-02","jobOrderId":"PATROL-1","searchId":"search-1"},
            "sawSkillTypes":["pick_place"]},"idempotencyKey":"$key","approval":null,"targetUnit":null}"""

    private fun taskIntent(kind: String) =
        """{"candidate":{"candidateId":"c","kind":"$kind","ref":{}},"idempotencyKey":"ep:a/r1:1:$kind","approval":null,"targetUnit":null}"""

    @Test fun `readPolicy reads the file`() {
        assertEquals("MISSING", acts.readPolicy().kind)
        Files.writeString(dir.resolve("active.yaml"), "version: \"1\"\n")
        assertEquals("FOUND", acts.readPolicy().kind)
    }

    @Test fun `record and recordIntent share the episode's seq space`() {
        acts.record("ep:a/r1", 1, "OPENED", "{}")
        acts.recordIntent("ep:a/r1", 2, remedyIntent())
        assertEquals(listOf("OPENED", "DISPATCH_INTENT"), store.events("ep:a/r1").map { it.kind })
    }

    @Test fun `a conflicting record fails without retry`() {
        acts.record("ep:a/r1", 1, "OPENED", """{"a":1}""")
        val e = assertThrows<ApplicationFailure> { acts.record("ep:a/r1", 1, "OPENED", """{"a":2}""") }
        assertTrue(e.isNonRetryable)
    }

    @Test fun `a remedy is dispatched to picasso once - a retry gets the stored answer`() {
        val first = acts.dispatch("ep:a/r1", remedyIntent(), "PERSON")
        val again = acts.dispatch("ep:a/r1", remedyIntent(), "PERSON")
        assertEquals(first, again)
        assertTrue("mock-exec-1" in first.answerJson!!)
    }

    @Test fun `a retry after the answer was lost meets picasso's consumption`() {
        val forgetful = object : EpisodeStore(EpisodeDb::connection) {
            var lose = true
            override fun saveOutcome(instanceId: String, key: String, outcome: DispatchOutcome): DispatchOutcome {
                if (lose) { lose = false; throw IllegalStateException("worker died before saving") }
                return super.saveOutcome(instanceId, key, outcome)
            }
        }
        val acts = EpisodeActivitiesImpl(PolicyFileReader(dir.resolve("active.yaml")), forgetful, picasso)
        assertThrows<IllegalStateException> { acts.dispatch("ep:a/r1", remedyIntent(), "PERSON") }
        val retry = acts.dispatch("ep:a/r1", remedyIntent(), "PERSON")
        assertTrue("REMEDY_NOT_APPLIED" in retry.answerJson!!, retry.answerJson)
    }

    @Test fun `a person task is issued once with its notice`() {
        assertEquals(DispatchOutcome("PERSON_TASK"), acts.dispatch("ep:a/r1", taskIntent("CHOOSE_SOURCE"), null))
        assertEquals(DispatchOutcome("PERSON_TASK"), acts.dispatch("ep:a/r1", taskIntent("CHOOSE_SOURCE"), null))
        assertEquals(1, store.notices("ep:a/r1").size)
    }

    @Test fun `ESCALATE has no dispatch target and fails without retry`() {
        val e = assertThrows<ApplicationFailure> { acts.dispatch("ep:a/r1", taskIntent("ESCALATE"), null) }
        assertTrue(e.isNonRetryable)
    }

    @Test fun `an intent without a key fails without retry`() {
        val e = assertThrows<ApplicationFailure> { acts.dispatch("ep:a/r1", """{"candidate":{"kind":"APPROVE_REMEDY"}}""", null) }
        assertTrue(e.isNonRetryable)
    }

    @Test fun `revalidate asks picasso`() =
        assertEquals("TRUE", acts.revalidate("""{"kind":"APPROVE_REMEDY","ref":{"robotId":"r","jobOrderId":"j","searchId":"s"}}"""))

    @Test fun `a notice goes to the channel table`() {
        acts.sendNotice("ep:a/r1", """{"kind":"ESCALATED","phase":"ESCALATED","reason":"AGENT_LAYER_OFF","detail":null}""")
        assertEquals(1, store.notices("ep:a/r1").size)
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeActivitiesImplTest"`
Expected: 컴파일 오류

- [ ] **Step 3: 구현한다**

```kotlin
package koshei.episode.runtime

import io.temporal.failure.ApplicationFailure
import org.slf4j.LoggerFactory

/**
 * The koshei-side activities of plan B (design §8.2–§8.5): the policy file, the episode tables, and Mock picasso.
 * They read, write and carry; every judgement stays in the pure core.
 */
class EpisodeActivitiesImpl(
    private val policy: PolicyFileReader,
    private val store: EpisodeStore,
    private val picasso: MockPicasso,
) : EpisodeActivities {

    override fun readPolicy(): PolicyReadResult = policy.read()

    override fun revalidate(candidateJson: String): String = picasso.revalidate(candidateJson)

    override fun record(instanceId: String, seq: Long, kind: String, payloadJson: String) {
        append(instanceId, seq, kind, payloadJson)
    }

    override fun recordIntent(instanceId: String, seq: Long, intentJson: String) {
        append(instanceId, seq, INTENT_KIND, intentJson)
    }

    /**
     * Idempotent on the intent's key (design §8.4, §11): a stored outcome is returned without calling anyone. A person
     * task is issued once. A remedy whose answer was lost before it was stored meets picasso's consumption on retry,
     * which the core reads as UNKNOWN(OUTCOME).
     */
    override fun dispatch(instanceId: String, intentJson: String, approverKind: String?): DispatchOutcome {
        val intent = try {
            strictJson.readTree(intentJson)
        } catch (e: Exception) {
            throw ApplicationFailure.newNonRetryableFailure("intent is not JSON: ${e.message}", "BadIntent")
        }
        val key = intent?.path("idempotencyKey")?.asText().orEmpty()
        if (key.isBlank()) throw ApplicationFailure.newNonRetryableFailure("intent without idempotencyKey", "BadIntent")
        store.outcome(key)?.let { return it }
        return when (val kind = intent.path("candidate").path("kind").asText()) {
            "APPROVE_REMEDY" -> store.saveOutcome(instanceId, key, DispatchOutcome("ANSWER", picasso.approve(intent, approverKind)))
            "CHOOSE_SOURCE", "OPERATOR_DECISION" -> store.issueTask(instanceId, key, intentJson)
            else -> throw ApplicationFailure.newNonRetryableFailure("no dispatch target for $kind", "NoDispatchTarget")
        }
    }

    override fun sendNotice(instanceId: String, noticeJson: String) {
        store.notice(instanceId, noticeJson)
        log.info("episode notice {} {}", instanceId, noticeJson)
    }

    private fun append(instanceId: String, seq: Long, kind: String, payloadJson: String) {
        try {
            store.append(instanceId, seq, kind, payloadJson)
        } catch (e: RecordConflict) {
            throw ApplicationFailure.newNonRetryableFailure(e.message, "RecordConflict")
        }
    }

    companion object {
        /** The intent record's kind in episode_event (the core's RecordKind has no value for it: it is a command). */
        const val INTENT_KIND = "DISPATCH_INTENT"
        private val log = LoggerFactory.getLogger(EpisodeActivitiesImpl::class.java)
    }
}
```

빌드 파일은 바꾸지 않는다. Temporal 의 공개 API 가 `org.slf4j.Logger` 를 돌려주므로 slf4j-api 는 이미 컴파일 경로에 있을 것이다. `org.slf4j` 가 풀리지 않을 때만 `implementation("org.slf4j:slf4j-api:2.0.13")` 를 더한다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeActivitiesImplTest"`
Expected: 10개 PASS

- [ ] **Step 5: 커밋한다**

```bash
git add episode-runtime/src episode-runtime/build.gradle.kts
git commit -m "feat(episode-runtime): koshei-side activities - policy file, episode tables, idempotent dispatch over Mock picasso" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Chunk 5: 배선, 끝에서 끝까지, 재생

### Task 8: 설정과 등록, `:app` 워커

**Files:**
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkers.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWorkersTest.kt`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/kotlin/koshei/app/Worker.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package koshei.episode.runtime

import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeWorkersTest {
    @Test fun `defaults - the repo's policy path, mock narrator, and NO episode worker until picasso is chosen`() {
        val c = EpisodeRuntimeConfig.fromEnv(emptyMap())
        assertEquals(Path.of("model", "episode-policy", "active.yaml"), c.policyPath)
        assertEquals(NarratorMode.MOCK, c.narrator)
        assertEquals(PicassoMode.OFF, c.picasso)   // the Mock is the test's (design §8.3, §11): never by default
    }

    @Test fun `mock picasso is an explicit choice`() =
        assertEquals(PicassoMode.MOCK, EpisodeRuntimeConfig.fromEnv(mapOf("KOSHEI_PICASSO" to "mock")).picasso)

    @Test fun `with picasso off nothing is registered`() {
        val env = episodeEnvironment { }
        try {
            EpisodeWorkers.register(env.workerFactory, EpisodeRuntimeConfig.fromEnv(emptyMap()), EpisodeStore { error("no DB needed") })
            kotlin.test.assertNull(env.workerFactory.tryGetWorker(EPISODE_TASK_QUEUE))
            kotlin.test.assertNull(env.workerFactory.tryGetWorker(NARRATOR_TASK_QUEUE))
        } finally {
            env.close()
        }
    }

    @Test fun `the environment chooses`() {
        val c = EpisodeRuntimeConfig.fromEnv(mapOf("KOSHEI_EPISODE_POLICY" to "/etc/koshei/p.yaml", "KOSHEI_NARRATOR" to "remote"))
        assertEquals(Path.of("/etc/koshei/p.yaml"), c.policyPath)
        assertEquals(NarratorMode.REMOTE, c.narrator)
    }

    @Test fun `an unknown narrator mode stops the worker at start`() {
        assertThrows<IllegalArgumentException> { EpisodeRuntimeConfig.fromEnv(mapOf("KOSHEI_NARRATOR" to "llm")) }
    }

    @Test fun `only the mock picasso exists until the host is placed`() {
        val e = assertThrows<IllegalArgumentException> { EpisodeRuntimeConfig.fromEnv(mapOf("KOSHEI_PICASSO" to "http")) }
        kotlin.test.assertTrue("§19" in e.message!!, e.message)
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeWorkersTest"`
Expected: 컴파일 오류

- [ ] **Step 3: 구현한다 — `EpisodeWorkers.kt`**

```kotlin
package koshei.episode.runtime

import io.temporal.worker.WorkerFactory
import io.temporal.worker.WorkerOptions
import java.nio.file.Path

/** Who answers `diagnose` on narrator-tq: koshei's Mock (plan B, P0) or narrator's own worker. Never both. */
enum class NarratorMode { MOCK, REMOTE }

/**
 * The approval window. Only the Mock exists until the host that reaches picasso's loopback window is placed (§19 C).
 * OFF (the default) runs no episode worker at all: the Mock is the test's (design §8.3, §11) — with it a worker would
 * take a fabricated APPROVED for a remedy that never reached picasso — so it runs only when chosen by name.
 */
enum class PicassoMode { OFF, MOCK }

/** The episode runtime's settings, read once at worker start (the workflow itself reads none, design §7.5). */
data class EpisodeRuntimeConfig(val policyPath: Path, val narrator: NarratorMode, val picasso: PicassoMode) {
    companion object {
        fun fromEnv(env: Map<String, String>): EpisodeRuntimeConfig = EpisodeRuntimeConfig(
            policyPath = Path.of(env["KOSHEI_EPISODE_POLICY"] ?: "model/episode-policy/active.yaml"),
            narrator = when (val v = env["KOSHEI_NARRATOR"] ?: "mock") {
                "mock" -> NarratorMode.MOCK
                "remote" -> NarratorMode.REMOTE
                else -> throw IllegalArgumentException("KOSHEI_NARRATOR must be mock or remote, was '$v'")
            },
            picasso = when (val v = env["KOSHEI_PICASSO"] ?: "off") {
                "off" -> PicassoMode.OFF
                "mock" -> PicassoMode.MOCK
                else -> throw IllegalArgumentException("KOSHEI_PICASSO: only 'mock' until the host is placed (design §19 C), was '$v'")
            },
        )
    }
}

/** Registers the episode workflow and its activities (design §4.1, §4.2). The caller starts the factory. */
object EpisodeWorkers {
    fun register(factory: WorkerFactory, config: EpisodeRuntimeConfig, store: EpisodeStore, picasso: MockPicasso = MockPicasso()) {
        if (config.picasso == PicassoMode.OFF) return
        factory.newWorker(
            EPISODE_TASK_QUEUE,
            // The same guard as the saga worker: a cold machine replaying parked episodes can exceed the 1 s default.
            WorkerOptions.newBuilder().setDefaultDeadlockDetectionTimeout(10_000).build(),
        ).apply {
            registerWorkflowImplementationTypes(EpisodeWorkflowImpl::class.java)
            registerActivitiesImplementations(EpisodeActivitiesImpl(PolicyFileReader(config.policyPath), store, picasso))
        }
        if (config.narrator == NarratorMode.MOCK) {
            factory.newWorker(NARRATOR_TASK_QUEUE).registerActivitiesImplementations(MockNarratorActivities())
        }
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeWorkersTest"`
Expected: 6개 PASS

- [ ] **Step 5: `:app` 에 잇는다**

`app/build.gradle.kts` 를 다음과 같이 바꾼다.
- `dependencies` 에 `implementation(project(":episode-runtime"))` 를 더한다.
- `kosheiEnvKeys` 에 `"KOSHEI_EPISODE_POLICY", "KOSHEI_NARRATOR", "KOSHEI_PICASSO"` 를 더한다.
- `run` 태스크를 다음으로 바꾼다.

```kotlin
tasks.named<JavaExec>("run") {
    forwardKosheiEnv()
    // The run task's working directory is app/, so the default relative policy path would miss the repo's model/.
    if (System.getenv("KOSHEI_EPISODE_POLICY") == null) {
        environment("KOSHEI_EPISODE_POLICY", rootProject.file("model/episode-policy/active.yaml").absolutePath)
    }
}
```

`Worker.kt` 에서 `worker.registerActivitiesImplementations(BlockActivitiesImpl(registry))` 바로 뒤에 다음을 넣는다.

```kotlin
    // The episode outer loop (design §4.1): its own queue, its own tables (created if absent, never dropped).
    // Off unless KOSHEI_PICASSO names an approval window; today only the test Mock exists (design §19 C).
    val episodeConfig = EpisodeRuntimeConfig.fromEnv(System.getenv())
    if (episodeConfig.picasso == PicassoMode.OFF) {
        println("[$workerName] episode worker off (KOSHEI_PICASSO unset)")
    } else {
        val episodeStore = EpisodeStore { Db.connect() }
        episodeStore.ensureSchema()
        EpisodeWorkers.register(factory, episodeConfig, episodeStore)
        System.err.println("[$workerName] WARNING episode worker on $EPISODE_TASK_QUEUE with the TEST Mock picasso: remedies reach no robot (design §8.3, §11)")
        println("[$workerName] episode worker on $EPISODE_TASK_QUEUE (narrator ${episodeConfig.narrator}, policy ${episodeConfig.policyPath})")
    }
```

import 는 `koshei.episode.runtime.EPISODE_TASK_QUEUE`, `EpisodeRuntimeConfig`, `EpisodeStore`, `EpisodeWorkers`, `PicassoMode` 다.

- [ ] **Step 6: 컴파일과 기존 시험을 확인한다**

Run: `./gradlew :app:compileKotlin :app:test`
Expected: BUILD SUCCESSFUL. `:app` 시험은 기존 수 그대로 PASS 한다.

Docker 위의 Temporal 과 Postgres 로 직접 띄우는 연기 시험은 하지 않는다. 증상을 넣는 길(감시자, 제어면)이 아직 없기 때문이다. 끝에서 끝까지는 Task 9 가 시험 환경에서 보인다.

- [ ] **Step 7: 커밋한다**

```bash
git add episode-runtime/src app/build.gradle.kts app/src/main/kotlin/koshei/app/Worker.kt
git commit -m "feat(app): koshei-episode-tq when KOSHEI_PICASSO=mock is chosen; mock narrator on narrator-tq unless KOSHEI_NARRATOR=remote" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 9: 끝에서 끝까지 — 실제 액티비티, 실제 표, 정책 v1

**Files:**
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeEndToEndTest.kt`

- [ ] **Step 1: 시험을 쓴다**

Task 2 에서 `Fixtures.kt` 로 옮긴 보조 함수(`episodeEnvironment`, `openEpisode`, `until(env, …)`, `result`, `eventually`, `searchSignal`)를 쓴다. `env.workerFactory`(`getWorkerFactory()`)는 1.25.1 에 있다(리뷰에서 확인).

```kotlin
package koshei.episode.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import io.temporal.client.WorkflowClient
import io.temporal.testing.TestWorkflowEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpisodeEndToEndTest {
    private var env: TestWorkflowEnvironment? = null
    private lateinit var client: WorkflowClient
    private lateinit var store: EpisodeStore
    @TempDir lateinit var dir: Path
    private val v1: Path = Path.of(System.getProperty("koshei.repoRoot"), "model", "episode-policy", "active.yaml")

    /** The real activities over the test Postgres, the Mock narrator and Mock picasso, [policy] as the policy file. */
    private fun start(policy: Path, store: EpisodeStore = this.store): TestWorkflowEnvironment {
        val e = episodeEnvironment {
            EpisodeWorkers.register(workerFactory, EpisodeRuntimeConfig(policy, NarratorMode.MOCK, PicassoMode.MOCK), store)
        }
        e.start()
        env = e
        client = e.workflowClient
        return e
    }

    @BeforeEach fun up() { store = EpisodeDb.reset() }
    @AfterEach fun down() { env?.close() }

    /** The instance's records once gap-free from seq 1 and [done] holds on them. */
    private fun stored(instanceId: String, done: (List<StoredEvent>) -> Boolean): List<StoredEvent> =
        eventually(describe = { "events ${store.events(instanceId).map { it.kind }}" }) {
            store.events(instanceId).takeIf { r -> r.map { it.seq } == (1L..r.size.toLong()).toList() && done(r) }
        }

    @Test fun `policy v1 - a person approves, the remedy goes to Mock picasso, everything is in episode_event`() {
        val e = start(v1)
        val ep = client.openEpisode()
        val waiting = ep.until(e) { it.phase == "AWAITING_APPROVAL" }   // auto-approval is off in v1
        assertEquals("ACCEPTED", ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1")))
        ep.until(e) { it.phase == "AWAITING_EVIDENCE" }
        val events = stored(waiting.instanceId) { r -> r.any { it.kind == "DISPATCH_RESULT" } }
        assertEquals("OPENED", events.first().kind)
        val intent = events.indexOfFirst { it.kind == EpisodeActivitiesImpl.INTENT_KIND }
        val result = events.indexOfFirst { it.kind == "DISPATCH_RESULT" }
        assertTrue(intent in 0 until result, "the intent is stored before the result: ${events.map { it.kind }}")
        assertTrue("mock-exec-1" in events[result].payloadJson, events[result].payloadJson)
        eventually { store.notices(waiting.instanceId).takeIf { n -> n.any { "APPROVAL_NEEDED" in it } } }   // the person was told
    }

    @Test fun `R2 - an answer lost before it was stored is an unknown outcome, never a second remedy`() {
        val forgetful = object : EpisodeStore(EpisodeDb::connection) {
            @Volatile var lose = true
            override fun saveOutcome(instanceId: String, key: String, outcome: DispatchOutcome): DispatchOutcome {
                if (lose) { lose = false; throw IllegalStateException("worker died before saving the answer") }
                return super.saveOutcome(instanceId, key, outcome)
            }
        }
        val e = start(v1, forgetful)
        val ep = client.openEpisode()
        val waiting = ep.until(e) { it.phase == "AWAITING_APPROVAL" }
        ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1"))
        ep.until(e) { it.phase == "UNKNOWN_OUTCOME" }
        val events = stored(waiting.instanceId) { r -> r.any { it.kind == "DISPATCH_RESULT" } }
        assertTrue(events.any { "REMEDY_NOT_APPLIED" in it.payloadJson }, "picasso's refusal is kept: ${events.map { it.kind }}")
    }

    @Test fun `a conflicting record fails once without retry, raises RECORD_LAG, and the episode goes on`() {
        val attempts = AtomicInteger()
        val conflicted = object : EpisodeStore(EpisodeDb::connection) {
            override fun append(instanceId: String, seq: Long, kind: String, payloadJson: String): Boolean {
                if (kind == "PROPOSED") { attempts.incrementAndGet(); throw RecordConflict("test: seq $seq is taken") }
                return super.append(instanceId, seq, kind, payloadJson)
            }
        }
        val e = start(v1, conflicted)
        val ep = client.openEpisode()
        val waiting = ep.until(e) { it.phase == "AWAITING_APPROVAL" }
        eventually { store.notices(waiting.instanceId).takeIf { n -> n.any { "RECORD_LAG" in it } } }
        Thread.sleep(2_000)   // a retry, were there one, would have come by now
        assertEquals(1, attempts.get(), "RecordConflict is not retried")
        assertEquals("AWAITING_APPROVAL", ep.view().phase)
    }

    @Test fun `switching the agent layer off in the file reaches a new episode and the next decision of an open one`() {
        val policy = dir.resolve("active.yaml")
        Files.copy(v1, policy)
        val e = start(policy)
        val a = client.openEpisode(id = "ep:a")
        val waiting = a.until(e) { it.phase == "AWAITING_APPROVAL" }
        Files.writeString(policy, Files.readString(v1).replace("agentLayerEnabled: true", "agentLayerEnabled: false"))
        val b = client.openEpisode(id = "ep:b", symptom = searchSignal("search-2"))
        assertEquals("AGENT_LAYER_OFF", b.until(e) { it.phase == "ESCALATED" }.escalationReason)
        eventually { store.notices(b.view().instanceId).takeIf { n -> n.any { "AGENT_LAYER_OFF" in it } } }
        // A's next decision reads the file again.
        a.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1"))
        assertEquals("AGENT_LAYER_OFF", a.until(e) { it.phase == "ESCALATED" }.escalationReason)
    }

    @Test fun `a repeated switch in the file is unreadable - with no valid table yet, the episode is POLICY_MISSING`() {
        val bad = dir.resolve("active.yaml")
        Files.writeString(bad, Files.readString(v1).replace("agentLayerEnabled: true", "agentLayerEnabled: false\nagentLayerEnabled: true"))
        val e = start(bad)
        val ep = client.openEpisode()
        // Unreadable has no tree, so the literal-switch rule cannot apply, and with no last valid table nothing is in
        // force (PolicyResolution.kt:62-65, 87). A reader that kept the first value would give a valid table with
        // `false` instead: AGENT_LAYER_OFF. So this reason tells the two apart.
        assertEquals("POLICY_MISSING", ep.until(e) { it.phase == "ESCALATED" }.escalationReason)
    }
}
```

`a.decide` 의 답은 단언하지 않는다. A 가 AWAITING_APPROVAL 에서 사람의 결정을 받는 순간 다시 읽는지(§7.5: Update 는 지금 표로 판단한다), 아니면 그 뒤 REVALIDATING 의 결과 사건에서 다시 읽는지에 따라 ESCALATED 로 가는 시점만 다르다. 끝 상태는 같다. 실제로 ESCALATED 에 닿지 않고 AWAITING_EVIDENCE 까지 간다면, 그것은 결정 사건에서 정책을 다시 읽지 않는다는 뜻이다. 그때는 시험을 고치지 말고 보고한다.

`search-2` 가 picasso run-1 의 다른 탐색 줄 id 인지 확인한다(`EpisodeWorkflowTest` 가 이미 `search-2` 를 쓴다).

`stored` 는 레코드가 비동기로 들어오므로 `eventually` 로 기다린다. `DISPATCH_RESULT` 는 코어 `RecordKind` 의 이름이다(`EpisodeModel.kt:97`).

- [ ] **Step 2: 돌려 본다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeEndToEndTest"`
Expected: 5개 PASS

실패하면 시험이 아니라 구현 쪽을 먼저 의심한다. 특히 둘째 시험이 RESOLVED 나 AWAITING_EVIDENCE 로 가면 멱등 · 소모 흉내가 틀린 것이다. 시험을 고쳐 통과시키지 않는다.

- [ ] **Step 3: 커밋한다**

```bash
git add episode-runtime/src
git commit -m "test(episode-runtime): end to end over real activities and tables - policy v1, R2 lost answer, record conflict, live file switch" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 10: 재생 시험

**Files:**
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeReplayTest.kt`

재생 API 는 리뷰에서 `javap` 로 확인했다. `io.temporal.testing.WorkflowReplayer` 에는 다음 둘이 있다.
- `replayWorkflowExecution(io.temporal.internal.common.WorkflowExecutionHistory, io.temporal.worker.Worker)`
- `replayWorkflowExecution(…, Class)`

`WorkflowClient.fetchHistory(…)` 가 돌려주는 `io.temporal.common.WorkflowExecutionHistory` 는 앞의 내부 형을 잇는다. 그래서 그대로 넘기면 된다. 대체 경로는 필요 없다.

- [ ] **Step 1: 시험을 쓴다**

시나리오 넷은 가짜 액티비티로 만든다. 재생은 액티비티를 다시 부르지 않는다. 각 함수는 끝 상태까지 몰고 간 뒤 이력을 가져온다.

```kotlin
package koshei.episode.runtime

import io.temporal.client.WorkflowClient
import io.temporal.common.WorkflowExecutionHistory
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import kotlin.test.Test

class EpisodeReplayTest {
    private val acts = FakeEpisodeActivities()
    private val narrator = ProgrammableNarrator()
    private lateinit var env: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient

    @BeforeEach fun up() {
        env = episodeEnvironment { fakeWorkers(acts, narrator) }
        env.start()
        client = env.workflowClient
    }

    @AfterEach fun down() = env.close()

    /** (a) Auto-approval to a person's DONE: diagnosis (a RawJson payload), revalidation, intent, dispatch, confirm. */
    private fun happyPath(): WorkflowExecutionHistory {
        val ep = client.openEpisode(id = "rp:a")
        val waiting = ep.until(env) { it.phase == "AWAITING_EVIDENCE" }
        ep.confirm(ConfirmRequest("OUTCOME", waiting.candidateId!!, waiting.proposalId!!, true, operatorId = "op-1"))
        ep.result()
        return client.fetchHistory("rp:a")
    }

    /** (b) Policy v1: a person approves; then takeover and close. */
    private fun personApproves(): WorkflowExecutionHistory {
        acts.policyJson = TestPolicies.table(autoApprove = false)
        try {
            val ep = client.openEpisode(id = "rp:b")
            val waiting = ep.until(env) { it.phase == "AWAITING_APPROVAL" }
            ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1"))
            ep.until(env) { it.phase == "AWAITING_EVIDENCE" }
            ep.takeover(TakeoverRequest("op-1"))
            ep.close(CloseRequest("op-1", "replay"))
            ep.result()
        } finally {
            acts.policyJson = TestPolicies.table(autoApprove = true)
        }
        return client.fetchHistory("rp:b")
    }

    /** (c) The first run of a workflow continued as new for a symptom that came while it drained. */
    private fun continuedRun(): WorkflowExecutionHistory {
        val gate = CountDownLatch(1)
        acts.dispatchGate = gate
        val ep = client.openEpisode(id = "rp:c")
        val first = ep.until(env) { it.phase == "DISPATCHED" }.instanceId
        ep.takeover(TakeoverRequest("op-1"))
        ep.close(CloseRequest("op-1", "replay"))
        ep.symptom(searchSignal("search-2"))
        gate.countDown()
        acts.dispatchGate = null
        client.newWorkflowStub(EpisodeWorkflow::class.java, "rp:c").until(env) { it.instanceId != first && it.phase != "OPENING" }
        return client.fetchHistory("rp:c", first.substringAfterLast('/'))
    }

    /** (d) A record that keeps failing: one RECORD_LAG notice, then a person's DONE. */
    private fun recordLag(): WorkflowExecutionHistory {
        acts.recordFault = { it == "PROPOSED" }
        try {
            val ep = client.openEpisode(id = "rp:d")
            val waiting = ep.until(env) { it.phase == "AWAITING_EVIDENCE" }
            // This may also match a RECORD_LAG of rp:c's still-running continued run; harmless: rp:d cannot end before
            // its own failed record and notice settle (the drain waits), so its history holds them either way.
            eventually { acts.notices.toList().takeIf { n -> n.any { "RECORD_LAG" in it } } }
            ep.confirm(ConfirmRequest("OUTCOME", waiting.candidateId!!, waiting.proposalId!!, true, operatorId = "op-1"))
            ep.result()
        } finally {
            acts.recordFault = { false }
        }
        return client.fetchHistory("rp:d")
    }

    @Test fun `every scenario's history replays on today's code with koshei's converter`() {
        val histories = listOf(happyPath(), personApproves(), continuedRun(), recordLag())
        val replay = episodeEnvironment { }
        try {
            val worker = replay.newWorker("replay").apply { registerWorkflowImplementationTypes(EpisodeWorkflowImpl::class.java) }
            histories.forEach { WorkflowReplayer.replayWorkflowExecution(it, worker) }
        } finally {
            replay.close()
        }
    }

    @Test fun `the default converter cannot replay them - the converter is part of determinism`() {
        val history = happyPath()
        assertThrows<Exception> { WorkflowReplayer.replayWorkflowExecution(history, EpisodeWorkflowImpl::class.java) }
    }
}
```

`episodeEnvironment { }` 는 `DataConverterSupport.clientOptions()` 로 만든 환경이다. 그래서 그 환경의 Worker 는 koshei 변환기를 쓴다.

대조군 시험이 실패한다면, 곧 기본 변환기로도 재생된다면, 이 시험을 지우지 않는다. 먼저 (a)의 이력에 narrator 의 `RawJson` 응답이 실렸는지 확인한다. 실렸는데도 재생된다면 `@Disabled("…이유…")` 로 두고 보고한다.

- [ ] **Step 2: 돌려 본다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeReplayTest"`
Expected: 2개 PASS

- [ ] **Step 3: 모듈 전체를 확인한다**

Run: `./gradlew :episode:test :episode-runtime:test :runtime:test --tests "koshei.runtime.RawJsonConverterTest" :app:test`

Expected: 모두 PASS. `:episode` 은 292개다. `:episode-runtime` 은 아래를 더해 85개다. 정확한 수를 보고한다.

| 출처 | 개수 |
|---|---|
| Task 2 까지 | 30 |
| StrictYaml | 13 |
| PolicyFileReader | 6 |
| Store | 6 |
| MockPicasso | 7 |
| Activities | 10 |
| Workers | 6 |
| e2e | 5 |
| 재생 | 2 |
| **합계** | **85** |

`--tests` 필터는 `:runtime:test` 에만 걸린다. 다른 모듈은 전체가 돈다.

- [ ] **Step 4: 커밋한다**

```bash
git add episode-runtime/src
git commit -m "test(episode-runtime): histories of four scenarios replay with koshei's converter; the default one cannot" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 11: 설계 문서에 구현된 모양을 적는다

**Files:**
- Modify: `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md`

- [ ] **Step 1: 고친다**

해당 절의 끝에 「구현(B3b)」 문단을 짧게 더한다. 설계 결정은 바꾸지 않는다.
- **§7.1**
  - `SymptomSignal.manifestJson`: 여는 증상의 manifest 를 쓴다.
  - 합류한 증상의 manifest 는 읽지 않는다. 이것은 알려진 한계다.
  - 이어 시작한 run 의 첫 증상 manifest 를 쓴다.
  - **B3c 메모:** 이전 run 의 결과를 `getResult` 로 기다리면 continue-as-new 사슬을 따라가 다음 에피소드 결과를 받는다. runId 를 고정하거나 `CONTINUED_AS_NEW` 를 끝으로 본다.
- **§8.2**
  - 경로 `KOSHEI_EPISODE_POLICY`. 기본은 `model/episode-policy/active.yaml`.
  - 엄격한 YAML(중복 키 · 앵커/별칭 · 둘째 문서 · 빈 파일 → UNREADABLE).
  - 결정 때마다 새로 읽는다.
- **§8.4 · §11**
  - `episode_dispatch` 열쇠 표와 먼저 쓴 쪽이 이김.
  - 사람 과업과 알림을 한 트랜잭션으로 쓴다.
  - 저장 전에 잃은 답 → Mock 의 소모 → UNKNOWN(OUTCOME). 끝에서 끝까지 시험이 R2 를 보인다.
  - `dispatch` 서명에 `instanceId` 가 들어간다.
- **§8.5**
  - `RECORD_LAG` 는 run 마다 한 번. 알림 실패에는 알리지 않는다.
  - 알림 채널은 `episode_notice` 이고 최소 한 번이다.
  - 같은 seq 의 다른 기록은 `RecordConflict`(재시도 없음).
- **§13**
  - 스키마 파일 `episode-runtime/src/main/resources/episode-schema.sql`.
  - `at` = 쓴 시각.
  - 권한 회수는 계획 D.
- **§4.1 · §4.2**: `EpisodeWorkers.register`, `KOSHEI_NARRATOR=mock|remote`, `KOSHEI_PICASSO=mock`.
- **§8.3 · §11:** `KOSHEI_PICASSO` 의 기본값은 `off` 다(에피소드 워커 없음). `mock` 은 이름으로 골라야 하고, 시작 때 경고를 남긴다.
  - Mock 은 프로세스 메모리다. 워커가 죽으면 소모 기록을 잊고, 그 뒤 재시도는 다시 승인된다.
  - 그래서 R2 시험은 같은 프로세스 안의 실패만 보인다.
- **B3c 메모(§8.5 끝):** `episode_notice` 의 알림 모양은 둘이다.
  - `noticeJson` 이 만든 것: `kind` · `phase` · `reason` · `detail`
  - 사람 과업: `kind: PERSON_TASK` · `idempotencyKey` · `intent`

  화면은 `kind` 로 가른다.
- **§16:** 표의 계획 B 줄 아래에 「B3b 끝(2026-10-03)」 주석을 단다. 남은 것도 함께 적는다: 실물 picasso 클라이언트는 §19 C 뒤, 그리고 B3c 제어면.

- [ ] **Step 2: 커밋한다**

```bash
git add docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md
git commit -m "docs(design): implemented shape of plan B3b - policy file, episode tables, idempotent dispatch, RECORD_LAG, wiring" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
