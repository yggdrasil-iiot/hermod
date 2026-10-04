# 계획 D-lite — 에이전트 층 끄기 방송 · 커밋된 재생 이력 · 기록 표 권한 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 설계 §16 의 계획 D(운영 관리) 가운데 작고 값이 큰 셋만 세운다. 나머지 D 는 사용자 결정으로 범위 밖에 둔다(2026-10-04).
1. **에이전트 층 끄기 방송(R5, §10.4).** 열린 에피소드 **전체**에 `agentOff` signal 을 한 번에 보낸다. 지금은 개발 CLI 가 하나씩 보낸다.
2. **커밋된 재생 이력(R3, §7.5).** 상태마다 멈춘 Temporal 이력을 저장소에 커밋해 두고, 시험이 오늘의 코드로 그것을 재생한다. 명령 순서를 바꾼 변경이 진행 중인 에피소드를 깨면 시험이 잡는다.
3. **기록 표의 권한(R11, §13).** 런타임 전용 DB 역할을 만드는 스크립트: 다섯 표에 `SELECT` · `INSERT` 만, `UPDATE` · `DELETE` · `TRUNCATE` 는 없다. 시험이 그 역할로 실제로 막히는지 본다.

**Architecture:**
- 방송은 `:episode-runtime` 의 작은 객체 `AgentOffBroadcast(client)` 다. Temporal 가시성에서 열린 `EpisodeWorkflow` 를 나열하고(옛 API `ListOpenWorkflowExecutions` — 시험 서버가 새 `ListWorkflowExecutions` 를 구현하지 않는다) 저마다 `agentOff` 를 보낸다. 개발 CLI 가 `episode agent-off --all` 로 부른다. 코어와 워크플로는 바뀌지 않는다 — `agentOff` 를 받는 쪽은 이미 있다(§10.4, B3).
  - 워크플로 id 에만 고정한 stub 으로 보낸다(그 id 의 **지금 run**). 열린 에피소드 전체가 대상이므로 run 을 고정할 까닭이 없다.
  - 나열과 보내기 사이에 끝난 run 은 `ended` 로 센다. 실패는 `failed` 로 세고 나머지를 계속 보낸다. 한 번 더 돌리면 된다 — `agentOff` 는 몇 번 받아도 같다(코어가 이미 끈 에피소드에 다시 와도 기록만 한다).
- 재생 이력은 `episode-runtime/src/test/resources/replay/*.json`(Temporal `WorkflowExecutionHistory.toJson`)이다.
  - 생성기는 시스템 속성 `koshei.writeReplayHistories=true` 일 때만 도는 시험이다. 평소에는 건너뛴다. 새 묶음을 쓰는 것은 **명령 순서를 일부러 바꾸고 `Workflow.getVersion` 을 건 뒤**뿐이다(§7.5).
  - **(실행 중 리뷰로 바꿈) 이력은 묶음(`replay/<묶음 이름>/`)으로 두고 덮어쓰지 않는다.** 생성기는 `-Dkoshei.replaySet=<이름>`(기본은 오늘 날짜)의 새 묶음만 쓰고, 그 디렉터리가 있으면 거절한다. 옛 코드가 남긴 묶음은 그 `getVersion` 갈래를 지울 때 함께만 지운다 — 덮어쓰면 R3 가 지키려는 「옛 이력이 새 코드에서 재생된다」가 사라진다.
  - 재생 시험은 모든 묶음의 모든 파일을 koshei 의 변환기로 재생한다(`EpisodeReplayTest` 와 같은 방법 — 기본 변환기로는 재생되지 않는다). 가장 새 묶음에는 시나리오 아홉이 다 있어야 한다. 재생 워커는 교착 감지를 늦춘다(차가운 JVM 의 첫 재생이 1초 감지기에 걸려 결정성 오류처럼 보였다). 실패 글은 비결정성인지 다른 오류인지를 가른다.
- 권한은 스크립트 `scripts/episode-db-roles.sql` 이다. 스키마(`episode-schema.sql`)는 소유 계정이 적용한다. 런타임 역할은 표를 고치지 않는다.
  - Postgres 15 부터 `public` 스키마의 `CREATE` 는 PUBLIC 에 없다. `CREATE TABLE IF NOT EXISTS` 도 표가 있어도 스키마의 `CREATE` 를 먼저 검사하므로 런타임 역할에서는 실패한다. 그래서 `EpisodeStore.ensureSchema()` 는 스키마에 `CREATE` 권한이 없으면 DDL 을 건너뛰고, 다섯 표와 색인이 있는지만 확인한다. 없으면 「소유 계정으로 episode-schema.sql 을 먼저 적용하라」고 실패한다.
    - **실행 중 바뀜(`26a8c8f`):** 객체가 다 있으면 `CREATE` 가 아니라 `episode_event` 의 소유로 정한다 — 설계 §13 구현(D-lite).
  - 워커는 같은 DB 연결로 블록 레지스트리 표도 읽는다. 그래서 워커 전체를 런타임 역할로 돌릴 수 없다. 에피소드 표만 쓰는 연결(`EpisodeStore`, `WatchStore`)에 따로 계정을 준다: `KOSHEI_EPISODE_DB_USER` · `KOSHEI_EPISODE_DB_PASS`(없으면 `KOSHEI_DB_USER` · `KOSHEI_DB_PASS`). URL 은 같다.

**Tech Stack:** Kotlin 2 / JVM 21, Temporal Java SDK 1.25.1(`TestWorkflowEnvironment`, `WorkflowReplayer`), plain JDBC + Testcontainers Postgres 16, Gradle.

**작업 위치:** worktree `C:\Users\Eisen\Desktop\Labs\[projects] koshchei\koshei-episode-core`, 브랜치 `feat/episode-core`(머리 `ae64ac9`).
- 원래 체크아웃(`koshei/`)은 건드리지 않는다. picasso · narrator 저장소는 읽기만 한다.
- 파일은 Write/Edit 로만 쓴다. bare `git stash` 를 쓰지 않는다. 푸시하지 않는다.
- 공용 Temporal · Postgres(`koshei-temporal-1`, `koshei-postgres-1`)는 시험에 쓰지 않는다. 시험은 `TestWorkflowEnvironment` 와 Testcontainers 로만 돈다. 워커 · 감시자를 띄우지 않는다. Gradle 데몬을 죽이지 않는다(`--stop` 금지).
- 시스템 속성은 bash 에서 넘긴다. PowerShell 5.1 은 따옴표 없는 `-Dkoshei.writeReplayHistories=true` 를 쪼갠다(쓰려면 `"-Dkoshei.writeReplayHistories=true"`).

**커밋 규칙:** `git commit -m "<제목>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`. `git add` 는 경로를 밝혀서.

**기준 시험 수 (머리 `ae64ac9`):** `:episode` 323, `:episode-runtime` 272, `:authoring-api` 146, `:app` 35. 셀 때는 `<모듈>/build/test-results/test/*.xml` 의 `tests=` 를 더한다. 모든 모듈을 다시 돌리려면 작업마다 `--rerun` 을 붙인다(`./gradlew :episode:test --rerun :episode-runtime:test --rerun …`).

**범위 밖 (계획 D 에서 빼는 것, 사용자 결정 2026-10-04):**

| 무엇 | 왜 빼나 | 그때까지 |
|---|---|---|
| 정책 관리: git 정본 + DB 활성 판 포인터 + 감사, CLI `policy validate` · `activate`(§10.3) | 지금은 파일 정책 하나로 충분하다. 런타임은 매 결정마다 파일을 읽는다(§8.2) | 정책은 `model/episode-policy/active.yaml`. 끄기는 파일의 `agentLayerEnabled: false` + 이 계획의 방송 |
| `SAGA_ACTION` · 조치 목록 · 자식 사가(§8.4, §9) | 지금 에피소드의 조치는 picasso 승인뿐이다. koshei 가 직접 하는 조치는 새 능력이다 | `actionCatalog: []` |
| 블록의 「모름」 보고, safe-hold 결함 D5(§14) | 자식 사가가 없으면 에피소드 경로에 닿지 않는다 | §14 의 기록 그대로 |
| 기록 필수 항목 전체 · 품질 이력 연결(작업 id, 차대번호)(§13) | 품질 시스템과의 잇기는 그쪽 결정이 먼저다 | 지금의 기록 항목 |
| 감시 기록 화면 · 애매한 병합의 서로 참조를 에피소드 기록에 붙이기(§12) | 운영 편의. 기록은 이미 `episode_watch_log` 에 있다 | 표를 직접 읽는다 |

---

### Task 1: `AgentOffBroadcast` — 열린 에피소드 전체에 `agentOff`

**Files:**
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/AgentOffBroadcast.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/AgentOffBroadcastTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

같은 디렉터리의 `EpisodeSignalsTest.kt` 가 쓰는 도우미(`episodeEnvironment { fakeWorkers(acts, ProgrammableNarrator()) }`, `TemporalEpisodeSignals.open`, `stub(id).until(env) { … }`, `searchSignal(...)`, `Picasso.manifest`)를 그대로 쓴다. 정책은 `FakeEpisodeActivities` 의 기본(자동 승인 켜짐)이 아니라, 에피소드가 열린 채 머물도록 `acts.policyJson = TestPolicies.table(autoApprove = false)` 로 둔다(AWAITING_APPROVAL 에서 멈춘다).

```kotlin
package koshei.episode.runtime

import io.temporal.client.WorkflowClient
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentOffBroadcastTest {
    private val acts = FakeEpisodeActivities().apply { policyJson = TestPolicies.table(autoApprove = false) }
    private lateinit var env: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient

    @BeforeEach fun up() {
        env = episodeEnvironment { fakeWorkers(acts, ProgrammableNarrator()) }
        env.start()
        client = env.workflowClient
    }

    @AfterEach fun down() = env.close()

    private fun stub(id: String) = client.newWorkflowStub(EpisodeWorkflow::class.java, id)

    private fun waiting(id: String, search: String): String {
        TemporalEpisodeSignals(client).open(id, EpisodeStart(Picasso.manifest), searchSignal(search))
        return stub(id).until(env) { it.phase == "AWAITING_APPROVAL" }.instanceId
    }

    @Test fun `every open episode is switched off - one broadcast reaches them all`() {
        waiting("ep:b-1", "search-1")
        waiting("ep:b-2", "search-2")
        val report = AgentOffBroadcast(client).send()
        assertEquals(setOf("ep:b-1", "ep:b-2"), report.sent.toSet())
        assertEquals(emptyList(), report.failed)
        for (id in listOf("ep:b-1", "ep:b-2")) assertEquals("ESCALATED", stub(id).until(env) { it.phase == "ESCALATED" }.phase, id)
    }

    @Test fun `an ended episode is not sent to - and a second broadcast changes nothing`() {
        waiting("ep:b-3", "search-1")
        stub("ep:b-3").takeover(TakeoverRequest("op-1"))
        stub("ep:b-3").close(CloseRequest("op-1", "done"))
        stub("ep:b-3").result()
        waiting("ep:b-4", "search-2")
        assertEquals(listOf("ep:b-4"), AgentOffBroadcast(client).send().sent)
        stub("ep:b-4").until(env) { it.phase == "ESCALATED" }
        val again = AgentOffBroadcast(client).send()
        assertEquals(AgentOffBroadcast.Report(listOf("ep:b-4"), emptyList(), emptyList()), again, "ESCALATED is still open; it only records the second agentOff")
        assertEquals("ESCALATED", stub("ep:b-4").view().phase)
    }

    @Test fun `a run that ended between the listing and the signal is counted as ended`() {
        waiting("ep:b-5", "search-1")
        stub("ep:b-5").takeover(TakeoverRequest("op-1"))
        stub("ep:b-5").close(CloseRequest("op-1", "done"))
        stub("ep:b-5").result()
        val report = AgentOffBroadcast(client) { listOf("ep:b-5") }.send()   // a listing taken before it ended
        assertEquals(AgentOffBroadcast.Report(emptyList(), listOf("ep:b-5"), emptyList()), report)
    }

    @Test fun `no open episode - nothing is sent`() {
        val report = AgentOffBroadcast(client).send()
        assertEquals(AgentOffBroadcast.Report(emptyList(), emptyList(), emptyList()), report)
    }
}
```

- `agentOff` 를 받은 AWAITING_APPROVAL 의 에피소드가 ESCALATED(`AGENT_LAYER_OFF`)로 가는 것은 B3 에서 이미 정해진 동작이다(§10.4). 다른 상태로 간다면 그 상태를 기대로 쓰고, 그 까닭(코어의 전이)을 보고에 적는다.
- ESCALATED 는 끝 상태가 아니다(보존 기한까지 run 이 열려 있다). 그래서 둘째 방송은 그 run 에도 다시 보낸다. 코어는 이미 끈 에피소드에 온 `agentOff` 를 기록만 한다 — `failed` 가 비어 있으면 된다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.AgentOffBroadcastTest"`
Expected: 컴파일 실패(`AgentOffBroadcast` 가 없다)

- [ ] **Step 3: 구현한다**

```kotlin
package koshei.episode.runtime

import io.temporal.api.filter.v1.WorkflowTypeFilter
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException

/**
 * The agent-layer switch's broadcast (design §10.4, R5): `agentOff` to every open episode at once, so a waiting episode
 * learns now rather than at its next decision. Each goes to the workflow id's current run — every open episode is meant,
 * so no run is pinned. Sending twice is harmless: the core only records an `agentOff` for an episode already switched off.
 *
 * At least once per call: a run that ended between the listing and the signal counts as [Report.ended]; any other
 * failure as [Report.failed], and the rest are still sent — run it again to retry them. Visibility is eventually
 * consistent: an episode that opens during the broadcast may be missed — switching the policy file off first (§10.4)
 * covers it, since every new episode reads the policy at its start.
 */
class AgentOffBroadcast(
    private val client: WorkflowClient,
    private val openEpisodes: () -> List<String> = { openEpisodeIds(client) },
) {
    data class Report(val sent: List<String>, val ended: List<String>, val failed: List<Pair<String, String>>)

    fun send(): Report {
        val sent = ArrayList<String>()
        val ended = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        for (id in openEpisodes()) {
            try {
                client.newWorkflowStub(EpisodeWorkflow::class.java, id).agentOff()
                sent += id
            } catch (e: WorkflowNotFoundException) {
                ended += id
            } catch (e: RuntimeException) {
                failed += id to (e.message ?: e.javaClass.simpleName)
            }
        }
        return Report(sent, ended, failed)
    }

    companion object {
        /** The workflow type of [EpisodeWorkflow] (its interface name; no explicit name is given). */
        const val TYPE = "EpisodeWorkflow"

        /**
         * Workflow ids of open episode workflows, page by page. The legacy `ListOpenWorkflowExecutions`: Temporal's test
         * server implements it and not `ListWorkflowExecutions`; the shared dev server (SQL visibility) serves both. The
         * type is checked again here — the test server ignores the type filter, and the broadcast must not widen.
         */
        fun openEpisodeIds(client: WorkflowClient): List<String> {
            val stub = client.workflowServiceStubs.blockingStub()
            val ids = LinkedHashSet<String>()
            var token = com.google.protobuf.ByteString.EMPTY
            do {
                val page = stub.listOpenWorkflowExecutions(
                    ListOpenWorkflowExecutionsRequest.newBuilder()
                        .setNamespace(client.options.namespace)
                        .setTypeFilter(WorkflowTypeFilter.newBuilder().setName(TYPE))
                        .setMaximumPageSize(PAGE)
                        .setNextPageToken(token)
                        .build(),
                )
                page.executionsList.filter { it.type.name == TYPE }.forEach { ids += it.execution.workflowId }
                token = page.nextPageToken
            } while (!token.isEmpty)
            return ids.toList()
        }

        private const val PAGE = 1000
    }
}
```

- `ListOpenWorkflowExecutionsRequest` · `WorkflowTypeFilter` 는 `io.temporal.api` 의 생성된 proto 다(SDK 1.25.1 에 들어 있다). `com.google.protobuf` 는 그것이 끌어온다 — import 로 올려도 된다.
- `send()` 의 `for (id in openEpisodes())` 는 생성자 인자 `openEpisodes` 를 부른다(이름이 같으므로 위 본문 그대로 된다).
- 시험 서버의 `listOpenWorkflowExecutions` 가 쪽 나누기를 안 하면 토큰이 비어 한 번에 끝난다 — 그것으로 된다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.AgentOffBroadcastTest"`
Expected: PASS (4개)

- [ ] **Step 5: 커밋한다**

```bash
git add episode-runtime/src/main/kotlin/koshei/episode/runtime/AgentOffBroadcast.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/AgentOffBroadcastTest.kt
git commit -m "feat(episode-runtime): AgentOffBroadcast - agentOff to every open episode at once" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 개발 CLI `episode agent-off --all`

**Files:**
- Modify: `app/src/main/kotlin/koshei/app/Cli.kt` (`episode` 의 `agent-off` 갈래, 사용법 글 36 · 56 · 501행 근처)
- Modify: `app/src/main/kotlin/koshei/app/EpisodeCli.kt` (`agentOffAll`)
- Test: `app/src/test/kotlin/koshei/app/EpisodeCliTest.kt` (인자 해석만 — 공용 Temporal 에 닿지 않는다)

- [ ] **Step 1: 인자 해석을 따로 뺀다 — 실패하는 시험부터**

`Cli.kt` 의 `agent-off` 갈래(71-78행 근처)는 지금 인자 셋(`episode agent-off <workflowId>`)만 받는다. 해석을 시험할 수 있게 `EpisodeCli` 에 순수 함수를 둔다:

```kotlin
    /** What `episode agent-off` was asked: one episode (its workflow id, checked) or every open one. */
    sealed interface AgentOffTarget {
        data class One(val workflowId: String) : AgentOffTarget
        data object All : AgentOffTarget
    }

    /** [args] after `episode agent-off`: exactly one `<workflowId>` or `--all`. */
    fun agentOffTarget(args: List<String>): AgentOffTarget {
        require(args.size == 1) { "episode agent-off takes exactly one of <workflowId> or --all" }
        return if (args[0] == "--all") AgentOffTarget.All else AgentOffTarget.One(checkWorkflowId(args[0]))
    }
```

`EpisodeCliTest.kt` 에 시험을 더한다:

```kotlin
    @Test fun `agent-off takes one episode or all of them`() {
        assertEquals(EpisodeCli.AgentOffTarget.All, EpisodeCli.agentOffTarget(listOf("--all")))
        assertEquals(EpisodeCli.AgentOffTarget.One("ep:k-1"), EpisodeCli.agentOffTarget(listOf("ep:k-1")))
        for (bad in listOf(emptyList(), listOf("--all", "ep:k-1"), listOf("k-1"), listOf("--ALL")))
            assertFailsWith<IllegalArgumentException>("$bad") { EpisodeCli.agentOffTarget(bad) }
    }
```

(`--ALL` 은 `ep:` 로 시작하지 않으므로 `checkWorkflowId` 가 거절한다.)

Run: `./gradlew :app:test --tests "koshei.app.EpisodeCliTest"`
Expected: 컴파일 실패

- [ ] **Step 2: 구현한다**

`EpisodeCli` 에 더한다(`agentOff` 아래):

```kotlin
    /** `agentOff` to every open episode (design §10.4); the broadcast's report. */
    fun agentOffAll(): AgentOffBroadcast.Report = withClient { client -> AgentOffBroadcast(client).send() }
```

`Cli.kt` 의 `agent-off` 갈래를 `EpisodeCli.agentOffTarget(args.drop(2))` 로 바꾼다. 해석 실패(`IllegalArgumentException`)는 지금처럼 `usageError`. `One` 이면 지금처럼 보내고 `agent-off sent to <id>` 를 찍는다. `All` 이면:
- `agentOffAll()` 을 `try { … } catch (e: Exception) { episodeFailed("agent-off", e) }` 로 부른다(나열 · 연결 실패).
- `agent-off sent to N open episode(s)` 한 줄(N = `sent.size`), 그 아래 보낸 id 한 줄씩.
- `ended` 가 있으면 `ended before the signal: <id>` 한 줄씩.
- `failed` 가 있으면 `failed: <id>: <why>` 한 줄씩 표준 오류로 찍고 `exitProcess(1)`. 예외가 없으므로 `episodeFailed` 는 맞지 않는다.

사용법 글 셋(36 · 56 · 501행 근처)을 `episode agent-off (<workflowId> | --all)` 로 고치고, 501행의 설명 끝에 「--all: every open episode (design §10.4)」를 더한다.

- [ ] **Step 3: 통과를 확인한다**

Run: `./gradlew :app:test`
Expected: PASS (`:app` 36)

- [ ] **Step 4: 커밋한다**

```bash
git add app/src/main/kotlin/koshei/app/Cli.kt app/src/main/kotlin/koshei/app/EpisodeCli.kt app/src/test/kotlin/koshei/app/EpisodeCliTest.kt
git commit -m "feat(app): episode agent-off --all broadcasts to every open episode" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 커밋된 재생 이력 (R3)

**Files:**
- Create: `episode-runtime/src/test/kotlin/koshei/episode/runtime/CommittedReplayTest.kt`
- Create: `episode-runtime/src/test/resources/replay/*.json` (생성기가 쓴다)
- Modify: `episode-runtime/build.gradle.kts` (`koshei.writeReplayHistories` 를 시험 JVM 에 넘기기)

**무엇을 커밋하나:** 상태마다 멈춘 이력, 그리고 끝난 이력. 아래 아홉이다. 이름이 곧 파일 이름(`replay/<이름>.json`)이다. 따로 적지 않으면 자동 승인이 꺼진 표(`TestPolicies.table(autoApprove = false)`)로 연다.

| 이름 | 어디서 멈추나 | 만드는 길 |
|---|---|---|
| `awaiting-approval` | AWAITING_APPROVAL | 연다 |
| `dispatched` | DISPATCHED (액티비티가 도는 중) | 사람이 승인하되 `acts.dispatchGate` 로 dispatch 를 붙든다(`EpisodeReplayTest` (c) 의 방법). 이력을 가져온 뒤 문을 연다 |
| `awaiting-evidence` | AWAITING_EVIDENCE | 사람이 승인 |
| `unknown-outcome` | UNKNOWN(OUTCOME) | 사람이 승인한 뒤 의심스러운 결과 통보(`inDoubtUnits` 비지 않음, 이어지는 통보) |
| `resolved-by-report` | RESOLVED (끝) | 사람이 승인한 뒤 이어지고, 모든 단위를 마치고, ONLINE 이고, 깨끗한 통보(C2) |
| `resolved-by-person` | RESOLVED (끝) | **자동 승인이 켜진 표**로 열어 → 사람의 `confirm` OUTCOME DONE |
| `escalated-agent-off` | ESCALATED | AWAITING_APPROVAL 에서 `agentOff` |
| `closed` | CLOSED (끝) | AWAITING_APPROVAL 에서 `takeover` → `close` |
| `continued-as-new` | 첫 run 의 끝 | **자동 승인이 켜진 표**로 열어, 끝난 뒤 들어온 증상으로 새 run 이 이어짐(`EpisodeReplayTest` 의 (c) 와 같은 길) |

- [ ] **Step 1: 생성기와 재생 시험을 쓴다**

`EpisodeReplayTest.kt` 의 길을 그대로 따른다(`FakeEpisodeActivities`, `ProgrammableNarrator`, `client.openEpisode(id = …)`, `ep.until(env) { … }`, `client.fetchHistory(…)`, 재생은 `episodeEnvironment { }` 의 워커에 `EpisodeWorkflowImpl` 을 등록해 `WorkflowReplayer.replayWorkflowExecution(history, worker)`).

```kotlin
package koshei.episode.runtime

import io.temporal.common.WorkflowExecutionHistory
import io.temporal.testing.WorkflowReplayer
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R3 (design §7.5): histories stopped in each state, committed, replayed on today's code with koshei's converter. A
 * change that reorders the workflow's commands without `Workflow.getVersion` fails here — as it would fail every open
 * episode on the first deployment after it.
 *
 * The histories are written only on purpose: `./gradlew :episode-runtime:test --tests "*CommittedReplayTest*"
 * -Dkoshei.writeReplayHistories=true --rerun` (the replay test is off while writing) — after a deliberate change guarded
 * by `getVersion`, never to make this test pass.
 */
class CommittedReplayTest {
    private val dir: Path = Path.of(System.getProperty("koshei.repoRoot"), "episode-runtime", "src", "test", "resources", "replay")

    @Test
    @DisabledIfSystemProperty(named = "koshei.writeReplayHistories", matches = "true")
    fun `every committed history replays on today's code`() {
        val files = dir.listDirectoryEntries().filter { it.extension == "json" }.sortedBy { it.fileName.toString() }
        assertEquals(EXPECTED, files.map { it.nameWithoutExtension }.toSet(), "the committed set (write them with the generator)")
        val replay = episodeEnvironment { }
        try {
            val worker = replay.newWorker("replay").apply { registerWorkflowImplementationTypes(EpisodeWorkflowImpl::class.java) }
            val failures = files.mapNotNull { f ->
                try {
                    WorkflowReplayer.replayWorkflowExecution(WorkflowExecutionHistory.fromJson(f.readText()), worker)
                    null
                } catch (e: Exception) {
                    "${f.fileName}: $e"
                }
            }
            assertEquals(emptyList(), failures)
        } finally {
            replay.close()
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "koshei.writeReplayHistories", matches = "true")
    fun `write the histories`() {
        // one scenario per name in EXPECTED; each returns the history to write (see the plan's table)
        …
        Files.createDirectories(dir)
        histories.forEach { (name, h) -> Files.writeString(dir.resolve("$name.json"), h.toJson(true)) }
    }

    private companion object {
        val EXPECTED = setOf(
            "awaiting-approval", "dispatched", "awaiting-evidence", "unknown-outcome", "resolved-by-report", "resolved-by-person",
            "escalated-agent-off", "closed", "continued-as-new",
        )
    }
}
```

- `write the histories` 의 본문은 위 표대로 시나리오 아홉을 짓는다. 워크플로 id 는 `cr:<이름>`.
  - **시나리오마다 환경을 따로 연다**(`episodeEnvironment { fakeWorkers(acts, narrator) }` 를 시나리오마다 새로, 끝나면 닫는다). 한 환경을 나누면 뒤 시나리오의 `result()` 가 시간을 건너뛰어 앞에 멈춰 둔 run 의 기한 타이머(승인 300초 등)가 터진다. `FakeEpisodeActivities` 도 시나리오마다 새로 만든다.
  - **멈춘 이력은 run 이 가라앉은 뒤에 가져온다.** 그 상태에 닿은 것을 `until` 로 본 뒤, `fetchHistory` 의 마지막 사건이 `EVENT_TYPE_WORKFLOW_TASK_COMPLETED`(또는 그 뒤의 타이머 · 마커 · 액티비티 예약 사건)일 때까지 `eventually` 로 다시 가져온다. 피하는 것은 끝이 `WORKFLOW_TASK_SCHEDULED`/`STARTED` 인 이력(워크플로 작업 하나가 반쯤 된 것)뿐이다 — 기록 · 알림 액티비티가 예약만 되어 끝이 `ACTIVITY_TASK_SCHEDULED` 인 것은 재생에 문제가 없으므로 받아들인다. 서버는 `ACTIVITY_TASK_STARTED` 를 완료와 함께 적으므로 `dispatched` 는 dispatch 의 `ACTIVITY_TASK_SCHEDULED` 로 끝난다. `escalated-agent-off` 는 `ep.agentOff()` 뒤 ESCALATED 를 본다. `resolved-by-report` 는 `PICASSO_APPROVED` 의 인스턴스 `mw-1` · 실행 `exec-42` · 단위 `remedy-1-pick_place` 로 `EvidenceSignal(…, picassoInstanceId = "mw-1", connection = "CONNECTION_STATE_ONLINE")` 를 보낸다(`EpisodeWorkflowTest` 의 「a linked report that completed every unit online resolves」와 같다). `unknown-outcome` 은 같은 통보에 `inDoubtUnits = listOf("remedy-1-pick_place")`.
- `@BeforeEach` 로 환경을 열지 않는다(재생 시험에도 환경이 떠서 느리다 — 생성기 안에서 시나리오마다 연다).
- `WorkflowExecutionHistory.toJson(true)` · `fromJson(String)` 은 SDK 1.25.1 의 것이다. 이름이 다르면 그 판의 것을 쓴다.

`episode-runtime/build.gradle.kts` 의 `tasks.test { … }` 안에 더한다:

```kotlin
    // R3's history generator runs only when asked: -Dkoshei.writeReplayHistories=true (design §7.5, plan D-lite).
    System.getProperty("koshei.writeReplayHistories")?.let { systemProperty("koshei.writeReplayHistories", it) }
```

- [ ] **Step 2: 재생 시험이 실패하는 것을 본다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.CommittedReplayTest"`
Expected: FAIL — `replay` 디렉터리가 없다(`listDirectoryEntries` 가 던진다) 또는 집합이 다르다. 생성기는 건너뛴다(SKIPPED).

- [ ] **Step 3: 이력을 쓴다**

Run (bash): `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.CommittedReplayTest" -Dkoshei.writeReplayHistories=true --rerun`
Expected: 생성기만 돌고(재생 시험은 SKIPPED) 파일 아홉이 생긴다. 하나씩 열어 처음 몇 사건이 `WorkflowExecutionStarted`(유형 `EpisodeWorkflow`)이고, 멈춘 이력은 끝 사건이 그 상태에서 멈춘 것인지 본다(완료 사건이 없다). 파일에 비밀 · 개인 정보가 없는지 본다 — 시험 고정값뿐이어야 한다.

- [ ] **Step 4: 재생 시험을 다시 돌린다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.CommittedReplayTest" --rerun`
Expected: 재생 시험 PASS, 생성기 SKIPPED.

- [ ] **Step 5: 시험이 실제로 잡는지 본다(커밋하지 않는다)**

`EpisodeWorkflowImpl.run()` 의 **첫 줄**에 `Workflow.sleep(Duration.ofMillis(1))` 를 임시로 넣는다(처음의 `readPolicy` 마커보다 앞이라 모든 이력이 갈린다). Promise 콜백(`handle`, `thenApply`) 안에 넣지 않는다 — 거기서는 갈리지 않고 던진다. 재생 시험이 실패하고 실패 글에 파일 이름과 `NonDeterministic` 이 드는 것을 본 뒤 **되돌린다**(`git diff` 가 비어야 한다). 보고에 어떤 변경으로 무엇이 실패했는지 적는다.

- [ ] **Step 6: 모듈 전체를 돌린다**

Run: `./gradlew :episode-runtime:test --rerun`
Expected: PASS. 시험 수 272 + 4(Task 1) + 2(재생 1, 생성기 SKIPPED 1) — 실제 수와 skipped 수를 보고한다.

- [ ] **Step 7: 커밋한다**

```bash
git add episode-runtime/src/test/kotlin/koshei/episode/runtime/CommittedReplayTest.kt episode-runtime/src/test/resources/replay episode-runtime/build.gradle.kts
git commit -m "test(episode-runtime): R3 - committed histories stopped in each state replay on today's code" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 기록 표의 런타임 역할 — `UPDATE` · `DELETE` 없음

> **실행 중 바뀜(`26a8c8f`):** 객체가 다 있으면 `CREATE` 가 아니라 `episode_event` 의 소유로 정한다 — 설계 §13 구현(D-lite). 아래 글은 그 전의 기록이다.

**Files:**
- Create: `scripts/episode-db-roles.sql`
- Modify: `episode-runtime/src/main/resources/episode-schema.sql` (주석만)
- Modify: `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeStore.kt` (`ensureSchema`)
- Modify: `app/src/main/kotlin/koshei/app/Worker.kt`, `app/src/main/kotlin/koshei/app/Watcher.kt`, `app/build.gradle.kts` (에피소드 표 전용 계정)
- Create: `app/src/main/kotlin/koshei/app/EpisodeDbLogin.kt`
- Modify: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeDb.kt` (다른 계정으로 잇는 도우미)
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeDbRolesTest.kt`, `app/src/test/kotlin/koshei/app/EpisodeDbLoginTest.kt`

**모양:**
- 스크립트는 소유 계정(지금 `koshei`)이 스키마를 적용한 **뒤에** 돈다. 역할 `koshei_episode_rt`(LOGIN, 비밀번호는 psql 변수 `:'rt_password'` 로 받는다 — 스크립트에 적지 않는다).
- 다섯 표(`episode_event`, `episode_notice`, `episode_dispatch`, `episode_watch_cursor`, `episode_watch_log`)에 `SELECT, INSERT` 만 준다. 두 `bigserial` 시퀀스(`episode_notice_id_seq`, `episode_watch_log_id_seq`)에 `USAGE, SELECT`.
- `UPDATE`, `DELETE`, `TRUNCATE`, `REFERENCES`, `TRIGGER` 는 `REVOKE` 로 밝혀 둔다(이미 준 적이 있어도 거둔다).
- 몇 번이든 돌려도 같아야 한다: 역할 생성은 `DO $$ … IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'koshei_episode_rt') …` 로.
- **`ensureSchema` 는 런타임 역할로 DDL 을 돌릴 수 없다**(Postgres 16: `CREATE TABLE IF NOT EXISTS` 는 표가 있어도 스키마의 `CREATE` 를 먼저 검사해 `42501`, `CREATE INDEX IF NOT EXISTS` 는 표 소유를 요구한다). 결정: 스키마의 `CREATE` 권한이 없는 계정이면 DDL 을 건너뛰고, 다섯 표와 세 색인이 있는지만 `to_regclass` 로 본다. 하나라도 없으면 `IllegalStateException("… missing: <이름들> — apply episode-schema.sql as the owner first")` 로 실패한다. 권한이 있는 계정(소유 계정, 시험 컨테이너)은 지금처럼 스크립트를 돌린다.
- **워커 전체를 런타임 역할로 돌리지 않는다.** 워커는 같은 `koshei.blocks.Db` 로 블록 레지스트리와 `workflow_def` 를 읽는다. 에피소드 표만 쓰는 `EpisodeStore` · `WatchStore` 에 따로 계정을 준다: `KOSHEI_EPISODE_DB_USER` · `KOSHEI_EPISODE_DB_PASS`, 없으면 `KOSHEI_DB_USER` · `KOSHEI_DB_PASS`(그것도 없으면 `Db` 의 기본값). URL 은 `Db.url` 그대로다. 감시자(`Watcher.kt`)도 같은 규칙.

- [ ] **Step 1: 실패하는 시험을 쓴다**

`EpisodeDb.kt` 에 도우미 둘을 더한다:

```kotlin
    /** A connection as another login of the test database (a role a test created). */
    fun connectionAs(user: String, password: String): Connection = DriverManager.getConnection(container.jdbcUrl, user, password)

    /** Runs [sql] as the owner account (the container's user). */
    fun execAsOwner(sql: String) = connection().use { c -> c.createStatement().use { it.execute(sql) } }
```

`EpisodeDbRolesTest.kt`:

```kotlin
package koshei.episode.runtime

import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EpisodeDbRolesTest {
    private val script: String = Files.readString(Path.of(System.getProperty("koshei.repoRoot"), "scripts", "episode-db-roles.sql"))

    /** The script with psql's `:'rt_password'` bound, as `psql -v rt_password=…` would. */
    private fun grants(password: String = "rt-test") = script.replace(":'rt_password'", "'$password'")

    @BeforeEach fun up() {
        EpisodeDb.reset()                  // the schema, applied by the owner
        EpisodeDb.execAsOwner(grants())
        EpisodeDb.execAsOwner(grants())    // twice: the script is idempotent
    }

    private fun asRuntime(sql: String) = EpisodeDb.connectionAs("koshei_episode_rt", "rt-test").use { c -> c.createStatement().use { it.execute(sql) } }

    @Test fun `the runtime role appends and reads every episode table`() {
        asRuntime("INSERT INTO episode_event (episode_instance_id, seq, kind, payload) VALUES ('i/r', 1, 'OPENED', '{}')")
        asRuntime("INSERT INTO episode_notice (episode_instance_id, notice) VALUES ('i/r', '{}')")
        asRuntime("INSERT INTO episode_dispatch (idempotency_key, episode_instance_id, outcome_kind, answer) VALUES ('k', 'i/r', 'ANSWER', 'a')")
        asRuntime("INSERT INTO episode_watch_cursor (source, run_id, position, last_id) VALUES ('s', 'r', 1, 'x')")
        asRuntime("INSERT INTO episode_watch_log (source, kind, detail) VALUES ('s', 'BROKEN', '{}')")
        asRuntime("SELECT count(*) FROM episode_event")
    }

    @Test fun `the runtime role can neither change nor remove a row, nor empty a table`() {
        asRuntime("INSERT INTO episode_event (episode_instance_id, seq, kind, payload) VALUES ('i/r', 1, 'OPENED', '{}')")
        for (table in listOf("episode_event", "episode_notice", "episode_dispatch", "episode_watch_cursor", "episode_watch_log")) {
            for (sql in listOf("UPDATE $table SET at = now()", "DELETE FROM $table", "TRUNCATE $table")) {
                val e = assertFailsWith<SQLException>(sql) { asRuntime(sql) }
                assertEquals("42501", e.sqlState, "$sql: ${e.message}")   // insufficient_privilege
            }
        }
    }

    @Test fun `the worker's schema step, as the runtime role, checks the owner's schema instead of applying it`() {
        EpisodeStore { EpisodeDb.connectionAs("koshei_episode_rt", "rt-test") }.ensureSchema()
    }

    @Test fun `as the runtime role, a missing table is a clear failure - never a half schema`() {
        EpisodeDb.execAsOwner("ALTER TABLE episode_watch_log RENAME TO episode_watch_log_away")
        try {
            val e = assertFailsWith<IllegalStateException> {
                EpisodeStore { EpisodeDb.connectionAs("koshei_episode_rt", "rt-test") }.ensureSchema()
            }
            assertTrue("episode_watch_log" in e.message.orEmpty() && "owner" in e.message.orEmpty(), e.message)
        } finally {
            EpisodeDb.execAsOwner("ALTER TABLE episode_watch_log_away RENAME TO episode_watch_log")
        }
    }

    @Test fun `as the owner the schema step still applies the script`() {
        EpisodeDb.reset().ensureSchema()   // the container's owner has CREATE: the script runs, idempotent as before
    }
}
```

`app/src/test/kotlin/koshei/app/EpisodeDbLoginTest.kt`:

```kotlin
package koshei.app

import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeDbLoginTest {
    @Test fun `the episode login falls back to the shared one, then to Db's defaults`() {
        assertEquals("rt" to "p1", EpisodeDbLogin.fromEnv(mapOf("KOSHEI_EPISODE_DB_USER" to "rt", "KOSHEI_EPISODE_DB_PASS" to "p1", "KOSHEI_DB_USER" to "u", "KOSHEI_DB_PASS" to "p")).let { it.user to it.pass })
        assertEquals("u" to "p", EpisodeDbLogin.fromEnv(mapOf("KOSHEI_DB_USER" to "u", "KOSHEI_DB_PASS" to "p")).let { it.user to it.pass })
        assertEquals(koshei.blocks.Db.user to koshei.blocks.Db.pass, EpisodeDbLogin.fromEnv(emptyMap()).let { it.user to it.pass }, "exactly the rest of the worker's login")
    }

    @Test fun `a user without its password is refused - never mixed with the shared password`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { EpisodeDbLogin.fromEnv(mapOf("KOSHEI_EPISODE_DB_USER" to "rt", "KOSHEI_DB_PASS" to "p")) }
    }
}
```

(둘째 기대 `"u" to "p"` 는 맵의 `KOSHEI_DB_*` 가 아니라 `Db` 를 따르므로 바꾼다 — 아래 구현의 마지막 갈래는 `Db.user`/`Db.pass` 그대로다. 둘째 줄을 `assertEquals(koshei.blocks.Db.user to koshei.blocks.Db.pass, EpisodeDbLogin.fromEnv(mapOf("KOSHEI_DB_USER" to "u", "KOSHEI_DB_PASS" to "p")).let { it.user to it.pass })` 로 쓴다 — 에피소드 계정이 없으면 워커의 나머지와 같은 계정이다.)

- `EpisodeDb.reset()` 이 TRUNCATE 하는 방식이면 그대로 두고, 스키마가 이미 적용된 상태에서 시작하는지 확인한다. 역할은 컨테이너에 남으므로 둘째 실행부터는 `IF NOT EXISTS` 갈래가 돈다 — 그것도 멱등성의 시험이다.
- `UPDATE … SET at = now()` 는 다섯 표 모두에 `at` 열이 있어서 고른 것이다(`episode-schema.sql`). 없으면 그 표의 아무 열이나 쓴다.

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeDbRolesTest"`
Expected: FAIL (스크립트가 없다)

- [ ] **Step 2: 스크립트를 쓴다**

```sql
-- The episode runtime's database role (design §13, R11; plan D-lite). Run by the OWNER account after episode-schema.sql:
--   psql -v rt_password='…' -U koshei -d koshei -f scripts/episode-db-roles.sql
-- The runtime appends and reads; it never changes or removes a row (the tables are append-only by use, §13). The
-- password comes from the psql variable rt_password, never from this file. Safe to run again.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'koshei_episode_rt') THEN
        CREATE ROLE koshei_episode_rt LOGIN;
    END IF;
END
$$;
ALTER ROLE koshei_episode_rt PASSWORD :'rt_password';

REVOKE ALL ON episode_event, episode_notice, episode_dispatch, episode_watch_cursor, episode_watch_log FROM koshei_episode_rt;
GRANT SELECT, INSERT ON episode_event, episode_notice, episode_dispatch, episode_watch_cursor, episode_watch_log TO koshei_episode_rt;
GRANT USAGE, SELECT ON SEQUENCE episode_notice_id_seq, episode_watch_log_id_seq TO koshei_episode_rt;
```

- `REVOKE ALL` 뒤 `GRANT` 로 다시 주므로, 앞에 `UPDATE` · `DELETE` 를 준 적이 있어도 이 스크립트 뒤에는 없다.
- psql 변수 `:'rt_password'` 는 `DO` 블록(달러 인용) 안에서는 바뀌지 않는다 — 그래서 비밀번호는 블록 밖의 `ALTER ROLE` 로 준다.
- `ALTER ROLE … PASSWORD :'rt_password'` 를 JDBC 로 돌리는 시험은 위 `grants()` 가 `:'rt_password'` 를 문자열로 바꾼다. 스크립트에 그 표기가 정확히 한 번 있어야 한다.
- 문서에 적는 실행 줄은 `psql -v ON_ERROR_STOP=1 -v rt_password='…' …` 다(없으면 psql 은 실패한 문장 뒤에도 0 으로 끝난다). `\set` 같은 psql 명령은 스크립트에 넣지 않는다 — JDBC 시험이 그 글을 그대로 돌린다.

`EpisodeStore.ensureSchema()` 를 바꾼다:

```kotlin
    /**
     * The episode tables (design §13). An account that may create in the schema (the owner) runs episode-schema.sql,
     * idempotent as before. The runtime role (scripts/episode-db-roles.sql) may not — Postgres checks CREATE even for
     * IF NOT EXISTS — so it only checks that the owner applied the schema, and fails clearly if not.
     */
    fun ensureSchema() {
        connect().use { c ->
            val mayCreate = c.createStatement().use { s ->
                s.executeQuery("SELECT has_schema_privilege(current_schema(), 'CREATE')").use { it.next(); it.getBoolean(1) }
            }
            if (mayCreate) {
                val sql = checkNotNull(javaClass.getResourceAsStream("/episode-schema.sql")) { "episode-schema.sql missing" }
                    .bufferedReader().use { it.readText() }
                c.createStatement().use { it.execute(sql) }
                return
            }
            val missing = SCHEMA_OBJECTS.filter { name ->
                c.prepareStatement("SELECT to_regclass(?) IS NULL").use { p -> p.setString(1, name); p.executeQuery().use { it.next(); it.getBoolean(1) } }
            }
            check(missing.isEmpty()) { "episode schema missing: ${missing.joinToString()} — apply episode-schema.sql as the owner first" }
        }
    }
```

`SCHEMA_OBJECTS` 는 `EpisodeStore` 의 companion(없으면 파일 수준 `private val`)에 둔다: 다섯 표와 `episode-schema.sql` 의 색인 셋(`episode_notice_instance`, `episode_dispatch_instance`, `episode_event_intent_order`). 스크립트에 표나 색인을 더하면 이 목록에도 더한다는 주석을 양쪽에 단다.

`app/src/main/kotlin/koshei/app/EpisodeDbLogin.kt`:

```kotlin
package koshei.app

import koshei.blocks.Db
import java.sql.Connection
import java.sql.DriverManager

/**
 * The login for the episode tables only (EpisodeStore, WatchStore; design §13, plan D-lite): the runtime role of
 * scripts/episode-db-roles.sql, while the rest of the worker keeps Db's login (the block registry, workflow_def).
 * KOSHEI_EPISODE_DB_USER / _PASS, else KOSHEI_DB_USER / _PASS, else Db's defaults. The URL is Db's.
 */
data class EpisodeDbLogin(val user: String, val pass: String) {
    fun connect(): Connection = DriverManager.getConnection(Db.url, user, pass)

    companion object {
        fun fromEnv(env: Map<String, String>): EpisodeDbLogin {
            val user = env["KOSHEI_EPISODE_DB_USER"]
            if (user != null) {
                val pass = requireNotNull(env["KOSHEI_EPISODE_DB_PASS"]) { "KOSHEI_EPISODE_DB_USER is set without KOSHEI_EPISODE_DB_PASS" }
                return EpisodeDbLogin(user, pass)
            }
            return EpisodeDbLogin(Db.user, Db.pass)   // exactly the rest of the worker's login (Db reads its override and env)
        }
    }
}
```

- 마지막 갈래가 `Db.user`/`Db.pass` 이므로 에피소드 계정이 없으면 워커의 나머지와 **정확히** 같은 계정이다(`DbConnectionOverride` 와 환경을 `Db` 의 순서대로 본다). 시험은 그래서 맵의 `KOSHEI_DB_*` 가 아니라 `Db` 와 견준다.
- 클래스 문서의 「else KOSHEI_DB_USER / _PASS, else Db's defaults」를 「else Db's own login (its override, KOSHEI_DB_USER / _PASS, its defaults)」로 쓴다.
- `Worker.kt:78` 근처의 `EpisodeStore { Db.connect() }` 를 `EpisodeDbLogin.fromEnv(System.getenv()).let { login -> EpisodeStore { login.connect() } }` 로 바꾼다. `Watcher.kt` 의 `EpisodeStore` · `WatchStore` 둘도 같은 로그인으로(`WatchStore { Db.connect() }` → `WatchStore { login.connect() }`).
- `app/build.gradle.kts` 의 `kosheiEnvKeys` 에 `"KOSHEI_EPISODE_DB_USER", "KOSHEI_EPISODE_DB_PASS"` 를 더한다.

`episode-schema.sql` 의 주석 둘을 고친다:
- 머리 주석 2-3행의 「production revokes UPDATE and DELETE on these tables from` / `-- the runtime account (plan D);」(두 줄에 걸쳐 있다 — 두 줄을 함께 바꾼다) → 「the runtime role of scripts/episode-db-roles.sql has SELECT and INSERT only (plan D-lite), and does not run this script: EpisodeStore.ensureSchema only checks the objects below (keep its list in step).」
- `episode_watch_log` 의 「Read by people (plan D shows it).」 → 「Read by people (no screen yet — out of scope, plan D-lite).」

- [ ] **Step 3: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeDbRolesTest" --rerun :app:test --tests "koshei.app.EpisodeDbLoginTest" --rerun`
Expected: PASS (5개 + 2개)

- [ ] **Step 4: 모듈 전체**

Run: `./gradlew :episode-runtime:test --rerun :app:test --rerun :authoring-api:test --rerun`
Expected: PASS. 다른 시험이 같은 컨테이너를 쓰므로 역할이 남아도 깨지지 않아야 한다. authoring-api 도 `EpisodeStore` 를 쓰면(소유 계정) 그대로 통과해야 한다.

- [ ] **Step 5: 커밋한다**

```bash
git add scripts/episode-db-roles.sql episode-runtime/src/main/resources/episode-schema.sql episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeStore.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeDb.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeDbRolesTest.kt app/src/main/kotlin/koshei/app/EpisodeDbLogin.kt app/src/main/kotlin/koshei/app/Worker.kt app/src/main/kotlin/koshei/app/Watcher.kt app/build.gradle.kts app/src/test/kotlin/koshei/app/EpisodeDbLoginTest.kt
git commit -m "feat(db): episode runtime role - SELECT and INSERT only on the five episode tables" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 설계 문서

**Files:**
- Modify: `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md`

- [ ] **Step 1: 고친다** (한국어, 짧은 평서문, 한 문장 한 사실)
1. **§10.4:** 「활성화 CLI 가 열린 에피소드 전체에 `agentOff` signal 을 방송한다(계획 D)」 아래 「구현(D-lite)」: 방송은 `AgentOffBroadcast` 와 개발 CLI `episode agent-off --all` 이다. 활성화 CLI 는 없다(§10.3 은 범위 밖). 끄는 절차는 ① 정책 파일의 `agentLayerEnabled: false` ② `episode agent-off --all`. 가시성에서 돌고 있는 `EpisodeWorkflow` 를 나열하고 저마다 지금 run 에 보낸다. 끝난 run 은 `ended`, 실패는 `failed` 로 세고 나머지를 계속 보낸다. 두 번 보내도 같다.
2. **§7.2 의 CLI 목록(478행 근처 `episode agent-off <workflowId>`):** `--all` 을 더한다. 「§10.4 의 방송(계획 D)이 아니다」 문장은 「`--all` 이 §10.4 의 방송이다」로.
3. **§7.5 (556행 근처 「R3 의 재생 시험 … 계획 D 다」):** 「구현(D-lite)」: 상태마다 멈춘 이력 아홉(이름 목록, `dispatched` 포함)을 묶음 `episode-runtime/src/test/resources/replay/2026-10-04/` 로 커밋했다. `CommittedReplayTest` 가 모든 묶음을 오늘의 코드와 koshei 의 변환기로 재생한다(교착 감지는 늦춘다 — 결정성과 상관없다). 새 묶음은 `-Dkoshei.writeReplayHistories=true -Dkoshei.replaySet=<이름>` 으로만 쓰고 있는 묶음은 덮어쓰지 않는다. 명령 순서를 바꾸는 변경은 `Workflow.getVersion` 을 건 뒤 새 묶음을 더하고, 옛 묶음은 그 갈래를 지울 때 함께 지운다. Task 3 Step 5 에서 시험이 실제로 잡은 변경을 한 줄 적는다.
4. **§13:** 「런타임 계정에서 `UPDATE`/`DELETE` 권한 회수」와 구현(B3b) · (C1) 의 「계획 D 에서 한다」 두 곳에 「구현(D-lite)」: `scripts/episode-db-roles.sql` 이 런타임 역할 `koshei_episode_rt` 를 만든다. 다섯 표에 `SELECT` · `INSERT`, 두 시퀀스에 `USAGE` · `SELECT` 만. 실행 줄은 `psql -v ON_ERROR_STOP=1 -v rt_password='…' -U <소유 계정> -d koshei -f scripts/episode-db-roles.sql`. 스키마는 소유 계정이 먼저 적용한다. 런타임 역할의 `ensureSchema` 는 DDL 을 돌리지 않고 다섯 표와 세 색인이 있는지만 본다(Postgres 는 `IF NOT EXISTS` 에도 스키마 `CREATE` 를 먼저 검사한다). 이 역할은 에피소드 표 전용 연결에만 쓴다: `KOSHEI_EPISODE_DB_USER` · `KOSHEI_EPISODE_DB_PASS`(워커 · 감시자). 워커의 나머지(블록 레지스트리, `workflow_def`)는 `KOSHEI_DB_*` 그대로다.
5. **§16:** 계획 표의 D 행 끝에 「D-lite 로 줄였다(2026-10-04, 사용자 결정): 방송 · 재생 이력 · 런타임 역할. 나머지는 범위 밖(아래)」. 「C2 끝」 아래에 「**D-lite 끝(날짜).**」 문단: 커밋 해시들, 모듈별 시험 수, 범위 밖으로 둔 다섯 묶음(이 계획의 범위 밖 표를 한 줄씩).
6. **§19 또는 §16 의 남은 일:** 범위 밖 다섯 묶음을 「사용자 결정으로 미룸(2026-10-04)」으로 적는다.
7. **「계획 D」가 나오는 다른 자리 전부**(`grep -n "계획 D"`, 28곳 남짓)에 규칙 하나를 적용한다: **D-lite 가 한 것(방송 · 재생 이력 · 런타임 역할)이 아니면 모두 「범위 밖(D-lite 에서 뺌, 2026-10-04)」**이다. 이력에서 기록 메우기(709행 근처), 글 고치기(1367행 근처), 정책 편집 권한(116행 근처)처럼 다섯 묶음에 딱 들지 않는 것도 범위 밖이다. 지금 사실을 말하는 문장은 그 자리에서 고치고, 역사 기록(구현(B3b) · (C1) · (C2) 문단)은 그대로 두고 괄호 한 줄을 붙인다.

- [ ] **Step 2: 남은 말을 찾는다**

Run: `grep -n "계획 D" docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md`
Expected: 모든 자리에 「D-lite」(한 것) 또는 「범위 밖」이 함께 적혀 있다.

- [ ] **Step 3: 커밋한다**

```bash
git add docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md
git commit -m "docs(design): D-lite - agentOff broadcast, committed replay histories, the runtime role; the rest of D out of scope" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
