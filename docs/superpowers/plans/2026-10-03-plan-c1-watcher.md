# 계획 C1 — 감시자(접수 · 근거 운반 · 병합) · Mock 담는 쪽 · drift 자동 디스패치 끄기 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 계획 C(설계 §16) 가운데 지금 바깥 답을 기다리지 않고 세울 수 있는 것을 세운다.
- 감시자 `EpisodeWatcher`: picasso 파일 내보내기의 새 줄을 에피소드로 연다(§12 접수). 병합 규칙 `correlate` 가 같은 원인의 증상을 한 에피소드로 묶는다(R10).
- 담는 쪽이 나른 결과 통보(JobResponse)를 실행 의도 기록의 주문으로 찾아 `evidence` signal 로 나른다(§12 근거).
- Mock 담는 쪽(시험 전용): 먼저 온 것, 관계없는 것, `jobResponseId` 가 겹치는 것을 섞어 낸다(§16 C).
- 에피소드 모드(`KOSHEI_PICASSO ≠ off`)에서는 drift 자동 디스패치를 막는다(§14, D5).

**Architecture:**
- **감시자는 운반만 한다.** 판정은 워크플로가 한다.
  - 접수: 줄 → `SymptomSignal` → `signalWithStart(ep:<key>)`.
  - 근거: 줄 → `EvidenceSignal` → `evidence` signal.
  - 감시자가 하는 판단은 둘뿐이다. 둘 다 결정적이고 LLM 에 묻지 않는다.
    - 병합 키: 순수 함수 `correlate`(`:episode`).
    - 어느 워크플로로 보낼지: 실행 의도 기록의 `jobOrderId`.
- **읽지 못하는 것은 추측하지 않고 멈춘다(§12).**
  - 내보내기는 `manifest.json` 이 있을 때만 읽는다.
  - 줄은 manifest 의 `counts` 만큼만 읽는다. 그 뒤의 줄은 아직 manifest 가 쓰이지 않은 다음 판의 것이다.
  - 다 읽은 뒤 manifest 를 다시 읽는다. 글자가 바뀌었으면 다음 폴링에 다시 읽는다.
  - 깨진 줄은 건너뛰지 않는다. JSON 이 아니거나 id 가 없는 줄은 그 묶음 전체를 멈춘다(그 앞의 줄과 다른 파일도 나르지 않는다). 읽히지만 쓸 수 없는 줄(digest 없는 사건, 모양이 틀린 결과 통보 칸)은 그 파일을 그 줄에서 멈춘다. 둘 다 기록을 남긴다.
- **커서는 Postgres 에 둔다(§12).**
  - 커서는 (원천, runId)마다 「몇 줄을 날랐나 + 마지막 줄의 id」다.
  - 다시 읽을 때 그 자리의 줄 id 가 커서와 다르면 멈춘다(`STUCK`). id 의 모양(`incident-N`)은 분해하지 않는다.
  - 결과 통보 파일에는 같은 `jobResponseId` 가 두 번 나올 수 있다(담는 쪽은 최소 한 번 나른다). 그래서 id 만으로는 자리를 못 정하고, 줄 수를 함께 둔다.
  - 커서 표도 추가 전용이다(§13). 앞으로 나아갈 때마다 행을 하나 더한다.
- **최소 한 번 나른다.** signal 이 성공한 뒤에 커서를 옮긴다. 두 번 간 증상과 근거는 코어가 `eventId` 와 `jobResponseId` 로 지운다(§6).
- **결과 통보의 나르는 모양은 아직 정해지지 않았다(§19 C).** 이 계획은 개발용 모양 `koshei-carry-0` 을 정한다.
  - 담는 쪽이 디렉터리에 `job-responses.jsonl` 과 `manifest.json` 을 picasso 내보내기와 같은 규율로 쓴다(임시 파일 → 이름 바꾸기, manifest 가 마지막).
  - 줄은 picasso `JobResponse` 의 칸 이름 그대로다. 여기에 담는 쪽이 아는 `executionId` 를 덧붙인다.
  - picasso 의 `JobResponse` 에는 `executionId` 가 없다(picasso `Model.kt:310-337`, `8ab5400`). 코어는 그 칸으로 시도에 잇는다(§12).
- **에피소드 모드는 새 스위치가 아니다.** 워커가 이미 읽는 `KOSHEI_PICASSO` 를 authoring-api 도 같은 해석으로 읽는다.
  - 모르는 값이면 시작을 멈춘다.
  - 에피소드 모드인데 `KOSHEI_AUTOCORRECT_DISPATCH=1` 이면 시작을 멈춘다. 둘 중 하나가 조용히 지지 않게 한다.
  - 에피소드 모드에서 `POST /api/autocorrect/sweep` 는 409 `EPISODE_MODE` 다.

**Tech Stack:** Kotlin 2 / JVM 21, Temporal Java SDK 1.25.1(`TestWorkflowEnvironment`), Jackson(엄격 매퍼), plain JDBC + Testcontainers Postgres 16, Spring Boot(authoring-api), Gradle.

**작업 위치:** worktree `C:\Users\Eisen\Desktop\Labs\[projects] koshchei\koshei-episode-core`, 브랜치 `feat/episode-core`(머리 `29a2fab`).
- 원래 체크아웃(`koshei/`)은 건드리지 않는다.
- picasso · narrator 저장소는 읽기만 한다.
- 파일은 Write/Edit 로만 쓴다(스크립트로 고치지 않는다).
- bare `git stash` 를 쓰지 않는다. 푸시하지 않는다.
- 공용 Temporal · Postgres(`koshei-temporal-1`, `koshei-postgres-1`)는 다른 세션의 것이다. 시험은 `TestWorkflowEnvironment` 와 Testcontainers 로만 돈다. `:app` 의 워커나 감시자를 그 위에 띄우지 않는다.

**커밋 규칙:**
- 모든 커밋은 `git commit -m "<제목>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"` 로 한다.
- `git add` 는 경로를 밝혀서 한다.

**범위 밖 (이 계획이 하지 않는 것):**

| 무엇 | 왜 | 그때까지 |
|---|---|---|
| 실물 picasso 승인 창구 클라이언트 | 담는 쪽의 자리(§19 C). 또 picasso 창구가 schema `3` 으로 올랐다(`cf81e02` CONSUMED · `5881e24`, 2026-10-01). koshei 는 `2` 만 읽는다. `CONSUMED` 를 읽을지는 사용자가 정한다(§19 B) | `KOSHEI_PICASSO` 는 `off` 아니면 `mock`. 실물 답이 와도 `schemaVersion 3` 이라 `DISPATCH_ANSWER_UNKNOWN` 으로 사람에게 간다 |
| drift 를 감시자의 둘째 입력으로(§7.1, §14) | 코어에 증상 종류 `DRIFT` 가 없다. drift 로 연 에피소드의 진단 요청 · 후보 투영(§9)을 먼저 설계해야 한다 | 이 계획은 자동 디스패치를 막기만 한다. drift 경보는 지금처럼 로그로 간다 |
| 결과 통보로 DONE 에 닿기 | 결과 통보의 어느 단위가 대상인지 picasso 에 물었다(§19). `targetUnit` 은 늘 null 이다 | 실물 에피소드는 사람의 `confirm` 이나 기한으로 끝난다. 결과 통보는 UNKNOWN(OUTCOME)으로만 몬다 |
| 정책 v1 의 병합 규칙을 켜기 | 무엇을 한 원인으로 볼지는 운영 결정이다 | `correlation: []` 그대로다. 증상마다 제 에피소드를 연다 |
| 감시 기록(`episode_watch_log`)을 화면에 보이기 | 계획 D | 표에만 남는다. 애매한 병합의 서로 참조도 이 표에만 있다 — 에피소드 기록에는 붙지 않는다 |
| 한 `executionId` 를 두 에피소드가 잇는 일 | picasso 는 같은 주문의 뒤 승인에 `executionId` 를 그대로 두고(`revise()`), 다시 시작하면 `exec-N` 을 되풀이한다. 코어의 `usedExecutionIds` 는 에피소드마다다. 결과 통보는 주문이 같은 모든 에피소드로 가므로 둘이 같은 통보를 이을 수 있다 | 지금은 UNKNOWN 까지만 간다(`targetUnit` 이 null 이라 DONE 이 없다). §19 의 대상 단위 답이 오기 전에 시도별 구분자와 함께 푼다 |
| 결과 통보의 다른 칸(`requiredEvidence`, `blockedBy`, `connection`, `autoResolvesInDoubt`, `incompleteUnits`) | 코어가 아직 읽지 않는다. picasso 에서 `connection` 이 끊김이면 결과가 확정되지 않았다는 뜻이다(picasso `Model.kt:335`) | 싣지 않는다. 지금은 DONE 이 없으므로 확정되지 않은 결과로 끝나는 일도 없다. 나르는 모양(§19 C)과 함께 정한다 |
| 신호와 커서 사이에 죽은 뒤 다시 보낸 증상 | 그 사이 에피소드가 끝났으면 다시 보낸 증상은 같은 키로 새 에피소드를 연다(`ALLOW_DUPLICATE`). 코어의 `eventId` 중복 제거는 열린 run 안에서만 먹는다 | 좁은 틈이다. 문서에 한계로 적는다(Task 11) |

---

## 파일 구조

| 파일 | 책임 |
|---|---|
| `episode/src/main/kotlin/koshei/episode/Correlation.kt` (새) | `CorrelationRule`, `Correlation`, `correlate` — 순수 병합 키 |
| `episode/src/main/kotlin/koshei/episode/Policy.kt` (고침) | `correlation` 을 `List<CorrelationRule>` 로 읽고 검사한다 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/Bundles.kt` (새) | manifest 로 경계 지은 jsonl 묶음 읽기(내보내기 · 결과 통보 공용) |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/ExportSymptoms.kt` (새) | 내보내기 줄 → `SymptomSignal`(`eventId` 포함). CLI 와 감시자가 함께 쓴다 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/CarriedEvidence.kt` (새) | 결과 통보 줄 → `EvidenceSignal` (`koshei-carry-0`) |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/WatchStore.kt` (새) | 커서 · 감시 기록 · 주문 → 에피소드 인스턴스 찾기 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeSignals.kt` (새) | `EpisodeSignals` 인터페이스와 Temporal 구현 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWatcher.kt` (새) | 한 번의 폴링: 접수와 근거 운반 |
| `episode-runtime/src/main/resources/episode-schema.sql` (고침) | `episode_watch_cursor`, `episode_watch_log`, 의도 주문 색인 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkers.kt` (고침) | `PicassoMode.fromEnv` 를 따로 꺼낸다 |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/MockCarrier.kt` (새) | Mock 담는 쪽 |
| `app/src/main/kotlin/koshei/app/EpisodeCli.kt` (고침) | `ExportSymptoms` · `TemporalEpisodeSignals` 를 쓴다 |
| `app/src/main/kotlin/koshei/app/Watcher.kt` (새) | 감시자 프로세스 `main` 과 설정 |
| `app/build.gradle.kts` (고침) | `watcher` 작업, 환경변수 전달 |
| `authoring-api/src/main/kotlin/koshei/authoring/AutoCorrectGate.kt` (새) | 에피소드 모드에서 drift 자동 디스패치를 막는 판정 |
| `authoring-api/src/main/kotlin/koshei/authoring/AutoCorrectBean.kt`, `AutoCorrectController.kt` (고침) | 판정을 따른다 |

설계 §4.1 은 `EpisodeWatcherMain` 을 `:episode-runtime` 에 두었다. 이 계획은 B3b 의 워커와 같은 모양으로 나눈다: 로직(`EpisodeWatcher`)은 `:episode-runtime`, 프로세스 `main`(환경변수, DB, Temporal 연결)은 `:app`. 문서에 그렇게 적는다(Task 11).

---

## Chunk 1: 병합 키와 묶음 읽기

### Task 1: 병합 규칙 `correlate` (`:episode`)

**Files:**
- Create: `episode/src/main/kotlin/koshei/episode/Correlation.kt`
- Modify: `episode/src/main/kotlin/koshei/episode/Policy.kt` (`PolicyTable.correlation`, `parsePolicy`, `PolicyReader`)
- Test: `episode/src/test/kotlin/koshei/episode/CorrelationTest.kt` (새), `episode/src/test/kotlin/koshei/episode/PolicyTest.kt`

규칙의 모양(정책 YAML):

```yaml
correlation:
  - { kinds: [SEARCH, INCIDENT], by: [robotId, jobOrderId] }
```

- 규칙 하나는 이렇게 읽는다: `kinds` 의 줄 가운데 `by` 의 칸 값이 모두 같은 것은 한 에피소드다.
- 키의 모양:
  - 규칙이 걸리면 `<runId>:by:<f1>:<v1>:<f2>:<v2>…` 이다(규칙의 칸 순서대로).
  - 걸리지 않으면 제 키 `<runId>:<id>` 이다. 개발용 CLI 의 기본 키와 같다.
- 값에는 `:` 가 없어야 한다(`[A-Za-z0-9._-]{1,64}`). 칸 이름은 `[A-Za-z][A-Za-z0-9]{0,63}` 이다. 줄의 id 도 같은 값 문자 집합이어야 한다(제 키). 그래서 한 run 안에서 서로 다른 규칙 · 칸 · 값 · 제 키가 같은 키를 내지 않는다. runId 에는 `:` 가 있을 수 있으므로(picasso `run-<시각>-<n>`) 서로 다른 run 사이의 겹침까지 막지는 않는다 — picasso 의 runId 모양에서는 생기지 않는다.
- 값이 없거나, 글자가 아니거나, 그 문자 집합 밖이면 그 규칙은 걸리지 않는다. 값을 고쳐 쓰지 않는다.
- 걸린 규칙이 둘 이상이고 키가 다르면 「애매함」이다. 묶지 않고 제 키를 쓰며, 걸린 키들을 `related` 로 돌려준다(§12 「서로 참조를 붙인다」).
- 키 전체는 `[A-Za-z0-9._:-]{1,200}` 이어야 한다. 제 키도 맞지 않으면 `null` 이다 — 감시자는 그 줄에서 멈춘다.

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/CorrelationTest.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CorrelationTest {
    private val mapper = ObjectMapper()
    private fun line(json: String): JsonNode = mapper.readTree(json)
    private val run = "run-2026-09-22T16:47:37.854173400Z-1"
    private val search1 = line("""{"searchId":"search-1","robotId":"hum-02","jobOrderId":"PATROL-1"}""")
    private val incident1 = line("""{"incidentId":"incident-1","robotId":"hum-02","jobOrderId":"PATROL-1","digest":"d0"}""")
    private val search2 = line("""{"searchId":"search-2","robotId":"hum-03","jobOrderId":"PATROL-2"}""")
    private val byRobotOrder = CorrelationRule(setOf(SymptomKind.SEARCH, SymptomKind.INCIDENT), listOf("robotId", "jobOrderId"))

    @Test fun `no rule - every symptom has its own key, the CLI's default`() =
        assertEquals(Correlation.Own("$run:search-1", emptyList()), correlate(SymptomKind.SEARCH, search1, run, "search-1", emptyList()))

    @Test fun `a search and an incident of one robot and order share a key`() {
        val a = correlate(SymptomKind.SEARCH, search1, run, "search-1", listOf(byRobotOrder))
        val b = correlate(SymptomKind.INCIDENT, incident1, run, "incident-1", listOf(byRobotOrder))
        assertEquals(Correlation.Merged("$run:by:robotId:hum-02:jobOrderId:PATROL-1"), a)
        assertEquals(a, b)
        assertEquals(Correlation.Merged("$run:by:robotId:hum-03:jobOrderId:PATROL-2"), correlate(SymptomKind.SEARCH, search2, run, "search-2", listOf(byRobotOrder)))
    }

    @Test fun `the run is part of the key - a second run of the same seed is not merged into the first`() {
        val other = correlate(SymptomKind.SEARCH, search1, "run-2", "search-1", listOf(byRobotOrder))
        assertEquals(Correlation.Merged("run-2:by:robotId:hum-02:jobOrderId:PATROL-1"), other)
    }

    @Test fun `a rule for other kinds does not apply`() {
        val searchesOnly = CorrelationRule(setOf(SymptomKind.SEARCH), listOf("robotId"))
        assertEquals(Correlation.Own("$run:incident-1", emptyList()), correlate(SymptomKind.INCIDENT, incident1, run, "incident-1", listOf(searchesOnly)))
    }

    @Test fun `a missing, non-text or unsafe value means the rule does not apply - never rewritten`() {
        val rule = listOf(byRobotOrder)
        listOf(
            """{"searchId":"s","robotId":"hum-02"}""",
            """{"searchId":"s","robotId":"hum-02","jobOrderId":null}""",
            """{"searchId":"s","robotId":"hum-02","jobOrderId":7}""",
            """{"searchId":"s","robotId":"hum-02","jobOrderId":"A:B"}""",
            """{"searchId":"s","robotId":"hum 02","jobOrderId":"P"}""",
            """{"searchId":"s","robotId":"","jobOrderId":"P"}""",
        ).forEach { assertEquals(Correlation.Own("$run:s", emptyList()), correlate(SymptomKind.SEARCH, line(it), run, "s", rule), it) }
    }

    @Test fun `two rules that disagree are ambiguous - not merged, both keys kept as references`() {
        val byRobot = CorrelationRule(setOf(SymptomKind.SEARCH), listOf("robotId"))
        val byOrder = CorrelationRule(setOf(SymptomKind.SEARCH), listOf("jobOrderId"))
        assertEquals(
            Correlation.Own("$run:search-1", listOf("$run:by:robotId:hum-02", "$run:by:jobOrderId:PATROL-1")),
            correlate(SymptomKind.SEARCH, search1, run, "search-1", listOf(byRobot, byOrder)),
        )
    }

    @Test fun `two rules that give the same key merge`() {
        val again = CorrelationRule(setOf(SymptomKind.SEARCH), listOf("robotId", "jobOrderId"))
        assertEquals(
            Correlation.Merged("$run:by:robotId:hum-02:jobOrderId:PATROL-1"),
            correlate(SymptomKind.SEARCH, search1, run, "search-1", listOf(byRobotOrder, again)),
        )
    }

    @Test fun `an own key outside the charset is null - the watcher stops, it does not guess`() {
        assertNull(correlate(SymptomKind.SEARCH, search1, run, "a/b", emptyList()))
        assertNull(correlate(SymptomKind.SEARCH, search1, run, "by:robotId:hum-02", emptyList()), "an id cannot pose as a merged key")
        assertNull(correlate(SymptomKind.SEARCH, search1, "r".repeat(200), "search-1", emptyList()))
    }

    @Test fun `a merged key outside the charset leaves the rule out`() {
        val longRun = "r".repeat(170)
        assertEquals(Correlation.Own("$longRun:search-1", emptyList()), correlate(SymptomKind.SEARCH, search1, longRun, "search-1", listOf(byRobotOrder)))
    }
}
```

`PolicyTest.kt` 에 더한다. 표는 이미 있는 시험 도우미 `Policies`(`episode/src/test/kotlin/koshei/episode/Policies.kt`)로 만든다. 그 표에는 이미 `"correlation": []` 이 있고, `valid { }` · `errors { }` 가 칸 하나를 바꾼 사본을 판정한다(`errors` 는 표가 유효하면 실패한다). `PolicyTest` 에 `import com.fasterxml.jackson.databind.JsonNode`, `import com.fasterxml.jackson.databind.ObjectMapper` 를 더한다.

```kotlin
private fun rules(json: String): JsonNode = ObjectMapper().readTree(json)

@Test fun `correlation rules are read and checked`() {
    val p = Policies.valid { set<JsonNode>("correlation", rules("""[{"kinds":["SEARCH","INCIDENT"],"by":["robotId","jobOrderId"]}]""")) }
    assertEquals(listOf(CorrelationRule(setOf(SymptomKind.SEARCH, SymptomKind.INCIDENT), listOf("robotId", "jobOrderId"))), p.correlation)
}

@Test fun `a malformed correlation rule rejects the table`() {
    listOf(
        """[{"kinds":[],"by":["robotId"]}]""" to "correlation[0].kinds",
        """[{"kinds":["DRIFT"],"by":["robotId"]}]""" to "correlation[0].kinds",
        """[{"kinds":["SEARCH","SEARCH"],"by":["robotId"]}]""" to "correlation[0].kinds",
        """[{"kinds":["SEARCH"],"by":[]}]""" to "correlation[0].by",
        """[{"kinds":["SEARCH"],"by":["a","b","c","d","e"]}]""" to "correlation[0].by",
        """[{"kinds":["SEARCH"],"by":["robot-id"]}]""" to "correlation[0].by",
        """[{"kinds":["SEARCH"],"by":["robotId","robotId"]}]""" to "correlation[0].by",
        """[{"kinds":["SEARCH"],"by":["robotId"],"window":5}]""" to "correlation[0].window",
        """[{"by":["robotId"]}]""" to "correlation[0].kinds",
        """["robotId"]""" to "correlation[0]",
    ).forEach { (json, path) ->
        val errors = Policies.errors { set<JsonNode>("correlation", rules(json)) }
        kotlin.test.assertTrue(errors.any { it.startsWith(path) }, "$json → $errors")
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode:test --tests "koshei.episode.CorrelationTest" --tests "koshei.episode.PolicyTest"`
Expected: 컴파일 실패(`CorrelationRule`, `correlate` 가 없다).

- [ ] **Step 3: 구현한다**

`episode/src/main/kotlin/koshei/episode/Correlation.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.JsonNode

/**
 * One merge rule (design §10.1, §12): symptoms of [kinds] whose lines carry every field of [by] with the same values
 * belong to one episode. A rule never guesses — a missing, non-text or unsafe value means it does not apply.
 */
data class CorrelationRule(val kinds: Set<SymptomKind>, val by: List<String>)

/** What [correlate] decided. The workflow id is `ep:<key>` (design §7.1). */
sealed interface Correlation {
    val key: String

    /** Rules gave exactly one key. */
    data class Merged(override val key: String) : Correlation

    /** No rule applied, or the rules disagreed: then [related] holds every key they gave — the cross-reference of §12. */
    data class Own(override val key: String, val related: List<String>) : Correlation
}

/** An episode key: one path segment of the control plane (design §7.2 구현(B3c)), short enough for a workflow id. */
val CORRELATION_KEY = Regex("[A-Za-z0-9._:-]{1,200}")

/** A field value a key may carry: no `:`, so values and field names can never run into each other. */
private val KEY_VALUE = Regex("[A-Za-z0-9._-]{1,64}")

/** A field name a rule may name. */
internal val RULE_FIELD = Regex("[A-Za-z][A-Za-z0-9]{0,63}")

/** At most this many fields per rule. */
internal const val RULE_MAX_FIELDS = 4

/**
 * The merge key of one picasso export line (design §12) — pure, deterministic, never an LLM. [id] is the line's own id
 * (`searchId` / `incidentId`). The own key is `<runId>:<id>`; one applicable rule gives `<runId>:by:<f1>:<v1>…`;
 * rules that disagree are ambiguous and merge nothing. Null when even the own key is not a valid key (or [id] carries
 * a `:`, so it could pose as a merged key): the caller stops.
 */
fun correlate(kind: SymptomKind, line: JsonNode, runId: String, id: String, rules: List<CorrelationRule>): Correlation? {
    if (!KEY_VALUE.matches(id)) return null
    val own = "$runId:$id".takeIf { CORRELATION_KEY.matches(it) } ?: return null
    val keys = rules.filter { kind in it.kinds }.mapNotNull { rule -> keyOf(rule, line, runId) }.distinct()
    return when (keys.size) {
        0 -> Correlation.Own(own, emptyList())
        1 -> Correlation.Merged(keys.single())
        else -> Correlation.Own(own, keys)
    }
}

private fun keyOf(rule: CorrelationRule, line: JsonNode, runId: String): String? {
    val parts = mutableListOf(runId, "by")
    for (field in rule.by) {
        val value = line.get(field)?.takeIf { it.isTextual }?.textValue()?.takeIf { KEY_VALUE.matches(it) } ?: return null
        parts += field
        parts += value
    }
    return parts.joinToString(":").takeIf { CORRELATION_KEY.matches(it) }
}
```

`Policy.kt`:
- `PolicyTable.correlation` 의 형을 `List<CorrelationRule>` 로 바꾸고 KDoc 을 「Merge rules (design §12), checked」로 고친다.
- `parsePolicy` 의 `val correlation = r.listOrEmpty(top, "correlation", "")` 를 다음으로 바꾼다.

```kotlin
    val correlation = r.listOrEmpty(top, "correlation", "")?.let { nodes ->
        val rules = nodes.mapIndexedNotNull { i, node -> r.correlationRule(node, "correlation[$i]") }
        if (rules.size == nodes.size) rules else null
    }
```

- `PolicyTable(...)` 를 만드는 자리의 `correlation!!.map { it.deepCopy<JsonNode>() }` 를 `correlation!!.toList()` 로 바꾼다.
- `PolicyReader` 에 더한다.

```kotlin
    fun correlationRule(node: JsonNode, path: String): CorrelationRule? {
        if (!node.isObject) { errors += "$path: expected an object { kinds, by }"; return null }
        val before = errors.size
        obj(node, path, setOf("kinds", "by"))
        val kinds = textList(node, "kinds", path)
        val by = textList(node, "by", path)
        if (kinds != null) {
            if (kinds.isEmpty()) errors += "$path.kinds: name at least one of ${SymptomKind.entries.map { it.name }}"
            kinds.filter { k -> SymptomKind.entries.none { it.name == k } }.forEach { errors += "$path.kinds: unknown kind '$it'" }
            if (kinds.toSet().size != kinds.size) errors += "$path.kinds: a kind is repeated"
        }
        if (by != null) {
            if (by.isEmpty() || by.size > RULE_MAX_FIELDS) errors += "$path.by: 1 to $RULE_MAX_FIELDS field names"
            by.filterNot { RULE_FIELD.matches(it) }.forEach { errors += "$path.by: '$it' is not a field name" }
            if (by.toSet().size != by.size) errors += "$path.by: a field is repeated"
        }
        if (errors.size != before || kinds == null || by == null) return null
        return CorrelationRule(kinds.map { SymptomKind.valueOf(it) }.toSet(), by)
    }
```

`obj` 는 모르는 키를 `"$path.$it: unknown key"` 로 알린다(path 가 `"policy"` 가 아니므로). `textList` 는 빈 목록을 받아들이므로 위에서 따로 막는다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode:test`
Expected: 모두 PASS. 원래 296개에 더한 수를 보고한다. `PolicyTest` 의 `assertEquals(emptyList(), p.correlation)` 은 그대로 통과한다.

- [ ] **Step 5: 커밋한다**

```bash
git add episode/src/main/kotlin/koshei/episode/Correlation.kt episode/src/main/kotlin/koshei/episode/Policy.kt episode/src/test/kotlin/koshei/episode/CorrelationTest.kt episode/src/test/kotlin/koshei/episode/PolicyTest.kt
git commit -m "feat(episode): correlate - deterministic merge keys from checked policy rules; disagreeing rules merge nothing" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: manifest 로 경계 지은 묶음 읽기 (`Bundles`)

**Files:**
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/Bundles.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/BundlesTest.kt`

picasso `BundleWriter`(picasso `src/test/.../BundleWriter.kt:36`, `8ab5400`)의 쓰는 순서를 기준으로 짠다.
- 줄 파일들을 먼저 쓰고 `manifest.json` 을 마지막에 쓴다. 파일마다 `<name>.tmp` 에 쓴 뒤 `ATOMIC_MOVE` 로 바꾼다.
- 살아 있는 호스트는 줄이 늘 때마다 세 파일을 통째로 다시 쓴다. 내용은 추가 전용이고 runId 는 같다.

그러므로 읽는 쪽은 이렇게 한다.
1. manifest 를 읽는다.
2. 줄 파일을 읽는다. 파일은 manifest 보다 새것일 수 있으므로 `counts` 만큼만 쓴다.
3. manifest 를 다시 읽어 글자가 같은지 본다.

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode-runtime/src/test/kotlin/koshei/episode/runtime/BundlesTest.kt`:

```kotlin
package koshei.episode.runtime

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BundlesTest {
    @TempDir lateinit var dir: Path
    private val files = listOf(BundleFile("a.jsonl", "a", "aId"), BundleFile("b.jsonl", "b", "bId"))

    private fun manifest(a: Int, b: Int, runId: String? = "run-1", schema: String = "5") =
        Files.writeString(dir.resolve("manifest.json"),
            """{"schemaVersion":"$schema",${runId?.let { "\"runId\":\"$it\"," } ?: ""}"counts":{"a":$a,"b":$b}}""")

    private fun lines(name: String, vararg lines: String) = Files.writeString(dir.resolve(name), lines.joinToString("\n", postfix = "\n"))

    private fun read(unique: Boolean = true, readText: (Path) -> String = Files::readString) =
        Bundles.read(dir, "runId", setOf("5"), files, unique, readText)

    @Test fun `nothing is read before the manifest is there`() {
        lines("a.jsonl", """{"aId":"a-1"}""")
        assertIs<BundleRead.NotReady>(read())
    }

    @Test fun `lines are bounded by the manifest's counts - later lines belong to the next snapshot`() {
        lines("a.jsonl", """{"aId":"a-1"}""", """{"aId":"a-2"}""", """{"aId":"a-3"}""")
        lines("b.jsonl")
        manifest(a = 2, b = 0)
        val r = assertIs<BundleRead.Ready>(read())
        assertEquals("run-1", r.runId)
        assertEquals(listOf("a-1", "a-2"), r.lines.getValue("a.jsonl").map { it.id })
        assertEquals(listOf(1, 2), r.lines.getValue("a.jsonl").map { it.position })
        assertEquals("""{"aId":"a-2"}""", r.lines.getValue("a.jsonl")[1].text)
        assertEquals(emptyList(), r.lines.getValue("b.jsonl"))
    }

    @Test fun `a file absent with a zero count is empty, absent with lines counted is broken`() {
        lines("a.jsonl", """{"aId":"a-1"}""")
        manifest(a = 1, b = 0)
        assertIs<BundleRead.Ready>(read())
        manifest(a = 1, b = 1)
        assertIs<BundleRead.Broken>(read())
    }

    @Test fun `fewer lines than counted is not ready yet`() {
        lines("a.jsonl", """{"aId":"a-1"}""")
        lines("b.jsonl")
        manifest(a = 2, b = 0)
        assertIs<BundleRead.NotReady>(read())
    }

    @Test fun `a manifest that changed while the lines were read is read again later`() {
        lines("a.jsonl", """{"aId":"a-1"}""")
        lines("b.jsonl")
        manifest(a = 1, b = 0)
        var manifestReads = 0
        val r = read(readText = { p ->
            val text = Files.readString(p)
            if (p.fileName.toString() == "manifest.json" && ++manifestReads == 2) text.replace("run-1", "run-2") else text
        })
        assertIs<BundleRead.NotReady>(r)
    }

    @Test fun `what cannot be read is broken, never skipped`() {
        lines("b.jsonl")
        fun broken(a: String, why: String) {
            lines("a.jsonl", a)
            manifest(a = 1, b = 0)
            val r = read()
            assertIs<BundleRead.Broken>(r, a)
            assertTrue(why in r.why, "${r.why} should name $why")
        }
        broken("""{"aId":"a-1","aId":"a-2"}""", "line 1")   // duplicate key
        broken("""["a-1"]""", "line 1")
        broken("""{"aId":7}""", "aId")
        broken("""{"aId":""}""", "aId")
        broken("\uFEFF{\"aId\":\"a-1\"}", "BOM")
        broken("{nope", "line 1")
        broken("""{"aId":"a-1"} trailing""", "line 1")
    }

    @Test fun `a manifest with a BOM is named`() {
        lines("a.jsonl"); lines("b.jsonl")
        Files.writeString(dir.resolve("manifest.json"), Char(0xFEFF) + """{"schemaVersion":"5","runId":"r","counts":{"a":0,"b":0}}""")
        val r = read()
        assertIs<BundleRead.Broken>(r)
        assertTrue("starts with a UTF-8 BOM" in r.why, r.why)
    }

    @Test fun `bytes that are not UTF-8 are broken, not waited for`() {
        Files.write(dir.resolve("a.jsonl"), byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte(), 0x0A))
        lines("b.jsonl")
        manifest(a = 1, b = 0)
        assertIs<BundleRead.Broken>(read())
    }

    @Test fun `a manifest without run id, counts or a known schema is broken`() {
        lines("a.jsonl"); lines("b.jsonl")
        manifest(a = 0, b = 0, runId = null); assertIs<BundleRead.Broken>(read())
        manifest(a = 0, b = 0, runId = " "); assertIs<BundleRead.Broken>(read())
        manifest(a = 0, b = 0, schema = "6"); assertIs<BundleRead.Broken>(read())
        Files.writeString(dir.resolve("manifest.json"), """{"schemaVersion":"5","runId":"r","counts":{"a":-1,"b":0}}""")
        assertIs<BundleRead.Broken>(read())
        Files.writeString(dir.resolve("manifest.json"), """{"schemaVersion":"5","runId":"r","counts":{"a":0}}""")
        assertIs<BundleRead.Broken>(read())
        Files.writeString(dir.resolve("manifest.json"), """{"schemaVersion":"5","runId":"r","runId":"s","counts":{"a":0,"b":0}}""")
        assertIs<BundleRead.Broken>(read())
    }

    @Test fun `repeated ids are broken when ids must be unique, kept when they may repeat`() {
        lines("a.jsonl", """{"aId":"a-1"}""", """{"aId":"a-1"}""")
        lines("b.jsonl")
        manifest(a = 2, b = 0)
        assertIs<BundleRead.Broken>(read(unique = true))
        assertEquals(listOf("a-1", "a-1"), assertIs<BundleRead.Ready>(read(unique = false)).lines.getValue("a.jsonl").map { it.id })
    }

    @Test fun `the manifest travels as written`() {
        lines("a.jsonl"); lines("b.jsonl")
        manifest(a = 0, b = 0)
        assertEquals(Files.readString(dir.resolve("manifest.json")), assertIs<BundleRead.Ready>(read()).manifestJson)
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.BundlesTest"`
Expected: 컴파일 실패(`Bundles` 가 없다).

- [ ] **Step 3: 구현한다**

`episode-runtime/src/main/kotlin/koshei/episode/runtime/Bundles.kt`:

```kotlin
package koshei.episode.runtime

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** One jsonl file of a bundle: its [name], its count's key in the manifest's `counts`, and the field that names a line. */
data class BundleFile(val name: String, val countKey: String, val idField: String)

/** One line as read: [position] is 1-based among the file's non-blank lines; [text] is the line exactly as written. */
class BundleLine(val position: Int, val id: String, val text: String, val node: JsonNode)

sealed interface BundleRead {
    /** [lines] per file name, bounded by the manifest's counts. [manifestJson] is the manifest exactly as written. */
    data class Ready(val runId: String, val manifestJson: String, val lines: Map<String, List<BundleLine>>) : BundleRead

    /** Not there yet, or being rewritten: read again at the next poll. */
    data class NotReady(val why: String) : BundleRead

    /** Cannot be read as it is: stop and say so — never skip, never guess (design §12). */
    data class Broken(val why: String) : BundleRead
}

/**
 * Reads a bundle written the picasso way (picasso `BundleWriter`): jsonl files first, `manifest.json` last, each by
 * rename. A live host rewrites the files whole as lines are added, so a file may be newer than the manifest: only the
 * first `counts.<key>` lines are taken, and the manifest is read again at the end — if it changed, the bundle is read
 * again later. JSON is read as strictly as the core reads it (duplicate keys refused, BOM refused, objects only).
 */
object Bundles {
    const val MANIFEST = "manifest.json"
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** The module's strict mapper, and no trailing tokens: one line is one JSON object and nothing after it. */
    private val reader = strictJson.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun read(
        dir: Path,
        runIdField: String,
        schemaVersions: Set<String>,
        files: List<BundleFile>,
        uniqueIds: Boolean,
        readText: (Path) -> String = Files::readString,
    ): BundleRead = try {
        readBundle(dir, runIdField, schemaVersions, files, uniqueIds, readText)
    } catch (e: CharacterCodingException) {
        // Bytes that are not UTF-8 will not become readable by waiting: broken, not "not ready".
        BundleRead.Broken("$dir: a file is not UTF-8 (${e.javaClass.simpleName})")
    }

    private fun readBundle(
        dir: Path,
        runIdField: String,
        schemaVersions: Set<String>,
        files: List<BundleFile>,
        uniqueIds: Boolean,
        readText: (Path) -> String,
    ): BundleRead {
        val manifestFile = dir.resolve(MANIFEST)
        val manifestJson = text(manifestFile, readText) ?: return BundleRead.NotReady("no $MANIFEST in $dir")
        if (manifestJson.startsWith(Char(0xFEFF))) return BundleRead.Broken("$MANIFEST in $dir starts with a UTF-8 BOM")
        val manifest = parse(manifestJson) ?: return BundleRead.Broken("$MANIFEST in $dir is not one strict JSON object")
        val schema = manifest.textOf("schemaVersion")
        if (schema !in schemaVersions) return BundleRead.Broken("$MANIFEST in $dir: schemaVersion $schema, this reader knows $schemaVersions")
        val runId = manifest.textOf(runIdField) ?: return BundleRead.Broken("$MANIFEST in $dir has no $runIdField")
        val counts = manifest.get("counts")?.takeIf { it.isObject } ?: return BundleRead.Broken("$MANIFEST in $dir has no counts")

        val lines = LinkedHashMap<String, List<BundleLine>>()
        for (file in files) {
            val count = counts.get(file.countKey)?.takeIf { it.isInt && it.intValue() >= 0 }?.intValue()
                ?: return BundleRead.Broken("$MANIFEST in $dir: counts.${file.countKey} is not a count")
            val path = dir.resolve(file.name)
            val fileText = when {
                count == 0 && !Files.exists(path) -> ""
                else -> {
                    val size = try { Files.size(path) } catch (e: NoSuchFileException) {
                        return BundleRead.Broken("${file.name} in $dir: the manifest counts $count lines, there is no file")
                    } catch (e: IOException) {
                        return BundleRead.NotReady("${file.name} in $dir: ${e.javaClass.simpleName}")   // e.g. denied during the writer's rename
                    }
                    if (size > MAX_FILE_BYTES) return BundleRead.Broken("${file.name} in $dir: $size bytes, over $MAX_FILE_BYTES")
                    text(path, readText) ?: return BundleRead.NotReady("${file.name} in $dir vanished while reading")
                }
            }
            val raw = fileText.lines().filter { it.isNotBlank() }
            if (raw.size < count) return BundleRead.NotReady("${file.name} in $dir has ${raw.size} lines, the manifest counts $count")
            val read = ArrayList<BundleLine>(count)
            val seen = HashSet<String>()
            for ((i, line) in raw.take(count).withIndex()) {
                val where = "${file.name} in $dir line ${i + 1}"
                if (line.startsWith('\uFEFF')) return BundleRead.Broken("$where starts with a UTF-8 BOM")
                val node = parse(line) ?: return BundleRead.Broken("$where is not one strict JSON object")
                val id = node.textOf(file.idField) ?: return BundleRead.Broken("$where has no ${file.idField}")
                if (uniqueIds && !seen.add(id)) return BundleRead.Broken("$where repeats ${file.idField} '$id'")
                read += BundleLine(i + 1, id, line, node)
            }
            lines[file.name] = read
        }
        if (text(manifestFile, readText) != manifestJson) return BundleRead.NotReady("$MANIFEST in $dir changed while reading")
        return BundleRead.Ready(runId, manifestJson, lines)
    }

    /** The file's text; null when it is absent or momentarily unreadable (read again later). Bad UTF-8 is thrown on. */
    private fun text(path: Path, readText: (Path) -> String): String? = try {
        readText(path)
    } catch (e: CharacterCodingException) {
        throw e
    } catch (e: IOException) {
        null
    }

    private fun parse(text: String): JsonNode? {
        val node = try { reader.readTree(text) } catch (e: JsonProcessingException) { return null }
        return node?.takeIf { it.isObject }
    }

    private fun JsonNode.textOf(field: String): String? = get(field)?.takeIf { it.isTextual }?.textValue()?.takeIf { it.isNotBlank() }
}
```

주의:
- 깨진 줄의 사유에 `line 1` 이 들어가야 한다(시험이 본다). `Broken` 사유는 `where` 로 시작한다.
- BOM 의 사유는 「starts with a UTF-8 BOM」이다(manifest 와 줄 모두). 이 검사가 파싱보다 앞이다. CLI 시험 `a BOM is named, and refused` 가 이 글자를 본다.
- UTF-8 이 아닌 바이트(`MalformedInputException`, `CharacterCodingException` 의 하위)는 `Broken` 이다 — 기다려도 읽히지 않는다.
- 그 밖의 `IOException` 은 `null`(NotReady)이다. 쓰는 쪽이 이름을 바꾸는 사이의 일시 오류(Windows 의 접근 거부 등) 때문이다. 오래 이어지면 감시자가 같은 사유를 한 번 기록한다(Task 6).
- 줄 뒤에 남은 토큰(`{"a":1} x`)은 깨진 줄이다(`FAIL_ON_TRAILING_TOKENS`). 공용 `strictJson` 은 바꾸지 않고 이 파일 안의 reader 만 쓴다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.BundlesTest"`
Expected: 11개 PASS

- [ ] **Step 5: 커밋한다**

```bash
git add episode-runtime/src/main/kotlin/koshei/episode/runtime/Bundles.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/BundlesTest.kt
git commit -m "feat(episode-runtime): read manifest-bounded jsonl bundles the way picasso writes them; what cannot be read is broken, not skipped" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 3: 내보내기 줄 → 증상 (`ExportSymptoms`), CLI 가 이것을 쓴다

**Files:**
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/ExportSymptoms.kt`
- Modify: `app/src/main/kotlin/koshei/app/EpisodeCli.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/ExportSymptomsTest.kt`, `app/src/test/kotlin/koshei/app/EpisodeCliTest.kt`

`eventId` 와 증상 신호의 모양은 지금 `EpisodeCli.symptom` 안에 있다. 감시자도 같은 재료를 써야 하므로(§7.2 구현(B3c), §12) `:episode-runtime` 으로 옮기고 CLI 는 그것을 부른다.

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode-runtime/src/test/kotlin/koshei/episode/runtime/ExportSymptomsTest.kt`:

```kotlin
package koshei.episode.runtime

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ExportSymptomsTest {
    private val run1: Path = Path.of(checkNotNull(javaClass.getResource("/picasso/run-1/manifest.json")).toURI()).parent
    private val runId = "run-2026-09-22T16:47:37.854173400Z-1"

    @Test fun `run-1 reads as nine incidents and four searches`() {
        val r = assertIs<BundleRead.Ready>(ExportSymptoms.read(run1))
        assertEquals(runId, r.runId)
        assertEquals(9, r.lines.getValue(ExportKind.INCIDENT.file.name).size)
        assertEquals(4, r.lines.getValue(ExportKind.SEARCH.file.name).size)
    }

    @Test fun `a search is keyed by run and searchId, an incident by run and digest`() {
        val r = assertIs<BundleRead.Ready>(ExportSymptoms.read(run1))
        val search = r.lines.getValue(ExportKind.SEARCH.file.name).first()
        val s = ExportSymptoms.symptom(ExportKind.SEARCH, r, search, observedAtMillis = 7)!!
        assertEquals("search:$runId:search-1", s.eventId)
        assertEquals("SEARCH", s.kind)
        assertEquals(search.text, s.lineJson)
        assertEquals(r.manifestJson, s.manifestJson)
        assertEquals(7, s.observedAtMillis)
        val incident = r.lines.getValue(ExportKind.INCIDENT.file.name).first()
        assertEquals("incident:$runId:d04ac2a20a63afc2ba147218bae871ef69746c990e3993deb00cfb4ddd262143",
            ExportSymptoms.symptom(ExportKind.INCIDENT, r, incident, 0)!!.eventId)
    }

    @Test fun `an incident without a digest has no symptom`() {
        val line = BundleLine(1, "incident-x", """{"incidentId":"incident-x"}""", strictJson.readTree("""{"incidentId":"incident-x"}"""))
        val r = BundleRead.Ready("run", "{}", emptyMap())
        assertNull(ExportSymptoms.symptom(ExportKind.INCIDENT, r, line, 0))
    }
}
```

`episode-runtime/src/test/resources/picasso/run-1` 에 `incidents.jsonl` 이 있는지 먼저 확인한다(있다고 보고됐다). 없으면 `episode/src/test/resources/picasso/run-1/incidents.jsonl` 를 바이트 그대로 복사해 넣는다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.ExportSymptomsTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현한다**

`episode-runtime/src/main/kotlin/koshei/episode/runtime/ExportSymptoms.kt`:

```kotlin
package koshei.episode.runtime

import java.nio.file.Path

/** The two line kinds of a picasso export (design §12). [name] is the `SymptomSignal.kind`. */
enum class ExportKind(val file: BundleFile) {
    INCIDENT(BundleFile("incidents.jsonl", "incidents", "incidentId")),
    SEARCH(BundleFile("remedy-searches.jsonl", "remedySearches", "searchId")),
}

/**
 * A picasso export as symptoms — the one place that says how a line becomes a `symptom` signal, shared by the dev CLI
 * and the watcher so both send the same `eventId` for the same line (design §7.2 구현(B3c), §12).
 */
object ExportSymptoms {
    /** picasso `LedgerExport.SCHEMA_VERSION` this reader was written against (picasso `8ab5400`). */
    val SCHEMA_VERSIONS = setOf("5")

    /** Incidents first, then searches: the order picasso writes them, and the order the watcher sends them. */
    fun read(dir: Path): BundleRead =
        Bundles.read(dir, "runId", SCHEMA_VERSIONS, ExportKind.entries.map { it.file }, uniqueIds = true)

    /** `search:<runId>:<searchId>`, `incident:<runId>:<digest>`. Null for an incident without a digest: never guessed. */
    fun eventId(kind: ExportKind, runId: String, line: BundleLine): String? = when (kind) {
        ExportKind.SEARCH -> "search:$runId:${line.id}"
        ExportKind.INCIDENT -> line.node.get("digest")?.takeIf { it.isTextual }?.textValue()?.takeIf { it.isNotBlank() }
            ?.let { "incident:$runId:$it" }
    }

    /** The `symptom` signal for [line]: the line and the manifest exactly as written (design §7.1 구현(B3b)). */
    fun symptom(kind: ExportKind, export: BundleRead.Ready, line: BundleLine, observedAtMillis: Long): SymptomSignal? =
        eventId(kind, export.runId, line)?.let { SymptomSignal(it, kind.name, line.text, observedAtMillis, export.manifestJson) }
}
```

`EpisodeCli.kt` 를 고친다.
- `Kind` enum 을 지우고 `ExportKind` 를 쓴다(`OpenArgs.kind: ExportKind`). `parseOpen` 에서 `--search` 는 `ExportKind.SEARCH`, `--incident` 는 `ExportKind.INCIDENT` 다.
- `symptom(open)` 은 이렇게 바뀐다.

```kotlin
    fun symptom(open: OpenArgs): OpenSymptom {
        val export = when (val r = ExportSymptoms.read(open.export)) {
            is BundleRead.Ready -> r
            is BundleRead.NotReady -> throw IllegalArgumentException("${r.why} — an export is read only once its manifest is there and complete")
            is BundleRead.Broken -> throw IllegalArgumentException(r.why)
        }
        val line = export.lines.getValue(open.kind.file.name).singleOrNull { it.id == open.id }
            ?: throw IllegalArgumentException("no ${open.kind.file.idField} '${open.id}' in ${open.kind.file.name}")
        val signal = ExportSymptoms.symptom(open.kind, export, line, System.currentTimeMillis())
            ?: throw IllegalArgumentException("${open.id} has no digest — refusing to guess its eventId")
        val key = open.key ?: "${export.runId}:${open.id}".also {
            require(KEY.matches(it)) { "the default key '$it' (<runId>:<id>) does not match ${KEY.pattern}; pass --key <correlationKey>" }
        }
        return OpenSymptom(workflowId = PREFIX + key, start = EpisodeStart(export.manifestJson), signal = signal)
    }
```

- 쓰지 않게 된 `json`, `BOM`, `parse`, `text` 를 지운다.
- import 를 정리한다: `BundleRead`, `ExportKind`, `ExportSymptoms` 를 더하고, 쓰지 않게 된 `JsonParser`, `JsonProcessingException`, `JsonNode`, `jacksonObjectMapper`, `Files` 를 지운다.
- KDoc(「One malformed line anywhere in the jsonl blocks the open」 부근)을 고친다: 내보내기는 감시자와 같은 `ExportSymptoms` 로 읽는다. 그래서 manifest 의 `schemaVersion "5"` 와 `counts` 가 있어야 하고, `counts` 뒤의 줄은 읽지 않으며, 두 파일 어디든 읽지 못하는 줄이 있으면 열지 않는다(`--search` 라도 사건 파일의 깨진 줄이 막는다).
- `EpisodeCliTest` 의 손으로 쓴 manifest 에는 `schemaVersion` 은 있지만 `counts` 가 없다. 그대로면 「has no counts」로 거절되어, 보려던 사유가 아닌 것으로 통과하거나 실패한다. 이렇게 고친다.
  - `exportOf` 의 manifest 기본값을 줄 수로 만든다. `manifest: String? = null` 로 두고, null 이면 `{"schemaVersion":"5","runId":"r1","counts":{"incidents":${incidents.size},"remedySearches":${searches.size}}}` 를 쓴다. 지금 `exportOf(null)` 로 「manifest 없음」을 뜻하는 호출은 없다(그 시험은 제 디렉터리를 만든다).
  - 글자로 준 manifest 셋에 `counts` 를 더한다: `the line and the manifest travel as written`(searches 2, incidents 0 — 이 시험은 manifest 가 글자 그대로 오는지 보므로 공백 모양은 그대로 둔다), `a run id the key cannot carry needs a key`(searches 1), `a blank run id is refused`(searches 1).
  - `duplicate keys are refused` 의 두 번째 manifest(키 중복)와 `a BOM is named, and refused` 의 manifest(BOM)는 고치지 않는다 — 각각 파싱과 BOM 검사에서 먼저 거절된다. BOM 시험은 「starts with a UTF-8 BOM」 글자를 본다. `Bundles` 가 manifest 와 줄 모두 그 글자로 거절하는지 확인한다.
  - 거절의 종류(`IllegalArgumentException`)는 그대로다.
- 「두 줄이 같은 id」는 이제 `Bundles` 가 `repeats searchId` 로 거절한다. 그 파일의 다른 줄을 고를 때도 거절된다 — 감시자와 같은 규칙이다. 보고에 적는다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.ExportSymptomsTest" :app:test`
Expected: ExportSymptomsTest 3개 PASS, `:app` 30개 PASS(시험을 더하거나 뺐다면 수를 보고한다)

- [ ] **Step 5: 커밋한다**

```bash
git add episode-runtime/src/main/kotlin/koshei/episode/runtime/ExportSymptoms.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/ExportSymptomsTest.kt app/src/main/kotlin/koshei/app/EpisodeCli.kt app/src/test/kotlin/koshei/app/EpisodeCliTest.kt
git commit -m "refactor(episode-runtime): one place turns an export line into a symptom; the dev CLI reads exports through it" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Chunk 2: 감시 표와 신호

### Task 4: 감시 표와 `WatchStore`

**Files:**
- Modify: `episode-runtime/src/main/resources/episode-schema.sql`
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/WatchStore.kt`
- Modify: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeDb.kt` (TRUNCATE 목록)
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/WatchStoreTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package koshei.episode.runtime

import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WatchStoreTest {
    private lateinit var events: EpisodeStore
    private lateinit var watch: WatchStore

    @BeforeEach fun up() {
        events = EpisodeDb.reset()
        watch = WatchStore(EpisodeDb::connection)
    }

    @Test fun `a cursor is per source and run, and moves forward by appending`() {
        assertNull(watch.cursor("dir#incidents.jsonl", "run-1"))
        watch.advance("dir#incidents.jsonl", "run-1", 1, "incident-1")
        watch.advance("dir#incidents.jsonl", "run-1", 2, "incident-2")
        assertEquals(WatchCursor(2, "incident-2"), watch.cursor("dir#incidents.jsonl", "run-1"))
        assertNull(watch.cursor("dir#incidents.jsonl", "run-2"))
        assertNull(watch.cursor("dir#remedy-searches.jsonl", "run-1"))
    }

    @Test fun `advancing to the same place twice is one row; a different id there is refused`() {
        watch.advance("s", "r", 1, "a")
        watch.advance("s", "r", 1, "a")   // a retry after a crash between the signal and the cursor
        assertFailsWith<IllegalStateException> { watch.advance("s", "r", 1, "b") }
        assertEquals(WatchCursor(1, "a"), watch.cursor("s", "r"))
    }

    @Test fun `the watch log keeps what was not carried`() {
        watch.log("s", "STUCK", """{"why":"x"}""")
        assertEquals(listOf("STUCK"), watch.logs(10).map { it.kind })
        assertEquals("x", strictJson.readTree(watch.logs(10).single().detailJson).get("why").textValue())
    }

    @Test fun `an order finds the episode instances whose intent named it`() {
        val intent = { order: String, kind: String -> """{"candidate":{"candidateId":"c","kind":"$kind","ref":{"robotId":"hum-02","jobOrderId":"$order","searchId":"search-1"},"sawSkillTypes":["pick_place"]},"idempotencyKey":"k","approval":null,"targetUnit":null}""" }
        events.append("ep:a/r1", 1, "OPENED", "{}")
        events.append("ep:a/r1", 7, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1", "APPROVE_REMEDY"))
        events.append("ep:b/r2", 5, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1", "APPROVE_REMEDY"))
        events.append("ep:c/r3", 5, EpisodeActivitiesImpl.INTENT_KIND, intent("SEQ-3", "APPROVE_REMEDY"))
        events.append("ep:d/r4", 5, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1", "CHOOSE_SOURCE"))   // a person task: no executionId
        events.append("ep:a/r1", 9, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1", "APPROVE_REMEDY"))   // a second attempt
        assertEquals(listOf("ep:a/r1", "ep:b/r2"), watch.instancesForOrder("PATROL-1"))
        assertEquals(emptyList(), watch.instancesForOrder("NOPE"))
    }
}
```

`OPENED` 의 페이로드 `{}` 가 `append` 를 지나는지 확인한다(검사가 없으면 지난다).

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.WatchStoreTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현한다**

`episode-schema.sql` 끝에 더한다.

```sql
-- The watcher's place in each source (design §12, plan C1): per (source, run), how many lines it has carried and the id
-- of the last one. Append-only like the rest: moving forward adds a row; the cursor is the row with the highest position.
-- A row at an existing position must name the same id (a retry) — anything else means the source changed under us.
CREATE TABLE IF NOT EXISTS episode_watch_cursor (
    source              text        NOT NULL,
    run_id              text        NOT NULL,
    position            integer     NOT NULL CHECK (position > 0),
    last_id             text        NOT NULL,
    at                  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source, run_id, position)
);

-- What the watcher did not carry, or carried without merging (design §12): BROKEN, STUCK, AMBIGUOUS, UNROUTED,
-- ROUTE_ENDED, SIGNAL_FAILED, POLICY_UNUSABLE. Read by people (plan D shows it).
CREATE TABLE IF NOT EXISTS episode_watch_log (
    id                  bigserial   PRIMARY KEY,
    source              text        NOT NULL,
    kind                text        NOT NULL,
    detail              jsonb       NOT NULL,
    at                  timestamptz NOT NULL DEFAULT now()
);

-- A JobResponse finds its episode by the order its dispatch intent named (design §12). On an existing database the first
-- start after this line builds the index and holds writes to episode_event meanwhile — fine at PoC scale.
CREATE INDEX IF NOT EXISTS episode_event_intent_order
    ON episode_event ((payload -> 'candidate' -> 'ref' ->> 'jobOrderId'))
    WHERE kind = 'DISPATCH_INTENT';
```

`'DISPATCH_INTENT'` 이 `EpisodeActivitiesImpl.INTENT_KIND` 와 같은 글자인지 확인한다.

`WatchStore.kt`:

```kotlin
package koshei.episode.runtime

import java.sql.Connection
import java.time.Instant

/** Where the watcher is in one (source, run): [position] lines carried, the last one named [lastId]. */
data class WatchCursor(val position: Int, val lastId: String)

/** One row of the watch log. */
data class WatchLogRow(val id: Long, val source: String, val kind: String, val detailJson: String, val at: Instant)

/** The watcher's tables (design §12): its cursor, its log, and the one read it makes of the episode records. */
open class WatchStore(private val connect: () -> Connection) {

    fun cursor(source: String, runId: String): WatchCursor? = connect().use { c ->
        c.prepareStatement(
            "SELECT position, last_id FROM episode_watch_cursor WHERE source = ? AND run_id = ? ORDER BY position DESC LIMIT 1",
        ).use { s ->
            s.setString(1, source); s.setString(2, runId)
            s.executeQuery().use { rs -> if (rs.next()) WatchCursor(rs.getInt(1), rs.getString(2)) else null }
        }
    }

    /** Moves the cursor to [position] / [lastId]. The same place again is a no-op; a different id there is refused. */
    open fun advance(source: String, runId: String, position: Int, lastId: String) = connect().use { c ->
        val written = c.prepareStatement(
            "INSERT INTO episode_watch_cursor (source, run_id, position, last_id) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        ).use { s ->
            s.setString(1, source); s.setString(2, runId); s.setInt(3, position); s.setString(4, lastId)
            s.executeUpdate() == 1
        }
        if (!written) c.prepareStatement(
            "SELECT last_id FROM episode_watch_cursor WHERE source = ? AND run_id = ? AND position = ?",
        ).use { s ->
            s.setString(1, source); s.setString(2, runId); s.setInt(3, position)
            s.executeQuery().use { rs ->
                check(rs.next() && rs.getString(1) == lastId) { "$source $runId #$position already names a different line" }
            }
        }
    }

    fun log(source: String, kind: String, detailJson: String) = connect().use { c ->
        c.prepareStatement("INSERT INTO episode_watch_log (source, kind, detail) VALUES (?, ?, ?::jsonb)").use { s ->
            s.setString(1, source); s.setString(2, kind); s.setString(3, detailJson)
            s.executeUpdate()
        }
        Unit
    }

    /** Newest first. */
    fun logs(limit: Int): List<WatchLogRow> = connect().use { c ->
        c.prepareStatement("SELECT id, source, kind, detail::text, at FROM episode_watch_log ORDER BY id DESC LIMIT ?").use { s ->
            s.setInt(1, limit)
            s.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) WatchLogRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant())
                    else null
                }.toList()
            }
        }
    }

    /**
     * Every episode instance with a remedy-approval intent for [jobOrderId], in the order their first such intent was
     * written. Only APPROVE_REMEDY: only a remedy approval gets an `executionId` that a JobResponse can link to (§12);
     * a person task (CHOOSE_SOURCE also names the order) would only record the report and hide that it went nowhere.
     */
    fun instancesForOrder(jobOrderId: String): List<String> = connect().use { c ->
        c.prepareStatement(
            """
            SELECT episode_instance_id FROM episode_event
             WHERE kind = 'DISPATCH_INTENT' AND payload -> 'candidate' -> 'ref' ->> 'jobOrderId' = ?
               AND payload -> 'candidate' ->> 'kind' = 'APPROVE_REMEDY'
             GROUP BY episode_instance_id ORDER BY min(at), episode_instance_id
            """.trimIndent(),
        ).use { s ->
            s.setString(1, jobOrderId)
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }
    }
}
```

`min(at)` 이 같은 행이 있을 수 있으므로 인스턴스 id 로 한 번 더 정렬한다. 시험의 두 행은 다른 문장에서 써서 `at` 이 다르다. 그래도 순서가 흔들리면 시험에서 순서를 보지 말고 집합으로 견준다 — 보내는 순서는 판정에 쓰이지 않는다.

`EpisodeDb.reset` 의 `TRUNCATE` 에 `episode_watch_cursor, episode_watch_log` 를 더한다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.WatchStoreTest" --tests "koshei.episode.runtime.EpisodeStoreTest"`
Expected: 모두 PASS. 스키마는 `reset` 이 두 번 돌리므로 두 번 돌아도 안전한지 함께 확인된다.

- [ ] **Step 5: 커밋한다**

```bash
git add episode-runtime/src/main/resources/episode-schema.sql episode-runtime/src/main/kotlin/koshei/episode/runtime/WatchStore.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/WatchStoreTest.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeDb.kt
git commit -m "feat(episode-runtime): watcher tables - an append-only cursor per source and run, a log, and the order-to-episode lookup" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 5: `EpisodeSignals` — 감시자와 CLI 가 Temporal 에 보내는 길

**Files:**
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeSignals.kt`
- Modify: `app/src/main/kotlin/koshei/app/EpisodeCli.kt` (`open` 이 이것을 쓴다)
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeSignalsTest.kt`

근거는 **인스턴스**에 보낸다. 워크플로 id 에 보내면 그 id 의 지금 run 이 받는다. 앞 에피소드(실행 의도를 쓴 인스턴스)가 끝난 뒤 같은 키로 새 에피소드가 열렸으면, 늦은 결과 통보가 새 에피소드의 기록에 섞이고 `ROUTE_ENDED` 는 남지 않는다. 제어면이 Update 에 쓰는 방법(`EpisodeGateway.current` · `send`, authoring-api)을 그대로 따른다.
1. describe 로 지금 run 을 묻는다. 없거나 RUNNING 이 아니면 `false` 다.
2. 그 run 에 고정한 stub 으로 `view()` 를 묻는다. `instanceId` 가 다르면 `false` 다.
3. 같은 stub 으로 signal 을 보낸다. 그 사이 run 이 끝났으면 `WorkflowNotFoundException` 이므로 `false` 다.

run id 를 인스턴스 id 에서 끌어내지 않는다 — reset 한 run 에서는 둘이 다르다(§7.1).

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package koshei.episode.runtime

import io.temporal.client.WorkflowClient
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EpisodeSignalsTest {
    private val acts = FakeEpisodeActivities()
    private lateinit var env: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient
    private lateinit var signals: TemporalEpisodeSignals

    @BeforeEach fun up() {
        env = episodeEnvironment { fakeWorkers(acts, ProgrammableNarrator()) }
        env.start()
        client = env.workflowClient
        signals = TemporalEpisodeSignals(client)
    }

    @AfterEach fun down() = env.close()

    private fun stub(id: String) = client.newWorkflowStub(EpisodeWorkflow::class.java, id)

    /** Opens [id] and waits until the episode exists (`view()` says OPENING before the state is built). */
    private fun opened(id: String, search: String = "search-1"): String {
        signals.open(id, EpisodeStart(Picasso.manifest), searchSignal(search))
        return stub(id).until(env) { it.phase != "OPENING" }.instanceId
    }

    @Test fun `open starts the episode once and joins the second symptom`() {
        signals.open("ep:sig", EpisodeStart(Picasso.manifest), searchSignal("search-1"))
        signals.open("ep:sig", EpisodeStart(Picasso.manifest), searchSignal("search-2"))
        assertEquals(2, stub("ep:sig").until(env) { it.symptoms == 2 }.symptoms)
    }

    @Test fun `evidence reaches the instance and says false for none`() {
        val instance = opened("ep:ev")
        assertTrue(signals.evidence(instance, EvidenceSignal(jobResponseId = "resp-1")))
        assertFalse(signals.evidence("ep:never/r0", EvidenceSignal(jobResponseId = "resp-1")))
    }

    @Test fun `an ended instance says false, and its successor on the same key does not receive what was meant for it`() {
        val first = opened("ep:done")
        assertEquals("ACCEPTED", stub("ep:done").takeover(TakeoverRequest("op-1")))
        assertEquals("ACCEPTED", stub("ep:done").close(CloseRequest("op-1", "replay")))
        stub("ep:done").result()
        assertFalse(signals.evidence(first, EvidenceSignal(jobResponseId = "resp-1")))
        val second = opened("ep:done", "search-2")
        assertNotEquals(first, second)
        assertFalse(signals.evidence(first, EvidenceSignal(jobResponseId = "resp-1")))
        assertTrue(signals.evidence(second, EvidenceSignal(jobResponseId = "resp-2")))
    }
}
```

- `"OPENING"` 은 `Phase` 의 값이 아니다. 상태가 서기 전에 `EpisodeWorkflowImpl.view()` 가 돌려주는 자리값이다(`EpisodeWorkflow.kt:184`). 그대로 쓴다.
- `ProgrammableNarrator()` 의 기본 동작으로 에피소드가 열린 채로 있는지 확인한다(B3 의 다른 시험들이 같은 방식으로 쓴다).
- `close` 의 `outcome` 값 `"replay"` 는 B3b 재생 시험이 쓰는 값이다. 다른 값만 받으면 그 값으로 바꾼다.
- `result()` 는 B3c 메모(§7.1)대로 이 run 의 결과다. 다음 run 을 기다리지 않는다 — 다음 run 은 시험이 새로 연다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeSignalsTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현한다**

```kotlin
package koshei.episode.runtime

import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import io.temporal.workflow.Functions
import java.util.Optional

/** What the watcher (and the dev CLI) send into the episode loop (design §7.2). Tests replace it. */
interface EpisodeSignals {
    /**
     * signalWithStart on [workflowId] (`ep:<key>`, design §7.1): starts the episode with [start] if no run is open, and
     * sends [symptom] to it. Returns the run id. Throws when Temporal does not take it.
     */
    fun open(workflowId: String, start: EpisodeStart, symptom: SymptomSignal): String

    /**
     * The `evidence` signal to episode instance [instanceId] (`<workflowId>/<run>`, design §7.1) — only while the
     * workflow's current run is running and holds that instance. False otherwise (ended, moved on, never started): the
     * next episode on the same key never receives a report meant for the one before.
     */
    fun evidence(instanceId: String, signal: EvidenceSignal): Boolean
}

class TemporalEpisodeSignals(private val client: WorkflowClient) : EpisodeSignals {
    override fun open(workflowId: String, start: EpisodeStart, symptom: SymptomSignal): String {
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(EPISODE_TASK_QUEUE)
            .setWorkflowId(workflowId)
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .build()
        val stub = client.newWorkflowStub(EpisodeWorkflow::class.java, options)
        val batch = client.newSignalWithStartRequest()
        batch.add(Functions.Func1<EpisodeStart, EpisodeResult> { stub.run(it) }, start)
        batch.add(Functions.Proc1<SymptomSignal> { stub.symptom(it) }, symptom)
        return client.signalWithStart(batch).runId
    }

    /** As the control plane sends an Update (authoring-api `EpisodeGateway`): describe, a stub pinned to that run, check, send. */
    override fun evidence(instanceId: String, signal: EvidenceSignal): Boolean {
        val workflowId = instanceId.substringBeforeLast('/')
        val info = try {
            client.workflowServiceStubs.blockingStub().describeWorkflowExecution(
                DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(client.options.namespace)
                    .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
                    .build(),
            ).workflowExecutionInfo
        } catch (e: StatusRuntimeException) {
            if (e.status.code == Status.Code.NOT_FOUND) return false else throw e
        }
        if (info.status != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING) return false
        val stub = client.newWorkflowStub(EpisodeWorkflow::class.java, workflowId, Optional.of(info.execution.runId))
        return try {
            if (stub.view().instanceId != instanceId) return false
            stub.evidence(signal)
            true
        } catch (e: WorkflowNotFoundException) {
            false   // the run ended between the describe and the signal
        }
    }
}
```

- `io.grpc` 가 `:episode-runtime` 의 컴파일 경로에 있는지 확인한다(temporal-sdk 가 끌어온다. authoring-api 의 `EpisodeGateway` 가 같은 import 를 쓴다). 없으면 `build.gradle.kts` 를 고치지 말고 보고한다.
- 질의(`view()`)는 워커가 답해야 한다. 워커가 없으면 질의가 실패해 예외가 난다. 감시자는 그것을 `SIGNAL_FAILED` 로 기록하고 다음 폴링에 다시 보낸다(Task 7) — 근거를 버리지 않는다.
- `run(it)` 은 `EpisodeWorkflow` 의 워크플로 메서드다(`EpisodeCli.open` 이 이미 그렇게 부른다).

`EpisodeCli.open` 은 `TemporalEpisodeSignals(client).open(s.workflowId, s.start, s.signal)` 을 부르고 run id(`String`)를 돌려준다. `Cli.kt:68` 은 반환값을 쓰지 않으므로 고치지 않는다. `EpisodeCli` 에서 쓰지 않게 된 import(`WorkflowExecution`, `WorkflowIdReusePolicy`, `WorkflowOptions`, `Functions`, `EPISODE_TASK_QUEUE`, `EpisodeResult`)를 지운다. `EpisodeWorkflow` 는 `agentOff` 가 쓰므로 남는다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeSignalsTest" :app:test`
Expected: 3개 PASS, `:app` 그대로 PASS

- [ ] **Step 5: 커밋한다**

```bash
git add episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeSignals.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeSignalsTest.kt app/src/main/kotlin/koshei/app/EpisodeCli.kt
git commit -m "feat(episode-runtime): EpisodeSignals - signalWithStart, and evidence pinned to the instance's run; the CLI opens through it" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Chunk 3: 감시자

### Task 6: 감시자의 접수 (`EpisodeWatcher.pollOnce` 의 앞 절반)

**Files:**
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWatcher.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWatcherIntakeTest.kt`

동작(한 번의 폴링, 내보내기 디렉터리마다):
1. `ExportSymptoms.read(dir)`.
   - `NotReady`: 이번에는 넘어간다. 같은 사유가 처음일 때만 `NOT_READY` 를 기록한다.
   - `Broken`: 같은 사유가 처음일 때만 `BROKEN` 을 기록하고 넘어간다.
2. `Ready` 면 사건 파일, 탐색 파일 순서로 처리한다. 원천 이름은 `<dir 절대경로>#<파일 이름>` 이다.
3. 커서를 확인한다. 커서가 있는데 그 자리(`position`)의 줄이 없거나 id 가 다르면 `STUCK` 을 기록하고(처음일 때만) 그 파일을 넘어간다. 추측해서 다시 맞추지 않는다 — 사람이 표를 보고 정한다.
4. 커서 뒤의 줄마다:
   - `ExportSymptoms.symptom` 이 null 이면(digest 없음) `BROKEN` 을 기록하고 그 파일을 멈춘다. (JSON 이 아니거나 id 가 없는 줄은 1 에서 이미 묶음 전체가 `Broken` 이다.)
   - `correlate` 가 null 이면 `STUCK`(키가 안 됨)을 기록하고 그 파일을 멈춘다.
   - `Own` 이고 `related` 가 있으면 `AMBIGUOUS`(`eventId`, `key`, `related`)를 기록한다 — §12 의 서로 참조다.
   - `signals.open("ep:" + key, EpisodeStart(manifestJson), symptom)`.
   - 예외가 나면 `SIGNAL_FAILED` 를 (같은 글이 처음일 때만) 기록하고 **이번 폴링 전체를 멈춘다**. 커서는 그대로다 — 다음 폴링이 다시 보낸다.
   - 성공하면 `advance(source, runId, line.position, line.id)`.
5. 병합 규칙은 폴링마다 정책 파일에서 읽는다(`PolicyFileReader` → `strictJson.readTree` → `parsePolicy`).
   - 유효하면 그 규칙을 쓰고 기억해 둔다.
   - 아니면 마지막으로 유효했던 규칙을 쓴다. 그것도 없으면 규칙 없음(묶지 않음)이다.
   - 사유가 바뀔 때마다 `POLICY_UNUSABLE` 을 한 번 기록한다.
   - 감시자는 정책으로 판정하지 않는다. 꺼짐 · 만료는 워크플로가 본다(§7.1). 감시자가 쓰는 것은 `correlation` 뿐이다.

「처음일 때만」은 프로세스 안의 기억(`HashSet<String>`)이다.
- 다시 시작하면 한 번 더 기록된다.
- 사유가 A → B → A 로 돌아오면 두 번째 A 는 기록하지 않는다. 운영자는 처음의 A 를 이미 보았다.
- `AMBIGUOUS` · `UNROUTED` · `ROUTE_ENDED` 는 줄마다의 사실이라 `once` 를 쓰지 않는다. `SIGNAL_FAILED` 뒤에 그 줄을 다시 나르면 한 번 더 남는다.
그것으로 충분하다.

- [ ] **Step 1: 실패하는 시험을 쓴다**

가짜 `EpisodeSignals` 와 시험 Postgres(`EpisodeDb`)로 돈다. Temporal 은 쓰지 않는다.

```kotlin
package koshei.episode.runtime

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Records what the watcher sent; [failOpen] makes `open` throw; [running] says which instances still take evidence. */
internal class RecordingSignals : EpisodeSignals {
    val opened = mutableListOf<Pair<String, SymptomSignal>>()
    val evidence = mutableListOf<Pair<String, EvidenceSignal>>()
    var failOpen: RuntimeException? = null
    var running: (String) -> Boolean = { true }
    override fun open(workflowId: String, start: EpisodeStart, symptom: SymptomSignal): String {
        failOpen?.let { throw it }
        opened += workflowId to symptom
        return "run"
    }
    override fun evidence(instanceId: String, signal: EvidenceSignal): Boolean {
        if (!running(instanceId)) return false
        evidence += instanceId to signal
        return true
    }
}

/** Writes a picasso-shaped export (lines first, manifest last), as picasso's BundleWriter does. */
internal class ExportDir(val dir: Path, var runId: String = "run-A") {
    val incidents = mutableListOf<String>()
    val searches = mutableListOf<String>()
    fun snapshot(): ExportDir {
        write("incidents.jsonl", incidents.joinToString("") { "$it\n" })
        write("remedy-searches.jsonl", searches.joinToString("") { "$it\n" })
        write("manifest.json", """{"schemaVersion":"5","runId":"$runId","writtenAt":"x","virtualNow":"x","contractSemver":"0.9.0","counts":{"incidents":${incidents.size},"remedySearches":${searches.size}}}""")
        return this
    }
    private fun write(name: String, text: String) {
        val tmp = dir.resolve("$name.tmp")
        Files.writeString(tmp, text)
        Files.move(tmp, dir.resolve(name), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

class EpisodeWatcherIntakeTest {
    @TempDir lateinit var root: Path
    private lateinit var watch: WatchStore
    private val signals = RecordingSignals()
    private lateinit var export: ExportDir
    private lateinit var policy: Path

    @BeforeEach fun up() {
        EpisodeDb.reset()
        watch = WatchStore(EpisodeDb::connection)
        export = ExportDir(Files.createDirectories(root.resolve("export")))
        policy = root.resolve("active.yaml")
        Files.writeString(policy, TestPolicies.table(autoApprove = false))   // JSON is YAML
    }

    private fun watcher() = EpisodeWatcher(listOf(export.dir), carry = null, PolicyFileReader(policy), watch, signals) { Instant.ofEpochMilli(42) }
    private fun withRules(rules: String) = Files.writeString(policy, TestPolicies.table(autoApprove = false).replace("\"correlation\": []", "\"correlation\": $rules"))

    @Test fun `each new line opens its own episode once; a second poll sends nothing`() {
        export.incidents += Picasso.incident("incident-1")
        export.searches += Picasso.search("search-1")
        export.snapshot()
        val w = watcher()
        assertEquals(2, w.pollOnce().opened)
        assertEquals(listOf("ep:run-A:incident-1", "ep:run-A:search-1"), signals.opened.map { it.first })
        assertEquals("incident:run-A:d04ac2a20a63afc2ba147218bae871ef69746c990e3993deb00cfb4ddd262143", signals.opened[0].second.eventId)
        assertEquals(42, signals.opened[0].second.observedAtMillis)
        assertEquals(0, w.pollOnce().opened)
        assertEquals(0, watcher().pollOnce().opened, "the cursor is in Postgres, not in the process")
    }

    @Test fun `a rewritten export sends only its new lines`() {
        export.searches += Picasso.search("search-1"); export.snapshot()
        val w = watcher(); w.pollOnce()
        export.searches += Picasso.search("search-2"); export.snapshot()
        assertEquals(1, w.pollOnce().opened)
        assertEquals("ep:run-A:search-2", signals.opened.last().first)
    }

    @Test fun `a new run in the same directory is new symptoms`() {
        export.searches += Picasso.search("search-1"); export.snapshot()
        val w = watcher(); w.pollOnce()
        export.runId = "run-B"; export.snapshot()
        assertEquals(1, w.pollOnce().opened)
        assertEquals("search:run-B:search-1", signals.opened.last().second.eventId)
    }

    @Test fun `a merge rule puts one robot and order into one episode`() {
        withRules("""[{"kinds":["SEARCH","INCIDENT"],"by":["robotId","jobOrderId"]}]""")
        export.incidents += Picasso.incident("incident-1")
        export.searches += Picasso.search("search-1")
        export.searches += Picasso.search("search-2")
        export.snapshot()
        watcher().pollOnce()
        assertEquals(
            listOf("ep:run-A:by:robotId:hum-02:jobOrderId:PATROL-1", "ep:run-A:by:robotId:hum-02:jobOrderId:PATROL-1",
                "ep:run-A:by:robotId:hum-03:jobOrderId:PATROL-2"),
            signals.opened.map { it.first },
        )
    }

    @Test fun `rules that disagree merge nothing and leave a cross-reference`() {
        withRules("""[{"kinds":["SEARCH"],"by":["robotId"]},{"kinds":["SEARCH"],"by":["jobOrderId"]}]""")
        export.searches += Picasso.search("search-1"); export.snapshot()
        watcher().pollOnce()
        assertEquals("ep:run-A:search-1", signals.opened.single().first)
        val log = watch.logs(10).single { it.kind == "AMBIGUOUS" }
        assertTrue("run-A:by:robotId:hum-02" in log.detailJson && "run-A:by:jobOrderId:PATROL-1" in log.detailJson, log.detailJson)
    }

    @Test fun `an unusable policy merges nothing, and says so once`() {
        Files.writeString(policy, "version: [")
        export.searches += Picasso.search("search-1"); export.snapshot()
        val w = watcher(); w.pollOnce(); w.pollOnce()
        assertEquals("ep:run-A:search-1", signals.opened.single().first)
        assertEquals(1, watch.logs(10).count { it.kind == "POLICY_UNUSABLE" })
    }

    @Test fun `a failed signal moves nothing - the next poll sends it again`() {
        export.searches += Picasso.search("search-1"); export.snapshot()
        val w = watcher()
        signals.failOpen = IllegalStateException("temporal down")
        assertEquals(0, w.pollOnce().opened)
        signals.failOpen = null
        assertEquals(1, w.pollOnce().opened)
        assertEquals(1, watch.logs(10).count { it.kind == "SIGNAL_FAILED" })
    }

    @Test fun `a source that changed under the cursor stops, it is not re-guessed`() {
        export.searches += Picasso.search("search-1"); export.searches += Picasso.search("search-2"); export.snapshot()
        val w = watcher(); w.pollOnce()
        export.searches.clear(); export.searches += Picasso.search("search-3"); export.searches += Picasso.search("search-4"); export.snapshot()
        assertEquals(0, w.pollOnce().opened)
        assertEquals(0, w.pollOnce().opened)
        assertEquals(1, watch.logs(10).count { it.kind == "STUCK" })
    }

    @Test fun `an incident without digest stops its file at that line`() {
        export.incidents += """{"incidentId":"incident-x","robotId":"hum-01","jobOrderId":"J"}"""
        export.incidents += Picasso.incident("incident-1")
        export.searches += Picasso.search("search-1")
        export.snapshot()
        watcher().pollOnce()
        assertEquals(listOf("ep:run-A:search-1"), signals.opened.map { it.first }, "the other file goes on")
        assertEquals(1, watch.logs(10).count { it.kind == "BROKEN" })
    }

    @Test fun `a line that is not JSON stops the whole export - nothing before it, nothing in the other file`() {
        export.incidents += Picasso.incident("incident-1")
        export.incidents += "{not json"
        export.searches += Picasso.search("search-1")
        export.snapshot()
        val w = watcher()
        assertEquals(0, w.pollOnce().opened)
        assertEquals(0, w.pollOnce().opened)
        assertEquals(1, watch.logs(10).count { it.kind == "BROKEN" })
    }

    @Test fun `an export without its manifest is not read`() {
        Files.writeString(export.dir.resolve("remedy-searches.jsonl"), Picasso.search("search-1") + "\n")
        assertEquals(0, watcher().pollOnce().opened)
    }
}
```

`TestPolicies.table` 의 JSON 은 YAML 로도 읽힌다(`StrictYaml` 은 JSON 흐름 표기를 받는다). 받지 않으면 시험 도우미에서 YAML 로 바꿔 쓴다 — 그 사실을 보고한다. `"correlation": []` 글자가 `TestPolicies.table` 에 그대로 있는지 확인한다(있다).

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeWatcherIntakeTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현한다**

```kotlin
package koshei.episode.runtime

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import koshei.episode.Correlation
import koshei.episode.CorrelationRule
import koshei.episode.PolicyParse
import koshei.episode.SymptomKind
import koshei.episode.correlate
import koshei.episode.parsePolicy
import java.nio.file.Path
import java.time.Instant

/**
 * The watcher (design §12): carries picasso export lines in as `symptom` signals and the host's JobResponses in as
 * `evidence` signals. It carries, it does not judge — the only choices it makes are the merge key ([correlate], pure)
 * and which workflow a JobResponse goes to (the order its dispatch intent named). At least once: the cursor moves only
 * after Temporal took the signal; the core drops repeats by `eventId` / `jobResponseId` (§6).
 */
class EpisodeWatcher(
    private val exports: List<Path>,
    private val carry: Path?,
    private val policy: PolicyFileReader,
    private val store: WatchStore,
    private val signals: EpisodeSignals,
    private val clock: () -> Instant = Instant::now,
) {
    data class PollReport(val opened: Int, val evidence: Int)

    private val said = HashSet<String>()
    private var lastRules: List<CorrelationRule>? = null

    /** One pass over every source. Stops the pass at the first signal Temporal did not take (it is sent again next time). */
    fun pollOnce(): PollReport {
        val rules = rules()
        var opened = 0
        for (dir in exports) {
            val n = intake(dir.toAbsolutePath().normalize(), rules) ?: return PollReport(opened, 0)
            opened += n
        }
        val evidence = carry?.let { carryIn(it.toAbsolutePath().normalize()) ?: return PollReport(opened, 0) } ?: 0
        return PollReport(opened, evidence)
    }

    /** Lines opened from [dir]; null when a signal failed and the pass must stop. */
    private fun intake(dir: Path, rules: List<CorrelationRule>): Int? {
        val export = when (val r = ExportSymptoms.read(dir)) {
            is BundleRead.Ready -> r
            is BundleRead.NotReady -> { once("$dir", "NOT_READY", "why" to r.why); return 0 }
            is BundleRead.Broken -> { once("$dir", "BROKEN", "why" to r.why); return 0 }
        }
        var opened = 0
        for (kind in ExportKind.entries) {
            val source = "$dir#${kind.file.name}"
            val lines = export.lines.getValue(kind.file.name)
            val from = resume(source, export.runId, lines) ?: continue
            for (line in lines.drop(from)) {
                val symptom = ExportSymptoms.symptom(kind, export, line, clock().toEpochMilli())
                    ?: run { once(source, "BROKEN", "runId" to export.runId, "id" to line.id, "why" to "no digest: no eventId"); null }
                    ?: break
                val correlation = correlate(SymptomKind.valueOf(kind.name), line.node, export.runId, line.id, rules)
                    ?: run { once(source, "STUCK", "runId" to export.runId, "id" to line.id, "why" to "no valid key"); null }
                    ?: break
                if (correlation is Correlation.Own && correlation.related.isNotEmpty())
                    log(source, "AMBIGUOUS", "eventId" to symptom.eventId, "key" to correlation.key, "related" to correlation.related)
                try {
                    signals.open("ep:${correlation.key}", EpisodeStart(export.manifestJson), symptom)
                } catch (e: RuntimeException) {
                    once(source, "SIGNAL_FAILED", "why" to (e.message ?: e.javaClass.simpleName))
                    return null
                }
                store.advance(source, export.runId, line.position, line.id)
                opened++
            }
        }
        return opened
    }

    /**
     * How many of [lines] were already carried: the cursor's position, if the line there still carries its id. Null
     * (and STUCK, once) when the source changed under the cursor — never re-guessed (design §12).
     */
    private fun resume(source: String, runId: String, lines: List<BundleLine>): Int? {
        val cursor = store.cursor(source, runId) ?: return 0
        if (cursor.position <= lines.size && lines[cursor.position - 1].id == cursor.lastId) return cursor.position
        once(source, "STUCK", "runId" to runId, "position" to cursor.position, "lastId" to cursor.lastId,
            "why" to "the line at the cursor is no longer the one carried")
        return null
    }

    /** The policy's merge rules; the last valid ones when the table cannot be used, none before any was valid. */
    private fun rules(): List<CorrelationRule> {
        val read = policy.read()
        val why = when (read.kind) {
            "FOUND" -> when (val p = runCatching { parsePolicy(strictJson.readTree(read.json)) }.getOrNull()) {
                is PolicyParse.Valid -> { lastRules = p.policy.correlation; return p.policy.correlation }
                is PolicyParse.Invalid -> "rejected: ${p.errors.joinToString("; ")}"
                null -> "not JSON"
            }
            "MISSING" -> "missing"
            else -> "unreadable: ${read.reason}"
        }
        once("policy", "POLICY_UNUSABLE", "why" to why, "using" to if (lastRules == null) "no rules" else "the last valid rules")
        return lastRules ?: emptyList()
    }

    /** The carry half — Task 7. */
    private fun carryIn(dir: Path): Int? = 0

    private fun once(source: String, kind: String, vararg detail: Pair<String, Any>) {
        if (said.add("$source|$kind|${detail.joinToString()}")) log(source, kind, *detail)
    }

    private fun log(source: String, kind: String, vararg detail: Pair<String, Any>) {
        val node = JsonNodeFactory.instance.objectNode()
        detail.forEach { (k, v) ->
            when (v) {
                is Int -> node.put(k, v)
                is List<*> -> node.putArray(k).also { a -> v.forEach { a.add(it.toString()) } }
                else -> node.put(k, v.toString())
            }
        }
        store.log(source, kind, strictJson.writeValueAsString(node))
    }
}
```

주의:
- `?: run { …; null } ?: break` 꼴이 읽기 어려우면 `if (x == null) { once(…); break }` 로 풀어 쓴다. 동작은 같아야 한다.
- `once` 의 열쇠는 사유를 담는다. 그래서 같은 사유는 한 번만 기록되고, 사유가 바뀌면 다시 기록된다.
- `NOT_READY` 는 manifest 가 아직 없는 디렉터리에서도 한 번 기록된다. 운영자가 경로를 잘못 적었을 때 알 수 있게 한다.
- `SIGNAL_FAILED` 에서 이번 폴링 전체를 멈추는 까닭: Temporal 이 닿지 않으면 다른 원천도 실패한다. 모두 시도하며 기록을 늘리지 않는다.
- `opened` 는 이번 폴링에서 Temporal 이 받은 줄의 수다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeWatcherIntakeTest"`
Expected: 11개 PASS

- [ ] **Step 5: 커밋한다**

```bash
git add episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWatcher.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWatcherIntakeTest.kt
git commit -m "feat(episode-runtime): the watcher's intake - new export lines open episodes by merge key, at least once, stopping where it cannot read" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 7: 결과 통보 운반과 Mock 담는 쪽

**Files:**
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/CarriedEvidence.kt`
- Modify: `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWatcher.kt` (`carryIn`)
- Create: `episode-runtime/src/test/kotlin/koshei/episode/runtime/MockCarrier.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/CarriedEvidenceTest.kt`, `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWatcherCarryTest.kt`

개발용 나르는 모양 `koshei-carry-0`(§19 C 가 정해지면 바뀐다):
- `manifest.json`: `{"schemaVersion":"koshei-carry-0","carryRunId":"…","writtenAt":"…","counts":{"jobResponses":N}}`.
  - `carryRunId` 는 담는 쪽이 picasso 를 세운 프로세스 하나다. picasso 의 `resp-N` · `exec-N` 은 프로세스 안의 셈이라 다시 시작하면 되풀이되기 때문이다(§19).
- `job-responses.jsonl`: 한 줄에 결과 통보 하나다.
  - 칸 이름은 picasso `JobResponse` 그대로다: `jobResponseId`, `jobOrderId`, `physicalState`, `requiredEvidence`, `reachedEvidence`, `completedUnits`, `unverifiedUnits`, `inDoubtUnits`, `operatorRequired`, `blockedBy`, …
  - 여기에 담는 쪽이 덧붙이는 `executionId` 가 더해진다.
  - 같은 `jobResponseId` 가 다시 나올 수 있다.

줄 → `EvidenceSignal` 규칙:
- `jobResponseId`: 비지 않은 글자여야 한다. 감시자 안에서는 `Bundles` 가 id 없는 줄을 이미 묶음째 거절하므로(`idField`) 이 검사는 `of` 를 따로 부를 때의 방어다.
- `jobOrderId`: 운반에만 쓴다(신호에 싣지 않는다). 없거나 글자가 아니면 `UNROUTED` 로 기록하고 넘어간다 — 줄은 읽혔지만 갈 곳이 없다.
- `executionId` · `physicalState` · `reachedEvidence`: 없거나 `null` 이면 `null` 이다. 글자면 그 값이다. 그 밖의 값이면 깨진 줄이다.
- `completedUnits` · `inDoubtUnits` · `unverifiedUnits`: 없거나 `null` 이면 `null`(보고 안 함)이다. 글자들의 배열이면 그 목록이다(`[]` 은 빈 목록 — null 과 다르다, §12). 그 밖이면 깨진 줄이다.
- `operatorRequired`: 없거나 `null` 이면 `null` 이다. 불이면 그 값이다. 그 밖이면 깨진 줄이다.
- `receivedAtMillis` 는 감시자가 받은 시각이다(`observedAt`, §12).
- 나머지 칸(`requiredEvidence`, `blockedBy`, `incompleteUnits` …)은 지금 코어가 읽지 않으므로 싣지 않는다. 모양은 검사하지 않는다.

운반:
- `store.instancesForOrder(jobOrderId)` 의 인스턴스마다 `signals.evidence(instanceId, …)` 로 보낸다. 워크플로 id 가 아니라 인스턴스에 보낸다 — 같은 키의 다음 에피소드가 앞 에피소드의 통보를 받지 않는다(Task 5).
- 찾은 것이 없으면 `UNROUTED`(`jobResponseId`, `jobOrderId`)를 기록한다.
- `signals.evidence` 가 `false` 면 `ROUTE_ENDED`(`jobResponseId`, `instanceId`)를 기록한다.
- 예외가 나면 `SIGNAL_FAILED` 를 기록하고 이번 폴링을 멈춘다(커서 그대로). 다음에 다시 보낸 근거는 코어가 `jobResponseId` 로 지운다.
- 어느 시도의 근거인지는 코어가 `executionId` 로 가른다(§12 ①). 감시자는 고르지 않는다.

- [ ] **Step 1: Mock 담는 쪽을 쓴다**

`episode-runtime/src/test/kotlin/koshei/episode/runtime/MockCarrier.kt`:

```kotlin
package koshei.episode.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The Mock host (plan C, design §16): carries picasso JobResponses to koshei in the dev shape `koshei-carry-0`, written
 * the picasso way (lines first, manifest last, each by rename). Tests mix in what a real host will send: reports before
 * the answer, reports of other executions, and the same `jobResponseId` twice.
 */
internal class MockCarrier(val dir: Path, val carryRunId: String = "carry-1") {
    private val mapper = ObjectMapper()
    private val lines = mutableListOf<String>()

    /** One JobResponse. A null list or flag is written as JSON null ("not reported"). */
    fun report(
        jobResponseId: String,
        jobOrderId: String?,
        executionId: String?,
        completedUnits: List<String>? = emptyList(),
        inDoubtUnits: List<String>? = emptyList(),
        unverifiedUnits: List<String>? = emptyList(),
        operatorRequired: Boolean? = false,
        physicalState: String? = "PHYSICALLY_DONE",
        reachedEvidence: String? = "E1",
    ): MockCarrier = raw(mapper.writeValueAsString(linkedMapOf(
        "jobResponseId" to jobResponseId, "jobOrderId" to jobOrderId, "executionId" to executionId, "version" to 1,
        "physicalState" to physicalState, "requiredEvidence" to "E1", "reachedEvidence" to reachedEvidence,
        "completedUnits" to completedUnits, "unverifiedUnits" to unverifiedUnits, "incompleteUnits" to emptyMap<String, String>(),
        "operatorRequired" to operatorRequired, "inDoubtUnits" to inDoubtUnits, "blockedBy" to emptyList<String>(),
    )))

    /** A line as given. */
    fun raw(line: String): MockCarrier { lines += line; return this }

    /** Writes everything so far: the lines, then the manifest. */
    fun snapshot(): MockCarrier {
        write("job-responses.jsonl", lines.joinToString("") { "$it\n" })
        write("manifest.json", """{"schemaVersion":"koshei-carry-0","carryRunId":"$carryRunId","writtenAt":"x","counts":{"jobResponses":${lines.size}}}""")
        return this
    }

    private fun write(name: String, text: String) {
        Files.createDirectories(dir)
        val tmp = dir.resolve("$name.tmp")
        Files.writeString(tmp, text)
        Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
```

`PHYSICALLY_DONE` 은 picasso `PhysicalState` 의 값이다(picasso `Model.kt:24-26`). 코어는 이 칸을 판정에 쓰지 않는다.

- [ ] **Step 2: 줄 읽기의 실패하는 시험을 쓴다**

`CarriedEvidenceTest.kt`:

```kotlin
package koshei.episode.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CarriedEvidenceTest {
    private fun read(json: String) = CarriedEvidence.of(strictJson.readTree(json), receivedAtMillis = 9)

    @Test fun `a report travels field by field`() {
        val r = assertIs<CarriedEvidence.Line.Routed>(read(
            """{"jobResponseId":"resp-1","jobOrderId":"PATROL-1","executionId":"exec-2","physicalState":"S","reachedEvidence":"E1",
               "completedUnits":["u1"],"inDoubtUnits":[],"unverifiedUnits":[],"operatorRequired":false,"blockedBy":["X"]}""",
        ))
        assertEquals("PATROL-1", r.jobOrderId)
        assertEquals(EvidenceSignal("resp-1", "exec-2", "S", "E1", listOf("u1"), emptyList(), emptyList(), false, 9), r.signal)
    }

    @Test fun `null and absent are not reported - never empty, never false`() {
        val r = assertIs<CarriedEvidence.Line.Routed>(read("""{"jobResponseId":"resp-1","jobOrderId":"P","completedUnits":null}"""))
        assertNull(r.signal.executionId)
        assertNull(r.signal.completedUnits)
        assertNull(r.signal.inDoubtUnits)
        assertNull(r.signal.operatorRequired)
    }

    @Test fun `no order is unrouted, not broken`() {
        assertIs<CarriedEvidence.Line.Unrouted>(read("""{"jobResponseId":"resp-1"}"""))
        assertIs<CarriedEvidence.Line.Unrouted>(read("""{"jobResponseId":"resp-1","jobOrderId":7}"""))
    }

    @Test fun `a field of the wrong shape is broken`() {
        listOf(
            """{"jobOrderId":"P"}""",
            """{"jobResponseId":"","jobOrderId":"P"}""",
            """{"jobResponseId":"r","jobOrderId":"P","executionId":3}""",
            """{"jobResponseId":"r","jobOrderId":"P","completedUnits":"u1"}""",
            """{"jobResponseId":"r","jobOrderId":"P","inDoubtUnits":[1]}""",
            """{"jobResponseId":"r","jobOrderId":"P","operatorRequired":"false"}""",
        ).forEach { assertIs<CarriedEvidence.Line.Broken>(read(it), it) }
    }
}
```

- [ ] **Step 3: 운반의 실패하는 시험을 쓴다**

`EpisodeWatcherCarryTest.kt` — 가짜 신호(`RecordingSignals`)와 시험 Postgres 를 쓴다. 실행 의도 기록은 `EpisodeStore.append` 로 손으로 넣는다(Task 4 시험의 `intent` 와 같은 모양).

```kotlin
package koshei.episode.runtime

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeWatcherCarryTest {
    @TempDir lateinit var root: Path
    private lateinit var events: EpisodeStore
    private lateinit var watch: WatchStore
    private val signals = RecordingSignals()
    private lateinit var carrier: MockCarrier

    @BeforeEach fun up() {
        events = EpisodeDb.reset()
        watch = WatchStore(EpisodeDb::connection)
        carrier = MockCarrier(root.resolve("carry"))
        val intent = { order: String -> """{"candidate":{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{"robotId":"hum-02","jobOrderId":"$order","searchId":"search-1"},"sawSkillTypes":["pick_place"]},"idempotencyKey":"k","approval":null,"targetUnit":null}""" }
        events.append("ep:a/r1", 7, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1"))
        events.append("ep:b/r2", 7, EpisodeActivitiesImpl.INTENT_KIND, intent("PATROL-1"))
    }

    private fun watcher(): EpisodeWatcher {
        val policy = root.resolve("active.yaml").also { Files.writeString(it, TestPolicies.table(autoApprove = false)) }
        return EpisodeWatcher(emptyList(), root.resolve("carry"), PolicyFileReader(policy), watch, signals) { Instant.ofEpochMilli(5) }
    }

    @Test fun `a report goes to every episode whose intent named its order - the core decides which attempt it is`() {
        carrier.report("resp-1", "PATROL-1", "exec-2").snapshot()
        assertEquals(1, watcher().pollOnce().evidence)
        assertEquals(listOf("ep:a/r1", "ep:b/r2"), signals.evidence.map { it.first })
        assertEquals(5, signals.evidence.first().second.receivedAtMillis)
    }

    @Test fun `the same jobResponseId twice is carried twice - the core drops the second`() {
        carrier.report("resp-1", "PATROL-1", "exec-2").report("resp-1", "PATROL-1", "exec-2").snapshot()
        assertEquals(2, watcher().pollOnce().evidence)
        assertEquals(0, watcher().pollOnce().evidence, "the cursor counts lines, so repeated ids do not confuse it")
    }

    @Test fun `a report for no known order is logged, not lost silently`() {
        carrier.report("resp-9", "NOPE", "exec-9").raw("""{"jobResponseId":"resp-10"}""").snapshot()
        assertEquals(2, watcher().pollOnce().evidence)
        assertEquals(emptyList(), signals.evidence)
        assertEquals(2, watch.logs(10).count { it.kind == "UNROUTED" })
    }

    @Test fun `an ended episode is logged as such`() {
        signals.running = { it != "ep:a/r1" }
        carrier.report("resp-1", "PATROL-1", "exec-2").snapshot()
        watcher().pollOnce()
        assertEquals(listOf("ep:b/r2"), signals.evidence.map { it.first })
        assertEquals(1, watch.logs(10).count { it.kind == "ROUTE_ENDED" })
    }

    @Test fun `a broken line stops the carry there`() {
        carrier.report("resp-1", "PATROL-1", "exec-2").raw("""{"jobResponseId":"resp-2","jobOrderId":"PATROL-1","inDoubtUnits":"u"}""")
            .report("resp-3", "PATROL-1", "exec-2").snapshot()
        assertEquals(1, watcher().pollOnce().evidence)
        assertEquals(1, watch.logs(10).count { it.kind == "BROKEN" })
    }

    @Test fun `a carry of an unknown shape is not read`() {
        Files.createDirectories(root.resolve("carry"))
        Files.writeString(root.resolve("carry/job-responses.jsonl"), "")
        Files.writeString(root.resolve("carry/manifest.json"), """{"schemaVersion":"koshei-carry-1","carryRunId":"c","counts":{"jobResponses":0}}""")
        assertEquals(0, watcher().pollOnce().evidence)
        assertEquals(1, watch.logs(10).count { it.kind == "BROKEN" })
    }
}
```

`evidence` 수는 「이번 폴링에서 다 처리한(커서를 옮긴) 결과 통보 줄의 수」다. 갈 곳이 없어 기록만 한 줄도 센다.

- [ ] **Step 4: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.CarriedEvidenceTest" --tests "koshei.episode.runtime.EpisodeWatcherCarryTest"`
Expected: 컴파일 실패

- [ ] **Step 5: 구현한다**

`CarriedEvidence.kt`:

```kotlin
package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode

/**
 * A JobResponse as the host carries it to koshei (design §12). The shape is the dev one, `koshei-carry-0`: picasso's
 * JobResponse fields by name, plus the `executionId` the host knows — picasso's JobResponse has none (picasso
 * `Model.kt:310-337`, `8ab5400`). The real shape is decided with the host (§19 C).
 */
object CarriedEvidence {
    val FILE = BundleFile("job-responses.jsonl", "jobResponses", "jobResponseId")
    val SCHEMA_VERSIONS = setOf("koshei-carry-0")

    sealed interface Line {
        /** A report and the order whose episodes it goes to. */
        data class Routed(val jobOrderId: String, val signal: EvidenceSignal) : Line
        /** Read, but it names no order: there is nowhere to carry it. */
        data class Unrouted(val jobResponseId: String) : Line
        /** A field has a shape this reader does not know: stop, never guess. */
        data class Broken(val why: String) : Line
    }

    fun of(node: JsonNode, receivedAtMillis: Long): Line {
        val id = node.get("jobResponseId")?.takeIf { it.isTextual && it.textValue().isNotBlank() }?.textValue()
            ?: return Line.Broken("no jobResponseId")
        fun text(f: String): Result<String?> = node.get(f).let { v ->
            when {
                v == null || v.isNull -> Result.success(null)
                v.isTextual -> Result.success(v.textValue())
                else -> Result.failure(IllegalArgumentException("$f is not text"))
            }
        }
        fun units(f: String): Result<List<String>?> = node.get(f).let { v ->
            when {
                v == null || v.isNull -> Result.success(null)
                v.isArray && v.all { it.isTextual } -> Result.success(v.map { it.textValue() })
                else -> Result.failure(IllegalArgumentException("$f is not a list of text"))
            }
        }
        val operatorRequired = node.get("operatorRequired").let { v ->
            when {
                v == null || v.isNull -> null
                v.isBoolean -> v.booleanValue()
                else -> return Line.Broken("$id: operatorRequired is not true or false")
            }
        }
        val signal = try {
            EvidenceSignal(
                jobResponseId = id,
                executionId = text("executionId").getOrThrow(),
                physicalState = text("physicalState").getOrThrow(),
                reachedEvidence = text("reachedEvidence").getOrThrow(),
                completedUnits = units("completedUnits").getOrThrow(),
                inDoubtUnits = units("inDoubtUnits").getOrThrow(),
                unverifiedUnits = units("unverifiedUnits").getOrThrow(),
                operatorRequired = operatorRequired,
                receivedAtMillis = receivedAtMillis,
            )
        } catch (e: IllegalArgumentException) {
            return Line.Broken("$id: ${e.message}")
        }
        val order = node.get("jobOrderId")?.takeIf { it.isTextual && it.textValue().isNotBlank() }?.textValue()
            ?: return Line.Unrouted(id)
        return Line.Routed(order, signal)
    }
}
```

`EvidenceSignal` 의 생성자 칸 순서가 시험의 위치 인자와 같은지 확인한다(`Wire.kt:33-43`: `jobResponseId, executionId, physicalState, reachedEvidence, completedUnits, inDoubtUnits, unverifiedUnits, operatorRequired, receivedAtMillis`).

`EpisodeWatcher.carryIn` 을 채운다.

```kotlin
    /** JobResponse lines carried from [dir]; null when a signal failed and the pass must stop. */
    private fun carryIn(dir: Path): Int? {
        val bundle = when (val r = Bundles.read(dir, "carryRunId", CarriedEvidence.SCHEMA_VERSIONS, listOf(CarriedEvidence.FILE), uniqueIds = false)) {
            is BundleRead.Ready -> r
            is BundleRead.NotReady -> { once("$dir", "NOT_READY", "why" to r.why); return 0 }
            is BundleRead.Broken -> { once("$dir", "BROKEN", "why" to r.why); return 0 }
        }
        val source = "$dir#${CarriedEvidence.FILE.name}"
        val lines = bundle.lines.getValue(CarriedEvidence.FILE.name)
        val from = resume(source, bundle.runId, lines) ?: return 0
        var carried = 0
        for (line in lines.drop(from)) {
            when (val e = CarriedEvidence.of(line.node, clock().toEpochMilli())) {
                is CarriedEvidence.Line.Broken -> { once(source, "BROKEN", "runId" to bundle.runId, "position" to line.position, "why" to e.why); break }
                is CarriedEvidence.Line.Unrouted -> log(source, "UNROUTED", "jobResponseId" to e.jobResponseId, "why" to "no jobOrderId")
                is CarriedEvidence.Line.Routed -> {
                    val instances = store.instancesForOrder(e.jobOrderId)
                    if (instances.isEmpty())
                        log(source, "UNROUTED", "jobResponseId" to e.signal.jobResponseId, "jobOrderId" to e.jobOrderId, "why" to "no remedy intent named this order")
                    for (instanceId in instances) {
                        val delivered = try {
                            signals.evidence(instanceId, e.signal)
                        } catch (x: RuntimeException) {
                            once(source, "SIGNAL_FAILED", "why" to (x.message ?: x.javaClass.simpleName))
                            return null
                        }
                        if (!delivered) log(source, "ROUTE_ENDED", "jobResponseId" to e.signal.jobResponseId, "instanceId" to instanceId)
                    }
                }
            }
            store.advance(source, bundle.runId, line.position, line.id)
            carried++
        }
        return carried
    }
```

`pollOnce` 의 `carry?.let { carryIn(…) ?: return … }` 는 그대로 둔다.

- [ ] **Step 6: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.CarriedEvidenceTest" --tests "koshei.episode.runtime.EpisodeWatcherCarryTest" --tests "koshei.episode.runtime.EpisodeWatcherIntakeTest"`
Expected: 4 + 6 + 11 = 21개 PASS

- [ ] **Step 7: 커밋한다**

```bash
git add episode-runtime/src/main/kotlin/koshei/episode/runtime/CarriedEvidence.kt episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWatcher.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/MockCarrier.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/CarriedEvidenceTest.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWatcherCarryTest.kt
git commit -m "feat(episode-runtime): carry the host's JobResponses to the episodes whose intent named the order; Mock host for tests" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 8: 끝에서 끝까지 — 감시자 · 실제 액티비티 · Mock 담는 쪽

**Files:**
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/WatcherEndToEndTest.kt`

B3b 의 `EpisodeEndToEndTest` 와 같은 틀이다. 실제 액티비티(`EpisodeWorkers.register`, Mock narrator, Mock picasso)와 시험 Postgres 위에서 `TestWorkflowEnvironment` 로 돈다. 감시자는 `TemporalEpisodeSignals(env.workflowClient)` 로 보낸다.

`EpisodeWorkers.register(factory, config, store, picasso = …)` 는 창구를 받는다. 「답보다 먼저 온 결과 통보」 시험은 Mock picasso 를 감싼 문(latch)을 넘긴다.

```kotlin
/** The Mock window, held at `approve` until [gate] opens: the episode stays DISPATCHED meanwhile. */
private class GatedWindow(private val inner: ApprovalWindow, val gate: CountDownLatch) : ApprovalWindow {
    override fun revalidate(candidateJson: String): String = inner.revalidate(candidateJson)
    override fun approve(intent: JsonNode, approverKind: String?): String { gate.await(30, TimeUnit.SECONDS); return inner.approve(intent, approverKind) }
}
```

`ApprovalWindow` 의 메서드가 이 둘뿐인지 확인한다(`ApprovalWindow.kt:14, 20`).

시험 넷:

1. **접수에서 승인까지.** 정책 v1 사본에 병합 규칙 `[{kinds:[SEARCH,INCIDENT], by:[robotId,jobOrderId]}]` 을 넣는다. `ExportDir` 에 run-1 의 `incident-1` 과 `search-1` 을 쓰고 `pollOnce()` 한다.
   - 워크플로 `ep:run-A:by:robotId:hum-02:jobOrderId:PATROL-1` 하나가 열린다. 그 `view().symptoms` 가 2 가 될 때까지 기다린다.
   - `AWAITING_APPROVAL` 에 닿는다.
   - 두 번째 `pollOnce()` 의 `opened` 는 0 이다.
2. **의심스러운 결과 통보 → UNKNOWN(OUTCOME), 겹친 id 는 한 번.**
   - 에피소드를 감시자로 열고 `decide` 로 승인해 `AWAITING_EVIDENCE` 에 닿게 한다. Mock picasso 의 `executionId` 는 `mock-exec-1` 이다 — B3b 시험이 보인 값을 확인해 쓴다.
   - `MockCarrier` 로 다음을 낸다.
     - `resp-1`(PATROL-1, `mock-exec-1`, `inDoubtUnits = ["u-1"]`)을 두 번.
     - 관계없는 `resp-2`(PATROL-1, `exec-other`).
     - 갈 곳 없는 `resp-3`(NOPE).
   - `pollOnce()` 뒤에 단계가 `UNKNOWN_OUTCOME` 이 된다.
   - `episode_event` 의 `EVIDENCE` 기록에 이것들이 있다.
     - `resp-1` 의 `"in doubt"` 하나.
     - `"duplicate jobResponseId"` 하나.
     - `resp-2` 의 `"not linked to this attempt's execution"` 하나.
     - 사유 글자는 `Outcome.kt` 의 `why` 그대로다. 기록 페이로드에서 사유와 `jobResponseId` 가 어느 칸에 실리는지 먼저 확인한다.
   - 감시 기록에 `UNROUTED` 가 하나 있다.
3. **답보다 먼저 온 결과 통보.**
   - `GatedWindow` 로 시작한다. 승인 뒤 `DISPATCHED` 에 닿으면 `resp-1`(PATROL-1, `mock-exec-1`, `inDoubtUnits = ["u-1"]`)을 내고 `pollOnce()` 한다.
   - **문이 닫혀 있는 동안은 `eventually`(벽시계)로만 기다린다. `until(env)` 를 쓰지 않는다.** `until(env)` 는 시험 시간을 1초씩 밀고, `dispatchMs`(30초) 가 지나면 디스패치가 「모름」(`Uncertain`)으로 끝나 같은 `UNKNOWN_OUTCOME` 에 닿는다 — 시험이 엉뚱한 까닭으로 통과한다. 승인에서 DISPATCHED 까지는 타이머가 없으므로 벽시계로 닿는다.
   - 실행 의도 기록은 `Dispatch` 보다 먼저 저장되므로 주문으로 찾아진다. 찾아지지 않으면 시험을 고치지 말고 보고한다 — §12 의 가정이 깨진 것이다.
   - `EVIDENCE` 기록 `"kept until the answer"`(`resp-1`)이 생길 때까지 기다린다.
   - 문을 연다. 단계가 `UNKNOWN_OUTCOME` 이 된다. 그 까닭이 보관한 근거라는 것을 기록으로 확인한다.
     - `DISPATCH_RESULT` 의 `judgement` 가 `ACCEPTED` 이고 답에 `mock-exec-1` 이 있다(시간 초과의 `Uncertain` 이 아니다).
     - `"kept until the answer"` 기록 **뒤에** `resp-1` 의 `EVIDENCE` 기록이 `why = "in doubt"`, `counted = true` 로 있다.
4. **끝난 에피소드로 가는 결과 통보.**
   - 시험 2 처럼 `AWAITING_EVIDENCE` 에 닿게 한 뒤 `takeover` → `close` 로 끝낸다.
   - 결과를 기다린 뒤 `resp-9`(PATROL-1)를 내고 `pollOnce()` 한다.
   - 감시 기록에 `ROUTE_ENDED` 가 하나 있다(`instanceId` 는 끝난 인스턴스).
   - 같은 워크플로 id 에 `TemporalEpisodeSignals.open` 으로 다른 탐색 줄(`search-2`)을 보내 새 에피소드를 연다. 그 에피소드에 `decide` 하기 **전에** `resp-10`(PATROL-1)을 내고 `pollOnce()` 한다(정책 v1 이라 사람의 승인 전에는 실행 의도가 없다). 새 에피소드의 기록에 `EVIDENCE` 가 없다 — 앞 인스턴스의 통보가 다음 에피소드에 섞이지 않는다(새 에피소드는 아직 실행 의도가 없으므로 주문으로 찾아지지도 않는다. `ROUTE_ENDED` 가 하나 더 는다).
   - `close` 는 ESCALATED 에서만 받는다. `takeover` 가 그 상태로 보내는지 B3b 재생 시험(`personApproves`)의 순서를 따른다.

시간: 활동 스레드는 벽시계로 돈다. 기다림은 `until(env)`(시험 시간)과 `eventually`(벽시계)를 B3b 시험처럼 섞어 쓴다. `GatedWindow` 는 30초를 넘겨 막지 않는다.

- [ ] **Step 1: 시험을 쓴다** (위 넷, `EpisodeEndToEndTest` 의 `start(policy, store)` · `stored` 도우미를 같은 모양으로 둔다)
- [ ] **Step 2: 돌린다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.WatcherEndToEndTest"`
Expected: 4개 PASS. 처음부터 통과하는 시험이 있어도 된다 — 앞 작업이 이미 구현했다. 실패하면 원인이 감시자에 있는지 코어에 있는지 가려 보고한다. 코어를 고쳐야 하면 고치지 말고 멈춰 보고한다.

- [ ] **Step 3: 모듈 전체를 확인한다**

Run: `./gradlew :episode:test :episode-runtime:test :app:test`
Expected: 모두 PASS. 모듈마다 수를 보고한다(시작점: `:episode` 296, `:episode-runtime` 182, `:app` 30).

- [ ] **Step 4: 커밋한다**

```bash
git add episode-runtime/src/test/kotlin/koshei/episode/runtime/WatcherEndToEndTest.kt
git commit -m "test(episode-runtime): watcher end to end - merged intake, doubtful and repeated reports, a report before the answer, an ended episode" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Chunk 4: 프로세스 · drift · 문서

### Task 9: 에피소드 모드에서 drift 자동 디스패치를 막는다 (authoring-api)

**Files:**
- Modify: `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkers.kt` (`PicassoMode.fromEnv`)
- Create: `authoring-api/src/main/kotlin/koshei/authoring/AutoCorrectGate.kt`
- Modify: `authoring-api/src/main/kotlin/koshei/authoring/AutoCorrectBean.kt`, `AutoCorrectController.kt`, `AutoCorrectDispatcher.kt`(인터페이스 하나를 구현하게)
- Modify: `scripts/run-fsm-autocorrect-dispatch-gate.sh` (에피소드 모드가 아님을 밝힌다)
- Test: `authoring-api/src/test/kotlin/koshei/authoring/AutoCorrectGateTest.kt`(게이트와 컨트롤러의 409 를 함께 본다 — 따로 `AutoCorrectControllerTest` 를 만들지 않는다), `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWorkersTest.kt`(`EpisodeRuntimeConfig` 시험이 있는 파일. `assertThrows` 를 이미 import 한다)

지금 drift 자동 교정은 authoring-api 안에서 돈다.
- `AutoCorrectBean` 이 20초마다 돈다. `KOSHEI_AUTOCORRECT_DISABLED=1` 이면 끄고, `KOSHEI_AUTOCORRECT_DISPATCH=1` 이면 디스패치한다.
- `POST /api/autocorrect/sweep` 는 늘 디스패치한다(관문 스크립트 `scripts/run-fsm-autocorrect-dispatch-gate.sh` 가 쓴다).

에피소드 모드에서는 둘 다 디스패치하지 않는다(§14).

| 설정 | Bean | 단발 경로 |
|---|---|---|
| `KOSHEI_PICASSO` 가 `off` 거나 없음 | 지금 그대로 | 지금 그대로(디스패치) |
| 에피소드 모드 + `DISABLED=1` | 꺼짐 | 409 `EPISODE_MODE` |
| 에피소드 모드 + `DISPATCH` 없음 | 경보만 | 409 `EPISODE_MODE` |
| 에피소드 모드 + `DISPATCH=1`(+ `DISABLED` 아님) | **시작을 멈춘다** | — |
| `KOSHEI_PICASSO` 에 모르는 값 | **시작을 멈춘다**(워커와 같은 해석) | — |

관문 스크립트는 `KOSHEI_PICASSO` 를 두지 않는다. 그런데 authoring-api 의 Gradle 작업은 셸의 `KOSHEI_*` 를 모두 넘긴다(`authoring-api/build.gradle.kts:45-47`). 에피소드 작업 중에 `KOSHEI_PICASSO=mock` 을 내보낸 셸에서 관문을 돌리면 단발 경로가 409 가 되어 관문이 엉뚱하게 실패한다. 그래서 스크립트의 `export KOSHEI_AUTOCORRECT_DISABLED=1` 줄(27행 부근) 옆에 이 줄을 더한다.

```bash
export KOSHEI_PICASSO=off                                     # drift dispatch gate: not episode mode (design §14)
```

- [ ] **Step 1: 실패하는 시험을 쓴다**

`AutoCorrectGateTest.kt`:

```kotlin
package koshei.authoring

import koshei.opcua.AutoCorrectAction
import org.springframework.http.HttpStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoCorrectGateTest {
    private fun gate(vararg env: Pair<String, String>) = AutoCorrectGate.fromEnv(env.toMap())

    @Test fun `outside episode mode nothing changes`() {
        assertEquals(AutoCorrectGate(AutoCorrectMode.ALARM_ONLY, episodeMode = false), gate())
        assertEquals(AutoCorrectGate(AutoCorrectMode.DISPATCH, episodeMode = false), gate("KOSHEI_AUTOCORRECT_DISPATCH" to "1"))
        assertEquals(AutoCorrectGate(AutoCorrectMode.OFF, episodeMode = false), gate("KOSHEI_AUTOCORRECT_DISABLED" to "1", "KOSHEI_AUTOCORRECT_DISPATCH" to "1"))
        assertEquals(AutoCorrectGate(AutoCorrectMode.ALARM_ONLY, episodeMode = false), gate("KOSHEI_PICASSO" to "off"))
        assertTrue(gate().oneShotAllowed)
    }

    @Test fun `episode mode alarms only, and refuses the one-shot dispatch`() {
        val g = gate("KOSHEI_PICASSO" to "mock")
        assertEquals(AutoCorrectGate(AutoCorrectMode.ALARM_ONLY, episodeMode = true), g)
        assertFalse(g.oneShotAllowed)
        assertEquals(AutoCorrectMode.OFF, gate("KOSHEI_PICASSO" to "mock", "KOSHEI_AUTOCORRECT_DISABLED" to "1").mode)
    }

    @Test fun `episode mode with auto-dispatch asked for stops the start - neither silently loses`() {
        assertFailsWith<IllegalArgumentException> { gate("KOSHEI_PICASSO" to "mock", "KOSHEI_AUTOCORRECT_DISPATCH" to "1") }
    }

    @Test fun `an unknown KOSHEI_PICASSO stops the start, as it stops the worker`() {
        assertFailsWith<IllegalArgumentException> { gate("KOSHEI_PICASSO" to "real") }
    }

    @Test fun `the one-shot endpoint answers 409 in episode mode and never sweeps`() {
        var swept = false
        val controller = AutoCorrectController({ _: File -> swept = true; emptyList<AutoCorrectAction>() }, gate("KOSHEI_PICASSO" to "mock"))
        val r = controller.sweep()
        assertEquals(HttpStatus.CONFLICT, r.statusCode)
        assertEquals("EPISODE_MODE", (r.body as Map<*, *>)["error"])
        assertFalse(swept)
    }
}
```

`EpisodeRuntimeConfig` 시험 파일에 하나를 더한다.

```kotlin
@Test fun `PicassoMode reads KOSHEI_PICASSO on its own, as the worker does`() {
    assertEquals(PicassoMode.OFF, PicassoMode.fromEnv(emptyMap()))
    assertEquals(PicassoMode.MOCK, PicassoMode.fromEnv(mapOf("KOSHEI_PICASSO" to "mock")))
    assertThrows<IllegalArgumentException> { PicassoMode.fromEnv(mapOf("KOSHEI_PICASSO" to "Mock")) }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "*EpisodeWorkers*" :authoring-api:test --tests "koshei.authoring.AutoCorrectGateTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현한다**

`EpisodeWorkers.kt`:

```kotlin
enum class PicassoMode {
    OFF, MOCK;

    companion object {
        /** `KOSHEI_PICASSO`, read the same way by every process that must agree on episode mode (worker, control plane). */
        fun fromEnv(env: Map<String, String>): PicassoMode = when (val v = env["KOSHEI_PICASSO"] ?: "off") {
            "off" -> OFF
            "mock" -> MOCK
            else -> throw IllegalArgumentException("KOSHEI_PICASSO: only 'mock' until the host is placed (design §19 C), was '$v'")
        }
    }
}
```

`EpisodeRuntimeConfig.fromEnv` 의 `picasso = when …` 를 `picasso = PicassoMode.fromEnv(env)` 로 바꾼다. 기존 KDoc 은 enum 위에 그대로 둔다.

`AutoCorrectGate.kt`:

```kotlin
package koshei.authoring

import koshei.episode.runtime.PicassoMode

enum class AutoCorrectMode { OFF, ALARM_ONLY, DISPATCH }

/**
 * How the drift auto-correct may act (design §14 D5, §16 C). In episode mode (`KOSHEI_PICASSO` ≠ off) drift never
 * dispatches: the episode loop is the one way to act, and drift is at most an alarm. Read once at start; a setting
 * that asks for both stops the start, so neither side silently loses.
 */
data class AutoCorrectGate(val mode: AutoCorrectMode, val episodeMode: Boolean) {
    /** `POST /api/autocorrect/sweep` always dispatches, so episode mode refuses it. */
    val oneShotAllowed: Boolean get() = !episodeMode

    companion object {
        fun fromEnv(env: Map<String, String>): AutoCorrectGate {
            val episodeMode = PicassoMode.fromEnv(env) != PicassoMode.OFF
            val disabled = env["KOSHEI_AUTOCORRECT_DISABLED"] == "1"
            val dispatch = env["KOSHEI_AUTOCORRECT_DISPATCH"] == "1"
            require(!(episodeMode && dispatch && !disabled)) {
                "KOSHEI_AUTOCORRECT_DISPATCH=1 with KOSHEI_PICASSO=${env["KOSHEI_PICASSO"]}: episode mode turns drift auto-dispatch off " +
                    "(design §14) — unset one of them"
            }
            val mode = when {
                disabled -> AutoCorrectMode.OFF
                dispatch -> AutoCorrectMode.DISPATCH
                else -> AutoCorrectMode.ALARM_ONLY
            }
            return AutoCorrectGate(mode, episodeMode)
        }
    }
}
```

빈 구성을 하나 더한다(같은 파일이나 `AutoCorrectGate` 옆):

```kotlin
@org.springframework.context.annotation.Configuration
class AutoCorrectGateConfig {
    @org.springframework.context.annotation.Bean fun autoCorrectGate(): AutoCorrectGate = AutoCorrectGate.fromEnv(System.getenv())
}
```

`AutoCorrectDispatcher.kt` 에 단발 경로가 쓰는 인터페이스를 둔다. 컨트롤러 시험이 가짜를 넘길 수 있게 하기 위해서다.

```kotlin
/** The one-shot sweep the endpoint runs (sweep, reconcile, dispatch). */
fun interface OneShotSweep {
    fun runOnce(modelDir: File): List<AutoCorrectAction>
}
```

`AutoCorrectDispatcher` 가 `OneShotSweep` 을 구현한다(`override fun runOnce`).

`AutoCorrectController` 는 `(sweeper: OneShotSweep, gate: AutoCorrectGate)` 를 받는다. `sweep()` 의 맨 앞에 넣는다.

```kotlin
        if (!gate.oneShotAllowed) return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf(
            "error" to "EPISODE_MODE",
            "detail" to "KOSHEI_PICASSO is not off: drift never dispatches in episode mode (design §14)",
        ))
```

Spring 이 `OneShotSweep` 자리에 `AutoCorrectDispatcher` 빈을 넣는다. 같은 인터페이스의 빈이 둘이 되지 않는지 확인한다.

`AutoCorrectBean` 은 `(dispatcher: AutoCorrectDispatcher, gate: AutoCorrectGate)` 를 받는다. `disabled` · `dispatch` 필드를 `gate.mode` 로 바꾼다.

```kotlin
        if (gate.mode == AutoCorrectMode.OFF) return
        …
            val actions = if (gate.mode == AutoCorrectMode.DISPATCH) dispatcher.runOnce(dir) else dispatcher.sweepAlarmOnly(dir)
```

- KDoc 에 「에피소드 모드에서는 경보만(`AutoCorrectGate`)」을 한 줄 더한다.
- 시작 때 `gate.episodeMode` 면 `log.info("auto-correct: episode mode — alarm only")` 를 한 번 남긴다. `init { … }` 블록은 `private val log = …` **뒤에** 둔다 — Kotlin 은 초기화를 적힌 순서로 돌리므로 앞에 두면 `log` 가 아직 null 이다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test :authoring-api:test`
Expected: 모두 PASS. `:authoring-api` 의 Spring 문맥 시험(`ContextLoadsTest`)이 새 빈과 함께 뜨는지 본다. 이 시험은 이제 `AutoCorrectGate.fromEnv(System.getenv())` 를 거친다. 셸에 `KOSHEI_PICASSO=mock` 과 `KOSHEI_AUTOCORRECT_DISPATCH=1` 이 함께 있거나 `KOSHEI_PICASSO` 에 모르는 값이 있으면 문맥이 뜨지 않는다 — 의도한 동작이다. 그런 셸이면 두 변수를 비우고 돌린다. 수를 보고한다.

- [ ] **Step 5: 커밋한다**

```bash
git add episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkers.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWorkersTest.kt scripts/run-fsm-autocorrect-dispatch-gate.sh authoring-api/src/main/kotlin/koshei/authoring/AutoCorrectGate.kt authoring-api/src/main/kotlin/koshei/authoring/AutoCorrectBean.kt authoring-api/src/main/kotlin/koshei/authoring/AutoCorrectController.kt authoring-api/src/main/kotlin/koshei/authoring/AutoCorrectDispatcher.kt authoring-api/src/test/kotlin/koshei/authoring/AutoCorrectGateTest.kt
git commit -m "feat(authoring-api): in episode mode drift alarms only - auto-dispatch with KOSHEI_PICASSO set stops the start, the one-shot sweep answers 409" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```


### Task 10: 감시자 프로세스 (`:app`)

**Files:**
- Create: `app/src/main/kotlin/koshei/app/Watcher.kt`
- Modify: `app/build.gradle.kts`
- Test: `app/src/test/kotlin/koshei/app/WatcherConfigTest.kt`

환경변수(시작 때 한 번 읽는다. 틀리면 시작을 멈춘다):

| 변수 | 뜻 | 기본 |
|---|---|---|
| `KOSHEI_WATCH_EXPORTS` | picasso 내보내기 디렉터리들. `File.pathSeparator`(Windows `;`, 그 밖 `:`)로 나눈다. 빈 칸은 거절한다 | 필수 |
| `KOSHEI_WATCH_CARRY` | 결과 통보 디렉터리(`koshei-carry-0`) | 없음(근거 운반 안 함) |
| `KOSHEI_WATCH_INTERVAL_MS` | 폴링 간격. 100 ~ 60000 | 2000 |
| `KOSHEI_EPISODE_POLICY` | 병합 규칙을 읽을 정책 파일. 워커와 같은 파일 | `model/episode-policy/active.yaml` |
| `KOSHEI_DB_*` | 커서 · 기록 표. 워커와 같은 DB | `Db.connect()` 기본 |
| `KOSHEI_PICASSO` | 에피소드 모드인가. 워커 · authoring-api 와 같은 해석(`PicassoMode.fromEnv`). `off`(기본)면 시작을 멈춘다 — 에피소드 워커가 없으면 감시자가 연 에피소드를 아무도 돌리지도 보지도 않는다(`signalWithStart` 는 그래도 만들고, 그것은 기다린다) | `off` → 시작 거절 |

- 상대 경로는 프로세스의 작업 디렉터리를 기준으로 풀린다. `./gradlew :app:watcher` 의 작업 디렉터리는 `app/` 이다. 그래서 시작 줄에 절대 경로를 찍는다.
- 세 프로세스(워커, 감시자, authoring-api)는 같은 `KOSHEI_PICASSO` 를 가져야 한다. 각 프로세스가 제 환경만 본다(Task 11 에서 §4.2 에 적는다).

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package koshei.app

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WatcherConfigTest {
    private val sep = File.pathSeparator
    private val on = "KOSHEI_PICASSO" to "mock"

    @Test fun `exports, carry, interval and policy come from the environment`() {
        val c = WatcherConfig.fromEnv(mapOf(
            on, "KOSHEI_WATCH_EXPORTS" to "a${sep}b", "KOSHEI_WATCH_CARRY" to "c", "KOSHEI_WATCH_INTERVAL_MS" to "500", "KOSHEI_EPISODE_POLICY" to "p.yaml",
        ))
        assertEquals(listOf(Path.of("a"), Path.of("b")), c.exports)
        assertEquals(Path.of("c"), c.carry)
        assertEquals(500, c.intervalMs)
        assertEquals(Path.of("p.yaml"), c.policyPath)
    }

    @Test fun `defaults`() {
        val c = WatcherConfig.fromEnv(mapOf(on, "KOSHEI_WATCH_EXPORTS" to "a"))
        assertNull(c.carry)
        assertEquals(2000, c.intervalMs)
        assertEquals(Path.of("model/episode-policy/active.yaml"), c.policyPath)
    }

    @Test fun `what is wrong stops the start`() {
        listOf(
            mapOf(on),
            mapOf(on, "KOSHEI_WATCH_EXPORTS" to ""),
            mapOf(on, "KOSHEI_WATCH_EXPORTS" to "a$sep"),
            mapOf(on, "KOSHEI_WATCH_EXPORTS" to "a", "KOSHEI_WATCH_INTERVAL_MS" to "50"),
            mapOf(on, "KOSHEI_WATCH_EXPORTS" to "a", "KOSHEI_WATCH_INTERVAL_MS" to "x"),
            mapOf(on, "KOSHEI_WATCH_EXPORTS" to "a", "KOSHEI_WATCH_CARRY" to " "),
        ).forEach { assertFailsWith<IllegalArgumentException>(it.toString()) { WatcherConfig.fromEnv(it) } }
    }

    @Test fun `outside episode mode the watcher does not start - no worker would run what it opens`() {
        assertFailsWith<IllegalArgumentException> { WatcherConfig.fromEnv(mapOf("KOSHEI_WATCH_EXPORTS" to "a")) }
        assertFailsWith<IllegalArgumentException> { WatcherConfig.fromEnv(mapOf("KOSHEI_PICASSO" to "off", "KOSHEI_WATCH_EXPORTS" to "a")) }
        assertFailsWith<IllegalArgumentException> { WatcherConfig.fromEnv(mapOf("KOSHEI_PICASSO" to "real", "KOSHEI_WATCH_EXPORTS" to "a")) }
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :app:test --tests "koshei.app.WatcherConfigTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현한다**

`app/src/main/kotlin/koshei/app/Watcher.kt`:

```kotlin
package koshei.app

import io.temporal.client.WorkflowClient
import io.temporal.serviceclient.WorkflowServiceStubs
import koshei.blocks.Db
import koshei.episode.runtime.EpisodeStore
import koshei.episode.runtime.EpisodeWatcher
import koshei.episode.runtime.PicassoMode
import koshei.episode.runtime.PolicyFileReader
import koshei.episode.runtime.TemporalEpisodeSignals
import koshei.episode.runtime.WatchStore
import koshei.runtime.DataConverterSupport
import java.io.File
import java.nio.file.Path

/** The watcher's settings (design §12), read once at start. */
data class WatcherConfig(val exports: List<Path>, val carry: Path?, val intervalMs: Long, val policyPath: Path) {
    companion object {
        fun fromEnv(env: Map<String, String>): WatcherConfig {
            require(PicassoMode.fromEnv(env) != PicassoMode.OFF) {
                "KOSHEI_PICASSO is off: no episode worker runs, so the episodes the watcher opens would never be run or seen " +
                    "(signalWithStart still creates them; they wait)"
            }
            val exports = requireNotNull(env["KOSHEI_WATCH_EXPORTS"]) { "KOSHEI_WATCH_EXPORTS: the picasso export directories, separated by '${File.pathSeparator}'" }
                .split(File.pathSeparator)
            require(exports.isNotEmpty() && exports.none { it.isBlank() }) { "KOSHEI_WATCH_EXPORTS has an empty entry: '${env["KOSHEI_WATCH_EXPORTS"]}'" }
            val carry = env["KOSHEI_WATCH_CARRY"]?.also { require(it.isNotBlank()) { "KOSHEI_WATCH_CARRY is blank" } }
            val interval = (env["KOSHEI_WATCH_INTERVAL_MS"] ?: "2000").toLongOrNull()
                ?.takeIf { it in 100..60_000 } ?: throw IllegalArgumentException("KOSHEI_WATCH_INTERVAL_MS must be 100..60000, was '${env["KOSHEI_WATCH_INTERVAL_MS"]}'")
            return WatcherConfig(
                exports.map { Path.of(it) }, carry?.let { Path.of(it) }, interval,
                Path.of(env["KOSHEI_EPISODE_POLICY"] ?: "model/episode-policy/active.yaml"),
            )
        }
    }
}

/**
 * The watcher process (design §4.2, §12): polls the exports and the carry, sends to Temporal on koshei's converter,
 * keeps its cursor in Postgres. It creates the episode tables if the worker has not yet (the script is idempotent).
 */
fun main() {
    val config = WatcherConfig.fromEnv(System.getenv())
    EpisodeStore { Db.connect() }.ensureSchema()
    val service = WorkflowServiceStubs.newLocalServiceStubs()
    val client = WorkflowClient.newInstance(service, DataConverterSupport.clientOptions())
    val watcher = EpisodeWatcher(config.exports, config.carry, PolicyFileReader(config.policyPath), WatchStore { Db.connect() }, TemporalEpisodeSignals(client))
    println("koshei watcher: exports=${config.exports.map { it.toAbsolutePath() }} carry=${config.carry?.toAbsolutePath() ?: "-"} " +
        "every ${config.intervalMs} ms (log: episode_watch_log)")
    Runtime.getRuntime().addShutdownHook(Thread { service.shutdown() })
    while (true) {
        try {
            val r = watcher.pollOnce()
            if (r.opened > 0 || r.evidence > 0) println("koshei watcher: opened ${r.opened}, evidence ${r.evidence}")
        } catch (e: Exception) {
            // The database or the file system failed mid-pass: nothing past the last cursor row is lost; try again.
            System.err.println("koshei watcher: pass failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        Thread.sleep(config.intervalMs)
    }
}
```

`koshei.blocks.Db` 의 패키지와 `connect()` 이름을 `Worker.kt` 의 import 에서 확인한다.

`app/build.gradle.kts`:
- `kosheiEnvKeys` 에 `"KOSHEI_WATCH_EXPORTS", "KOSHEI_WATCH_CARRY", "KOSHEI_WATCH_INTERVAL_MS"` 를 더한다.
- 작업을 더한다.

```kotlin
tasks.register<JavaExec>("watcher") {
    group = "application"
    mainClass.set("koshei.app.WatcherKt")
    classpath = sourceSets["main"].runtimeClasspath
    forwardKosheiEnv()
    // As for run: the working directory is app/, so the default relative policy path would miss the repo's model/.
    if (System.getenv("KOSHEI_EPISODE_POLICY") == null) {
        environment("KOSHEI_EPISODE_POLICY", rootProject.file("model/episode-policy/active.yaml").absolutePath)
    }
}
```

이 프로세스를 공용 Temporal · Postgres 에 띄우지 않는다. 컴파일과 설정 시험만 한다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :app:test :app:compileKotlin`
Expected: PASS(`:app` 은 Task 3 · 5 뒤의 수 + 4). `PicassoMode.fromEnv` 는 Task 9 에서 섰다

- [ ] **Step 5: 커밋한다**

```bash
git add app/src/main/kotlin/koshei/app/Watcher.kt app/src/test/kotlin/koshei/app/WatcherConfigTest.kt app/build.gradle.kts
git commit -m "feat(app): the watcher process - exports and carry from the environment, cursor in Postgres, gradle task watcher" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 11: 설계 문서

**Files:**
- Modify: `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md`

각 절 끝에 「**구현(C1).**」 단락을 더한다. 있던 글은 지우지 않는다.

- §4.1, §4.2:
  - 감시자의 자리: 로직은 `:episode-runtime` 의 `EpisodeWatcher`, 프로세스는 `:app` 의 `Watcher.kt`(`./gradlew :app:watcher`)다.
  - 환경변수 표(Task 10). 상대 경로는 작업 디렉터리(`:app:watcher` 는 `app/`) 기준이다.
  - authoring-api 와 감시자도 `KOSHEI_PICASSO` 를 읽는다(Task 9 · 10). 각 프로세스는 제 환경만 보므로 워커 · 감시자 · authoring-api 가 같은 값을 가져야 한다 — 다른 셸에서 띄운 authoring-api 는 에피소드 모드를 모른다.
  - 감시자는 `KOSHEI_PICASSO=off` 면 시작하지 않는다.
- §7.1: 병합 키의 모양(`<runId>:<id>`, `<runId>:by:<f>:<v>…`, 200자까지). 애매하면 제 키를 쓰고 `AMBIGUOUS` 기록에 서로 참조를 남긴다. 한계 셋을 적는다.
  - 서로 참조는 감시 기록에만 있고 에피소드 기록에는 붙지 않는다(§12 의 「서로 참조를 붙인다」를 반만 한다).
  - 병합 규칙이 켜져도, 뒤 증상이 다음 폴링에 `correlatingMs` 를 넘겨 오면 합류만 한다(ESCALATED 면 기록만).
  - 신호와 커서 사이에 감시자가 죽고 그 사이 에피소드가 끝났으면, 다시 보낸 증상은 새 에피소드를 연다.
- §7.2 구현(B3c) 의 CLI 단락 뒤에 「구현(C1)」을 더한다.
  - 384행 부근의 「계획 C 에서 감시자가 지킨다」를 닫는다: 감시자는 `CORRELATION_KEY`(200자까지)로 키를 지키고, 맞지 않으면 그 줄에서 멈춘다. CLI 의 `--key` 는 길이 상한이 없다 — 그 차이를 적는다.
  - CLI 는 이제 감시자와 같은 `ExportSymptoms` · `Bundles` 로 읽는다: manifest 의 `schemaVersion "5"` 와 `counts` 가 필요하고, `counts` 뒤의 줄은 읽지 않으며, 두 파일 어디든 깨진 줄이나 겹친 id 가 있으면 열지 않는다. 보내는 길은 `TemporalEpisodeSignals` 다.
- §10.1: `correlation` 규칙의 모양과 검사(Task 1). v1 은 `[]`.
- §12:
  - manifest 로 경계 지은 읽기와 다시 읽기.
  - 커서 표(추가 전용, 줄 수 + 마지막 id)와 `STUCK`.
  - 감시 기록의 종류.
  - 개발용 나르는 모양 `koshei-carry-0`. picasso `JobResponse` 에 `executionId` 가 없어 담는 쪽이 덧붙인다는 것. 싣지 않는 칸(`requiredEvidence`, `blockedBy`, `connection` …)과 그 까닭.
  - 주문으로 찾아 **인스턴스에** 보내기(APPROVE_REMEDY 의도만). 같은 키의 다음 에피소드는 앞 에피소드의 통보를 받지 않는다.
  - 한 `executionId` 를 두 에피소드가 이을 수 있다는 한계(범위 밖 표).
  - 「결과 통보는 `APPROVED` 답의 `executionId` 로 이 시도에 잇는다」(952행 부근)에 가리킴을 단다: 「C1: picasso `JobResponse` 에는 이 칸이 없다 — §12 구현(C1), §19」.
- §13: 새 표 둘(`episode_watch_cursor`, `episode_watch_log`)과 색인 — 표 셋에서 다섯이 된다. 둘 다 추가 전용이다(계획 D 의 권한 회수에 넣는다). 스키마는 워커만이 아니라 감시자도 시작 때 돌린다(멱등, advisory lock).
- §14 의 drift 행: 에피소드 모드에서 경보만 한다. 표(Task 9)를 함께 적는다.
- §18 에 판단 하나를 더한다: **에피소드 모드에서는 단발 `POST /api/autocorrect/sweep` 도 409 다.** §14 는 「자동 디스패치 끔」만 말한다. 단발 경로는 운영자의 명시적 행동이지만 늘 디스패치하므로, 에피소드 모드에서 열어 두면 에피소드 밖에서 조치가 나가는 두 번째 길이 된다. 관문 스크립트는 `KOSHEI_PICASSO=off` 를 밝혀 그대로 돈다.
- §16: 「**C1 끝(날짜).**」 단락을 더한다. 계획 C 에 남은 것:
  - 실물 승인 창구 클라이언트(§19 C, B).
  - drift 를 둘째 입력으로 — 코어에 `DRIFT` 증상 종류와 그 투영이 먼저 필요하다.
  - 나르는 모양을 담는 쪽과 정하기(§19 C).
  - 제어면의 503 사유(`EpisodeController` 의 「no episode worker has run (KOSHEI_PICASSO)」)는 이제 감시자가 먼저 떠도 풀린다 — 글을 고칠지는 계획 D 에서 본다.
- §19 기다리는 것 표에 두 행을 더한다.
  - **picasso 승인 창구 schema `3`** (picasso `cf81e02` · `5881e24`, 2026-10-01): `CONSUMED` 거절과 `consumed` 칸, 이미 실행이 선 주문의 승인 거절. koshei 는 `2` 만 읽으므로 실물 답은 모두 `DISPATCH_ANSWER_UNKNOWN` 이다. 판 올림은 §19 B(`CONSUMED` 를 읽을지)의 결정과 함께 한다. 막는 항목: 실물 클라이언트.
  - **결과 통보의 `executionId`**: picasso `JobResponse` 에는 이 칸이 없다(주문은 `jobOrderId` 뿐). 담는 쪽이 덧붙일지, picasso 가 실을지 정한다(§19 C 와 함께). 그 전까지 `koshei-carry-0` 은 담는 쪽이 덧붙인다고 가정한다. 1150행 부근의 「대상 단위」 행(「실행은 … `executionId` 로 잇는다」)에도 이 행을 가리키는 한 줄을 단다.
- §19 사용자가 정할 것 표:
  - B 행에 날짜를 단 메모: picasso 가 `CONSUMED` 를 이미 냈다(schema `3`, `cf81e02`, 2026-10-01). 결정은 그대로 사용자의 것이고, 위 「schema 3」 행이 막는 항목이다.
  - C 행의 「그 전까지」 칸에 더한다: 개발용 나르는 모양 `koshei-carry-0` 과 Mock 담는 쪽(시험 전용).

- [ ] **Step 1: 쓴다**
- [ ] **Step 2: 커밋한다**

```bash
git add docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md
git commit -m "docs(design): plan C1 - watcher, merge keys, carry shape, drift alarm-only in episode mode; picasso approval schema 3 and JobResponse executionId as open items" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### 마지막 확인

Run: `./gradlew :episode:test :episode-runtime:test :app:test :authoring-api:test`
Expected: 모두 PASS. 모듈마다 수를 보고한다.

## 검토 뒤 바뀐 것

2026-10-02, 구현 기준(`f885319..83f2408`). 계획의 글과 코드가 일부러 다른 곳이다. 설계 문서의 「구현(C1)」 단락이 코드대로 적는다.
마지막 확인(`KOSHEI_*` 없이): `:episode` 312, `:episode-runtime` 249, `:app` 35, `:authoring-api` 146, 모두 통과.

- **근거 signal 은 run 에 고정한 stub 으로 보내지 않는다(Task 5).** Temporal Java SDK 1.25.1 은 run 에 고정한 stub 의 **signal** 에서 run id 를
  떨군다(`WorkflowStub.signal` 은 지금 실행으로 간다). Update 와 질의는 고정을 지킨다. 그래서 `TemporalEpisodeSignals.evidence` 는 describe
  (RUNNING) → 고정한 stub 의 `view().instanceId` 확인 → 워크플로 id 와 run id 를 적은 날 `SignalWorkflowExecutionRequest` 로 보낸다. NOT_FOUND 는
  「그런 run 없음」(`false`)이지만 `NamespaceNotFoundFailure` 를 든 것은 다시 던진다. 제어면은 Update 를 쓰므로 영향이 없다. (설계 §7.2 · §12 구현(C1))
- **`Bundles` 의 읽기 규칙(Task 2).**
  - manifest 를 다시 읽어 글자 그대로 같아야 한다. 줄 파일을 읽다 나온 깨짐 판정도 그 사이 manifest 가 바뀌었으면 `NotReady` 다(갈아 쓰는
    묶음은 깨져 보일 수 있다).
  - UTF-8 이 아닌 바이트는 묶음 전체의 예외가 아니라 그 파일을 든 `Broken` 이다.
  - manifest 는 1 MiB, 줄 파일은 64 MiB 까지만 읽는다(계획에는 줄 파일 상한만 있었다).
  - 건너뛰는 줄은 비었거나 공백 · 탭 · CR 만 든 줄뿐이다(계획은 `isNotBlank`, 곧 유니코드 공백도 건너뛰었다).
  - 센 수가 0 인 파일은 읽지 않는다.
  - 깨짐의 사유에 Jackson 의 원인 글(`originalMessage`)을 붙인다.
  - JSON 이 아니거나 id 가 없는 줄은 묶음 전체를 멈추고, 읽히지만 쓸 수 없는 줄(digest 없는 사건, 모양이 틀린 결과 통보 칸)은 그 파일을 그
    줄에서 멈춘다 — 계획 그대로이고, 설계 §12 에 밝혀 적었다.
- **CLI 의 NotReady 거절 글(Task 3).** 계획의 「an export is read only once its manifest is there and complete」 대신 사유 한 줄과 「not ready yet;
  read again once the export is complete」다. 까닭이 여럿(manifest 없음, 줄이 모자람, 읽는 사이 바뀜)이라 하나로 짚지 않는다.
- **커서와 감시 기록(Task 4).** `advance` 는 지금 자리 + 1 을 넘는 자리(빈틈)와 이미 있는 자리의 다른 id 를 거절한다. `log` 는 jsonb 가 detail 을
  거절하면(NUL, 짝 없는 서로게이트 — SQLState 22 계열) 같은 원천 · 종류로 `{"unstorable":true}` 행을 쓴다.
- **한 번만 남기기(Task 6 · 7).**
  - 프로세스마다, 사유를 열쇠로 한다. 계획과 같다.
  - `SIGNAL_FAILED` 의 열쇠는 글이 아니라 예외의 클래스와 gRPC 상태 코드다. 글은 detail 에 남는다.
  - 기억은 1000개까지다. 차면 비운다.
  - `UNROUTED` 는 (원천, run, 자리), `ROUTE_ENDED` 는 (원천, run, 자리, 인스턴스)마다 한 번이다. 계획은 줄마다의 사실이라 `once` 를 쓰지 않고
    실패 뒤 다시 나른 줄이 한 번 더 남는 것을 받아들였다. 이제 한 줄에 한 번이다. `AMBIGUOUS` 는 계획대로 보낼 때마다 남는다(마지막 검토에서
    바뀌었다, 아래).
  - 사유는 그 행을 쓴 뒤에만 기억한다. 쓰기가 실패하면 다음 폴링에 다시 쓴다.
- **원천 이름은 실제 경로(Task 6).** `toAbsolutePath().normalize()` 대신 폴링마다 `toRealPath()` 다(아직 없으면 절대 정규 경로). 그래서 내보내기
  경로는 안정해야 한다. 알려진 한계: 같은 `runId` 를 든 내보내기 디렉터리 둘은 키와 `eventId` 가 부딪친다(설계 §7.1 의 키에는 경로가 없다).
- **나른 수는 `Carried(count, stopped)` 하나로 센다(Task 6 · 7).** 계획은 신호가 실패하면 그 디렉터리의 수를 버렸다(`null`). 이제 실패 앞에 나른
  줄도 `PollReport` 에 들고, 접수와 근거가 같은 결과를 쓴다.
- **`CorrelationRule` 이 스스로 검사한다(Task 1).** 계획은 `data class` 만 두었다. 이제 `init` 에서 정책 읽개와 같은 모양(종류 하나 이상, 칸 1 ~ 4개,
  칸 이름 모양, 겹침 없음)을 요구한다 — 칸 없는 규칙이 키 `<runId>:by` 로 모든 것을 묶는 일이 없게.
- **`PolicyReader` 는 바꿀 수 없는 규칙을 만든다(Task 1).** `kinds` 와 `by` 를 `unmodifiableSet` · `unmodifiableList` 의 사본으로 싣는다.
- **좁은 규칙과 넓은 규칙(Task 1, 설계 §7.1 · §10.1).** `[robotId]` 옆에 `[robotId, jobOrderId]` 를 두면 두 칸을 다 든 줄은 모두 애매하다. 좁은
  규칙이 병합을 다듬지 않는다. 의도한 것이다(「서로 다르게 말하면 아무것도 묶지 않는다」). 문서에 밝혀 적었다.
- **drift(Task 9).** 에피소드 모드에서도 `DISABLED=1` 이 `DISPATCH=1` 을 이긴다(꺼짐, 시작한다). 계획의 표 그대로이고, 설계 §14 구현(C1)에 표를 옮겼다.

마지막 검토 뒤(2026-10-02, `9341c36` 다음 커밋):

- **`SIGNAL_FAILED` 는 어느 줄인지 적는다(Task 6 · 7).** detail 에 `runId`, `position`, 줄의 `id`, 증상이면 `eventId`, 근거면 보내던 `instanceId` 가
  든다. 열쇠는 예외 클래스 | 상태 코드에 원천 | run | 자리를 더한다 — 한 줄에서 한 번의 장애는 행 하나이고, 다음 줄이 막히면 새 행이다.
- **알려진 한계: 늘 실패하는 줄.** 신호가 늘 실패하는 줄(크기를 넘은 페이로드의 `INVALID_ARGUMENT`, `view()` 질의가 계속 실패하는 인스턴스)은
  폴링마다 이번 폴링을 그 줄에서 멈춘다 — 그 뒤의 줄과 모든 근거가 기다린다. 그 줄의 `SIGNAL_FAILED` 행이 줄을 짚는다. 설계 §12 구현(C1)과
  `EpisodeWatcher` KDoc 에 적었다.
- **결과 통보는 주문만으로 찾는다(Task 7).** 설계 §12 는 `(기체, 주문)` 을 말하지만 picasso `JobResponse` 에는 기체 id 가 없다(`jobOrderId` 뿐).
  `instancesForOrder` 는 주문 하나로 맞추고, 다른 시도의 통보를 받은 인스턴스는 기록만 한다(코어가 `executionId` 로 잇는다). 설계 §12 구현(C1)과
  `instancesForOrder` KDoc 에 적었다.
- **`AMBIGUOUS` 는 보낸 뒤에, 줄마다 한 번(Task 6).** `open` 이 성공한 뒤에만 남기고, (원천, run, 자리)를 열쇠로 `once` 를 쓴다 — 장애 동안
  폴링마다 행이 늘지 않는다.
- **`PinnedRun.stub` 을 지웠다.** 읽는 곳이 없었다(제어면의 `EpisodeGateway` 는 제 것을 쓴다). `WatchStore` 의 `open` 은 시험이 상속하므로 남기고
  KDoc 에 「open for tests」를 적었다.
- **`KOSHEI_PICASSO=off` 를 거절하는 까닭(Task 10).** 증상이 사라지는 것이 아니다 — 에피소드 워커가 없으면 감시자가 연 에피소드를 아무도
  돌리지도 보지도 않는다(`signalWithStart` 는 그래도 만들고, 그것은 기다린다). 설계 §4.2, `WatcherConfig.fromEnv` 의 글, 위 Task 10 을 고쳤다.
- **CLI 와 감시자의 차이(설계 §7.2 구현(C1)).** CLI 의 기본 키는 id 에 `:` 가 있거나 64자를 넘어도 연다(감시자는 `STUCK`). CLI 는 병합 규칙을
  보지 않는다(줄 하나, 제 키). 감시자는 묶는다.
- **같은 방식으로 매번 실패하는 폴링(`Watcher.kt`).** 정책 읽기가 던지거나 커서 검사가 실패하는 폴링은 간격마다 stderr 에 찍히고 감시 기록에는
  행이 남지 않는다. 개발 프로세스라 받아들인다 — 주 반복문에 주석으로 적었다.

