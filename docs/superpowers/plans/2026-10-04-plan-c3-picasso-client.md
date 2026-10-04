# 계획 C3 — 실물 picasso 승인 창구 클라이언트 · 참조 담는 쪽과 끝까지 한 번 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** koshei 가 Mock 이 아닌 진짜 picasso 미들웨어의 승인 창구(`POST /approvals`)를 부른다(`KOSHEI_PICASSO=picasso`). picasso 의 참조 담는 쪽(`runApprovalHost`)을 상대로 감시 → 진단 → 사람 승인 → 전제 확인 → 디스패치 → 결과 통보 → 판정까지 한 번 돌린다.

**Architecture:**
- 새 구현 `HttpApprovalWindow : ApprovalWindow`(`:episode-runtime`)가 하는 일은 셋이다.
  - 실행 의도에서 picasso 요청 다섯 칸을 짓는다: `approverId`, `approverKind`, `robotId`, `jobOrderId`, `sawSkillTypes`.
  - 루프백 URL 의 `/approvals` 로 POST 한다.
  - 200 이면 본문을 글자 그대로 돌려준다. 판정은 지금처럼 코어의 `judgePicassoAnswer` 가 한다.
- **`revalidate` 는 늘 `UNKNOWN` 이다.** 대장을 지금 기준으로 다시 읽는 표면이 없다(§8.3: 낡은 읽기로 TRUE 를 내지 않는다). 그래서 실물 에피소드는 UNKNOWN(PRECONDITION)을 지나고, 사람이 전제를 확인해야 디스패치한다. 설계가 이미 그렇게 정했다.
- **실패의 갈래 (picasso `ApprovalHost` 의 규약, main `3d6530a`):**
  - 200: 승인이든 거절이든 판 4 의 답이다. 그대로 돌려준다.
  - 400 · 405: 요청이 잘못됐다. 본문은 `{"error":"…"}`(한국어 사유)이다. 다시 보내도 같으므로 재시도하지 않는다(`ApplicationFailure` 재시도 없음, 종류 `PicassoRequestRefused`). 이 실패는 코어에 `DispatchResult.Uncertain` 으로 가서 UNKNOWN(OUTCOME)이 된다. 나가지 않은 것이 분명하지만 지금 모델에는 그 값이 없으므로 보수 쪽으로 둔다. 워크플로는 지금 `ActivityFailure` 의 바깥 글(「Activity task failed」)만 적으므로, 원인(`ApplicationFailure`)의 글을 적게 고친다(Task 3) — 기록의 `detail` 에 `PicassoRequestRefused` 와 상태가 남아야 사람이 까닭을 안다.
  - 그 밖의 상태(404 등): 같은 재시도 없는 실패다. 본문은 JSON 이 아닐 수 있다.
  - 연결 실패 · 시간 초과: 재시도한다(액티비티 재시도 셋). 두 번 나감은 picasso 가 막는다(`CONSUMED`, §11).
- **설정 (`KOSHEI_PICASSO=picasso` 일 때만):**
  - `KOSHEI_PICASSO_URL`: 필수. `http://` 이고 호스트가 `127.0.0.1` 또는 `localhost` 여야 한다. 창구는 신원을 인증하지 않으므로(picasso §15.3) 루프백 밖으로 승인자 id 를 보내지 않는다. `[::1]` 은 받지 않는다 — 참조 구동기는 IPv4 루프백에만 붙어 닿지 않고 재시도만 돈다. 끝의 `/approvals` 는 클라이언트가 붙인다(있으면 거절). 경로 · 질의 · 조각 · 사용자 정보가 있으면 거절한다(조용히 버리지 않는다). 프록시를 거치지 않는다(`NO_PROXY`).
  - `KOSHEI_PICASSO_AGENT_ID`: 필수. POLICY 자동 승인(`approverKind=AGENT`)이 보낼 승인자 id 다. 정책 v1 은 자동 승인을 끄지만, 정책 파일은 결정마다 다시 읽히므로 워커를 다시 띄우지 않고 켜질 수 있다. 그래서 시작 때 요구한다.
  - **picasso 는 승인자 id 마다 선언을 찾는다 — PERSON 도 AGENT 도.** 선언에 없는 승인자는 `NOT_DECLARED` 로 거절된다(`RemedyDesk.judge`, 설계 §8.4 「선언」 행). 참조 구동기의 선언(`handoff/narrator/entitlements.json`)은 `narrator-1` · `narrator-3` · `narrator-4` 뿐이다. 운영자 id 로 승인하려면 그 id 를 선언한 파일을 구동기에 `--entitlements` 로 준다.
  - `KOSHEI_PICASSO_TIMEOUT_MS`: 선택, 기본 8000. dispatch 액티비티는 ScheduleToClose = `dispatchMs`(정책 v1 30000) 안에서 시도 셋, 기본 물러섬 1초 · 2초다. 한 요청의 한도 T 가 `(dispatchMs − 3000) / 3` 이하여야 셋째 시도가 잘리지 않는다(30000 이면 9000). 이 값은 시작 때만 읽으므로 검사하지 않고 문서에 이 식을 적는다.
- **승인자 id:** PERSON 이면 실행 의도의 `approval.approverId`(사람의 `decide` 가 적은 운영자 id), AGENT 면 `KOSHEI_PICASSO_AGENT_ID`. PERSON 인데 id 가 없으면 재시도 없는 실패(`BadIntent`)다 — 지어내지 않는다.
- `PicassoMode` 에 `PICASSO` 를 더한다. 워커 · 감시자 · authoring-api 가 같은 해석을 쓰므로, 셋 모두 `picasso` 를 에피소드 모드로 읽는다.

**Tech Stack:** Kotlin 2 / JVM 21, `java.net.http.HttpClient`, 시험에서는 JDK `com.sun.net.httpserver.HttpServer`(picasso 참조 구동기와 같은 것)로 창구를 흉내 낸다, Temporal SDK 1.25.1, Gradle.

**근거 (읽기만):** picasso main `3d6530a`:
- `picasso/src/test/kotlin/dev/picasso/middleware/host/ApprovalHost.kt` — 경로 `/approvals`, 200/400/405, 루프백.
- `picasso/src/test/kotlin/dev/picasso/middleware/host/ScenarioHost.kt` — 참조 담는 쪽.
- `picasso/src/main/kotlin/dev/picasso/middleware/Approval.kt` `ApprovalWire.decode` — 요청 칸 다섯, 그 밖의 칸은 무시.
- picasso 세션 답(2026-10-04). 400 은 다음 경우에 난다: JSON 이 아님, 다섯 칸 중 하나가 없음, 타입이 틀림, `approverKind` 가 PERSON · AGENT 밖. 요청의 Content-Type 은 보지 않는다. 응답은 `application/json; charset=utf-8` 이다. 경로는 정확히 `/approvals` 로 쓴다(접두 일치에 기대지 않는다).

**작업 위치:** worktree `C:\Users\Eisen\Desktop\Labs\[projects] koshchei\koshei-episode-core`, 브랜치 `feat/episode-core`(머리 `6baa3aa`, PR #5).
- 원래 체크아웃은 건드리지 않는다. picasso · narrator 저장소는 읽기만 한다.
- 파일은 Write/Edit 로만 쓴다. bare `git stash` 금지. 푸시는 Task 5 뒤 컨트롤러가 한다(PR #5 에 더해짐).
- 시험은 `TestWorkflowEnvironment` · Testcontainers · 시험 안의 `HttpServer` 로만 돈다. 공용 Temporal · Postgres 와 picasso 구동기는 Task 5 에서 **컨트롤러만** 쓴다. `./gradlew --stop` 금지(데몬 풀을 picasso 세션과 나눈다).

**커밋 규칙:** `git commit -m "<제목>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`. `git add` 는 경로를 밝혀서.

**기준 시험 수 (머리 `6baa3aa`):** `:episode` 323, `:episode-runtime` 290(건너뜀 1), `:authoring-api` 146, `:app` 41.

**범위 밖:**

| 무엇 | 왜 | 그때까지 |
|---|---|---|
| 실물 담는 쪽(배치 저장소) | 사용자 결정(2026-10-04): 실물 현장이 생길 때 별도 저장소. picasso 동의 | 참조 구동기가 연결 시험의 상대 |
| 지금 기준 `revalidate` | 대장을 실시간으로 읽는 표면이 없다(§8.3, §19 E ④) | 늘 UNKNOWN → 사람이 전제를 확인 |
| 「나가지 않음이 분명함」을 코어에 따로 알리기 | 지금 `DispatchResult` 에 그 값이 없다. 400 은 koshei 의 결함이라 드물다 | UNKNOWN(OUTCOME) |

---

## Chunk 1: 클라이언트와 설정

### Task 1: `PicassoMode.PICASSO` 와 창구 설정

**Files:**
- Modify: `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkers.kt` (`PicassoMode`, `EpisodeRuntimeConfig`)
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWorkersTest.kt`
- Test: `authoring-api/src/test/kotlin/koshei/authoring/AutoCorrectGateTest.kt` (picasso 도 에피소드 모드)
- Test: `app/src/test/kotlin/koshei/app/WatcherConfigTest.kt` (감시자가 picasso 를 받는다)

- [ ] **Step 1: 실패하는 시험을 쓴다**

`EpisodeWorkersTest.kt`:
- `` `only the mock picasso exists until the host is placed` `` 를 지우고 아래로 바꾼다:

```kotlin
    @Test fun `picasso names the real approval window - a loopback URL and an agent id are required`() {
        val c = EpisodeRuntimeConfig.fromEnv(mapOf(
            "KOSHEI_PICASSO" to "picasso", "KOSHEI_PICASSO_URL" to "http://127.0.0.1:8770", "KOSHEI_PICASSO_AGENT_ID" to "narrator-1",
        ))
        assertEquals(PicassoMode.PICASSO, c.picasso)
        assertEquals(PicassoWindowConfig(java.net.URI("http://127.0.0.1:8770/approvals"), "narrator-1", 8_000), c.window)
        assertEquals(15_000, EpisodeRuntimeConfig.fromEnv(mapOf(
            "KOSHEI_PICASSO" to "picasso", "KOSHEI_PICASSO_URL" to "http://localhost:8770/", "KOSHEI_PICASSO_AGENT_ID" to "a",
            "KOSHEI_PICASSO_TIMEOUT_MS" to "15000",
        )).window!!.timeoutMs)
    }

    @Test fun `the window's settings are refused unless exact`() {
        val ok = mapOf("KOSHEI_PICASSO" to "picasso", "KOSHEI_PICASSO_URL" to "http://127.0.0.1:8770", "KOSHEI_PICASSO_AGENT_ID" to "a")
        val bad = listOf(
            ok - "KOSHEI_PICASSO_URL",
            ok - "KOSHEI_PICASSO_AGENT_ID",
            ok + ("KOSHEI_PICASSO_AGENT_ID" to " "),
            ok + ("KOSHEI_PICASSO_URL" to "https://127.0.0.1:8770"),
            ok + ("KOSHEI_PICASSO_URL" to "http://10.0.0.5:8770"),
            ok + ("KOSHEI_PICASSO_URL" to "http://picasso.example:8770"),
            ok + ("KOSHEI_PICASSO_URL" to "http://127.0.0.1:8770/approvals"),
            ok + ("KOSHEI_PICASSO_URL" to "http://127.0.0.1"),
            ok + ("KOSHEI_PICASSO_URL" to "http://[::1]:8770"),
            ok + ("KOSHEI_PICASSO_URL" to "http://127.0.0.1:8770?x=1"),
            ok + ("KOSHEI_PICASSO_URL" to "http://127.0.0.1:8770#f"),
            ok + ("KOSHEI_PICASSO_URL" to "http://u:p@127.0.0.1:8770"),
            ok + ("KOSHEI_PICASSO_URL" to "not a url"),
            ok + ("KOSHEI_PICASSO_TIMEOUT_MS" to "0"),
            ok + ("KOSHEI_PICASSO_TIMEOUT_MS" to "ten"),
        )
        for (env in bad) assertThrows<IllegalArgumentException>("$env") { EpisodeRuntimeConfig.fromEnv(env) }
    }

    @Test fun `mock and off carry no window settings`() {
        kotlin.test.assertNull(EpisodeRuntimeConfig.fromEnv(mapOf("KOSHEI_PICASSO" to "mock")).window)
        kotlin.test.assertNull(EpisodeRuntimeConfig.fromEnv(emptyMap()).window)
        assertThrows<IllegalArgumentException> { EpisodeRuntimeConfig.fromEnv(mapOf("KOSHEI_PICASSO" to "http")) }
    }
```

- `` `PicassoMode reads KOSHEI_PICASSO on its own, as the worker does` `` 에 `assertEquals(PicassoMode.PICASSO, PicassoMode.fromEnv(mapOf("KOSHEI_PICASSO" to "picasso")))` 를 더한다.

`AutoCorrectGateTest.kt`: 에피소드 모드를 `mock` 으로 보는 시험이 있으면, 같은 기대를 `picasso` 로도 한 줄 더한다. `AutoCorrectGate` 는 `PicassoMode.fromEnv(env) != OFF` 만 보므로 코드는 바뀌지 않는다 — 시험이 그것을 붙든다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeWorkersTest"`
Expected: 컴파일 실패(`PICASSO`, `PicassoWindowConfig`, `window`)

- [ ] **Step 3: 구현한다**

`EpisodeWorkers.kt`:

```kotlin
/**
 * The approval window (design §8.3, §8.4). OFF (the default) runs no episode worker at all. MOCK is the test's — with it a
 * worker would take a fabricated APPROVED for a remedy that never reached picasso — so it runs only when chosen by name.
 * PICASSO is the real window, `POST /approvals` on a loopback URL (plan C3): picasso's reference host today, a site's
 * deployment host later (§19 C).
 */
enum class PicassoMode {
    OFF, MOCK, PICASSO;

    companion object {
        /** `KOSHEI_PICASSO`, read the same way by every process that must agree on episode mode (worker, watcher, control plane). */
        fun fromEnv(env: Map<String, String>): PicassoMode = when (val v = env["KOSHEI_PICASSO"] ?: "off") {
            "off" -> OFF
            "mock" -> MOCK
            "picasso" -> PICASSO
            else -> throw IllegalArgumentException("KOSHEI_PICASSO must be off, mock or picasso (design §19 C), was '$v'")
        }
    }
}

/**
 * The real window's settings (plan C3). [url] is the full `…/approvals` URL on a loopback host: the window does not
 * authenticate (picasso §15.3), so approver ids never leave the machine. [agentId] is the approver id a POLICY approval
 * sends as AGENT. [timeoutMs] bounds one request; keep it under the policy's `dispatchMs` so the activity's retries fit.
 */
data class PicassoWindowConfig(val url: java.net.URI, val agentId: String, val timeoutMs: Long) {
    companion object {
        /** IPv4 loopback only: picasso's reference host binds InetAddress.getLoopbackAddress() (127.0.0.1). */
        private val LOOPBACK = setOf("127.0.0.1", "localhost")

        fun fromEnv(env: Map<String, String>): PicassoWindowConfig {
            val raw = requireNotNull(env["KOSHEI_PICASSO_URL"]?.takeIf { it.isNotBlank() }) { "KOSHEI_PICASSO=picasso needs KOSHEI_PICASSO_URL (http://127.0.0.1:<port>)" }
            val base = try { java.net.URI(raw.trimEnd('/')) } catch (e: java.net.URISyntaxException) { throw IllegalArgumentException("KOSHEI_PICASSO_URL is not a URL: $raw") }
            require(base.scheme == "http") { "KOSHEI_PICASSO_URL must be http:// (the window is loopback only), was $raw" }
            require(base.host in LOOPBACK) { "KOSHEI_PICASSO_URL must name a loopback host (the window does not authenticate, picasso §15.3), was ${base.host}" }
            require(base.port > 0) { "KOSHEI_PICASSO_URL must name the port the window printed, was $raw" }
            require(base.path.isNullOrEmpty()) { "KOSHEI_PICASSO_URL is the window's origin; the client adds /approvals, was $raw" }
            require(base.rawQuery == null && base.rawFragment == null && base.rawUserInfo == null) { "KOSHEI_PICASSO_URL carries a query, fragment or user info, which would be dropped: $raw" }
            val agent = requireNotNull(env["KOSHEI_PICASSO_AGENT_ID"]?.takeIf { it.isNotBlank() }) { "KOSHEI_PICASSO=picasso needs KOSHEI_PICASSO_AGENT_ID (the AGENT approver id picasso declares)" }
            val timeout = env["KOSHEI_PICASSO_TIMEOUT_MS"]?.let { t -> t.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("KOSHEI_PICASSO_TIMEOUT_MS must be a positive number of ms, was $t") } ?: 8_000
            return PicassoWindowConfig(java.net.URI("http://${base.host}:${base.port}/approvals"), agent, timeout)
        }
    }
}
```

`EpisodeRuntimeConfig` 에 칸 `val window: PicassoWindowConfig? = null` 을 끝에 더하고, `fromEnv` 에서 `window = if (picasso == PicassoMode.PICASSO) PicassoWindowConfig.fromEnv(env) else null` 로 채운다(`picasso` 를 먼저 지역 변수로 읽는다).
- `URI("not a url")` 는 공백 때문에 `URISyntaxException` 을 던지고, 위 `catch` 가 그것을 `IllegalArgumentException` 으로 바꾼다.
- `http://127.0.0.1` 은 port 가 -1 이라 걸린다. `http://localhost:8770/` 은 `trimEnd('/')` 뒤 path 가 빈 문자열이다.
- **중간 커밋이 시험 Mock 으로 돌지 않게:** 이 Task 에서 `EpisodeWorkers.register` 의 창구 인자를 `picasso: ApprovalWindow? = null` 로 바꾸고, 첫 줄 `if (config.picasso == PicassoMode.OFF) return` 은 **그대로 두고**, 그 아래에서 `val window = picasso ?: when (config.picasso) { PicassoMode.MOCK -> MockPicasso(); PicassoMode.PICASSO -> error("KOSHEI_PICASSO=picasso needs the HTTP window (plan C3 Task 3)"); PicassoMode.OFF -> error("unreachable") }` 로 고른다(창구를 넘긴 호출도 OFF 면 아무것도 등록하지 않는다). 아래에서 `picasso` 를 쓰던 자리는 `window` 로. Task 3 이 `error(...)` 를 `HttpApprovalWindow(...)` 로 바꾼다.

`app` 의 감시자가 `picasso` 를 받는지도 붙든다: `app/src/test/kotlin/koshei/app/WatcherConfigTest.kt` 에 `KOSHEI_PICASSO=picasso` 로 `WatcherConfig.fromEnv` 가 통과하는 시험 한 줄을 더한다(그 파일의 다른 시험이 쓰는 최소 환경을 따른다). Files 와 커밋에 이 파일을 더한다.
- import 를 쓰면 `java.net.URI` 를 위로 올린다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.EpisodeWorkersTest" :authoring-api:test --tests "*AutoCorrectGate*" :app:test --tests "*WatcherConfig*"`
Expected: PASS

- [ ] **Step 5: 커밋한다**

```bash
git add episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkers.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWorkersTest.kt authoring-api/src/test/kotlin/koshei/authoring/AutoCorrectGateTest.kt app/src/test/kotlin/koshei/app/WatcherConfigTest.kt
git commit -m "feat(episode-runtime): KOSHEI_PICASSO=picasso names the real window - loopback URL, agent id, timeout" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `HttpApprovalWindow`

**Files:**
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/HttpApprovalWindow.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/HttpApprovalWindowTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

시험은 JDK `HttpServer` 를 루프백 임의 포트에 띄워 picasso 창구를 흉내 낸다. 받은 요청 본문을 모아 두고, 정해 준 상태와 본문으로 답한다.

```kotlin
package koshei.episode.runtime

import com.sun.net.httpserver.HttpServer
import io.temporal.failure.ApplicationFailure
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpApprovalWindowTest {
    private val received = CopyOnWriteArrayList<Pair<String, String>>()   // (method + path, body)
    @Volatile private var status = 200
    @Volatile private var answer = PICASSO_APPROVED
    @Volatile private var delayMs = 0L
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { ex ->
            received += "${ex.requestMethod} ${ex.requestURI.path}" to ex.requestBody.readBytes().toString(Charsets.UTF_8)
            if (delayMs > 0) Thread.sleep(delayMs)
            val bytes = answer.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @AfterEach fun down() = server.stop(0)

    private fun window(timeoutMs: Long = 2_000) =
        HttpApprovalWindow(PicassoWindowConfig(URI("http://127.0.0.1:${server.address.port}/approvals"), "narrator-1", timeoutMs))

    private fun intent(approval: String = """{"by":"PERSON","approverId":"op-7","assurance":"HEADER","at":"2026-10-04T00:00:00Z"}""") = strictJson.readTree(
        """{"candidate":{"candidateId":"c-1","kind":"APPROVE_REMEDY","ref":{"robotId":"hum-02","jobOrderId":"PATROL-APPROVES","searchId":"search-1"},""" +
            """"sawSkillTypes":["pick_place","place"]},"idempotencyKey":"ep:k/r:1:APPROVE_REMEDY","approval":$approval}""",
    )

    @Test fun `a person's approval posts exactly picasso's five fields to slash approvals and returns the answer verbatim`() {
        assertEquals(PICASSO_APPROVED, window().approve(intent(), "PERSON"))
        val (where, body) = received.single()
        assertEquals("POST /approvals", where)
        assertEquals(
            strictJson.readTree("""{"approverId":"op-7","approverKind":"PERSON","robotId":"hum-02","jobOrderId":"PATROL-APPROVES","sawSkillTypes":["pick_place","place"]}"""),
            strictJson.readTree(body),
        )
    }

    @Test fun `a policy approval goes as AGENT under the configured agent id`() {
        window().approve(intent("""{"by":"POLICY","approverId":null,"assurance":null,"at":"2026-10-04T00:00:00Z"}"""), "AGENT")
        val body = strictJson.readTree(received.single().second)
        assertEquals("AGENT", body["approverKind"].textValue())
        assertEquals("narrator-1", body["approverId"].textValue())
    }

    @Test fun `a refusal is an answer too - 200 is returned verbatim, the core judges it`() {
        answer = """{"schemaVersion":"4","contractSemver":"0.9.0","instanceId":"mw-1","outcome":"REFUSED","refusal":"NOT_DECLARED","reason":"선언 없음","consumed":null}"""
        assertEquals(answer, window().approve(intent(), "PERSON"))
    }

    @Test fun `a request picasso cannot read is not retried - 400, 405 and anything not 200`() {
        for ((s, body) in listOf(400 to """{"error":"승인 요청에 «robotId» 이 없다"}""", 405 to """{"error":"승인 시도는 POST 다"}""", 404 to "<h1>404 Not Found</h1>")) {
            status = s
            answer = body
            val e = assertThrows<ApplicationFailure>("$s") { window().approve(intent(), "PERSON") }
            assertTrue(e.isNonRetryable, "$s")
            assertEquals("PicassoRequestRefused", e.type, "$s")
            assertTrue("$s" in e.originalMessage && body.take(20) in e.originalMessage, e.originalMessage)
        }
    }

    @Test fun `a person approval without the operator's id is a bad intent - never invented`() {
        val e = assertThrows<ApplicationFailure> { window().approve(intent("""{"by":"PERSON","approverId":null,"assurance":null,"at":"x"}"""), "PERSON") }
        assertEquals("BadIntent", e.type)
        assertTrue(e.isNonRetryable)
        assertTrue(received.isEmpty(), "nothing was sent")
    }

    @Test fun `an intent without robot, order or skills is a bad intent`() {
        val noRobot = strictJson.readTree("""{"candidate":{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{"jobOrderId":"O"},"sawSkillTypes":["a"]},"idempotencyKey":"k","approval":{"by":"PERSON","approverId":"op"}}""")
        val noSkills = strictJson.readTree("""{"candidate":{"candidateId":"c","kind":"APPROVE_REMEDY","ref":{"robotId":"R","jobOrderId":"O"},"sawSkillTypes":[]},"idempotencyKey":"k","approval":{"by":"PERSON","approverId":"op"}}""")
        for (i in listOf(noRobot, noSkills)) assertEquals("BadIntent", assertThrows<ApplicationFailure> { window().approve(i, "PERSON") }.type)
        assertEquals("BadIntent", assertThrows<ApplicationFailure> { window().approve(intent(), null) }.type)
        assertTrue(received.isEmpty())
    }

    @Test fun `a window that does not answer in time is retried by the activity - a plain failure`() {
        delayMs = 1_500
        val e = assertThrows<Exception> { window(timeoutMs = 300).approve(intent(), "PERSON") }
        assertFalse(e is ApplicationFailure && e.isNonRetryable, "$e")
    }

    @Test fun `a closed port is retried by the activity - a plain failure`() {
        val closed = HttpApprovalWindow(PicassoWindowConfig(URI("http://127.0.0.1:${server.address.port}/approvals"), "a", 1_000))
        server.stop(0)
        val e = assertThrows<Exception> { closed.approve(intent(), "PERSON") }
        assertFalse(e is ApplicationFailure && e.isNonRetryable, "$e")
    }

    @Test fun `revalidation is UNKNOWN - there is no surface to read the ledgers now (design 8_3)`() {
        assertEquals("UNKNOWN", window().revalidate("""{"candidateId":"c","kind":"APPROVE_REMEDY"}"""))
        assertTrue(received.isEmpty())
    }
}
```

- `PICASSO_APPROVED` 는 `Fixtures.kt` 의 판 4 승인 답이다.
- `received.single()` 이 맞으려면 클라이언트가 한 번만 보내야 한다(클라이언트는 재시도하지 않는다 — 재시도는 액티비티의 일).
- `ApplicationFailure.originalMessage` 는 SDK 1.25.1 의 이름이다(다르면 `message` 에서 찾는다).
- 마지막에서 둘째 시험은 `server.stop(0)` 뒤 `@AfterEach` 가 다시 `stop` 을 부른다 — JDK `HttpServer.stop` 은 두 번 불러도 된다. 안 되면 `@AfterEach` 를 `runCatching` 으로 감싼다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.HttpApprovalWindowTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현한다**

```kotlin
package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode
import io.temporal.failure.ApplicationFailure
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * picasso's approval window over HTTP (design §8.4, plan C3): `POST /approvals` on a loopback URL, the request of picasso
 * §7.1 (`ApprovalWire.decode`: approverId, approverKind, robotId, jobOrderId, sawSkillTypes — nothing else is read).
 *
 * - 200 is an answer, an approval or a refusal alike (picasso `ApprovalHost`): returned as the text it is; the core judges it.
 * - Any other status is a request picasso could not take (400: unreadable, 405: not POST, and anything else): not retried,
 *   since the same request gets the same answer. The core reads the failure as an uncertain dispatch (UNKNOWN(OUTCOME)).
 * - No answer (connection refused, timeout) is an ordinary failure: the activity retries, and picasso refuses a second
 *   approval of a consumed proposal (CONSUMED, §11), so a retry cannot dispatch twice.
 *
 * [revalidate] is always UNKNOWN: no surface reads the ledgers as of now (§8.3), so a real episode passes UNKNOWN(PRECONDITION)
 * and a person confirms the precondition.
 *
 * Thread-safe: [HttpClient] is, and nothing else is shared.
 */
class HttpApprovalWindow(private val config: PicassoWindowConfig) : ApprovalWindow {
    private val client: HttpClient = HttpClient.newBuilder()
        .proxy(HttpClient.Builder.NO_PROXY)   // loopback only: an approver id never goes through a configured proxy
        .connectTimeout(Duration.ofMillis(config.timeoutMs))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    override fun revalidate(candidateJson: String): String = "UNKNOWN"

    override fun approve(intent: JsonNode, approverKind: String?): String {
        val body = request(intent, approverKind)
        val response = client.send(
            HttpRequest.newBuilder(config.url)
                .timeout(Duration.ofMillis(config.timeoutMs))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (response.statusCode() == 200) return response.body()
        throw ApplicationFailure.newNonRetryableFailure(
            "picasso's window refused the request: ${response.statusCode()} ${response.body().take(500)}", "PicassoRequestRefused",
        )
    }

    /** picasso §7.1's five fields, from the intent the activity holds; anything missing is a bad intent, never invented. */
    private fun request(intent: JsonNode, approverKind: String?): String {
        val candidate = intent.path("candidate")
        fun text(node: JsonNode, field: String): String =
            node.path(field).takeIf { it.isTextual && it.textValue().isNotBlank() }?.textValue() ?: bad("no $field")
        val skills = candidate.path("sawSkillTypes").takeIf { it.isArray && it.size() > 0 && it.all { s -> s.isTextual } }
            ?.map { it.textValue() } ?: bad("no sawSkillTypes")
        val approverId = when (approverKind) {
            "PERSON" -> text(intent.path("approval"), "approverId")
            "AGENT" -> config.agentId
            else -> bad("approverKind $approverKind")
        }
        return strictJson.createObjectNode().apply {
            put("approverId", approverId)
            put("approverKind", approverKind)
            put("robotId", text(candidate.path("ref"), "robotId"))
            put("jobOrderId", text(candidate.path("ref"), "jobOrderId"))
            putArray("sawSkillTypes").apply { skills.forEach { add(it) } }
        }.toString()
    }

    private fun bad(why: String): Nothing = throw ApplicationFailure.newNonRetryableFailure("intent for picasso: $why", "BadIntent")
}
```

- `client.send` 가 던지는 `IOException`(연결 거부) · `HttpTimeoutException` 은 그대로 올린다 — 재시도할 수 있는 실패다. `InterruptedException` 도 그대로 둔다.
- `strictJson` 은 `Conversions.kt` 의 엄격 매퍼다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.HttpApprovalWindowTest"`
Expected: PASS (9개)

- [ ] **Step 5: 커밋한다**

```bash
git add episode-runtime/src/main/kotlin/koshei/episode/runtime/HttpApprovalWindow.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/HttpApprovalWindowTest.kt
git commit -m "feat(episode-runtime): HttpApprovalWindow - POST /approvals on loopback, 200 verbatim, other statuses not retried" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 워커가 실물 창구를 쓴다 · 끝에서 끝까지(흉내 낸 창구)

**Files:**
- Modify: `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkers.kt` (`register` 의 기본 창구)
- Modify: `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkflow.kt` (dispatch 실패의 `detail` 을 원인 글로)
- Modify: `app/src/main/kotlin/koshei/app/Worker.kt` (기동 안내), `app/build.gradle.kts` (`kosheiEnvKeys`)
- Modify: `docs/usage.md` (환경 표)
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/PicassoWindowEndToEndTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`EpisodeEndToEndTest.kt` 의 길을 따른다: 실제 액티비티, 시험 Postgres, Mock narrator, 정책 v1. 다만 창구는 `EpisodeWorkers.register` 가 설정에서 고른 `HttpApprovalWindow` 이고, 그 상대는 시험 안의 `HttpServer` 다. 서버는 받은 요청을 기록하고 판 4 승인 답(`PICASSO_APPROVED`)으로 답한다.

시험 하나 `` `with KOSHEI_PICASSO=picasso a person's approval reaches the window over HTTP - after a person confirms the precondition` `` 의 흐름:
1. `EpisodeRuntimeConfig.fromEnv(mapOf("KOSHEI_PICASSO" to "picasso", "KOSHEI_PICASSO_URL" to "http://127.0.0.1:<port>", "KOSHEI_PICASSO_AGENT_ID" to "narrator-1", "KOSHEI_EPISODE_POLICY" to <v1 경로>))` 로 설정을 짓는다. `EpisodeWorkers.register(factory, config, store)` 를 **창구 인자 없이** 부른다.
2. 에피소드를 연다(`EpisodeEndToEndTest` 와 같은 증상). AWAITING_APPROVAL 을 기다린다.
3. `decide(…, true, "op-7")`.
4. REVALIDATING 을 지나 `UNKNOWN_PRECONDITION` 에 닿는지 본다(`revalidate` 가 UNKNOWN). 그 상태의 `view` 에서 후보와 제안 id 를 읽는다.
5. 사람이 전제를 확인한다: `confirm(ConfirmRequest("PRECONDITION", candidateId, proposalId, true, operatorId = "op-7", proposition = "(hum-02, PATROL-1)에 searchId search-1 뒤로 더 새 탐색 줄이 없다"))` 를 보내고 답이 `"ACCEPTED"` 인지 본다. **`proposition` 이 비면 코어가 `REFUSED_NO_PROPOSITION` 으로 거절한다**(`Revalidating.kt`, `EpisodeWorkflowTest:242-253` 이 그 둘을 보인다). `ConfirmRequest` 의 나머지 칸 이름은 `Wire.kt` 를 따른다. 명제 글은 운영자 카드(`OperatorCards.kt`)가 APPROVE_REMEDY 에 적는 모양 그대로다(§8.3) — 시험도 카드가 보이는 명제만 확인하는 습관을 따른다.
6. AWAITING_EVIDENCE 를 기다린다.
7. 기대:
   - 서버가 요청을 정확히 한 번 받았다.
   - 경로는 `/approvals` 다.
   - 본문은 `{"approverId":"op-7","approverKind":"PERSON","robotId":"hum-02","jobOrderId":"PATROL-1","sawSkillTypes":["pick_place"]}` 와 같은 JSON 이다. 후보의 실제 값을 쓴다.
   - `DISPATCH_RESULT` 기록의 `answer` 가 서버가 보낸 글자 그대로다.

시험 둘째 `` `a request the window refuses with 400 ends the dispatch as unknown, once, without retries` ``:
- 흐름은 첫째 시험의 1–5 단계와 같다(승인 → UNKNOWN_PRECONDITION → `proposition` 을 든 전제 확인). 그 뒤 디스패치가 나간다.
- 서버가 400 `{"error":"…"}` 로 답한다.
- 기대: 요청이 **한 번만** 왔다(재시도 없음). 에피소드는 UNKNOWN_OUTCOME 이다. `DISPATCH_RESULT` 의 judgement 가 `UNCERTAIN` 이고, detail 에 `PicassoRequestRefused` 나 `400` 이 든다.

- `register` 의 창구 인자를 빼고 부르는 것이 이 시험의 요점이다. 워커가 설정에서 창구를 고르는지를 본다.
- UNKNOWN(PRECONDITION) 의 이름은 `Phase` 의 값을 쓴다(`EpisodeWorkflowTest` 에서 찾는다).

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :episode-runtime:test --tests "koshei.episode.runtime.PicassoWindowEndToEndTest"`
Expected: FAIL — Task 1 뒤의 `register` 는 PICASSO 에서 `error("KOSHEI_PICASSO=picasso needs the HTTP window (plan C3 Task 3)")` 를 던진다.

- [ ] **Step 3: 구현한다**

`EpisodeWorkers.register` 의 서명과 기본 창구:

```kotlin
    fun register(factory: WorkerFactory, config: EpisodeRuntimeConfig, store: EpisodeStore, picasso: ApprovalWindow? = null) {
        if (config.picasso == PicassoMode.OFF) return
        val window = picasso ?: when (config.picasso) {
            PicassoMode.MOCK -> MockPicasso()
            PicassoMode.PICASSO -> HttpApprovalWindow(checkNotNull(config.window) { "KOSHEI_PICASSO=picasso without window settings" })
            PicassoMode.OFF -> error("unreachable")
        }
        …   // the rest as before, with `window` where `picasso` was
    }
```

(Task 1 이 둔 `PicassoMode.PICASSO -> error(...)` 갈래를 위의 `HttpApprovalWindow(...)` 로 바꾸는 것이다.) 문서(KDoc)의 「the Mock by default, the only one until §19 C」를 「the configured window: the Mock for `mock`, [HttpApprovalWindow] for `picasso`; a test may pass its own」로 고친다.

`EpisodeWorkflow.kt` 의 dispatch 실패 갈래(275행 근처 `failed = { EpisodeEvent.DispatchReturned(DispatchResult.Uncertain(it.message), command.token) }`): `it.message` 는 `ActivityFailure` 의 바깥 글(「Activity with activityType=… failed: 'Activity task failed'…」)이라 까닭이 남지 않는다. 기록 실패 갈래(256-257행 근처)가 이미 쓰는 방법대로 원인의 글을 쓴다:

```kotlin
failed = { EpisodeEvent.DispatchReturned(DispatchResult.Uncertain(((it as? ActivityFailure)?.cause as? TemporalFailure)?.message ?: it.message), command.token) },
```

- 명령은 그대로이고 사건의 입력 글만 바뀐다. 재생은 사건의 결과를 다시 계산하지 않고 기록된 실패를 읽으므로 커밋된 재생 이력(`CommittedReplayTest`)은 그대로 통과해야 한다. 통과하는지 본다.
- `ApplicationFailure.message` 는 종류와 원래 글을 함께 싣는다 — 둘째 시험이 `PicassoRequestRefused` 와 `400` 을 찾는 근거다.

`Worker.kt` 의 기동 안내:
- `PicassoMode.MOCK` 경고는 그대로 둔다.
- `PicassoMode.PICASSO` 면 `println("[$workerName] episode approvals go to ${episodeConfig.window!!.url} as PERSON, or AGENT '${episodeConfig.window!!.agentId}' (revalidation is UNKNOWN: a person confirms each precondition)")` 를 찍는다.
- 위 주석의 「today only the test Mock exists (design §19 C)」를 「mock (the test's) or picasso (the real window, plan C3)」로 고친다.

`app/build.gradle.kts` 의 `kosheiEnvKeys` 에 `"KOSHEI_PICASSO_URL", "KOSHEI_PICASSO_AGENT_ID", "KOSHEI_PICASSO_TIMEOUT_MS"` 를 더한다.

`docs/usage.md` §4 「Environment keys」 표(200-217행 근처)의 `KOSHEI_EPISODE_DB_PASS` 행 뒤에 **영어로** 네 행을 더한다(그 파일은 영어다; 지금 표에 `KOSHEI_PICASSO` 행이 없다):
- `KOSHEI_PICASSO`: `off` (default) | `mock` (the test Mock) | `picasso` (the real window).
- `KOSHEI_PICASSO_URL`: the window's origin, `http://127.0.0.1:<port>` or `http://localhost:<port>` only; the client adds `/approvals`.
- `KOSHEI_PICASSO_AGENT_ID`: the approver id a POLICY approval sends as AGENT.
- `KOSHEI_PICASSO_TIMEOUT_MS`: one request's limit, default 8000; keep it at most `(dispatchMs − 3000) / 3`.

표 아래에 두 줄:
- With the real window, revalidation is UNKNOWN, so a person confirms each precondition before a dispatch.
- picasso looks up a declaration for every approver id, PERSON or AGENT; an undeclared one is refused `NOT_DECLARED`.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :episode-runtime:test --rerun :app:test --rerun :authoring-api:test --rerun`
Expected: PASS. 시험 수를 보고한다(`:episode-runtime` 은 대략 290 + Task 1 의 새 시험 + 9 + 2).

- [ ] **Step 5: 커밋한다**

```bash
git add episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkers.kt episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkflow.kt episode-runtime/src/test/kotlin/koshei/episode/runtime/PicassoWindowEndToEndTest.kt app/src/main/kotlin/koshei/app/Worker.kt app/build.gradle.kts docs/usage.md
git commit -m "feat: the worker uses the real approval window for KOSHEI_PICASSO=picasso; end to end over HTTP" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Chunk 2: 참조 담는 쪽과 끝까지 한 번 · 문서

### Task 4: 실물 연결 한 번 (컨트롤러가 한다)

**이 Task 는 하위 에이전트가 아니라 컨트롤러가 한다.** 공용 Temporal · Postgres 를 쓰고, picasso 저장소에서 구동기를 띄운다. 사용자의 승인이 있어야 한다(2026-10-04: 워커를 직접 띄우라는 지시). picasso 쪽 조건:
- `--export` 는 picasso 저장소 밖의 절대 경로로 준다.
- `clean` 과 `./gradlew --stop` 을 쓰지 않는다.
- 띄우기 전에 picasso 세션에 알린다.

컨테이너의 Temporal CLI 는 프런트엔드가 컨테이너 IP 에 붙어 있으므로 늘 `docker exec koshei-temporal-1 sh -c "temporal … --address \$(hostname -i):7233"` 로 부른다. 먼저 `temporal workflow update --help` 로 `update execute --name … --input …` 이 있는지 본다.

- [ ] **Step 1: 큐가 비었는지 본다.** `koshei-episode-tq` · `narrator-tq` · `koshei-v0_1-tq` 의 폴러가 없어야 한다(`temporal task-queue describe`).
- [ ] **Step 2: 선언 파일을 준비한다.** picasso 의 `handoff/narrator/entitlements.json` 을 scratch 의 `<scratch>/picasso-entitlements.json` 으로 복사한다. 그 `entitlements` 배열 끝에 이 항목 하나를 더한다(항목은 승인자마다 하나, 같은 id 두 번은 거절된다):
  `{"approverId":"op-c3","robotIds":["hum-02"],"expiresAt":"2099-01-01T00:00:00Z","actions":[{"skillType":"pick_place","parameters":{"destination":"DROP-01"}}]}`
  `destination` 값은 그 파일의 `narrator-1` 항목의 hum-02 조치 값과 같게 맞춘다(다르면 그 값을 쓴다). picasso 는 PERSON 승인도 승인자 id 의 선언을 찾는다. picasso 저장소 안에는 아무것도 쓰지 않는다. scratch 경로에 공백이 없어야 한다 — `ScenarioHost` 는 인자를 공백으로 나눈다.
- [ ] **Step 3: picasso 세션에 띄운다고 알리고, 참조 담는 쪽을 띄운다** (picasso 저장소 뿌리, 백그라운드, `clean` · `--stop` 금지):
  `./gradlew :picasso:runApprovalHost --args="--port 8770 --seconds 3600 --export <scratch 절대 경로>/picasso-live --entitlements <scratch 절대 경로>/picasso-entitlements.json"`
  배너의 「승인 창구: http://127.0.0.1:8770/approvals」와 instanceId 를 적는다. 포트는 기체 넷에 제안을 세운 뒤 열린다. 배너의 선언 목록에 `op-c3` 가 있는지 본다.
- [ ] **Step 4: koshei 워커를 띄운다** (백그라운드):
  `KOSHEI_PICASSO=picasso KOSHEI_PICASSO_URL=http://127.0.0.1:8770 KOSHEI_PICASSO_AGENT_ID=narrator-1 KOSHEI_NARRATOR=mock KOSHEI_WORKER_NAME=episode-c3 ./gradlew :app:run`
- [ ] **Step 5: 감시자를 띄운다** (백그라운드, 경로는 Windows 꼴 `C:/…`):
  `KOSHEI_PICASSO=picasso KOSHEI_WATCH_EXPORTS=<scratch>/picasso-live KOSHEI_WATCH_CARRY=<scratch>/picasso-live ./gradlew :app:watcher`
  정책 v1 의 병합 규칙이 같은 기체 · 주문의 사건 줄과 탐색 줄을 한 에피소드로 묶는다.
- [ ] **Step 6: 에피소드가 열리는지 본다.** `episode_event` 의 OPENED · TRANSITION 을 본다. 기대는 기체마다 다르다:
  - `hum-02` / `PATROL-APPROVES`: 진단 → AWAITING_APPROVAL.
  - `hum-04`(WITHHELD): 사람에게(`WITHHELD_HUMAN_FIRST`).
  - `hum-03`(NONE): 근거 없음으로 사람에게.
  - `hum-05`(범위 밖): 진단 뒤 승인 대기. 선언된 `op-c3` 로 승인하면 picasso 가 `ROBOT_OUT_OF_SCOPE` 로 거절할 것이다(선언이 그 기체를 범위에 두지 않으면) — 시간이 되면 이것도 본다.
- [ ] **Step 7: hum-02 를 사람이 승인한다.** 기한을 지킨다: `approvalMs` 5분 안에 승인하고, 승인에서 디스패치 관문까지 `approvalValidityMs` 10분 안에 전제를 확인한다. 넘으면 APPROVAL_EXPIRED · APPROVAL_LAPSED 로 끝난다.
  - `view` 질의로 `proposalId` · `candidatesVersion` 을 읽는다: `temporal workflow query --workflow-id <id> --type view`.
  - `decide` Update 를 보낸다: `temporal workflow update execute --workflow-id <id> --name decide --input '{"proposalId":…,"sawCandidatesVersion":…,"approve":true,"operatorId":"op-c3"}'`.
  - UNKNOWN(PRECONDITION) 에 닿으면 `confirm` Update 를 보낸다: kind `PRECONDITION`, holds `true`, `proposition` 은 **운영자 카드의 명제 그대로** `(hum-02, PATROL-APPROVES)에 searchId <그 줄의 searchId> 뒤로 더 새 탐색 줄이 없다`(§8.3, `OperatorCards.kt`) — 명제가 없으면 거절되고(`REFUSED_NO_PROPOSITION`), 카드가 보이지 않은 글을 적으면 감사 기록이 거짓이 된다. 다른 길로는 제어면 `POST /api/episodes/{wf}/{run}/confirm` 이 카드의 명제로 채운다.
  - 입력 JSON 은 scratch 파일에 UTF-8 로 쓰고 `docker cp` 로 컨테이너에 넣어 `--input-file` 로 준다(셸 따옴표와 한글 인코딩이 깨지지 않게). `task-queue describe` 는 큐마다 `--task-queue <이름>` 과 같은 `--address` 를 준다.
  - 변환기는 Jackson JSON 이라 평문 JSON 이 그대로 읽힌다. Update 이름과 입력 모양은 `EpisodeWorkflow` 인터페이스와 `Wire.kt` 에서 확인한다.
- [ ] **Step 8: 디스패치를 본다.**
  - picasso 가 판 4 의 APPROVED 를 돌려줘야 한다. 확인할 것은 `DISPATCH_RESULT` 의 answer 와 구동기 로그의 「승인이 섰다」다.
  - 시계가 흐르면 `job-responses.jsonl` 에 그 `executionId` 의 통보가 생긴다. 감시자가 그것을 나른다.
  - 에피소드가 어디로 가는지 적는다. RESOLVED 면 「모든 단위 완료 + ONLINE」, UNKNOWN_OUTCOME 이면 그 통보의 의심 칸이다. 어느 쪽이든 실물 통보가 실물 승인 답의 (instanceId, executionId) 로 이어졌는지가 확인할 점이다.
- [ ] **Step 9: 내린다.** 워커 · 감시자 · 구동기 순서로 내린다. 남은 JVM 이 없는지 본다(제가 띄운 것만). 열린 시험 에피소드는 보존 기한으로 닫히게 둔다(필요하면 `episode agent-off --all`).
- [ ] **Step 10: 결과를 picasso 세션에 알린다.** 무엇을 보냈고, 무엇이 돌아왔고, 어디로 갔는지 알린다.

### Task 5: 설계 문서

**Files:**
- Modify: `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md`

- [ ] **Step 1: 고친다** (한국어, 짧은 평서문)
  1. **§8.4 끝:** 「구현(C3)」 문단을 더한다. 담을 내용:
     - `HttpApprovalWindow`, 요청 다섯 칸과 승인자 id 의 출처(PERSON 이면 운영자, AGENT 면 `KOSHEI_PICASSO_AGENT_ID`).
     - 200 은 글자 그대로 돌려준다. 그 밖의 상태는 재시도하지 않는다(`PicassoRequestRefused` → UNKNOWN(OUTCOME)). 무응답은 재시도한다.
     - 루프백만 받고, 시간 한도는 `dispatchMs` 보다 작게 둔다.
  2. **§8.3 「재조회만으로 TRUE 까지 가는 길은 시험의 Mock picasso 뿐이다」 뒤:** 「실물 창구(`HttpApprovalWindow`)의 `revalidate` 는 늘 UNKNOWN 이다(C3)」.
  3. **§4.2:**
     - 감시자의 설정 표(C1): `KOSHEI_PICASSO` 행에 `picasso` 를 더한다. `KOSHEI_PICASSO_URL` · `_AGENT_ID` · `_TIMEOUT_MS` 행을 더한다.
     - 워커의 구현(B3b) 항목(121-123행 근처 「그 밖의 값은 시작을 멈춘다 — 실물 승인 창구 클라이언트는 §19 C 뒤다」, 133행 근처 「지금 구현은 MockPicasso 하나다」): 「(C3: `picasso` 가 실물 창구 `HttpApprovalWindow` 다)」를 붙인다.
     - §16 의 「그때까지 `KOSHEI_PICASSO` 는 `off` 아니면 `mock` 이다」(1399 · 1426행 근처): 「(C3: 이제 `picasso` 도 있다)」를 붙인다.
     - §8.4 의 「선언」 행 옆에: 참조 구동기의 선언은 `narrator-1` · `3` · `4` 뿐이라, 운영자 id 로 승인하려면 그 id 를 선언한 파일을 구동기에 준다(C3 의 연결 시험이 그렇게 했다).
  4. **§16:** 「C3 끝(날짜)」 문단을 더한다. 담을 내용: 커밋, 시험 수, Task 4 의 실물 연결 결과(무엇이 나갔고 무엇이 돌아왔고 에피소드가 어디로 갔는지).
     - 「계획 C 에 남은 것」을 고친다. 실물 승인 창구 클라이언트는 섰다. 실물 담는 쪽(배치 저장소)은 남는다 — 사용자 결정(2026-10-04): 실물 현장이 생길 때 별도 저장소, picasso 동의.
  5. **§19 C 행:** 결정을 적는다 — 실물 담는 쪽은 별도 배치 저장소다. 현장 운영 쪽이 소유하고 picasso 를 라이브러리(main 소스: `ApprovalWire`, `ResultExport`, `Middleware`)로 쓴다. 참조 구동기(`ApprovalHost`, `ScenarioHost`, `BundleWriter.carry`)는 picasso 의 시험 소스라 그 저장소가 새로 쓴다. 그 저장소에 넘길 조건은 결과 통보 파일이 추가 전용 · 전체 이력이라는 것(§12)이다.
- [ ] **Step 2: 커밋한다**

```bash
git add docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md
git commit -m "docs(design): C3 - the real approval-window client; host placement decided (a separate deployment repo)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
