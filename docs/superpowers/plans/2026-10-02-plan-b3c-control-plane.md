# 계획 B3c — 에피소드 제어면(`/api/episodes…`) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영자가 HTTP 로 에피소드를 보고(목록 · 상세 · 운영자 카드 · 기록 · 알림), 사람의 결정(`decide` · `confirm` · `takeover` · `close`)을 워크플로 Update 로 넘기게 한다(설계 §4.1 · §4.2 · §7.2 · §7.3 · §8.5 · §9.5). 손으로 시험할 수 있게, 개발용 CLI 로 증상 하나를 넣는 길도 연다(§7.2: 「symptom · agentOff 는 감시자 또는 CLI 의 signal」).

**Architecture:**
- **제어면은 조회와 결정 중계뿐이다(§4.2, R14).**
  - 판단은 워크플로 안의 순수 코어가 한다.
  - 제어면은 정책을 고치지 않는다. 표도 만들지 않는다. 읽기 전용 인터페이스 `EpisodeReader` 만 받는다.
  - 증상을 넣지 않는다. 그것은 CLI 가 한다.
  - 거절은 코어가 값(`Reply`)으로 돌려주고, 제어면은 그 값을 그대로 전한다.
- **결정은 현재 run 에만, 그 run 이 살아 있을 때만 보낸다.**
  - 현재 run 의 id 와 상태는 Temporal 에 묻는다(describe). 인스턴스 id 에서 꺼내지 않는다(§7.1 B3c 메모 — reset run 은 둘이 다르다).
  - run 이 끝났으면 질의하지 않고 `EPISODE_ENDED` 로 답한다. 끝난 run 의 질의는 워커가 이력을 재생해야 해서, 워커가 없으면 매달린다.
  - run 이 살아 있으면 그 run 에 고정한 stub 으로 질의해, 운영자가 본 인스턴스인지 확인한다. 확인되면 같은 stub 으로 Update 를 보낸다.
  - 확인과 Update 사이에 continue-as-new 가 일어나면, 고정한 run 이 닫혀 있어 Update 가 실패한다(`EPISODE_ENDED`). 결정이 다음 에피소드에 들어가지 않는다.
  - `getResult` 는 쓰지 않는다(§7.1 메모 — 사슬을 따라간다).
- **목록과 기록은 DB 에서, 지금 상태는 Temporal 에서 읽는다.**
  - 목록은 `episode_event` 를 인스턴스별로 모아 만든다. Temporal visibility 는 쓰지 않는다(검색 속성이 없다).
  - 상세 = 기록 · 알림(DB) + 살아 있는 run 의 질의(Temporal). Temporal 에 닿지 못해도 상세는 기록만으로 답한다(`viewError`).
- **운영자 카드는 순수 함수다.** 지금 권고(narrator 의 답)는 **질의 결과(`EpisodeView.diagnosisJson`)** 에서 읽는다. 기록은 비동기라 늦을 수 있어서다(§8.5). 사실(들어온 증상)은 기록에서 읽는다. 카드 순서는 §19 D 가 정해질 때까지 §9.5 그대로이고, `unknowns` 가 맨 위다.
- **운영자 신원은 머리글 `X-Koshei-Operator` 하나다(§7.2).**
  - 본문에 운영자 id 가 오면 400 으로 거절한다. 모르는 칸은 모두 거절한다.
  - 코어는 기록에 `assurance: SELF_ASSERTED` 를 남긴다(§14 — 인증 없는 PoC 한계).
- **전제 확인은 카드가 보인 명제를 기록한다(§7.2).**
  - 제어면이 자기 카드에서 명제를 꺼내 `ConfirmRequest.proposition` 에 싣는다. 클라이언트가 보낸 글자는 받지 않는다.
  - 코어는 그것을 `CONFIRMATION` 기록에 남긴다.
  - 명제가 없으면 「전제 맞음」(holds=true)을 400 으로 거절한다(§8.3: 명제 없이 「전제 맞음」만 누르게 하지 않는다).

**Tech Stack:** Kotlin 2.2 / JDK 21, Spring Boot 3.3.4(`authoring-api`, `@WebMvcTest` + Mockito 5), Temporal Java SDK 1.25.1(`temporal-testing`), PostgreSQL 16(plain JDBC, Testcontainers 1.20.1 — `episode-runtime` 시험만), Jackson 2.17

**작업 위치:** worktree `C:\Users\Eisen\Desktop\Labs\[projects] koshchei\koshei-episode-core`, 브랜치 `feat/episode-core`(머리 `91de3be`).
- 지금 시험 수:

  | 모듈 | 시험 수 |
  |---|---|
  | `:episode` | 292 |
  | `:episode-runtime` | 142 |
  | `:app` | 15 |
  | `:authoring-api` | 99 |

- 원래 체크아웃(`koshei/`)은 건드리지 않는다.
- 파일은 Write/Edit 로만 쓴다. 예외: Task 6 · 8 의 시험 자원 사본은 `cp` 로 바이트 그대로 복사한다.
- heredoc 이나 스크립트로 소스를 치환하지 않는다.
- bare `git stash` 를 쓰지 않는다.
- 푸시하지 않는다.
- Docker 가 돌아야 한다(Testcontainers).

**커밋 규칙:** 모든 커밋은 `git commit -m "<제목>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`.

**범위 밖:**
- **계획 B3c-2:** `authoring-ui` 의 에피소드 화면(목록, 상세, 카드, 결정 버튼, 알림 흐름). 이 계획의 API 를 쓴다.
- **계획 C:** 감시자와 담는 쪽(증상과 근거의 실제 운반), 병합 규칙. 이 계획의 CLI 는 개발용이며 감시자를 대신하지 않는다.
- **계획 D:** 인증과 권한(지금은 루프백 바인드뿐), 정책 활성화 CLI, `agentOff` 방송.
- 알림의 바깥 채널(메일 · 메신저). 알림은 `episode_notice` 표와 로그뿐이다(§8.5).
- Temporal visibility(검색 속성, `listExecutions`).
- 목록의 커서와 색인. 목록은 감사 표 전체를 모은다. PoC 규모에서는 충분하다.

---

## 설계에서 코드로 옮길 때 정한 것

| 설계 | 이 계획의 코드 |
|---|---|
| §7.2 경로 `GET /api/episodes`, `GET /api/episodes/{id}`, `POST /api/episodes/{id}/decide\|confirm\|takeover\|close` | `{id}` 는 인스턴스 id(`<workflowId>/<run>`)를 그대로 두 경로 조각으로 쓴 것이다: `/api/episodes/{workflowId}/{run}`. 워크플로 id 에는 `/` 가 없어야 한다. 그래서 CLI 의 `--key` 는 `[A-Za-z0-9._:-]+` 만 받고, 감시자도 같은 규칙을 지킨다(계획 C, 문서에 적는다). `{run}` 은 인스턴스 id 의 둘째 부분일 뿐, runId 로 쓰지 않는다 |
| §7.1 메모: 현재 runId 를 인스턴스 id 에서 꺼내지 않는다 | `EpisodeGateway.current(workflowId)` 가 describe 로 현재 runId 와 상태를 받는다. run 이 살아 있을 때만 그 run 에 고정한 stub 으로 `view()` 를 질의한다 |
| §7.1 메모: `getResult` 는 사슬을 따라간다 | 제어면은 `getResult` 를 부르지 않는다 |
| §7.2 거절은 값, 검증기는 해독과 형식만 본다 | 처리기의 `Reply` 는 200 `{"reply": "<이름>"}` 로 답한다(`ACCEPTED` 포함). 나머지 갈래는 아래 표와 같다 |
| §7.2 운영자 신원 = `X-Koshei-Operator` | 모든 POST 에 필수다. 앞뒤 공백을 자른 뒤, 비었거나 128자를 넘으면 400. 본문 DTO 는 모르는 칸을 거절하므로, 본문의 `operatorId` 도 400 이다 |
| 본문의 참 · 거짓 칸 | `approve`, `holds` 는 필수다(`Boolean?`, null 이면 400). 빠뜨리거나 오타를 내도 거절이나 「안 됨」으로 읽히지 않게 한다 |
| §7.2 전제 확인은 카드가 보인 명제를 기록한다 | `ConfirmRequest.proposition`(새 칸) → `EpisodeEvent.ConfirmedPrecondition.proposition`(새 칸) → `CONFIRMATION` 기록의 `proposition`. 제어면이 자기 카드에서 채운다(본문의 후보 · 제안이 카드의 것과 같을 때만). UNKNOWN_PRECONDITION 의 그 후보에 명제가 없는데 holds=true 면 400. 그 밖의 경우는 명제 없이 넘기고 코어가 값으로 거절한다 |
| §7.3 질의 = 현재 상태 · 시도 이력 · 현재 권고 · 후보 목록 · 남은 기한 | `EpisodeView` 에 `candidateJson`(시도의 후보), `candidatesJson`(그 진단 요청의 후보 목록), `diagnosisJson`(그 시도의 narrator 답 원문)을 더한다. 상세 응답 = `view` + `viewError` + `card` + `events`(seq 순, `at` 포함) + `notices`(id 순, `at` 포함) |
| §9.5 운영자 카드 | `operatorCard(view, events)` 순수 함수(`episode-runtime/OperatorCards.kt`). 아래 Task 4 의 규칙대로. `cause` 는 싣지 않는다. WITHHELD_HUMAN_FIRST 로 사람에게 간 에피소드는 사실과 ESCALATE 과업만 보인다(§9.5, §19 D) |
| §8.5 B3c 메모: 알림 모양은 둘, `kind` 로 가른다 | 알림은 JSON 그대로 싣고 모양을 바꾸지 않는다. 전체 흐름은 `GET /api/episodes/notices?after=<id>&limit=<n>`(id 오름차순). `bigserial` id 는 커밋 순서가 아니므로, 화면은 겹쳐 다시 읽고(`after = 마지막 id - 50`) id 로 중복을 지운다. 문서에 적는다 |
| 목록 | `instances(limit)`: 인스턴스별 최근 기록 순. `lastPhase` = 마지막 `TRANSITION` 의 `to`(기록이 늦으면 늦게 보인다). `notices` = 알림 수. `recordLag` = RECORD_LAG 알림이 있었는지(있으면 `lastPhase` 를 믿지 말라는 뜻) |
| 표가 없을 때 | 제어면은 표를 만들지 않는다. 표가 없으면(SQLState `42P01`) 503 |
| Temporal 연결 | 기존 `EngineConfig` 처럼 `lazy` 로 감싼 `WorkflowClient`(로컬 7233, `DataConverterSupport.clientOptions()`). 컨텍스트는 Temporal 없이 뜬다(`ContextLoadsTest`) |
| Update 가 끝없이 기다림 | Update 는 시간 상한(기본 30초) 안에서 기다린다. 넘으면 504 |
| §7.2 symptom · agentOff = 감시자 또는 CLI | `:app` CLI 에 `episode open` 을 더한다. picasso 내보내기 한 벌에서 줄 하나를 골라 signalWithStart 한다. `episode agent-off <workflowId>` 도 더한다. 개발용이다 |

응답 갈래:

| 경우 | 응답 |
|---|---|
| 처리기의 값 | 200 `{"reply": …}` |
| 검증기 거절, 본문 · 머리글 오류, 명제 없는 「전제 맞음」 | 400 `{"error": …}` |
| 워크플로 id 를 모른다(기록도 없다) | 404 |
| 현재 run 이 다른 인스턴스다 | 409 `{"error":"EPISODE_MOVED","currentInstanceId":…}` |
| run 이 끝났다 | 409 `{"error":"EPISODE_ENDED"}` |
| Temporal · DB 에 닿지 못함, 표 없음 | 503 |
| Update 시간 초과 | 504 |

---

## 파일 구조

| 파일 | 할 일 | 책임 |
|---|---|---|
| `episode/src/main/kotlin/koshei/episode/EpisodeEvents.kt` | 수정 | `ConfirmedPrecondition.proposition` |
| `episode/src/main/kotlin/koshei/episode/Revalidating.kt` | 수정 | `CONFIRMATION` 기록에 명제 |
| `episode/src/test/kotlin/koshei/episode/…`(전제 확인 시험이 있는 파일) | 수정 | Task 2 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/Wire.kt` | 수정 | `EpisodeView` 세 칸, `ConfirmRequest.proposition` |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/Conversions.kt` | 수정 | `viewOf`, `ConfirmRequest.toEvent` |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeStore.kt` | 수정 | `EpisodeReader`, `StoredEvent.at`, `instances`, `noticeRows`, `noticesAfter` |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/OperatorCards.kt` | 생성 | §9.5 카드(순수) |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/{EpisodeWorkflowTest,ConversionsTest,EpisodeStoreTest,OperatorCardsTest}.kt` | 수정 / 생성 | Task 1–4 |
| `authoring-api/build.gradle.kts` | 수정 | `:episode-runtime`, `temporal-testing`, `koshei.repoRoot` |
| `authoring-api/src/main/kotlin/koshei/authoring/EpisodeGateway.kt` | 생성 | Temporal: 현재 run, 질의, Update, 오류 형 |
| `authoring-api/src/main/kotlin/koshei/authoring/EpisodeController.kt` | 생성 | `/api/episodes…` |
| `authoring-api/src/main/kotlin/koshei/authoring/EpisodeConfig.kt` | 생성 | 게이트웨이와 읽기 저장소 빈 |
| `authoring-api/src/test/kotlin/koshei/authoring/{EpisodeGatewayTest,EpisodeControllerTest}.kt` | 생성 | Task 5–6 |
| `authoring-api/src/test/resources/picasso/run-1/{manifest.json,remedy-searches.jsonl}` | 복사 | 시험 자원 |
| `app/build.gradle.kts` | 수정 | `:episode-runtime`(이미 있음), `jackson-kotlin` |
| `app/src/main/kotlin/koshei/app/{EpisodeCli,Cli}.kt` | 생성 / 수정 | `episode open` · `agent-off` |
| `app/src/test/kotlin/koshei/app/EpisodeCliTest.kt` | 생성 | Task 7 |
| `app/src/test/resources/picasso/run-1/*` | 복사 | 시험 자원 |
| `.gitattributes` | 수정 | 새 시험 자원을 LF 로 |
| `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md` | 수정 | Task 8 |

---

## Chunk 1: 코어와 `episode-runtime` 의 읽기 쪽

### Task 1: 질의에 후보 · 후보 목록 · 지금 권고를 싣는다

**Files:**
- Modify: `episode-runtime/src/main/kotlin/koshei/episode/runtime/Wire.kt`(`EpisodeView`)
- Modify: `episode-runtime/src/main/kotlin/koshei/episode/runtime/Conversions.kt:125-147`(`viewOf`)
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWorkflowTest.kt`

카드는 제안을 「후보의 kind 와 ref 로」 그린다(§9.5). 질의는 「현재 권고와 후보 목록」을 보인다(§7.3). 지금 뷰에는 `candidateId` 밖에 없다. narrator 의 답을 기록에서만 읽으면 기록이 늦을 때 카드가 비므로, 질의 결과에 싣는다.

- [ ] **Step 1: 실패하는 시험을 쓴다**

`EpisodeWorkflowTest.kt` 에 더한다(기존 보조 함수와 클래스의 `json` 을 쓴다).

```kotlin
    @Test fun `the view carries the candidate, the candidates offered and the diagnosis that chose it`() {
        acts.policyJson = TestPolicies.table(autoApprove = false)
        val ep = open()
        val waiting = ep.until { it.phase == "AWAITING_APPROVAL" }
        val candidate = json.readTree(waiting.candidateJson!!)
        assertEquals(waiting.candidateId, candidate["candidateId"].textValue())
        assertEquals("APPROVE_REMEDY", candidate["kind"].textValue())
        assertEquals(json.readTree(Picasso.search("search-1"))["searchId"].textValue(), candidate["ref"]["searchId"].textValue())
        val candidates = json.readTree(waiting.candidatesJson!!)
        assertTrue(candidates.any { it["candidateId"].textValue() == waiting.candidateId })
        assertTrue(candidates.any { it["kind"].textValue() == "ESCALATE" })
        val diagnosis = json.readTree(waiting.diagnosisJson!!)
        assertEquals("RECOMMENDED", diagnosis["outcome"].textValue())
        assertEquals(waiting.candidateId, diagnosis["candidateId"].textValue())
    }

    @Test fun `before a diagnosis the view has no candidate and no diagnosis`() {
        val ep = open()
        val v = ep.until { it.phase == "CORRELATING" }
        assertNull(v.candidateJson)
        assertNull(v.diagnosisJson)
    }
```

후보 목록에는 ESCALATE 가 늘 들어 있다(`Candidates.kt` 의 `projectCandidates` 가 맨 앞에 넣는다).

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeWorkflowTest"`
Expected: 컴파일 오류(`candidateJson` 없음)

- [ ] **Step 3: 구현한다**

`Wire.kt` 의 `EpisodeView` 끝에 칸 셋을 더한다. 기본값이 있어서 옛 페이로드도 읽힌다.

```kotlin
    /** The attempt's candidate as `Candidate.toJson()`: the card draws the proposal from kind and ref (§9.5). */
    val candidateJson: String? = null,
    /** The candidates the attempt's diagnosis request offered, each as `Candidate.toJson()` (§7.3). */
    val candidatesJson: String? = null,
    /** The attempt's narrator answer as received, when there is one (§7.3 current recommendation). Records may lag; this does not. */
    val diagnosisJson: String? = null,
```

`viewOf` 에 다음을 더한다.

```kotlin
        candidateJson = attempt?.candidate?.toJson()?.toString(),
        candidatesJson = attempt?.request?.candidates?.let { cs -> strictJson.createArrayNode().apply { cs.forEach { add(it.toJson()) } }.toString() },
        diagnosisJson = when (val v = attempt?.verdict) {
            is DiagnosisVerdict.Proposed -> v.response.raw.toString()
            // An unreadable answer is kept too (raw is NullNode for non-JSON), so the card can say it could not read it.
            is DiagnosisVerdict.Escalate -> (v.response?.raw ?: v.raw)?.toString()
            null -> null
        },
```

`DiagnosisVerdict`(`Proposed`, `Escalate`)의 `response` 와 `Escalate.raw`, `DiagnosisResponse.raw`, `Attempt.request.candidates` 는 모두 공개다(리뷰에서 확인). `Conversions.kt` 에 `import koshei.episode.DiagnosisVerdict` 를 더한다. 질의 결과에 칸을 더하는 것은 명령 순서를 바꾸지 않으므로 재생에 안전하다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test`
Expected: 144개 PASS

- [ ] **Step 5: 커밋한다**

```bash
git add episode-runtime/src episode/src
git commit -m "feat(episode-runtime): the view carries the attempt's candidate, the candidates offered and the live diagnosis" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: 전제 확인이 확인한 명제를 기록한다

**Files:**
- Modify: `episode/src/main/kotlin/koshei/episode/EpisodeEvents.kt:35`
- Modify: `episode/src/main/kotlin/koshei/episode/Revalidating.kt:29-37`
- Modify: `episode-runtime/src/main/kotlin/koshei/episode/runtime/Wire.kt`(`ConfirmRequest`)
- Modify: `episode-runtime/src/main/kotlin/koshei/episode/runtime/Conversions.kt:82`
- Test: `episode/src/test/kotlin/koshei/episode/`(먼저 `grep -rln "ConfirmedPrecondition" episode/src/test` 로 전제 확인 시험이 있는 파일을 찾는다)
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/ConversionsTest.kt`

설계 §7.2: 「카드가 보인 명제를 확인한 것으로 기록한다」. 지금 `CONFIRMATION` 기록에는 후보 id 만 있다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - **코어:** `episode/src/test/kotlin/koshei/episode/RevalidatingTest.kt:94-102` 의 기존 모양을 따른다(`u.on(EpisodeEvent.ConfirmedPrecondition(REMEDY, "$INSTANCE#1", true, OPERATOR), t)` 와 `holds.records(RecordKind.CONFIRMATION).single().entry.payload`). 같은 보조 함수로 UNKNOWN_PRECONDITION 에 이른다. `ConfirmedPrecondition(candidateId, proposalId, holds = true, by, proposition = "(r, j)에 searchId s 뒤로 더 새 탐색 줄이 없다")` 를 넣고, 나온 `CONFIRMATION` 기록의 `payload["proposition"]` 이 그 글자인지 단언한다. 명제가 null 인 기존 호출은 `"proposition": null` 을 남기는지도 단언한다.
  - **`ConversionsTest`:** `ConfirmRequest("PRECONDITION", "c", "p", true, operatorId = "op", proposition = "명제").toEvent()` 가 `ConfirmedPrecondition` 이고, 그 `proposition` 이 `"명제"` 인지 단언한다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode:test :episode-runtime:test --tests "koshei.episode.runtime.ConversionsTest"`
Expected: 컴파일 오류

- [ ] **Step 3: 구현한다**
  - `EpisodeEvents.kt`:

    ```kotlin
    data class ConfirmedPrecondition(val candidateId: String, val proposalId: String, val holds: Boolean, val by: Approver, val proposition: String? = null) : EpisodeEvent
    ```

    KDoc 한 줄: 「the proposition the person was shown (§7.2, §8.3), filled by the control plane from its own card — never by the client」.
  - `Revalidating.kt` 의 `CONFIRMATION` 기록에 `put("proposition", event.proposition)` 을 더한다.
  - `Wire.kt` 의 `ConfirmRequest` 에 `val proposition: String? = null` 을 더한다(끝 칸).
  - `Conversions.kt:82` 를 `ConfirmedPrecondition(candidateId, proposalId, holds, Approver(operatorId), proposition)` 로 바꾼다.

  `EpisodeFuzzer` 가 `ConfirmedPrecondition` 을 만들고 있으면 기본값으로 그대로 컴파일된다. 확인한다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode:test :episode-runtime:test`
Expected: `:episode` 293개(292 + 1), `:episode-runtime` 145개(144 + 1) PASS

- [ ] **Step 5: 커밋한다**

```bash
git add episode/src episode-runtime/src
git commit -m "feat(episode): a precondition confirmation records the proposition the person was shown (design §7.2)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 3: 읽기 전용 저장소 — 목록, 기록의 시각, 알림 흐름

**Files:**
- Modify: `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeStore.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeStoreTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`EpisodeStoreTest.kt` 에 더한다(`assertNotNull` import 만 더한다 — `assertNull` 은 이미 있다).

```kotlin
    @Test fun `events carry the time they were written`() {
        store.append("ep:a/r1", 1, "OPENED", "{}")
        assertNotNull(store.events("ep:a/r1").single().at)
    }

    @Test fun `instances are listed newest activity first, with the last transition, the notice count and record lag`() {
        store.append("ep:a/r1", 1, "OPENED", "{}")
        store.append("ep:a/r1", 2, "TRANSITION", """{"from":"CORRELATING","to":"DIAGNOSING"}""")
        store.append("ep:a/r1", 3, "TRANSITION", """{"from":"DIAGNOSING","to":"AWAITING_APPROVAL"}""")
        store.notice("ep:a/r1", """{"kind":"APPROVAL_NEEDED"}""")
        store.notice("ep:a/r1", """{"kind":"RECORD_LAG","phase":"AWAITING_APPROVAL","reason":null,"detail":"x"}""")
        Thread.sleep(20)   // each autocommit statement takes its own now(); 20 ms keeps the two episodes apart
        store.append("ep:b/r1", 1, "OPENED", "{}")
        val list = store.instances(10)
        assertEquals(listOf("ep:b/r1", "ep:a/r1"), list.map { it.instanceId })
        val a = list.single { it.instanceId == "ep:a/r1" }
        assertEquals("ep:a", a.workflowId)
        assertEquals("AWAITING_APPROVAL", a.lastPhase)
        assertEquals(2, a.notices)
        assertTrue(a.recordLag)
        val b = list.single { it.instanceId == "ep:b/r1" }
        assertNull(b.lastPhase)
        assertEquals(false, b.recordLag)
        assertEquals(1, store.instances(1).size)
    }

    @Test fun `notice rows carry their id and time, and the feed continues after an id`() {
        store.notice("ep:a/r1", """{"kind":"APPROVAL_NEEDED"}""")
        store.notice("ep:b/r1", """{"kind":"ESCALATED"}""")
        store.notice("ep:a/r1", """{"kind":"ESCALATED"}""")
        val a = store.noticeRows("ep:a/r1")
        assertEquals(2, a.size)
        assertTrue(a[0].id < a[1].id)
        assertTrue(a.all { it.instanceId == "ep:a/r1" })
        val all = store.noticesAfter(0, 100)
        assertEquals(3, all.size)
        assertEquals(all.drop(1), store.noticesAfter(all[0].id, 100))
        assertEquals(1, store.noticesAfter(0, 1).size)
    }

    @Test fun `the store is an episode reader`() {
        val reader: EpisodeReader = store
        assertTrue(reader.instances(5).isEmpty())
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeStoreTest"`
Expected: 컴파일 오류

- [ ] **Step 3: 구현한다**

`EpisodeStore.kt`:

```kotlin
/** One stored record of an episode. [payloadJson] is the stored JSON value (jsonb's own spacing). [at] is when it was written. */
data class StoredEvent(val seq: Long, val kind: String, val payloadJson: String, val at: Instant? = null)

/** One episode instance as the records show it (design §13): the control plane's list. */
data class StoredInstance(
    val instanceId: String,
    val workflowId: String,
    val openedAt: Instant,
    val lastAt: Instant,
    /** The `to` of the last TRANSITION record, or null before the first. Lags the live phase by any record lag. */
    val lastPhase: String?,
    val notices: Int,
    /** A RECORD_LAG notice was raised: some record never landed, so [lastPhase] may be stale for good. */
    val recordLag: Boolean,
)

/** One row of the operator channel (design §8.5). [noticeJson] is the notice as written, either shape. */
data class StoredNotice(val id: Long, val instanceId: String, val noticeJson: String, val at: Instant)

/** What the control plane may do with the episode tables: read (design §4.2, R14). */
interface EpisodeReader {
    fun events(instanceId: String): List<StoredEvent>
    fun instances(limit: Int): List<StoredInstance>
    fun noticeRows(instanceId: String): List<StoredNotice>
    fun noticesAfter(afterId: Long, limit: Int): List<StoredNotice>
}
```

`open class EpisodeStore(…) : EpisodeReader` 로 바꾸고, `events` 에 `override` 를 붙인다. SELECT 에 `at` 을 더해 `rs.getTimestamp(4).toInstant()` 로 채운다. 그리고 다음을 더한다.

```kotlin
    /** Instances by their latest record, newest first (design §13: the audit table is the source of the list). */
    override fun instances(limit: Int): List<StoredInstance> = connect().use { c ->
        c.prepareStatement(
            """
            SELECT e.episode_instance_id, min(e.at), max(e.at),
                   (SELECT t.payload->>'to' FROM episode_event t
                     WHERE t.episode_instance_id = e.episode_instance_id AND t.kind = 'TRANSITION'
                     ORDER BY t.seq DESC LIMIT 1),
                   (SELECT count(*) FROM episode_notice n WHERE n.episode_instance_id = e.episode_instance_id),
                   EXISTS (SELECT 1 FROM episode_notice n
                            WHERE n.episode_instance_id = e.episode_instance_id AND n.notice->>'kind' = 'RECORD_LAG')
              FROM episode_event e
             GROUP BY e.episode_instance_id
             ORDER BY max(e.at) DESC, e.episode_instance_id
             LIMIT ?
            """.trimIndent(),
        ).use { s ->
            s.setInt(1, limit)
            s.executeQuery().use { rs ->
                generateSequence {
                    if (!rs.next()) null else {
                        val id = rs.getString(1)
                        StoredInstance(
                            id, id.substringBeforeLast('/'), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant(),
                            rs.getString(4), rs.getInt(5), rs.getBoolean(6),
                        )
                    }
                }.toList()
            }
        }
    }

    override fun noticeRows(instanceId: String): List<StoredNotice> = connect().use { c ->
        c.prepareStatement("SELECT id, episode_instance_id, notice::text, at FROM episode_notice WHERE episode_instance_id = ? ORDER BY id").use { s ->
            s.setString(1, instanceId)
            s.executeQuery().use(::noticeList)
        }
    }

    /**
     * The whole channel after [afterId], oldest first. bigserial ids are not commit-ordered: a reader re-reads an
     * overlap (after = last id − 50) and drops ids it has seen (design §8.5).
     */
    override fun noticesAfter(afterId: Long, limit: Int): List<StoredNotice> = connect().use { c ->
        c.prepareStatement("SELECT id, episode_instance_id, notice::text, at FROM episode_notice WHERE id > ? ORDER BY id LIMIT ?").use { s ->
            s.setLong(1, afterId); s.setInt(2, limit)
            s.executeQuery().use(::noticeList)
        }
    }

    private fun noticeList(rs: ResultSet): List<StoredNotice> =
        generateSequence { if (rs.next()) StoredNotice(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()) else null }.toList()
```

인스턴스 id 에서 **워크플로 id** 를 꺼내는 것은 괜찮다. 꺼내면 안 되는 것은 현재 runId 다(§7.1). 기존 `notices(instanceId)` 는 B3b 시험이 쓰므로 그대로 둔다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeStoreTest"`
Expected: 기존 + 4개 PASS

- [ ] **Step 5: 커밋한다**

```bash
git add episode-runtime/src
git commit -m "feat(episode-runtime): a read-only episode reader - instances, timed records, the notice feed by id" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 4: 운영자 카드(§9.5)

**Files:**
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/OperatorCards.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/OperatorCardsTest.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWorkflowTest.kt`

먼저 읽는다.
- 설계 §9.5(카드 칸), §8.3 「사람이 확인할 명제」 줄, §19 D.
- `DiagnosisResponses.kt`:
  - `parseDiagnosisResponse(JsonNode)` 의 결과는 `ResponseParse.Valid` / `ResponseParse.Invalid` 다. Valid 의 응답 칸 이름을 확인한다.
  - `DiagnosisResponse` 의 `rationale`, `card`(원소는 `:episode` 의 `CardLine`), `citations`, `uncitedSentences`, `unverifiedClaims` 를 확인한다.
  - 계약의 `sawCandidatesVersion` 은 `sha256:` + 소문자 16진수 64자여야 한다. 아니면 Invalid 다.
- `Candidates.kt` 의 kind 별 `ref` 열쇠:

  | kind | ref 열쇠 |
  |---|---|
  | `APPROVE_REMEDY` | robotId · jobOrderId · searchId |
  | `CHOOSE_SOURCE` | jobOrderId · material · missingSource · alternative · searchId |
  | `OPERATOR_DECISION` | executionId · unitId · decision |

- 기록 payload:
  - `OPENED` 와 `SYMPTOM_JOINED` 의 `line`(그 줄 원문 트리), `eventId`, `kind`.
  - `DIAGNOSIS_REQUESTED` 의 `attempt`.

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package koshei.episode.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperatorCardsTest {
    private val json = ObjectMapper()
    private val version = "sha256:" + "a".repeat(64)

    private val remedy = """{"candidateId":"APPROVE_REMEDY:hum-02:PATROL-1:pick_place","kind":"APPROVE_REMEDY",
        "ref":{"robotId":"hum-02","jobOrderId":"PATROL-1","searchId":"search-7"},"sawSkillTypes":["pick_place"]}"""
    private val source = """{"candidateId":"CHOOSE_SOURCE:PATROL-1:B-07","kind":"CHOOSE_SOURCE",
        "ref":{"jobOrderId":"PATROL-1","material":"M-1","missingSource":"B-03","alternative":"B-07","searchId":"search-7"}}"""
    private val decision = """{"candidateId":"OPERATOR_DECISION:exec-1:u-2:CONFIRM_DONE","kind":"OPERATOR_DECISION",
        "ref":{"executionId":"exec-1","unitId":"u-2","decision":"CONFIRM_DONE"}}"""

    private val response = """
        {"contractVersion":"0.6","episodeId":"ep:x/r1","attempt":1,"outcome":"RECOMMENDED","candidateId":"APPROVE_REMEDY:hum-02:PATROL-1:pick_place",
         "picked":null,"sawCandidatesVersion":"$version","rationale":"다시 한다 [출처: SOP-01, §5.1].",
         "card":[{"label":"절차","text":"먼저 본다."},{"label":"금지","text":"반복하지 않는다."}],
         "cause":"## 원인 후보 — 기록만 한다",
         "citations":[{"title":"SOP-01","section":"§5.1","verified":true,"provenance_tier":"authored","provenance_mark":""}],
         "unverifiedClaims":[{"kind":"CITATION","text":"SOP-99 §2","foundIn":null},{"kind":"CITATION","text":null,"foundIn":null},
                             {"kind":"NUMBER","text":"30초","foundIn":["SOP-01"]},
                             {"kind":"NUMBER","text":null,"foundIn":null},{"kind":"NUMBER","text":null,"foundIn":null}],
         "uncitedSentences":["근거 없는 문장."],
         "versions":{"modelId":"m","promptVersion":"p","corpusVersion":"c","searchFingerprint":"s","narratorCommit":"n"},"elapsedSeconds":1.0}
    """.trimIndent()

    private fun view(phase: String, candidate: String? = remedy, diagnosis: String? = response, unknowns: String = "[]") = EpisodeView(
        instanceId = "ep:x/r1", phase = phase, attempt = 1, proposalId = "ep:x/r1#1", candidateId = "c",
        candidatesVersion = version, stateDeadlineMillis = 1_000L, unknownsJson = unknowns,
        candidateJson = candidate, diagnosisJson = diagnosis,
    )

    private fun opened(seq: Long, eventId: String) = StoredEvent(seq, "OPENED", """{"eventId":"$eventId","kind":"SEARCH","line":{"searchId":"$eventId"}}""")
    private fun requested(seq: Long, attempt: Int) = StoredEvent(seq, "DIAGNOSIS_REQUESTED", """{"attempt":$attempt,"candidatesVersion":"$version"}""")
    private fun joined(seq: Long, eventId: String) = StoredEvent(seq, "SYMPTOM_JOINED", """{"eventId":"$eventId","kind":"SEARCH","line":{"searchId":"$eventId"}}""")

    @Test fun `a proposal awaiting approval shows what the person decides on, in §9-5 order`() {
        val card = operatorCard(view("AWAITING_APPROVAL"), listOf(opened(1, "s-1")))
        assertEquals("APPROVE_REMEDY", card.proposal!!.kind)
        assertEquals("hum-02", card.proposal!!.ref["robotId"])
        assertEquals("다시 한다 [출처: SOP-01, §5.1].", card.rationale)
        assertFalse(card.rationaleMissing)
        assertEquals(listOf("절차" to "먼저 본다.", "금지" to "반복하지 않는다."), card.guidance.map { it.label to it.text })
        assertEquals("SOP-01", card.citations.single()["title"].textValue())
        assertEquals(listOf("근거 없는 문장."), card.uncitedSentences)
        assertEquals(listOf("SOP-99 §2"), card.unverifiedCitations.map { it.text })
        assertEquals(1, card.unlocatedCitations)
        assertEquals(listOf("30초"), card.unverifiedNumbers.map { it.text })
        assertEquals(listOf("SOP-01"), card.unverifiedNumbers.single().foundIn)
        assertEquals(2, card.unlocatedNumbers)
        assertEquals(CardDecision("decide", "ep:x/r1#1", version, 1_000L), card.decision)
        assertNull(card.precondition)
        assertEquals(listOf("s-1"), card.symptoms.map { it["searchId"].textValue() })
        assertEquals("SELF_ASSERTED", card.identityAssurance)
        assertFalse("원인" in json.writeValueAsString(card), "cause is recorded, never shown (§9.5)")
    }

    @Test fun `unknowns come first and a missing rationale is said, not hidden`() {
        val noReason = response.replace("\"rationale\":\"다시 한다 [출처: SOP-01, §5.1].\"", "\"rationale\":null")
        val unknowns = """[{"subject":{"robotId":"hum-02"},"what":"OBSERVATION_ABSENT","since":null,"source":"x"}]"""
        val card = operatorCard(view("AWAITING_APPROVAL", diagnosis = noReason, unknowns = unknowns), emptyList())
        assertEquals("OBSERVATION_ABSENT", card.unknowns.single()["what"].textValue())
        assertNull(card.rationale)
        assertTrue(card.rationaleMissing)
    }

    @Test fun `an unknown remedy precondition shows the proposition and the symptoms that joined after the snapshot`() {
        val events = listOf(opened(1, "s-1"), joined(2, "s-2"), requested(3, 1), joined(7, "s-3"))
        val card = operatorCard(view("UNKNOWN_PRECONDITION"), events)
        assertEquals("(hum-02, PATROL-1)에 searchId search-7 뒤로 더 새 탐색 줄이 없다", card.precondition)
        assertEquals(listOf("s-3"), card.joinedAfterSnapshot.map { it["searchId"].textValue() })
        assertEquals(CardDecision("confirm", "ep:x/r1#1", version, 1_000L), card.decision)
    }

    @Test fun `the other kinds' propositions are filled from their refs`() {
        assertEquals(
            "주문 PATROL-1 의 자재 M-1 이 아직 결품이고 대체 위치 B-07 가 아직 쓸 수 있다",
            operatorCard(view("UNKNOWN_PRECONDITION", candidate = source), emptyList()).precondition,
        )
        assertEquals(
            "실행 exec-1 의 단위 u-2 가 아직 운영자 보류이고 판단이 나지 않았다",
            operatorCard(view("UNKNOWN_PRECONDITION", candidate = decision), emptyList()).precondition,
        )
    }

    @Test fun `a proposition is never invented - a missing ref value leaves none, and no confirm decision`() {
        val unnamed = remedy.replace("\"searchId\":\"search-7\"", "\"searchId\":null")
        val card = operatorCard(view("UNKNOWN_PRECONDITION", candidate = unnamed), emptyList())
        assertNull(card.precondition)
        assertNull(card.decision)
    }

    @Test fun `outside approval and precondition there is no decision to make`() {
        for (phase in listOf("DIAGNOSING", "DISPATCHED", "AWAITING_EVIDENCE", "UNKNOWN_OUTCOME", "ESCALATED", "CLOSED")) {
            assertNull(operatorCard(view(phase), emptyList()).decision, phase)
        }
    }

    @Test fun `with no candidate and no diagnosis the card holds the facts only`() {
        val card = operatorCard(view("ESCALATED", candidate = null, diagnosis = null).copy(attempt = null, proposalId = null), listOf(opened(1, "s-1")))
        assertNull(card.proposal)
        assertTrue(card.guidance.isEmpty())
        assertFalse(card.rationaleMissing)
        assertNull(card.decision)
        assertEquals(listOf("s-1"), card.symptoms.map { it["searchId"].textValue() })
    }

    @Test fun `an episode withheld for a person shows the facts and no earlier diagnosis`() {
        val v = view("ESCALATED").copy(escalationReason = "WITHHELD_HUMAN_FIRST")
        val card = operatorCard(v, listOf(opened(1, "s-1"), joined(2, "s-2")))
        assertNull(card.rationale)
        assertTrue(card.guidance.isEmpty())
        assertTrue(card.citations.isEmpty())
        assertFalse(card.rationaleMissing)
        assertEquals(2, card.symptoms.size)
    }

    @Test fun `an unreadable diagnosis leaves the narrator fields empty instead of failing`() {
        val card = operatorCard(view("AWAITING_APPROVAL", diagnosis = """{"contractVersion":"0.6"}"""), emptyList())
        assertTrue(card.guidance.isEmpty())
        assertTrue(card.responseUnreadable)
        assertFalse(card.rationaleMissing)
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.OperatorCardsTest"`
Expected: 컴파일 오류

- [ ] **Step 3: 구현한다**

```kotlin
package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode
import koshei.episode.ClaimKind
import koshei.episode.ResponseParse
import koshei.episode.parseDiagnosisResponse

/** The proposal as the candidate names it (§9.5: from kind and ref, never by taking the id apart). */
data class CardProposal(val candidateId: String, val kind: String, val ref: Map<String, String?>)

/** One guidance line of the narrator's card, in its order (named apart from `:episode`'s CardLine). */
data class GuidanceLine(val label: String, val text: String)

data class CardClaim(val text: String, val foundIn: List<String>?)

/**
 * What a decision must carry back (§9.5 결정 칸): which Update ("decide" in AWAITING_APPROVAL, "confirm" of the
 * precondition in UNKNOWN_PRECONDITION), the proposal it answers, the candidates it saw, and until when. An OUTCOME
 * confirmation in AWAITING_EVIDENCE is sent from the view (proposalId, candidateId) and has no decision here.
 */
data class CardDecision(val update: String, val proposalId: String, val candidatesVersion: String, val deadlineMillis: Long?)

/**
 * The operator card (design §9.5). The narrator fields come from the live view (records may lag, §8.5); the facts —
 * the symptoms that arrived — from the records. Order is §9.5's until §19 D is decided: unknowns first. The
 * narrator's `cause` is recorded only and never reaches the card.
 */
data class OperatorCard(
    val unknowns: List<JsonNode>,
    val symptoms: List<JsonNode>,
    val proposal: CardProposal?,
    val rationale: String?,
    /** The diagnosis gave no rationale: the screen says "이유 없음" rather than leaving a gap (§9.5). */
    val rationaleMissing: Boolean,
    val guidance: List<GuidanceLine>,
    /** Documents that were in the evidence bundle — not facts (R13). */
    val citations: List<JsonNode>,
    val uncitedSentences: List<String>,
    val unverifiedCitations: List<CardClaim>,
    /** CITATION claims with no text: "확인 못 한 인용 n개(본문 없음)". */
    val unlocatedCitations: Int,
    val unverifiedNumbers: List<CardClaim>,
    /** NUMBER claims with no text: "확인 못 한 숫자 n개(위치 모름)" (§9.5). */
    val unlocatedNumbers: Int,
    /** UNKNOWN_PRECONDITION only: the proposition the person confirms, verbatim (§8.3); null rather than invented. */
    val precondition: String?,
    /** APPROVE_REMEDY precondition: the symptoms that joined after the attempt's snapshot (§8.3). */
    val joinedAfterSnapshot: List<JsonNode>,
    val decision: CardDecision?,
    val identityAssurance: String = "SELF_ASSERTED",
    /** The diagnosis could not be read as contract 0.6: the narrator fields are empty, the facts remain. */
    val responseUnreadable: Boolean = false,
)

fun operatorCard(view: EpisodeView, events: List<StoredEvent>): OperatorCard { … }
```

구현 규칙:
- `unknowns`: `view.unknownsJson` 의 배열 원소들.
- `symptoms`: `OPENED` 와 `SYMPTOM_JOINED` 기록의 `line`. seq 순서로 둔다.
- `proposal`: `view.candidateJson` 이 있으면 그 `candidateId`, `kind`, `ref` 다(값은 문자열 또는 null). `ref` 가 null 이면 빈 맵.
- narrator 칸: `view.escalationReason == "WITHHELD_HUMAN_FIRST"` 이면 모두 비운다(§9.5, §19 D). 이때 `rationaleMissing = false`, `responseUnreadable = false`. 그렇지 않을 때는 아래와 같다.
  - `view.diagnosisJson` 이 null 이면 비고, `rationaleMissing = false`.
  - 있으면 `parseDiagnosisResponse(strictJson.readTree(it))`. `Invalid` 이거나 읽을 수 없으면 비고, `responseUnreadable = true`, `rationaleMissing = false`.
  - `Valid` 이면 다음과 같이 채운다.
    - `rationale` 은 그대로 둔다. `rationaleMissing = rationale == null`.
    - `guidance` 는 `card` 를 순서 그대로 `GuidanceLine(label, text)` 로 옮긴다. `:episode` 의 `CardLine` 칸 이름을 확인한다.
    - `citations` 는 그대로 둔다.
    - `unverifiedClaims` 를 CITATION 과 NUMBER 로 가른다. 각 kind 에서 `text == null` 인 것은 글자를 지어내지 않고 세기만 한다(`unlocatedCitations` / `unlocatedNumbers`, 따로 센다).
- `precondition`: `view.phase == "UNKNOWN_PRECONDITION"` 이고 `proposal` 이 있을 때, kind 별 명제에 ref 값을 채운 글자다(§8.3). 명제에 쓰는 ref 값이 하나라도 null 이거나 비어 있으면 명제를 지어내지 않고 null 로 둔다(§8.3: 명제 없이 「전제 맞음」만 누르게 하지 않는다).
  - `APPROVE_REMEDY`: `"(${robotId}, ${jobOrderId})에 searchId ${searchId} 뒤로 더 새 탐색 줄이 없다"`
  - `CHOOSE_SOURCE`: `"주문 ${jobOrderId} 의 자재 ${material} 이 아직 결품이고 대체 위치 ${alternative} 가 아직 쓸 수 있다"`
  - `OPERATOR_DECISION`: `"실행 ${executionId} 의 단위 ${unitId} 가 아직 운영자 보류이고 판단이 나지 않았다"`
  - 그 밖의 kind: null.
- `joinedAfterSnapshot`: `precondition` 이 있고 kind 가 `APPROVE_REMEDY` 일 때만 채운다. `DIAGNOSIS_REQUESTED` 중 `attempt == view.attempt` 인 기록의 seq 보다 뒤에 온 `SYMPTOM_JOINED` 의 `line` 들이다. 그런 기록이 없으면 빈 목록. 기록이 늦으면 늦게 보인다.
- `decision`: 아래 둘 중 하나일 때만 있고, 그 밖에는 null 이다.
  - `phase == "AWAITING_APPROVAL"` → `CardDecision("decide", proposalId, candidatesVersion, stateDeadlineMillis)`
  - `phase == "UNKNOWN_PRECONDITION"` 이고 `precondition != null` → `CardDecision("confirm", …)`
  - 어느 쪽이든 `proposalId` 와 `candidatesVersion` 이 있어야 한다.

JSON 은 이 모듈의 `strictJson` 으로 읽는다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.OperatorCardsTest"`
Expected: 9개 PASS

- [ ] **Step 5: 코어가 실제로 쓴 기록과 질의로 한 번 더 확인한다**

`EpisodeWorkflowTest.kt` 에 더한다(`settledRecords` 는 이 클래스의 private 보조 함수다).

```kotlin
    @Test fun `the card is drawn from the view and records the core actually wrote`() {
        acts.policyJson = TestPolicies.table(autoApprove = false)
        val ep = open()
        val waiting = ep.until { it.phase == "AWAITING_APPROVAL" }
        val events = settledRecords(waiting.instanceId) { r -> r.any { it.kind == "PROPOSED" } }.map { StoredEvent(it.seq, it.kind, it.payload) }
        val card = operatorCard(waiting, events)
        assertEquals(waiting.candidateId, card.proposal!!.candidateId)
        assertEquals(CardDecision("decide", waiting.proposalId!!, waiting.candidatesVersion!!, waiting.stateDeadlineMillis), card.decision)
        assertEquals("mock: the first offered candidate [mock-sop, 1]", card.rationale)
        assertEquals("mock-sop", card.citations.single()["title"].textValue())
        assertEquals(1, card.symptoms.size)
    }

    @Test fun `an unknown precondition's card names the proposition from the real candidate`() {
        acts.revalidation = "UNKNOWN"
        val ep = open()
        val v = ep.until { it.phase == "UNKNOWN_PRECONDITION" }
        val line = json.readTree(Picasso.search("search-1"))
        val card = operatorCard(v, emptyList())
        assertEquals(
            "(${line["robotId"].textValue()}, ${line["jobOrderId"].textValue()})에 searchId ${line["searchId"].textValue()} 뒤로 더 새 탐색 줄이 없다",
            card.precondition,
        )
        assertEquals("confirm", card.decision!!.update)
    }
```

Mock narrator 의 `rationale` 과 `citations` 문자열을 `MockNarratorActivities` 에서 확인하고 맞춘다. search-1 줄의 robotId · jobOrderId 칸 이름이 다르면 `Candidates.approveRemedy` 를 부르는 곳(`DiagnosisRequests.kt` 등)에서 확인하고 맞춘다.

Run: `./gradlew :episode-runtime:test`
Expected: 145 + 4 + 9 + 2 = 160개 PASS

- [ ] **Step 6: 커밋한다**

```bash
git add episode-runtime/src
git commit -m "feat(episode-runtime): the operator card (design §9.5) from the live view and the recorded facts" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Chunk 2: `authoring-api` 의 제어면

### Task 5: `EpisodeGateway`

**Files:**
- Modify: `authoring-api/build.gradle.kts`
- Modify: `.gitattributes`
- Create: `authoring-api/src/main/kotlin/koshei/authoring/EpisodeGateway.kt`
- Test: `authoring-api/src/test/kotlin/koshei/authoring/EpisodeGatewayTest.kt`
- Copy: `episode-runtime/src/test/resources/picasso/run-1/{manifest.json,remedy-searches.jsonl}` → `authoring-api/src/test/resources/picasso/run-1/`

- [ ] **Step 1: 의존성과 자원을 더한다**

`authoring-api/build.gradle.kts` 를 고친다.
- `implementation(project(":episode-runtime")) { exclude(group = "org.slf4j", module = "slf4j-simple") }`: 이 파일의 `:runtime` 제외와 같은 모양이다.
- `testImplementation(libs.temporal.testing)`
- `tasks.test` 에 `systemProperty("koshei.repoRoot", rootProject.projectDir.absolutePath)` 를 더한다.

`:episode` 는 `episode-runtime` 의 `implementation` 이라 이 모듈에서 보이지 않는다. 게이트웨이와 컨트롤러는 Wire DTO 와 카드 형만 쓴다.

`.gitattributes` 에 다음 두 줄을 더한다.

```
authoring-api/src/test/resources/** text eol=lf
app/src/test/resources/** text eol=lf
```

자원을 복사하고 바이트가 같은지 확인한다.

```bash
mkdir -p authoring-api/src/test/resources/picasso/run-1
cp episode-runtime/src/test/resources/picasso/run-1/manifest.json episode-runtime/src/test/resources/picasso/run-1/remedy-searches.jsonl authoring-api/src/test/resources/picasso/run-1/
cmp episode-runtime/src/test/resources/picasso/run-1/manifest.json authoring-api/src/test/resources/picasso/run-1/manifest.json
```

Run: `./gradlew :authoring-api:test`
Expected: 99개 PASS(`ContextLoadsTest` 포함)

- [ ] **Step 2: 실패하는 시험을 쓴다**

```kotlin
package koshei.authoring

import com.fasterxml.jackson.databind.ObjectMapper
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import koshei.episode.runtime.CloseRequest
import koshei.episode.runtime.DecideRequest
import koshei.episode.runtime.DispatchOutcome
import koshei.episode.runtime.EPISODE_TASK_QUEUE
import koshei.episode.runtime.EpisodeActivities
import koshei.episode.runtime.EpisodeStart
import koshei.episode.runtime.EpisodeView
import koshei.episode.runtime.EpisodeWorkflow
import koshei.episode.runtime.EpisodeWorkflowImpl
import koshei.episode.runtime.MockNarratorActivities
import koshei.episode.runtime.MockPicasso
import koshei.episode.runtime.NARRATOR_TASK_QUEUE
import koshei.episode.runtime.PolicyFileReader
import koshei.episode.runtime.PolicyReadResult
import koshei.episode.runtime.SymptomSignal
import koshei.episode.runtime.TakeoverRequest
import koshei.runtime.DataConverterSupport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** koshei-side activities for the gateway test: the committed v1 policy (auto-approval off), Mock picasso, no records. */
private class TestActivities : EpisodeActivities {
    private val policy = PolicyFileReader(Path.of(System.getProperty("koshei.repoRoot"), "model", "episode-policy", "active.yaml"))
    private val picasso = MockPicasso()
    private val json = ObjectMapper()
    override fun readPolicy(): PolicyReadResult = policy.read()
    override fun revalidate(candidateJson: String): String = picasso.revalidate(candidateJson)
    override fun record(instanceId: String, seq: Long, kind: String, payloadJson: String) {}
    override fun recordIntent(instanceId: String, seq: Long, intentJson: String) {}
    override fun dispatch(instanceId: String, intentJson: String, approverKind: String?): DispatchOutcome =
        DispatchOutcome("ANSWER", picasso.approve(json.readTree(intentJson), approverKind))
    override fun sendNotice(instanceId: String, noticeJson: String) {}
}

class EpisodeGatewayTest {
    private lateinit var env: TestWorkflowEnvironment
    private lateinit var gateway: EpisodeGateway
    private val json = ObjectMapper()

    @BeforeEach fun up() {
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(DataConverterSupport.clientOptions()).build(),
        )
        env.newWorker(EPISODE_TASK_QUEUE).apply {
            registerWorkflowImplementationTypes(EpisodeWorkflowImpl::class.java)
            registerActivitiesImplementations(TestActivities())
        }
        env.newWorker(NARRATOR_TASK_QUEUE).registerActivitiesImplementations(MockNarratorActivities())
        env.start()
        gateway = EpisodeGateway(lazyOf(env.workflowClient))
    }

    @AfterEach fun down() = env.close()

    private fun resource(name: String) = checkNotNull(javaClass.getResource("/picasso/run-1/$name")).readText()

    private fun searchLine(id: String) =
        resource("remedy-searches.jsonl").lines().first { it.isNotBlank() && json.readTree(it)["searchId"].textValue() == id }

    /** signalWithStart, as the watcher (plan C) and the dev CLI do. */
    private fun open(workflowId: String) {
        val options = WorkflowOptions.newBuilder().setTaskQueue(EPISODE_TASK_QUEUE).setWorkflowId(workflowId)
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE).build()
        val stub = env.workflowClient.newWorkflowStub(EpisodeWorkflow::class.java, options)
        WorkflowStub.fromTyped(stub).signalWithStart(
            "symptom", arrayOf(SymptomSignal("search:search-1", "SEARCH", searchLine("search-1"), 0)), arrayOf(EpisodeStart(resource("manifest.json"))),
        )
    }

    private fun until(workflowId: String, cond: (EpisodeView) -> Boolean): EpisodeView {
        repeat(120) {
            val v = gateway.current(workflowId).view
            if (v != null && cond(v)) return v
            env.sleep(Duration.ofSeconds(1))
        }
        fail("not reached: ${gateway.current(workflowId).view}")
    }

    @Test fun `the current run's view names its instance`() {
        open("ep:g1")
        val v = until("ep:g1") { it.phase == "AWAITING_APPROVAL" }
        assertTrue(v.instanceId.startsWith("ep:g1/"))
        assertTrue(gateway.current("ep:g1").running)
        assertEquals(v.instanceId, gateway.view(v.instanceId)!!.instanceId)
    }

    @Test fun `an unknown workflow is not found`() {
        assertThrows<EpisodeNotFound> { gateway.current("ep:nobody") }
    }

    @Test fun `a decision reaches the episode and its reply comes back as a value`() {
        open("ep:g2")
        val v = until("ep:g2") { it.phase == "AWAITING_APPROVAL" }
        assertEquals("REFUSED_STALE", gateway.decide(v.instanceId, DecideRequest("${v.instanceId}#0", v.candidatesVersion!!, true, "op-1")))
        assertEquals("ACCEPTED", gateway.decide(v.instanceId, DecideRequest(v.proposalId!!, v.candidatesVersion!!, true, "op-1")))
        until("ep:g2") { it.phase == "AWAITING_EVIDENCE" }
    }

    @Test fun `a malformed decision is refused by the validator, before the episode`() {
        open("ep:g3")
        val v = until("ep:g3") { it.phase == "AWAITING_APPROVAL" }
        val e = assertThrows<InvalidEpisodeRequest> { gateway.decide(v.instanceId, DecideRequest(v.proposalId!!, "", true, "op-1")) }
        assertTrue("sawCandidatesVersion" in e.message!! || "proposalId" in e.message!!, e.message)
        assertEquals("AWAITING_APPROVAL", gateway.current("ep:g3").view!!.phase)
    }

    @Test fun `a decision for an instance that is not the current run's is refused - never sent to another episode`() {
        open("ep:g4")
        val v = until("ep:g4") { it.phase == "AWAITING_APPROVAL" }
        val moved = assertThrows<EpisodeMoved> { gateway.takeover("ep:g4/someone-else", TakeoverRequest("op-1")) }
        assertEquals(v.instanceId, moved.currentInstanceId)
        assertEquals("AWAITING_APPROVAL", gateway.current("ep:g4").view!!.phase)
    }

    @Test fun `an ended run has no live view, and a decision on it is refused as ended`() {
        open("ep:g5")
        val v = until("ep:g5") { it.phase == "AWAITING_APPROVAL" }
        assertEquals("ACCEPTED", gateway.takeover(v.instanceId, TakeoverRequest("op-1")))
        assertEquals("ACCEPTED", gateway.close(v.instanceId, CloseRequest("op-1", "handled on site")))
        repeat(30) {
            if (!gateway.current("ep:g5").running) {
                assertNull(gateway.current("ep:g5").view)
                assertNull(gateway.view(v.instanceId))
                assertThrows<EpisodeEnded> { gateway.takeover(v.instanceId, TakeoverRequest("op-1")) }
                return
            }
            env.sleep(Duration.ofSeconds(1))
        }
        fail("the run did not end")
    }

    @Test fun `a past instance has no live view`() {
        open("ep:g6")
        val v = until("ep:g6") { it.phase == "AWAITING_APPROVAL" }
        assertNull(gateway.view("ep:g6/an-earlier-run"))
        assertEquals(v.instanceId, gateway.current("ep:g6").view!!.instanceId)
    }
}
```

- [ ] **Step 3: 실패를 확인한다**

Run: `./gradlew :authoring-api:test --tests "koshei.authoring.EpisodeGatewayTest"`
Expected: 컴파일 오류(`EpisodeGateway` 없음)

- [ ] **Step 4: 구현한다**

```kotlin
package koshei.authoring

import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowUpdateException
import io.temporal.failure.ApplicationFailure
import koshei.episode.runtime.CloseRequest
import koshei.episode.runtime.ConfirmRequest
import koshei.episode.runtime.DecideRequest
import koshei.episode.runtime.EpisodeView
import koshei.episode.runtime.EpisodeWorkflow
import koshei.episode.runtime.TakeoverRequest
import java.time.Duration
import java.util.Optional
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class EpisodeNotFound(val workflowId: String) : RuntimeException("no episode workflow $workflowId")
/** The instance asked for is not the workflow's current run: the decision is not sent (design §7.1). */
class EpisodeMoved(val instanceId: String, val currentInstanceId: String) :
    RuntimeException("$instanceId is not the current episode ($currentInstanceId)")
/** The run has ended (or ended between the check and the Update): nothing can be decided on it. */
class EpisodeEnded(val instanceId: String) : RuntimeException("$instanceId has ended")
/** The Update's validator refused the request's form (design §7.2): nothing reached the episode or its history. */
class InvalidEpisodeRequest(message: String) : RuntimeException(message)
/** The Update did not complete within the bound: the worker may be down. Its outcome is unknown. */
class EpisodeTimeout(val instanceId: String) : RuntimeException("no answer from $instanceId in time")

/** The workflow's current run as Temporal reports it. [view] is queried only while the run is running. */
class CurrentRun(val runId: String, val running: Boolean, val view: EpisodeView?, val stub: EpisodeWorkflow)

/**
 * The control plane's way to the episode workflows (design §7.2, §7.3). Decisions go only to the workflow's current
 * run, only while it runs, and only when it holds the instance the operator saw. The run id comes from Temporal
 * (describe), never from the instance id — they differ after a reset (§7.1). A stub pinned to that run carries both
 * the check and the Update, so a run that continued as new in between refuses the Update rather than the next
 * episode taking it. An ended run is not queried: that needs a worker to replay it, and the records answer instead.
 */
class EpisodeGateway(private val client: Lazy<WorkflowClient>, private val updateTimeout: Duration = Duration.ofSeconds(30)) {
    private val updates = Executors.newCachedThreadPool { r -> Thread(r, "episode-update").apply { isDaemon = true } }

    fun current(workflowId: String): CurrentRun {
        val c = client.value
        val info = try {
            c.workflowServiceStubs.blockingStub().describeWorkflowExecution(
                DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(c.options.namespace)
                    .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
                    .build(),
            ).workflowExecutionInfo
        } catch (e: StatusRuntimeException) {
            if (e.status.code == Status.Code.NOT_FOUND) throw EpisodeNotFound(workflowId) else throw e
        }
        val runId = info.execution.runId
        val running = info.status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
        val stub = c.newWorkflowStub(EpisodeWorkflow::class.java, workflowId, Optional.of(runId))
        return CurrentRun(runId, running, if (running) stub.view() else null, stub)
    }

    /** The live view of [instanceId], or null when it is not the workflow's running current run (its records still answer). */
    fun view(instanceId: String): EpisodeView? = current(workflowIdOf(instanceId)).view?.takeIf { it.instanceId == instanceId }

    fun decide(instanceId: String, request: DecideRequest): String = act(instanceId) { it.decide(request) }
    fun confirm(instanceId: String, request: ConfirmRequest): String = act(instanceId) { it.confirm(request) }
    fun takeover(instanceId: String, request: TakeoverRequest): String = act(instanceId) { it.takeover(request) }
    fun close(instanceId: String, request: CloseRequest): String = act(instanceId) { it.close(request) }

    private fun act(instanceId: String, update: (EpisodeWorkflow) -> String): String {
        val run = current(workflowIdOf(instanceId))
        val view = run.view ?: throw EpisodeEnded(instanceId)
        if (view.instanceId != instanceId) throw EpisodeMoved(instanceId, view.instanceId)
        val pending = updates.submit<String> { update(run.stub) }
        return try {
            pending.get(updateTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.cancel(true)
            throw EpisodeTimeout(instanceId)
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is WorkflowUpdateException -> {
                    val failure = cause.cause
                    if (failure is ApplicationFailure && failure.type == IllegalArgumentException::class.java.name) {
                        throw InvalidEpisodeRequest(failure.originalMessage)
                    }
                    throw cause                                   // anything else stays a 500: not hidden
                }
                // 1.25.1: an Update to a completed run is NOT_FOUND "workflow execution already completed"
                is WorkflowNotFoundException -> throw EpisodeEnded(instanceId)
                is RuntimeException -> throw cause
                else -> throw e
            }
        }
    }

    companion object {
        /** The workflow id is the instance id's first part (design §7.1). The run part is never used as a run id. */
        fun workflowIdOf(instanceId: String): String = instanceId.substringBeforeLast('/')
    }
}
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :authoring-api:test --tests "koshei.authoring.EpisodeGatewayTest"`
Expected: 7개 PASS

- [ ] **Step 6: 커밋한다**

```bash
git add .gitattributes authoring-api/build.gradle.kts authoring-api/src
git commit -m "feat(authoring-api): the episode gateway decides only on the running current run that holds the instance, its run id asked of Temporal" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 6: `EpisodeController` 와 빈

**Files:**
- Create: `authoring-api/src/main/kotlin/koshei/authoring/EpisodeConfig.kt`
- Create: `authoring-api/src/main/kotlin/koshei/authoring/EpisodeController.kt`
- Test: `authoring-api/src/test/kotlin/koshei/authoring/EpisodeControllerTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`RunControllerTest.kt` 의 모양을 따른다. 그 파일의 `anyNonNull()` · `eqOf()` 는 `private` 이므로 이 파일에 같은 보조 함수를 다시 둔다.

```kotlin
package koshei.authoring

import io.grpc.Status
import io.grpc.StatusRuntimeException
import koshei.episode.runtime.CloseRequest
import koshei.episode.runtime.ConfirmRequest
import koshei.episode.runtime.DecideRequest
import koshei.episode.runtime.EpisodeReader
import koshei.episode.runtime.EpisodeView
import koshei.episode.runtime.StoredEvent
import koshei.episode.runtime.StoredInstance
import koshei.episode.runtime.StoredNotice
import koshei.episode.runtime.TakeoverRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.SQLException
import java.time.Instant

private fun <T> anyNonNull(): T { ArgumentMatchers.any<T>(); @Suppress("UNCHECKED_CAST") return null as T }

@WebMvcTest(EpisodeController::class)
class EpisodeControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @MockBean lateinit var gateway: EpisodeGateway
    @MockBean lateinit var reader: EpisodeReader

    private val t = Instant.parse("2026-10-02T00:00:00Z")
    private val id = "ep:a/r1"
    private val version = "sha256:" + "a".repeat(64)
    private val waiting = EpisodeView(instanceId = id, phase = "AWAITING_APPROVAL", attempt = 1, proposalId = "$id#1", candidateId = "c", candidatesVersion = version)

    @BeforeEach fun clear() = reset(gateway, reader)

    private fun postJson(path: String, body: String, operator: String? = "op-1") =
        post(path).contentType(APPLICATION_JSON).content(body).apply { if (operator != null) header("X-Koshei-Operator", operator) }

    @Test fun `the list reads the records`() {
        given(reader.instances(2)).willReturn(listOf(StoredInstance(id, "ep:a", t, t, "AWAITING_APPROVAL", 1, false)))
        mvc.perform(get("/api/episodes?limit=2")).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].instanceId").value(id)).andExpect(jsonPath("$[0].workflowId").value("ep:a"))
            .andExpect(jsonPath("$[0].lastPhase").value("AWAITING_APPROVAL")).andExpect(jsonPath("$[0].notices").value(1))
            .andExpect(jsonPath("$[0].recordLag").value(false))
    }

    @Test fun `the list limit is bounded`() {
        mvc.perform(get("/api/episodes?limit=0")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/episodes?limit=501")).andExpect(status().isBadRequest)
        verifyNoInteractions(reader)
    }

    @Test fun `the detail joins the live view, the card, the records and the notices`() {
        given(gateway.view(id)).willReturn(waiting)
        given(reader.events(id)).willReturn(listOf(StoredEvent(1, "OPENED", """{"eventId":"s-1","kind":"SEARCH","line":{"searchId":"s-1"}}""", t)))
        given(reader.noticeRows(id)).willReturn(listOf(StoredNotice(7, id, """{"kind":"APPROVAL_NEEDED"}""", t)))
        mvc.perform(get("/api/episodes/ep:a/r1")).andExpect(status().isOk)
            .andExpect(jsonPath("$.instanceId").value(id))
            .andExpect(jsonPath("$.view.phase").value("AWAITING_APPROVAL"))
            .andExpect(jsonPath("$.card.decision.update").value("decide"))
            .andExpect(jsonPath("$.events[0].kind").value("OPENED"))
            .andExpect(jsonPath("$.events[0].payload.eventId").value("s-1"))
            .andExpect(jsonPath("$.notices[0].id").value(7))
            .andExpect(jsonPath("$.notices[0].notice.kind").value("APPROVAL_NEEDED"))
    }

    @Test fun `an ended instance answers from its records, and an unknown one is not found`() {
        given(gateway.view(id)).willReturn(null)
        given(reader.events(id)).willReturn(listOf(StoredEvent(1, "OPENED", "{}", t)))
        given(reader.noticeRows(id)).willReturn(emptyList())
        mvc.perform(get("/api/episodes/ep:a/r1")).andExpect(status().isOk)
            .andExpect(jsonPath("$.view").doesNotExist()).andExpect(jsonPath("$.card").doesNotExist())
        given(gateway.view("ep:b/r1")).willThrow(EpisodeNotFound("ep:b"))
        given(reader.events("ep:b/r1")).willReturn(emptyList())
        mvc.perform(get("/api/episodes/ep:b/r1")).andExpect(status().isNotFound)
    }

    @Test fun `the detail still answers from the records when Temporal does not`() {
        given(gateway.view(id)).willThrow(StatusRuntimeException(Status.UNAVAILABLE))
        given(reader.events(id)).willReturn(listOf(StoredEvent(1, "OPENED", "{}", t)))
        given(reader.noticeRows(id)).willReturn(emptyList())
        mvc.perform(get("/api/episodes/ep:a/r1")).andExpect(status().isOk)
            .andExpect(jsonPath("$.view").doesNotExist()).andExpect(jsonPath("$.viewError").exists())
    }

    @Test fun `a decision is relayed under the operator header and its value comes back`() {
        given(gateway.decide(id, DecideRequest("$id#1", version, true, "op-1", null, null))).willReturn("ACCEPTED")
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", """{"proposalId":"$id#1","sawCandidatesVersion":"$version","approve":true}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.reply").value("ACCEPTED"))
        given(gateway.decide(id, DecideRequest("$id#0", version, true, "op-1", null, null))).willReturn("REFUSED_STALE")
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", """{"proposalId":"$id#0","sawCandidatesVersion":"$version","approve":true}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.reply").value("REFUSED_STALE"))
    }

    @Test fun `no operator, a blank one, a long one, an operator in the body, an unknown field or a missing approve is refused`() {
        val ok = """{"proposalId":"p","sawCandidatesVersion":"v","approve":true}"""
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", ok, operator = null)).andExpect(status().isBadRequest)
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", ok, operator = "  ")).andExpect(status().isBadRequest)
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", ok, operator = "x".repeat(129))).andExpect(status().isBadRequest)
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", """{"proposalId":"p","sawCandidatesVersion":"v","approve":true,"operatorId":"someone"}"""))
            .andExpect(status().isBadRequest)
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", """{"proposalId":"p","sawCandidatesVersion":"v","aprove":true}""")).andExpect(status().isBadRequest)
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", """{"proposalId":"p","sawCandidatesVersion":"v"}""")).andExpect(status().isBadRequest)
        verifyNoInteractions(gateway)
    }

    @Test fun `the gateway's refusals map to their statuses`() {
        val body = """{"proposalId":"p","sawCandidatesVersion":"v","approve":true}"""
        // doThrow form: a given(...) on a method already stubbed to throw would call it, and throw, while stubbing.
        fun answer(e: RuntimeException) = willThrow(e).given(gateway).decide(anyNonNull(), anyNonNull())
        answer(InvalidEpisodeRequest("bad"))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("bad"))
        answer(EpisodeMoved(id, "ep:a/r2"))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("EPISODE_MOVED")).andExpect(jsonPath("$.currentInstanceId").value("ep:a/r2"))
        answer(EpisodeEnded(id))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)).andExpect(status().isConflict).andExpect(jsonPath("$.error").value("EPISODE_ENDED"))
        answer(EpisodeNotFound("ep:a"))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)).andExpect(status().isNotFound)
        answer(StatusRuntimeException(Status.UNAVAILABLE))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)).andExpect(status().isServiceUnavailable)
        answer(EpisodeTimeout(id))
        mvc.perform(postJson("/api/episodes/ep:a/r1/decide", body)).andExpect(status().isGatewayTimeout)
    }

    @Test fun `missing tables are a 503, not a 500`() {
        given(reader.instances(50)).willAnswer { throw SQLException("relation \"episode_event\" does not exist", "42P01") }
        mvc.perform(get("/api/episodes")).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").value("episode tables not found — no episode worker has run (KOSHEI_PICASSO)"))
    }

    @Test fun `a precondition confirmation carries the card's proposition, never the client's`() {
        val unknown = waiting.copy(
            phase = "UNKNOWN_PRECONDITION",
            candidateJson = """{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{"robotId":"r","jobOrderId":"j","searchId":"s"},"sawSkillTypes":[]}""",
        )
        given(gateway.view(id)).willReturn(unknown)
        given(reader.events(id)).willReturn(emptyList())
        val sent = ConfirmRequest("PRECONDITION", "c", "$id#1", true, emptyMap(), "", "op-1", null, "(r, j)에 searchId s 뒤로 더 새 탐색 줄이 없다")
        given(gateway.confirm(id, sent)).willReturn("ACCEPTED")
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"PRECONDITION","candidateId":"c","proposalId":"$id#1","holds":true}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.reply").value("ACCEPTED"))
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"PRECONDITION","candidateId":"c","proposalId":"$id#1","holds":true,"proposition":"x"}"""))
            .andExpect(status().isBadRequest)
    }

    @Test fun `a precondition confirmation outside UNKNOWN_PRECONDITION is relayed and refused by the core as a value`() {
        given(gateway.view(id)).willReturn(waiting)   // AWAITING_APPROVAL
        given(reader.events(id)).willReturn(emptyList())
        given(gateway.confirm(id, ConfirmRequest("PRECONDITION", "c", "$id#1", true, emptyMap(), "", "op-1", null, null))).willReturn("REFUSED_SUBJECT_NOT_UNKNOWN")
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"PRECONDITION","candidateId":"c","proposalId":"$id#1","holds":true}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.reply").value("REFUSED_SUBJECT_NOT_UNKNOWN"))
    }

    @Test fun `with no proposition on the card, 'the precondition holds' is refused`() {
        // The candidate is named but its ref lacks the searchId: no proposition can be filled (§8.3).
        val noProposition = waiting.copy(
            phase = "UNKNOWN_PRECONDITION",
            candidateJson = """{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{"robotId":"r","jobOrderId":"j","searchId":null},"sawSkillTypes":[]}""",
        )
        given(gateway.view(id)).willReturn(noProposition)
        given(reader.events(id)).willReturn(emptyList())
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"PRECONDITION","candidateId":"c","proposalId":"$id#1","holds":true}"""))
            .andExpect(status().isBadRequest)
        verify(gateway, never()).confirm(anyNonNull(), anyNonNull())
    }

    @Test fun `outcome and unknown confirmations, takeover and close reach the gateway`() {
        given(gateway.confirm(id, ConfirmRequest("OUTCOME", "c", "$id#1", false, emptyMap(), "", "op-1", null, null))).willReturn("ACCEPTED")
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"OUTCOME","candidateId":"c","proposalId":"$id#1","holds":false}"""))
            .andExpect(jsonPath("$.reply").value("ACCEPTED"))
        given(gateway.confirm(id, ConfirmRequest("UNKNOWN", "", "", true, mapOf("robotId" to "r"), "OBSERVATION_ABSENT", "op-1", "seen", null))).willReturn("ACCEPTED")
        mvc.perform(postJson("/api/episodes/ep:a/r1/confirm", """{"kind":"UNKNOWN","subject":{"robotId":"r"},"what":"OBSERVATION_ABSENT","holds":true,"note":"seen"}"""))
            .andExpect(jsonPath("$.reply").value("ACCEPTED"))
        given(gateway.takeover(id, TakeoverRequest("op-1"))).willReturn("ACCEPTED")
        mvc.perform(post("/api/episodes/ep:a/r1/takeover").header("X-Koshei-Operator", "op-1")).andExpect(jsonPath("$.reply").value("ACCEPTED"))
        given(gateway.close(id, CloseRequest("op-1", "handled"))).willReturn("REFUSED_NOT_ESCALATED")
        mvc.perform(postJson("/api/episodes/ep:a/r1/close", """{"outcome":"handled"}""")).andExpect(jsonPath("$.reply").value("REFUSED_NOT_ESCALATED"))
    }

    @Test fun `the notice feed continues after an id and does not collide with an episode path`() {
        given(reader.noticesAfter(5, 10)).willReturn(listOf(StoredNotice(6, id, """{"kind":"PERSON_TASK","idempotencyKey":"k","intent":{}}""", t)))
        mvc.perform(get("/api/episodes/notices?after=5&limit=10")).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(6)).andExpect(jsonPath("$[0].instanceId").value(id))
            .andExpect(jsonPath("$[0].notice.kind").value("PERSON_TASK"))
    }
}
```

`ConfirmRequest` 의 위치 인자 순서(`kind, candidateId, proposalId, holds, subject, what, operatorId, note, proposition`)는 `Wire.kt` 에서 확인하고 맞춘다. `UNKNOWN` 확인의 `holds` 는 코어가 쓰지 않을 수 있다. 그래도 본문의 `holds` 는 모든 confirm 에서 필수다. 빠뜨린 칸이 「안 됨」으로 읽히는 일을 막는 규칙을 하나로 두기 위해서다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :authoring-api:test --tests "koshei.authoring.EpisodeControllerTest"`
Expected: 컴파일 오류

- [ ] **Step 3: 구현한다**

`EpisodeConfig.kt`:

```kotlin
package koshei.authoring

import com.zaxxer.hikari.HikariDataSource
import io.temporal.client.WorkflowClient
import io.temporal.serviceclient.WorkflowServiceStubs
import koshei.episode.runtime.EpisodeReader
import koshei.episode.runtime.EpisodeStore
import koshei.runtime.DataConverterSupport
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The episode control plane's beans. Lazy like EngineConfig: the context starts without Temporal or the database. */
@Configuration
class EpisodeConfig {
    @Bean fun episodeGateway(): EpisodeGateway =
        EpisodeGateway(lazy { WorkflowClient.newInstance(WorkflowServiceStubs.newLocalServiceStubs(), DataConverterSupport.clientOptions()) })

    /** Read only (R14): the episode worker creates and writes the tables (design §13). */
    @Bean fun episodeReader(ds: HikariDataSource): EpisodeReader = EpisodeStore { ds.connection }
}
```

`EpisodeController.kt` 의 본문 DTO 는 참 · 거짓 칸이 필수다.

```kotlin
data class DecideBody(val proposalId: String = "", val sawCandidatesVersion: String = "", val approve: Boolean? = null,
                      val reason: String? = null, val note: String? = null)

data class ConfirmBody(val kind: String = "", val candidateId: String = "", val proposalId: String = "", val holds: Boolean? = null,
                       val subject: Map<String, String?> = emptyMap(), val what: String = "", val note: String? = null)

data class CloseBody(val outcome: String = "")
```

모르는 칸의 거절: Spring Boot 의 `ObjectMapper` 는 `FAIL_ON_UNKNOWN_PROPERTIES` 를 끈 채 만들어지고, 클래스의 `@JsonIgnoreProperties(ignoreUnknown = false)` 로는 되살아나지 않는다(jackson-databind 2.17.2 에서 확인). 그래서 POST 본문은 `@RequestBody body: String` 으로 받고, 이 컨트롤러 전용 `private val bodies = jacksonObjectMapper()`(Kotlin 모듈, 새 매퍼라 `FAIL_ON_UNKNOWN_PROPERTIES` 가 켜져 있다)로 읽는다. `takeover` 는 본문을 받지 않는다. `JsonProcessingException`(`UnrecognizedPropertyException` 포함)은 이 컨트롤러의 처리기가 400 으로 바꾼다.

경로와 처리:

```kotlin
@RestController
@RequestMapping("/api/episodes")
class EpisodeController(private val gateway: EpisodeGateway, private val reader: EpisodeReader) {
    @GetMapping fun list(@RequestParam(defaultValue = "50") limit: Int)                       // 1..500 밖이면 ResponseEntity.badRequest() 로 400
    @GetMapping("/notices") fun notices(@RequestParam(defaultValue = "0") after: Long, @RequestParam(defaultValue = "100") limit: Int)
    @GetMapping("/{workflowId}/{run}") fun one(@PathVariable workflowId: String, @PathVariable run: String)
    @PostMapping("/{workflowId}/{run}/decide")   // DecideBody: approve 가 null 이면 400
    @PostMapping("/{workflowId}/{run}/confirm")  // ConfirmBody: holds 가 null 이면 400
    @PostMapping("/{workflowId}/{run}/takeover") // 본문 없음
    @PostMapping("/{workflowId}/{run}/close")    // CloseBody
}
```

- `instanceId = "$workflowId/$run"`.
- **머리글:** `@RequestHeader("X-Koshei-Operator", required = false)` 로 받아 자른 뒤 비었거나 128자를 넘으면 400 `{"error":"X-Koshei-Operator is required (design §7.2)"}`.
- **상세:**
  - `view` 를 `gateway.view(instanceId)` 로 읽는다.
    - `EpisodeNotFound` 면 view = null 로 두고, 「Temporal 에 없음」을 기억한다.
    - `StatusRuntimeException`, `WorkflowServiceException`, `WorkflowQueryException`, `EpisodeTimeout` 이면 view = null, `viewError = e.message`.
  - `events` 와 `notices` 는 reader 로 읽는다.
  - Temporal 에 없고 기록도 없으면 404.
  - `card = view?.let { operatorCard(it, events) }`.
  - 응답은 `{instanceId, view, viewError, card, events:[{seq, kind, payload, at}], notices:[{id, at, notice}]}` 이다. `payload` 와 `notice` 는 `ObjectMapper().readTree` 로 만든 JSON 트리다.
- **confirm(PRECONDITION):**
  - 제어면이 `gateway.view(instanceId)` 와 `reader.events(instanceId)` 로 자기 카드를 만든다.
  - view 가 null 이면 명제 없이(`proposition = null`) 게이트웨이에 넘긴다. 게이트웨이가 409 나 404 로 답한다.
  - `view.phase == "UNKNOWN_PRECONDITION"` 이고, 본문의 `candidateId == card.proposal?.candidateId`, `proposalId == view.proposalId` 일 때만 `proposition = card.precondition` 을 싣는다. 그 경우에 `holds == true` 인데 `card.precondition == null` 이면 400 `{"error":"no proposition to confirm (design §8.3)"}` 이고, 게이트웨이를 부르지 않는다.
  - 그 밖에는 `proposition = null` 로 넘기고, 코어가 값으로 거절한다(§7.2). 다른 단계나 다른 시도에 대한 확인을 제어면이 400 으로 가로채지 않는다.
  - 본문에는 `proposition` 칸이 없다. 클라이언트가 보내면 모르는 칸이라 400 이다.
  - 다른 kind 는 `proposition = null`.
- **예외 처리기(이 컨트롤러 안의 `@ExceptionHandler`):**

  | 예외 | 응답 |
  |---|---|
  | `InvalidEpisodeRequest` | 400 `{"error": message}` |
  | `EpisodeMoved` | 409 `{"error":"EPISODE_MOVED","currentInstanceId": …}` |
  | `EpisodeEnded` | 409 `{"error":"EPISODE_ENDED"}` |
  | `EpisodeNotFound` | 404 `{"error": message}` |
  | `StatusRuntimeException`(UNAVAILABLE · DEADLINE_EXCEEDED), `WorkflowServiceException`, `java.sql.SQLTransientConnectionException` | 503 `{"error": message}` |
  | `java.sql.SQLException`(sqlState `42P01`) | 503(위 문구) |
  | 그 밖의 `SQLException` | 다시 던진다(500) |
  | `EpisodeTimeout` | 504 `{"error": message}` |
  | 본문 해독 실패, 필수 칸 누락 | 400 |

  `ApiExceptionHandler`(전역 `IllegalArgumentException` → 400)와 겹치지 않게, 이 컨트롤러의 오류는 이 컨트롤러의 처리기가 먼저 받는다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :authoring-api:test`
Expected: 99 + 7 + 14 = 120개 PASS(`ContextLoadsTest` 포함 — 새 빈이 Temporal 과 DB 없이 뜬다)

- [ ] **Step 5: 커밋한다**

```bash
git add authoring-api/src
git commit -m "feat(authoring-api): /api/episodes - list, detail with the operator card, decisions under X-Koshei-Operator, the notice feed" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Chunk 3: 개발용 CLI 와 문서

### Task 7: `episode open` · `episode agent-off`

**Files:**
- Modify: `app/build.gradle.kts`(`implementation(libs.jackson.kotlin)` — `:app` 의 컴파일 경로에는 Jackson 이 없다)
- Create: `app/src/main/kotlin/koshei/app/EpisodeCli.kt`
- Modify: `app/src/main/kotlin/koshei/app/Cli.kt`
- Test: `app/src/test/kotlin/koshei/app/EpisodeCliTest.kt`
- Copy: `episode-runtime/src/test/resources/picasso/run-1/*` → `app/src/test/resources/picasso/run-1/`(`cp` 로 복사한 뒤 `cmp` 로 확인)

사용법:

```
cli episode open --export <dir> (--search <searchId> | --incident <incidentId>) [--key <correlationKey>]
cli episode agent-off <workflowId>
```

- `--export` 는 picasso 내보내기 한 벌의 디렉터리다. `manifest.json` 이 있어야 읽는다(§12).
- manifest 의 `runId` 가 없거나 비었으면 추측하지 않고 멈춘다(§12).
- 줄 하나를 골라 `SymptomSignal` 을 만든다.
  - `eventId` 는 감시자의 중복 제거 열쇠와 같은 꼴이다(§12): 탐색 줄은 `search:<runId>:<searchId>`, 사건 줄은 `incident:<runId>:<digest>`.
  - `kind` 는 `SEARCH` / `INCIDENT`.
  - `lineJson` 은 그 줄 원문 그대로, `observedAtMillis` 는 `System.currentTimeMillis()`, `manifestJson` 은 manifest 원문이다.
- 워크플로 id 는 `ep:<key>` 이다.
  - `--key` 가 없으면 `<runId>:<searchId 또는 incidentId>` 를 키로 쓴다.
  - 키는 `[A-Za-z0-9._:-]+` 만 받는다. 제어면 경로가 `/`, `;`, `%`, `?` 를 허락하지 않는다. 기본 키의 runId 가 이 규칙을 어기면 오류로 알리고 `--key` 를 쓰라고 한다.
- signalWithStart 는 `ALLOW_DUPLICATE` 로, 큐 `EPISODE_TASK_QUEUE` 에 보낸다. 시작 인자는 `EpisodeStart(manifest)` 이다.
- 성공하면 워크플로 id 와 `eventId` 를 찍는다.
- `agent-off` 는 `newWorkflowStub(EpisodeWorkflow::class.java, workflowId).agentOff()` 다.
- `parseOpen` 과 `symptom` 은 잘못된 입력에 `IllegalArgumentException` 을 던진다. `Cli.kt` 의 `doEpisode` 가 그것을 받아 `usageError(e.message)` 를 부른다. `usageError` 는 `Cli.kt` 의 `private` 함수이고 프로세스를 끝낸다 — 시험은 그 길을 타지 않는다.
- `doEpisode` 는 `args.drop(2).toTypedArray()` 를 `parseOpen` 에 넘긴다.
- `agent-off <workflowId>` 의 워크플로 id 는 `ep:` 로 시작하고 그 뒤가 `[A-Za-z0-9._:-]+` 여야 한다. 아니면 거절한다.
- `Cli.kt` 의 `command required (…)` 와 `unknown command …` 문구, 사용법 KDoc 에 `episode` 를 더한다.
- 클라이언트는 `Worker.kt` 와 같은 방식으로 만든다(`WorkflowServiceStubs.newLocalServiceStubs()` + `DataConverterSupport.clientOptions()`).

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package koshei.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EpisodeCliTest {
    private val export: Path = Path.of(checkNotNull(javaClass.getResource("/picasso/run-1/manifest.json")).toURI()).parent
    private val runId = "run-2026-09-22T16:47:37.854173400Z-1"

    private fun open(vararg args: String) = EpisodeCli.symptom(EpisodeCli.parseOpen(arrayOf("--export", export.toString(), *args)))

    @Test fun `a search line becomes a symptom keyed like the watcher's`() {
        val s = open("--search", "search-1")
        assertEquals("SEARCH", s.signal.kind)
        assertEquals("search:$runId:search-1", s.signal.eventId)
        assertEquals("ep:$runId:search-1", s.workflowId)
        assertTrue(s.signal.manifestJson!!.contains(runId))
        assertTrue(s.signal.lineJson.contains("\"searchId\""))
    }

    @Test fun `an incident line is keyed by its digest`() {
        val s = open("--incident", "incident-1")
        assertEquals("INCIDENT", s.signal.kind)
        assertEquals("incident:$runId:d04ac2a20a63afc2ba147218bae871ef69746c990e3993deb00cfb4ddd262143", s.signal.eventId)
    }

    @Test fun `a key names the workflow`() = assertEquals("ep:k-1", open("--search", "search-1", "--key", "k-1").workflowId)

    @Test fun `what cannot be read is refused, never guessed`() {
        assertFailsWith<IllegalArgumentException> { EpisodeCli.symptom(EpisodeCli.parseOpen(arrayOf("--export", Files.createTempDirectory("empty").toString(), "--search", "search-1"))) }
        assertFailsWith<IllegalArgumentException> { open("--search", "nope") }
        assertFailsWith<IllegalArgumentException> { open("--search", "search-1", "--key", "a/b") }
        assertFailsWith<IllegalArgumentException> { open("--search", "search-1", "--key", "a?b") }
        assertFailsWith<IllegalArgumentException> { open("--search", "search-1", "--incident", "incident-1") }
        assertFailsWith<IllegalArgumentException> { open() }
        val noRun = Files.createTempDirectory("norun")
        Files.writeString(noRun.resolve("manifest.json"), """{"schemaVersion":"5"}""")
        Files.copy(export.resolve("remedy-searches.jsonl"), noRun.resolve("remedy-searches.jsonl"))
        assertFailsWith<IllegalArgumentException> { EpisodeCli.symptom(EpisodeCli.parseOpen(arrayOf("--export", noRun.toString(), "--search", "search-1"))) }
    }
}
```

`runId` 와 incident-1 의 `digest` 는 run-1 의 실제 값이다. 복사한 파일에서 다시 확인한다. manifest 의 `schemaVersion` 실제 값도 맞춘다(시험에는 관계없다).

`EpisodeCli.symptom` 은 `OpenSymptom(workflowId: String, start: EpisodeStart, signal: SymptomSignal)` 을 돌려준다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :app:test --tests "koshei.app.EpisodeCliTest"`
Expected: 컴파일 오류

- [ ] **Step 3: 구현한다**

위 규칙대로 `EpisodeCli.kt`(`object EpisodeCli { parseOpen, symptom, open, agentOff }`)와 `Cli.kt` 를 고친다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :app:test`
Expected: 15 + 4 = 19개 PASS

- [ ] **Step 5: 커밋한다**

```bash
git add app/build.gradle.kts app/src
git commit -m "feat(app): dev CLI opens an episode from one picasso export line, and switches an episode's agent off" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 8: 설계 문서와 손으로 하는 연기 시험

**Files:**
- Modify: `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md`

- [ ] **Step 1: 고친다**

한국어로, 해당 절 끝에 「구현(B3c)」 문단을 더한다.
- **§7.2:**
  - 경로는 `/api/episodes/{workflowId}/{run}` 이다. 워크플로 id 는 `[A-Za-z0-9._:-]+`(감시자가 지킨다, 계획 C).
  - 응답 갈래 표.
  - 신원 출처는 머리글뿐이다. 본문의 운영자 id 와 모르는 칸은 거절한다. `approve`, `holds` 는 필수다.
  - 결정은 현재 run 이 살아 있고 그 인스턴스일 때만 보낸다. run id 는 describe 로 얻는다.
  - Update 의 시간 상한은 30초다(넘으면 504 — 결과는 모른다).
  - 전제 확인의 명제는 제어면이 자기 카드에서 채워 `CONFIRMATION` 기록에 남긴다. 명제가 없으면 「전제 맞음」을 거절한다.
- **§7.3:**
  - 질의에 `candidateJson`, `candidatesJson`, `diagnosisJson` 을 더한다.
  - 상세 응답의 모양.
  - 끝난 run 은 질의하지 않는다(워커 재생이 필요하다). 기록만으로 답한다.
  - 목록은 `episode_event` 에서 만든다(마지막 TRANSITION — 기록이 늦으면 늦게 보인다. `recordLag` 가 서 있으면 믿지 않는다).
- **§9.5:**
  - 카드를 만드는 함수와 그 규칙: narrator 칸은 질의에서, 사실은 기록에서.
  - `cause` 를 싣지 않는다.
  - 명제를 지어내지 않는다.
  - WITHHELD 는 사실만 보인다.
  - `decision.update` 는 `decide` 또는 `confirm` 이다.
  - 확인 못 한 인용 · 숫자 중 본문이 없는 것은 센다.
- **§8.5:** 알림 흐름 `GET /api/episodes/notices?after=`. id 는 커밋 순서가 아니므로 화면은 겹쳐 다시 읽고 id 로 중복을 지운다.
- **§16:** 계획 B 줄 아래 주석에 「B3c(API) 끝(날짜)」을 적는다. 남은 것은 B3c-2(화면)다.

- [ ] **Step 2: 손으로 하는 연기 시험(환경이 허락할 때만)**

이 단계는 커밋하지 않고 결과만 보고한다. 포트를 여는 컨테이너가 막히면 건너뛰고 그렇다고 보고한다.

1. `docker compose up -d postgres temporal`
2. `KOSHEI_PICASSO=mock ./gradlew :app:run` (다른 셸)
3. `./gradlew :authoring-api:run` (다른 셸)
4. `./gradlew :app:cli --args="episode open --export ../episode-runtime/src/test/resources/picasso/run-1 --search search-1 --key smoke-1"`
   - `:app:cli` 의 작업 디렉터리는 `app/` 이다.
5. `curl -s localhost:18090/api/episodes` → `ep:smoke-1/…` 인스턴스 하나
6. `curl -s localhost:18090/api/episodes/ep:smoke-1/<run>` → `view.phase` 가 `AWAITING_APPROVAL`, `card.decision.update` 가 `decide`
7. `curl -s -X POST -H "X-Koshei-Operator: op-1" -H "Content-Type: application/json" -d '{"proposalId":"…","sawCandidatesVersion":"…","approve":true}' localhost:18090/api/episodes/ep:smoke-1/<run>/decide` → `{"reply":"ACCEPTED"}`
8. 다시 GET → `AWAITING_EVIDENCE`, `events` 에 `DISPATCH_INTENT` · `DISPATCH_RESULT`(`mock-exec-1`)

- [ ] **Step 3: 커밋한다**

```bash
git add docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md
git commit -m "docs(design): implemented shape of plan B3c - the episode control plane and the dev CLI" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
