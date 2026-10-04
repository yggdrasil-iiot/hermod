# 계획 B3a — 에피소드 워크플로 구동부 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 순수 전이 함수(`:episode`)를 Temporal 워크플로로 감싸는 `:episode-runtime` 모듈을 만든다. 시작(signalWithStart) · signal · Update · 쿼리 · 타이머 · 액티비티 호출을 전이 함수의 사건과 명령으로 잇고, Mock 진단으로 끝까지 도는 것을 시험 환경에서 보인다(설계 §4.1 · §7 · §8.1).

**Architecture:**
- **판단은 순수 함수가 한다.** 워크플로는 사건을 넣고 나온 명령을 실행하는 껍질이다(§6).
- **상태는 워크플로 메모리에만 둔다.** Temporal 이 이력을 재생해 같은 사건 순서로 다시 만든다. 그래서 `:episode` 의 형(내부 생성자 · 봉인 인터페이스 · `Instant`)은 페이로드로 건너지 않고, 경계를 넘는 것은 기본값을 가진 데이터 클래스와 JSON 글자뿐이다.
- **명령은 막지 않고 실행한다.** 액티비티와 타이머는 `Async` · `Workflow.newTimer` 로 띄우고 결과를 수신함에 사건으로 넣는다. 본 루프는 수신함을 하나씩 전이 함수에 넣는다.
- **Update 처리기는 전이를 바로 적용하고 답을 값으로 돌려준다.** 전이 함수 안에는 막히는 호출이 없으므로 처리기와 본 루프가 섞이지 않는다.
- **narrator 와는 JSON 글자 그대로 오간다.** 요청은 계약 0.6 §2 의 키 순서와 명시적 null 을 지키고, 응답은 받은 바이트 그대로 받는다. 응답의 겹친 키는 `parseDiagnosisResponse(String)` 이 잡는다(B2b-1). 이를 위해 `:runtime` 의 페이로드 변환기에 표지 타입 `RawJson` 을 하나 더한다. Temporal 1.25.1 에는 `RawValue` 가 없다.

**Tech Stack:** Kotlin 2.2 / JDK 21, Temporal Java SDK 1.25.1 (`temporal-testing` 의 `TestWorkflowEnvironment` — 시간 건너뛰기), Jackson 2.17 + jackson-module-kotlin

**작업 위치:** worktree `C:\Users\Eisen\Desktop\Labs\[projects] koshchei\koshei-episode-core`, 브랜치 `feat/episode-core` (머리 `688d128`, `:episode` 시험 291개). 원래 체크아웃(`koshei/`)은 건드리지 않는다. 파일은 Write/Edit 로만 쓴다(heredoc 금지). bare `git stash` 금지. 푸시하지 않는다.

**커밋 규칙:** 모든 커밋은 `git commit -m "<제목>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`.

**범위 밖:**
- **계획 B3b:** 실제 액티비티 — YAML 정책 파일 읽기(겹친 키 거부), `episode_event` 추가 전용 테이블, 알림 채널, Mock picasso, `:app` 워커 등록과 `koshei-episode-tq`, Mock ↔ 실물 진단 스위치(narrator 워커가 `narrator-tq` 를 폴링), 재생(replay) 시험.
- **계획 B3c:** 제어면(`/api/episodes…`).
- **계획 C:** 감시자 · 담는 쪽.

---

## 설계에서 코드로 옮길 때 정한 것

| 설계 | 이 계획의 코드 |
|---|---|
| §7.1 시작은 `signalWithStart` 만 | 시작 입력 `EpisodeStart(manifestJson)` 과 signal `symptom(SymptomSignal)`. 워크플로는 첫 증상을 기다려 `startEpisode` 를 부른다. 열리기 전에 온 증상은 모아 두었다가 열린 뒤 `SymptomJoined` 로 넣는다 |
| §7.1 인스턴스 id = 워크플로 id + runId | `"<workflowId>/<runId>"` |
| §7.5 정책을 결정할 때마다 읽는다 | 액티비티 결과와 타이머가 낳은 사건은 넣기 전에 `readPolicy` 를 다시 부른다(로컬 액티비티). 사람의 Update 와 증상 · 근거 signal 은 지금 표로 판단한다(`read = null`). ESCALATED 와 종료 상태에서는 읽지 않는다 |
| §7.2 Update 검증기 = 해독 · 형식만 | 운영자 id 가 비었는지, 열거값 이름이 맞는지, 필요한 칸이 있는지만 보고 `IllegalArgumentException` 을 던진다(이력에 남지 않음, 스파이크 결과). 거절은 처리기가 `Reply` 이름으로 돌려준다 |
| §8.1 진단 액티비티 | 큐 `narrator-tq`, 이름 `diagnose`(정확히 — Java 의 기본 이름 규칙은 첫 글자를 대문자로 바꾸므로 `@ActivityMethod(name = "diagnose")`). ScheduleToClose · StartToClose · Heartbeat · 최대 시도는 정책의 `diagnosis` 칸. `CancellationScope` 로 감싸 `CancelDiagnosis` 에 취소한다 |
| §6 `Command.Record`(기다리지 않음) | `record` 액티비티를 띄우고 결과를 버린다. 실패 알림(`RECORD_LAG`)은 B3b |
| 증상 줄을 읽을 수 없음 | 빈 객체의 증상으로 넣는다. 전이 함수가 「열 수 없는 증상」으로 기록하거나 사람에게 넘긴다(B2b-2a). 껍질은 판단하지 않는다 |

---

## 파일 구조

| 파일 | 할 일 | 책임 |
|---|---|---|
| `runtime/src/main/kotlin/koshei/runtime/DataConverterSupport.kt` | 수정 | `RawJson` 과 그것을 지나보내는 변환기 |
| `runtime/src/test/kotlin/koshei/runtime/RawJsonConverterTest.kt` | 생성 | Task 1 |
| `settings.gradle.kts` | 수정 | 모듈 추가 |
| `episode/src/main/kotlin/koshei/episode/EpisodeState.kt` | 수정 | `currentUnknowns()` 공개(쿼리용) |
| `episode-runtime/build.gradle.kts` | 생성 | 모듈 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/Wire.kt` | 생성 | 페이로드 데이터 클래스, 큐 이름 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/Conversions.kt` | 생성 | 페이로드 ↔ 전이 함수의 사건 · 명령 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/Activities.kt` | 생성 | 액티비티 인터페이스, Mock 진단 |
| `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkflow.kt` | 생성 | 워크플로 인터페이스와 구현 |
| `episode-runtime/src/test/resources/picasso/run-1/*` | 복사 | `:episode` 의 picasso run-1 바이트 사본 |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/ConversionsTest.kt` | 생성 | Task 2 |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/Fixtures.kt` | 생성 | 시험용 줄 · 정책 · 가짜 액티비티 |
| `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWorkflowTest.kt` | 생성 | Task 3 |

---

## Chunk 1: 변환기와 모듈

### Task 1: `RawJson` — JSON 을 글자 그대로

**Files:**
- Modify: `runtime/src/main/kotlin/koshei/runtime/DataConverterSupport.kt`
- Test: `runtime/src/test/kotlin/koshei/runtime/RawJsonConverterTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`runtime/src/test/kotlin/koshei/runtime/RawJsonConverterTest.kt`:

```kotlin
package koshei.runtime

import com.google.protobuf.ByteString
import io.temporal.api.common.v1.Payload
import kotlin.test.Test
import kotlin.test.assertEquals

class RawJsonConverterTest {
    private val converter = DataConverterSupport.dataConverter()

    @Test fun `raw JSON goes out and comes back byte for byte, duplicate keys and all`() {
        val text = """{"b":1,"a":null,"a":2}"""
        val payload = converter.toPayload(RawJson(text)).get()
        assertEquals("json/plain", payload.getMetadataOrThrow("encoding").toStringUtf8())
        assertEquals(text, payload.data.toStringUtf8())
        assertEquals(RawJson(text), converter.fromPayload(payload, RawJson::class.java, RawJson::class.java))
    }

    @Test fun `a payload another SDK wrote is read as it was written`() {
        val written = """{"outcome": "NO_GROUNDS", "candidateId": null}"""
        val python = Payload.newBuilder()
            .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
            .setData(ByteString.copyFromUtf8(written))
            .build()
        assertEquals(written, converter.fromPayload(python, RawJson::class.java, RawJson::class.java).text)
    }

    @Test fun `everything else still goes through the Kotlin-aware Jackson converter`() {
        val payload = converter.toPayload(WorkflowInput(workflowName = "x")).get()
        assertEquals("x", converter.fromPayload(payload, WorkflowInput::class.java, WorkflowInput::class.java).workflowName)
    }
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew --no-daemon :runtime:test --tests 'koshei.runtime.RawJsonConverterTest'`
Expected: 컴파일 실패 — `Unresolved reference 'dataConverter'`, `'RawJson'`. (`:runtime` 의 다른 시험은 Docker 의 Postgres 를 쓰므로 이 계획에서는 이 시험만 돌린다.)

- [ ] **Step 3: 구현한다**

`DataConverterSupport.kt` 전체를 이것으로 바꾼다:

```kotlin
package koshei.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.protobuf.ByteString
import io.temporal.api.common.v1.Payload
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.PayloadConverter
import java.lang.reflect.Type
import java.util.Optional

/**
 * A JSON payload kept as its exact text: what goes out is these bytes, what comes in is the bytes as received, with no
 * Jackson round trip. The episode workflow talks to narrator's Python `diagnose` activity this way — the request keeps
 * its key order and explicit nulls (narrator contract 0.6 §2), and a duplicated key in the answer is still there to be
 * caught (episode `parseDiagnosisResponse(String)`).
 */
data class RawJson(val text: String = "")

/** The `json/plain` converter with one exception: [RawJson] passes through untouched. Everything else is [jackson]. */
class RawAwareJsonPayloadConverter(private val jackson: JacksonJsonPayloadConverter) : PayloadConverter {
    override fun getEncodingType(): String = jackson.encodingType

    override fun toData(value: Any?): Optional<Payload> =
        if (value is RawJson) Optional.of(
            Payload.newBuilder()
                .putMetadata(ENCODING_KEY, ByteString.copyFromUtf8(jackson.encodingType))
                .setData(ByteString.copyFromUtf8(value.text))
                .build(),
        )
        else jackson.toData(value)

    override fun <T> fromData(content: Payload, valueType: Class<T>, valueGenericType: Type): T =
        if (valueType == RawJson::class.java) valueType.cast(RawJson(content.data.toStringUtf8()))
        else jackson.fromData(content, valueType, valueGenericType)

    private companion object {
        /** Temporal's payload metadata key for the encoding (`EncodingKeys.METADATA_ENCODING_KEY`). */
        const val ENCODING_KEY = "encoding"
    }
}

/**
 * Kotlin-aware Temporal client options. (REF: spike TemporalClientSupport.clientOptions().)
 *
 * SDK 1.25.1's default JacksonJsonPayloadConverter uses a plain ObjectMapper that cannot construct
 * Kotlin data classes lacking a no-arg constructor (e.g. WorkflowInput/WorkflowOutput). Both the
 * Worker and the Starter build their WorkflowClient with these options so the same Kotlin-module-aware
 * JSON converter is used worker-side and client-side. SDK config only; the saga logic is untouched.
 * (Mandatory — see design §8.) [RawJson] payloads pass through verbatim (episode design §8.1).
 */
object DataConverterSupport {
    fun clientOptions(): WorkflowClientOptions = WorkflowClientOptions.newBuilder().setDataConverter(dataConverter()).build()

    /** The converter [clientOptions] uses, for code that needs it directly (a test environment, a replayer). */
    fun dataConverter(): DataConverter = DefaultDataConverter.newDefaultInstance()
        .withPayloadConverterOverrides(RawAwareJsonPayloadConverter(JacksonJsonPayloadConverter(jacksonObjectMapper())))
}
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew --no-daemon :runtime:test --tests 'koshei.runtime.RawJsonConverterTest'` 그리고 `./gradlew --no-daemon :runtime:compileTestKotlin :app:compileKotlin :authoring-api:compileKotlin`
Expected: `BUILD SUCCESSFUL` — 시험 3개, 나머지 모듈도 컴파일된다(`clientOptions()` 의 모양은 그대로).

`com.google.protobuf` 나 `io.temporal.api.common.v1.Payload` 가 컴파일 경로에 없다는 오류가 나면, `runtime/build.gradle.kts` 에 `implementation("com.google.protobuf:protobuf-java")` 를 더하지 말고 먼저 `./gradlew :runtime:dependencies --configuration compileClasspath` 로 temporal-sdk 가 무엇을 끌어오는지 보고한다.

- [ ] **Step 5: Commit**

```bash
git add runtime/src/main/kotlin/koshei/runtime/DataConverterSupport.kt runtime/src/test/kotlin/koshei/runtime/RawJsonConverterTest.kt
git commit -m "feat(runtime): RawJson payloads cross the converter verbatim" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: 모듈, 페이로드, 변환

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `episode/src/main/kotlin/koshei/episode/EpisodeState.kt`
- Create: `episode-runtime/build.gradle.kts`
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/Wire.kt`
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/Conversions.kt`
- Copy: `episode-runtime/src/test/resources/picasso/run-1/` ← `episode/src/test/resources/picasso/run-1/`
- Create: `episode-runtime/src/test/kotlin/koshei/episode/runtime/Fixtures.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/ConversionsTest.kt`

- [ ] **Step 1: 모듈을 세운다**

`settings.gradle.kts` 의 `"delegation", "episode")` 를 `"delegation", "episode", "episode-runtime")` 로 바꾼다.

`episode-runtime/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm")
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
// The Temporal shell around the pure episode core (design §4.1): workflow, activity interfaces, the Mock narrator.
dependencies {
    implementation(project(":episode"))
    implementation(project(":runtime"))   // DataConverterSupport, RawJson
    implementation(libs.temporal.sdk)
    implementation(libs.jackson.kotlin)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.temporal.testing)
}
```

`episode/src/main/kotlin/koshei/episode/EpisodeState.kt` 에서 `    internal fun currentUnknowns(): List<Unknown> {` 를 `    fun currentUnknowns(): List<Unknown> {` 로 바꾼다(쿼리가 읽는다. 상태를 바꾸지 않는 함수다).

시험용 picasso 줄을 복사한다(원본은 읽기만):

```bash
mkdir -p episode-runtime/src/test/resources/picasso/run-1
cp episode/src/test/resources/picasso/run-1/manifest.json episode/src/test/resources/picasso/run-1/incidents.jsonl episode/src/test/resources/picasso/run-1/remedy-searches.jsonl episode-runtime/src/test/resources/picasso/run-1/
```

`.gitattributes` 에 한 줄 더한다: `episode-runtime/src/test/resources/** text eol=lf`

- [ ] **Step 2: 시험 몰개를 쓴다**

`episode-runtime/src/test/kotlin/koshei/episode/runtime/Fixtures.kt`:

```kotlin
package koshei.episode.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Collections

/** picasso run-1 lines (byte copies of the `:episode` test resources; the picasso repo is read-only). */
internal object Picasso {
    private val mapper = ObjectMapper()
    private fun res(name: String): String =
        requireNotNull(javaClass.getResource("/picasso/run-1/$name")) { "missing test resource run-1/$name" }.readText(Charsets.UTF_8)

    val manifest: String get() = res("manifest.json")

    private fun line(file: String, key: String, id: String): String =
        res(file).lines().filter { it.isNotBlank() }.single { mapper.readTree(it).get(key)?.asText() == id }

    fun search(id: String): String = line("remedy-searches.jsonl", "searchId", id)
    fun incident(id: String): String = line("incidents.jsonl", "incidentId", id)
}

/** Policy tables as `readPolicy` would return them (design §10.1; the same values as the `:episode` test table). */
internal object TestPolicies {
    fun table(autoApprove: Boolean): String = """
        {
          "version": "2026-10-02.1",
          "expiresAt": null,
          "agentLayerEnabled": true,
          "autoApprove": { "APPROVE_REMEDY": { "allowed": $autoApprove, "requireClean": true, "skills": ["pick_place"] } },
          "actionCatalog": [],
          "deadlines": {
            "correlatingMs": 5000, "approvalMs": 300000, "approvalValidityMs": 600000, "revalidateMs": 30000,
            "recordMs": 10000, "dispatchMs": 30000, "childMaxMs": 600000, "evidenceMs": 600000,
            "unknownRecheckMs": 30000, "unknownMs": 900000, "escalatedRetentionMs": 86400000, "episodeMs": 3600000
          },
          "diagnosis": { "scheduleToCloseMs": 1200000, "startToCloseMs": 540000, "heartbeatMs": 30000, "maxAttempts": 2 },
          "maxAttemptsPerEpisode": 3,
          "repeatedRemedyThreshold": 2,
          "snapshotMaxBytes": 262144,
          "correlation": []
        }
    """.trimIndent()
}

/** picasso's approval-window answer for an approved remedy (picasso `ApprovalWire.encode`, schema 2). */
internal const val PICASSO_APPROVED =
    """{"schemaVersion":"2","contractSemver":"1.0.0","outcome":"APPROVED","executionId":"exec-42","steps":[{"skillType":"pick_place","parameters":{"slot":"B-03"}}]}"""

/** koshei-side activities for tests: programmable answers, every call remembered in order. */
internal class FakeEpisodeActivities : EpisodeActivities {
    @Volatile var policyJson: String? = TestPolicies.table(autoApprove = true)
    @Volatile var revalidation: String = "TRUE"
    @Volatile var dispatchOutcome: DispatchOutcome = DispatchOutcome("ANSWER", PICASSO_APPROVED)
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun readPolicy(): PolicyReadResult = policyJson?.let { PolicyReadResult("FOUND", it) } ?: PolicyReadResult("MISSING")

    override fun revalidate(candidateJson: String): String {
        calls += "revalidate"
        return revalidation
    }

    override fun record(instanceId: String, seq: Long, kind: String, payloadJson: String) {
        calls += "record:$kind"
    }

    override fun recordIntent(instanceId: String, seq: Long, intentJson: String) {
        calls += "intent:$seq"
    }

    override fun dispatch(intentJson: String, approverKind: String?): DispatchOutcome {
        calls += "dispatch:$approverKind"
        return dispatchOutcome
    }

    override fun sendNotice(instanceId: String, noticeJson: String) {
        calls += "notice"
    }
}
```

- [ ] **Step 3: 실패하는 시험을 쓴다**

`episode-runtime/src/test/kotlin/koshei/episode/runtime/ConversionsTest.kt`:

```kotlin
package koshei.episode.runtime

import koshei.episode.DispatchResult
import koshei.episode.EpisodeEvent
import koshei.episode.PolicyRead
import koshei.episode.RejectReason
import koshei.episode.SymptomKind
import koshei.episode.UnknownWhat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConversionsTest {
    @Test fun `a symptom signal becomes a symptom, and an unreadable one an empty line the core will refuse`() {
        val ok = SymptomSignal("search:search-1", "SEARCH", Picasso.search("search-1"), 1_000).toSymptom()
        assertEquals(SymptomKind.SEARCH, ok.kind)
        assertEquals("search-1", ok.line.get("searchId").asText())
        assertEquals(1_000, ok.observedAt.toEpochMilli())
        val bad = SymptomSignal("x", "SEARCH", "{not json", 0).toSymptom()
        assertEquals(0, bad.line.size())
        assertEquals(SymptomKind.SEARCH, SymptomSignal("y", "NOT_A_KIND", Picasso.search("search-1"), 0).toSymptom().kind)
        assertEquals(0, SymptomSignal("y", "NOT_A_KIND", Picasso.search("search-1"), 0).toSymptom().line.size())
    }

    @Test fun `a policy read becomes the core's read, and anything odd is unreadable`() {
        assertIs<PolicyRead.Found>(PolicyReadResult("FOUND", TestPolicies.table(true)).toRead())
        assertEquals(PolicyRead.Missing, PolicyReadResult("MISSING").toRead())
        assertIs<PolicyRead.Unreadable>(PolicyReadResult("FOUND", """{"a":1,"a":2}""").toRead())   // duplicate key
        assertIs<PolicyRead.Unreadable>(PolicyReadResult("FOUND", null).toRead())
        assertIs<PolicyRead.Unreadable>(PolicyReadResult("UNREADABLE", null, "yaml: bad indent").toRead())
        assertIs<PolicyRead.Unreadable>(PolicyReadResult("SOMETHING").toRead())
    }

    @Test fun `Update requests become events, and the validators refuse only malformed ones`() {
        val decide = DecideRequest("ep#1", "sha256:x", false, "op-1", "WRONG_TARGET", "note").toEvent()
        assertEquals(EpisodeEvent.ApprovalDecided("ep#1", "sha256:x", false, koshei.episode.Approver("op-1"), RejectReason.WRONG_TARGET, "note"), decide)
        assertFailsWith<IllegalArgumentException> { DecideRequest(operatorId = "").validate() }
        assertFailsWith<IllegalArgumentException> { DecideRequest(proposalId = "p", sawCandidatesVersion = "v", operatorId = "op", reason = "NOPE").validate() }

        assertIs<EpisodeEvent.ConfirmedPrecondition>(ConfirmRequest("PRECONDITION", "c", "ep#1", true, operatorId = "op").toEvent())
        assertIs<EpisodeEvent.ConfirmedOutcome>(ConfirmRequest("OUTCOME", "c", "ep#1", true, operatorId = "op").toEvent())
        val unknown = ConfirmRequest("UNKNOWN", subject = mapOf("unitId" to "u-1"), what = "LINK_BROKEN", operatorId = "op").toEvent()
        assertIs<EpisodeEvent.ConfirmedUnknown>(unknown)
        assertEquals(UnknownWhat.LINK_BROKEN, unknown.what)
        assertFailsWith<IllegalArgumentException> { ConfirmRequest("MAYBE", operatorId = "op").validate() }
        assertFailsWith<IllegalArgumentException> { ConfirmRequest("UNKNOWN", what = "LINK_BROKEN", operatorId = "op").validate() }   // no subject
        assertFailsWith<IllegalArgumentException> { ConfirmRequest("OUTCOME", candidateId = "c", operatorId = "op").validate() }        // no proposal
        assertFailsWith<IllegalArgumentException> { TakeoverRequest("").validate() }
        assertFailsWith<IllegalArgumentException> { CloseRequest("op", "").validate() }
    }

    @Test fun `a dispatch outcome becomes the core's result, and anything odd is uncertain`() {
        assertEquals(DispatchResult.Answer(PICASSO_APPROVED), DispatchOutcome("ANSWER", PICASSO_APPROVED).toResult())
        assertEquals(DispatchResult.PersonTaskIssued, DispatchOutcome("PERSON_TASK").toResult())
        assertIs<DispatchResult.Uncertain>(DispatchOutcome("ANSWER", null).toResult())
        assertIs<DispatchResult.Uncertain>(DispatchOutcome("SOMETHING").toResult())
    }

    @Test fun `an evidence signal keeps null as not reported`() {
        val e = EvidenceSignal("jr-1", "exec-42", null, null, listOf("u-1"), null, emptyList(), null, 5).toEvidence()
        assertEquals(listOf("u-1"), e.completedUnits)
        assertEquals(null, e.inDoubtUnits)
        assertEquals(null, e.operatorRequired)
        assertTrue(e.unverifiedUnits!!.isEmpty())
    }
}
```

- [ ] **Step 4: 실패를 본다**

Run: `./gradlew --no-daemon :episode-runtime:test --tests 'koshei.episode.runtime.ConversionsTest'`
Expected: 컴파일 실패 — `Unresolved reference 'SymptomSignal'` 등(그리고 `Fixtures.kt` 의 `EpisodeActivities`).

- [ ] **Step 5: `Wire.kt` 를 쓴다**

```kotlin
package koshei.episode.runtime

/** The episode workflow's own queue (design §4.2). */
const val EPISODE_TASK_QUEUE = "koshei-episode-tq"

/** narrator's queue (narrator contract 0.6 §2). Its Python worker — or, in mock mode, koshei's — polls it. */
const val NARRATOR_TASK_QUEUE = "narrator-tq"

// Everything below crosses a Temporal payload boundary: Kotlin data classes whose fields all have defaults (so any
// Jackson converter can build them), plain strings and numbers, JSON as text. The core's own types never cross.

/** signalWithStart's start input (design §7.1). [manifestJson] is the picasso export manifest the symptoms come from. */
data class EpisodeStart(val manifestJson: String = "", val schemaVersion: Int = 1)

/** `symptom` signal: one picasso export line (design §12). [kind] is `INCIDENT` or `SEARCH`. */
data class SymptomSignal(val eventId: String = "", val kind: String = "", val lineJson: String = "", val observedAtMillis: Long = 0)

/** `evidence` signal: a JobResponse the host carried (design §12). A null list or flag means "not reported". */
data class EvidenceSignal(
    val jobResponseId: String = "",
    val executionId: String? = null,
    val physicalState: String? = null,
    val reachedEvidence: String? = null,
    val completedUnits: List<String>? = null,
    val inDoubtUnits: List<String>? = null,
    val unverifiedUnits: List<String>? = null,
    val operatorRequired: Boolean? = null,
    val receivedAtMillis: Long = 0,
)

/** `decide` Update (design §7.2). [reason] is a `RejectReason` name; [note] stays in the audit record. */
data class DecideRequest(
    val proposalId: String = "",
    val sawCandidatesVersion: String = "",
    val approve: Boolean = false,
    val operatorId: String = "",
    val reason: String? = null,
    val note: String? = null,
)

/**
 * `confirm` Update (design §7.2). [kind]: `PRECONDITION` and `OUTCOME` name the proposal ([candidateId], [proposalId])
 * and say whether it [holds] (for OUTCOME: whether it was done); `UNKNOWN` names an unknown ([subject], [what]).
 */
data class ConfirmRequest(
    val kind: String = "",
    val candidateId: String = "",
    val proposalId: String = "",
    val holds: Boolean = false,
    val subject: Map<String, String?> = emptyMap(),
    val what: String = "",
    val operatorId: String = "",
    val note: String? = null,
)

data class TakeoverRequest(val operatorId: String = "")

data class CloseRequest(val operatorId: String = "", val outcome: String = "")

/** What `readPolicy` found (design §8.2): `FOUND` with the table as JSON text, `UNREADABLE` with why, or `MISSING`. */
data class PolicyReadResult(val kind: String = "MISSING", val json: String? = null, val reason: String? = null)

/** What `dispatch` came back with: `ANSWER` with picasso's answer as raw text, or `PERSON_TASK`. */
data class DispatchOutcome(val kind: String = "", val answerJson: String? = null)

/** `view` query (design §7.3). Times are epoch milliseconds; history and unknowns are JSON text. */
data class EpisodeView(
    val instanceId: String = "",
    val phase: String = "",
    val escalationReason: String? = null,
    val escalationDetail: String? = null,
    val attempt: Int? = null,
    val proposalId: String? = null,
    val candidateId: String? = null,
    val candidatesVersion: String? = null,
    val stateDeadlineMillis: Long? = null,
    val episodeDeadlineMillis: Long? = null,
    val historyJson: String = "[]",
    val unknownsJson: String = "[]",
    val symptoms: Int = 0,
)

/** What the workflow returns when the episode ends. */
data class EpisodeResult(val phase: String = "", val escalationReason: String? = null)
```

- [ ] **Step 6: `Conversions.kt` 를 쓴다**

```kotlin
package koshei.episode.runtime

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import koshei.episode.Approver
import koshei.episode.DispatchIntent
import koshei.episode.DispatchResult
import koshei.episode.EpisodeEvent
import koshei.episode.EpisodeState
import koshei.episode.Evidence
import koshei.episode.Notice
import koshei.episode.PolicyRead
import koshei.episode.RejectReason
import koshei.episode.Symptom
import koshei.episode.SymptomKind
import koshei.episode.TriState
import koshei.episode.UnknownWhat
import java.time.Instant

/** Strict like the core's own mapper: a duplicated key is an error, never silently the last value. */
internal val strictJson: ObjectMapper = ObjectMapper()
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

/**
 * A symptom signal as the core's [Symptom]. A line that is not a JSON object, or an unknown kind, becomes an empty
 * SEARCH line: the core then refuses it with a record (opening: escalates, joining: ignored) — the shell does not judge.
 */
internal fun SymptomSignal.toSymptom(): Symptom {
    val known = SymptomKind.entries.firstOrNull { it.name == kind }
    val node = if (known == null) null else try {
        strictJson.readTree(lineJson) as? ObjectNode
    } catch (e: Exception) {
        null
    }
    return Symptom(eventId, known ?: SymptomKind.SEARCH, node ?: strictJson.createObjectNode(), Instant.ofEpochMilli(observedAtMillis))
}

internal fun EvidenceSignal.toEvidence(): Evidence = Evidence(
    jobResponseId, executionId, physicalState, reachedEvidence, completedUnits, inDoubtUnits, unverifiedUnits,
    operatorRequired, Instant.ofEpochMilli(receivedAtMillis),
)

/** design §8.2: a table that does not even parse is read like a rejected one (the core keeps the last valid table). */
internal fun PolicyReadResult.toRead(): PolicyRead = when (kind) {
    "MISSING" -> PolicyRead.Missing
    "FOUND" -> json?.let {
        try {
            PolicyRead.Found(strictJson.readTree(it))
        } catch (e: Exception) {
            PolicyRead.Unreadable("not JSON: ${e.message}")
        }
    } ?: PolicyRead.Unreadable("FOUND without a table")
    "UNREADABLE" -> PolicyRead.Unreadable(reason ?: "unreadable")
    else -> PolicyRead.Unreadable("unknown read kind $kind")
}

internal fun DecideRequest.validate() {
    require(operatorId.isNotBlank()) { "operatorId is required" }
    require(proposalId.isNotBlank() && sawCandidatesVersion.isNotBlank()) { "proposalId and sawCandidatesVersion are required" }
    require(reason == null || RejectReason.entries.any { it.name == reason }) { "unknown reject reason $reason" }
}

internal fun DecideRequest.toEvent(): EpisodeEvent.ApprovalDecided = EpisodeEvent.ApprovalDecided(
    proposalId, sawCandidatesVersion, approve, Approver(operatorId),
    reason?.let { r -> RejectReason.entries.firstOrNull { it.name == r } }, note,
)

internal fun ConfirmRequest.validate() {
    require(operatorId.isNotBlank()) { "operatorId is required" }
    when (kind) {
        "PRECONDITION", "OUTCOME" -> require(candidateId.isNotBlank() && proposalId.isNotBlank()) { "$kind needs candidateId and proposalId" }
        "UNKNOWN" -> {
            require(subject.isNotEmpty()) { "UNKNOWN needs a subject" }
            require(UnknownWhat.entries.any { it.name == what }) { "unknown what $what" }
        }
        else -> throw IllegalArgumentException("unknown confirm kind $kind")
    }
}

internal fun ConfirmRequest.toEvent(): EpisodeEvent = when (kind) {
    "PRECONDITION" -> EpisodeEvent.ConfirmedPrecondition(candidateId, proposalId, holds, Approver(operatorId))
    "OUTCOME" -> EpisodeEvent.ConfirmedOutcome(candidateId, proposalId, holds, Approver(operatorId))
    else -> EpisodeEvent.ConfirmedUnknown(LinkedHashMap(subject), UnknownWhat.valueOf(what), Approver(operatorId), note)
}

internal fun TakeoverRequest.validate() = require(operatorId.isNotBlank()) { "operatorId is required" }

internal fun CloseRequest.validate() {
    require(operatorId.isNotBlank()) { "operatorId is required" }
    require(outcome.isNotBlank()) { "outcome is required" }
}

/** A dispatch outcome the core understands; anything else is "did it go out? unknown" (design §11). */
internal fun DispatchOutcome.toResult(): DispatchResult = when {
    kind == "ANSWER" && answerJson != null -> DispatchResult.Answer(answerJson)
    kind == "PERSON_TASK" -> DispatchResult.PersonTaskIssued
    else -> DispatchResult.Uncertain("unrecognised dispatch outcome $kind")
}

internal fun triState(value: String?): TriState = TriState.entries.firstOrNull { it.name == value } ?: TriState.UNKNOWN

/** The execution intent as the record and the dispatch activity see it. */
internal fun intentJson(intent: DispatchIntent): String = strictJson.createObjectNode().apply {
    set<ObjectNode>("candidate", intent.candidate.toJson())
    put("idempotencyKey", intent.idempotencyKey)
    val approval = intent.approval
    if (approval == null) putNull("approval") else putObject("approval").apply {
        put("by", approval.by.name)
        put("approverId", approval.approver?.id)
        put("assurance", approval.approver?.assurance?.name)
        put("at", approval.at.toString())
    }
    put("targetUnit", intent.targetUnit)
}.toString()

internal fun noticeJson(notice: Notice): String = strictJson.createObjectNode().apply {
    put("kind", notice.kind.name)
    put("phase", notice.phase.name)
    put("reason", notice.reason?.name)
    put("detail", notice.detail)
}.toString()

/** The query view of a state (design §7.3); never changes the state. */
internal fun viewOf(state: EpisodeState): EpisodeView {
    val attempt = state.attempt
    val unknowns = try {
        state.currentUnknowns().map { it.toJson() }
    } catch (e: Exception) {
        emptyList()
    }
    return EpisodeView(
        instanceId = state.instanceId,
        phase = state.phase.name,
        escalationReason = state.escalation?.reason?.name,
        escalationDetail = state.escalation?.detail,
        attempt = attempt?.number,
        proposalId = attempt?.proposalId,
        candidateId = attempt?.candidate?.candidateId,
        candidatesVersion = attempt?.request?.candidatesVersion,
        stateDeadlineMillis = state.stateDeadline?.toEpochMilli(),
        episodeDeadlineMillis = state.episodeDeadline?.toEpochMilli(),
        historyJson = strictJson.createArrayNode().apply { state.history.forEach { add(it.toJson()) } }.toString(),
        unknownsJson = strictJson.createArrayNode().apply { unknowns.forEach { add(it) } }.toString(),
        symptoms = state.symptoms.size,
    )
}
```

- [ ] **Step 7: 액티비티 인터페이스를 먼저 쓴다(시험 몰개가 쓴다)**

`episode-runtime/src/main/kotlin/koshei/episode/runtime/Activities.kt`:

```kotlin
package koshei.episode.runtime

import com.fasterxml.jackson.databind.node.ObjectNode
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import koshei.runtime.RawJson

/**
 * koshei-side activities of the episode workflow (design §8). Names are explicit: they must not collide with the saga's
 * activities on a shared worker, and they are part of the workflow history.
 */
@ActivityInterface
interface EpisodeActivities {
    /** design §8.2: the active policy table as JSON text. Run as a local activity. */
    @ActivityMethod(name = "episodeReadPolicy")
    fun readPolicy(): PolicyReadResult

    /** design §8.3: re-read the source; `TRUE`, `FALSE` or `UNKNOWN`. */
    @ActivityMethod(name = "episodeRevalidate")
    fun revalidate(candidateJson: String): String

    /** design §8.5, §13: one append-only record, idempotent on (instance, seq). */
    @ActivityMethod(name = "episodeRecord")
    fun record(instanceId: String, seq: Long, kind: String, payloadJson: String)

    /** design §5.2 DISPATCH_PENDING: the execution intent; its success or failure decides whether anything is dispatched. */
    @ActivityMethod(name = "episodeRecordIntent")
    fun recordIntent(instanceId: String, seq: Long, intentJson: String)

    /** design §8.4: the approval window (APPROVE_REMEDY) or an operator task (the person-task kinds). */
    @ActivityMethod(name = "episodeDispatch")
    fun dispatch(intentJson: String, approverKind: String?): DispatchOutcome

    /** design §8.5: the operator channel. A failure never blocks a transition. */
    @ActivityMethod(name = "episodeNotify")
    fun sendNotice(instanceId: String, noticeJson: String)
}

/** narrator's activity (contract 0.6 §2): queue `narrator-tq`, name `diagnose`, request and answer as exact JSON text. */
@ActivityInterface
interface NarratorActivities {
    @ActivityMethod(name = "diagnose")
    fun diagnose(request: RawJson): RawJson
}

/**
 * A narrator stand-in for P0 (design §8.1): recommends the first offered candidate other than ESCALATE with a clean,
 * cited answer, or answers NO_GROUNDS when only ESCALATE is offered. Every key of contract 0.6 §4 is written.
 */
class MockNarratorActivities : NarratorActivities {
    override fun diagnose(request: RawJson): RawJson {
        val r = strictJson.readTree(request.text)
        val offered = r.get("candidates").map { it.get("candidateId").asText() }.firstOrNull { it != "ESCALATE" }
        val answer: ObjectNode = strictJson.createObjectNode().apply {
            put("contractVersion", r.get("contractVersion").asText())
            put("episodeId", r.get("episodeId").asText())
            put("attempt", r.get("attempt").asInt())
            put("outcome", if (offered != null) "RECOMMENDED" else "NO_GROUNDS")
            put("candidateId", offered)
            putNull("picked")
            put("sawCandidatesVersion", r.get("candidatesVersion").asText())
            put("rationale", "mock: the first offered candidate [mock-sop, 1]")
            putArray("card")
            putNull("cause")
            putArray("citations").addObject().put("title", "mock-sop").put("section", "1").put("verified", true)
            putArray("unverifiedClaims")
            putArray("uncitedSentences")
            putObject("versions").apply {
                put("modelId", "mock")
                putNull("promptVersion")
                putNull("corpusVersion")
                putNull("searchFingerprint")
                put("narratorCommit", "mock")
            }
            put("elapsedSeconds", 0.0)
        }
        return RawJson(answer.toString())
    }
}
```

- [ ] **Step 8: 통과를 본다**

Run: `./gradlew --no-daemon :episode-runtime:test --tests 'koshei.episode.runtime.ConversionsTest'` 그리고 `./gradlew --no-daemon :episode:test`
Expected: `BUILD SUCCESSFUL` — `ConversionsTest` 5개, `:episode` 291개 그대로.

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts .gitattributes episode/src/main/kotlin/koshei/episode/EpisodeState.kt episode-runtime
git commit -m "feat(episode-runtime): module, wire types and conversions between payloads and the core" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Chunk 2: 워크플로

### Task 3: 워크플로와 시험 환경

이 과제는 Temporal 의 동작(시간 건너뛰기, signalWithStart 의 signal 이 워크플로 메서드보다 먼저 오는 순서, Update 처리기와 본 루프의 맞물림)에 기대므로, 계획의 코드가 그대로 돌지 않을 수 있다. 그럴 때는 **전이 함수와 시험의 기대는 바꾸지 말고** 구동부 코드만 최소로 고친 뒤 무엇을 왜 바꿨는지 보고한다.

**Files:**
- Create: `episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkflow.kt`
- Test: `episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWorkflowTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode-runtime/src/test/kotlin/koshei/episode/runtime/EpisodeWorkflowTest.kt`:

```kotlin
package koshei.episode.runtime

import io.temporal.activity.Activity
import io.temporal.client.ActivityCompletionException
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.client.WorkflowUpdateException
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import koshei.runtime.DataConverterSupport
import koshei.runtime.RawJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class EpisodeWorkflowTest {
    private lateinit var env: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient
    private val acts = FakeEpisodeActivities()
    private val narrator = ProgrammableNarrator()

    @BeforeEach fun up() {
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(DataConverterSupport.clientOptions()).build(),
        )
        env.newWorker(EPISODE_TASK_QUEUE).apply {
            registerWorkflowImplementationTypes(EpisodeWorkflowImpl::class.java)
            registerActivitiesImplementations(acts)
        }
        env.newWorker(NARRATOR_TASK_QUEUE).registerActivitiesImplementations(narrator)
        env.start()
        client = env.workflowClient
    }

    @AfterEach fun down() = env.close()

    private fun open(id: String = "ep:test", symptom: SymptomSignal = searchSignal("search-1")): EpisodeWorkflow {
        val stub = client.newWorkflowStub(
            EpisodeWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(EPISODE_TASK_QUEUE).setWorkflowId(id).build(),
        )
        WorkflowStub.fromTyped(stub).signalWithStart("symptom", arrayOf(symptom), arrayOf(EpisodeStart(Picasso.manifest)))
        return stub
    }

    private fun searchSignal(id: String) = SymptomSignal("search:$id", "SEARCH", Picasso.search(id), 0)

    /** Advances test time a second at a time until [cond] holds on the view (the workflow runs between steps). */
    private fun EpisodeWorkflow.until(seconds: Int = 120, cond: (EpisodeView) -> Boolean): EpisodeView {
        repeat(seconds) {
            val v = view()
            if (cond(v)) return v
            env.sleep(Duration.ofSeconds(1))
        }
        fail("condition not reached; last view ${view()}")
    }

    private fun EpisodeWorkflow.result(): EpisodeResult = WorkflowStub.fromTyped(this).getResult(EpisodeResult::class.java)

    @Test fun `an episode runs through the mock narrator to a person's DONE`() {
        val ep = open()
        val waiting = ep.until { it.phase == "AWAITING_EVIDENCE" }
        assertEquals(1, waiting.attempt)
        val reply = ep.confirm(ConfirmRequest("OUTCOME", waiting.candidateId!!, waiting.proposalId!!, true, operatorId = "op-1"))
        assertEquals("ACCEPTED", reply)
        assertEquals(EpisodeResult("RESOLVED", null), ep.result())
        val intent = acts.calls.indexOfFirst { it.startsWith("intent:") }
        val dispatch = acts.calls.indexOfFirst { it.startsWith("dispatch:") }
        assertTrue(intent in 0 until dispatch, "the intent is recorded before anything is dispatched: ${acts.calls}")
        assertEquals("dispatch:AGENT", acts.calls[dispatch])
    }

    @Test fun `policy v1 waits for a person - a stale decide is refused as a value, the right one approves`() {
        acts.policyJson = TestPolicies.table(autoApprove = false)
        val ep = open()
        val waiting = ep.until { it.phase == "AWAITING_APPROVAL" }
        assertEquals("REFUSED_STALE", ep.decide(DecideRequest("${waiting.instanceId}#0", waiting.candidatesVersion!!, true, "op-1")))
        assertEquals("ACCEPTED", ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1")))
        ep.until { it.phase == "AWAITING_EVIDENCE" }
        assertTrue("dispatch:PERSON" in acts.calls)
    }

    @Test fun `the approval deadline escalates and a late decide is refused`() {
        acts.policyJson = TestPolicies.table(autoApprove = false)
        val ep = open()
        val waiting = ep.until { it.phase == "AWAITING_APPROVAL" }
        env.sleep(Duration.ofMinutes(6))
        val escalated = ep.until { it.phase == "ESCALATED" }
        assertEquals("APPROVAL_EXPIRED", escalated.escalationReason)
        assertEquals("REFUSED_NOT_AWAITING", ep.decide(DecideRequest(waiting.proposalId!!, waiting.candidatesVersion!!, true, "op-1")))
    }

    @Test fun `a malformed Update is rejected by the validator, not by the episode`() {
        val ep = open()
        ep.until { it.phase == "CORRELATING" }
        assertThrows<WorkflowUpdateException> { ep.decide(DecideRequest(operatorId = "")) }
        assertThrows<WorkflowUpdateException> { ep.confirm(ConfirmRequest("MAYBE", operatorId = "op-1")) }
        assertEquals("CORRELATING", ep.view().phase)
    }

    @Test fun `takeover cancels a diagnosis in flight`() {
        narrator.block = true
        val ep = open()
        ep.until { it.phase == "DIAGNOSING" }
        assertEquals("ACCEPTED", ep.takeover(TakeoverRequest("op-1")))
        assertEquals("TAKEN_OVER", ep.view().escalationReason)
        repeat(50) { if (!narrator.cancelled) Thread.sleep(200) }
        assertTrue(narrator.cancelled, "the diagnose activity saw the cancellation")
    }

    @Test fun `agentOff escalates and close ends the workflow`() {
        val ep = open()
        ep.until { it.phase == "CORRELATING" }
        ep.agentOff()
        assertEquals("AGENT_LAYER_OFF", ep.until { it.phase == "ESCALATED" }.escalationReason)
        assertEquals("ACCEPTED", ep.close(CloseRequest("op-1", "handled on site")))
        assertEquals(EpisodeResult("CLOSED", "AGENT_LAYER_OFF"), ep.result())
    }

    @Test fun `narrator's answer crosses as raw text, so a duplicated key is caught`() {
        narrator.answer = { r ->
            val mock = MockNarratorActivities().diagnose(r).text
            RawJson(mock.replaceFirst("\"outcome\":", "\"outcome\":\"NO_GROUNDS\",\"outcome\":"))
        }
        val ep = open()
        assertEquals("DIAGNOSIS_FAILED", ep.until { it.phase == "ESCALATED" }.escalationReason)
    }

    @Test fun `no policy at the start escalates at once and retention closes it`() {
        acts.policyJson = null
        val ep = open()
        assertEquals("POLICY_MISSING", ep.until { it.phase == "ESCALATED" }.escalationReason)
        env.sleep(Duration.ofHours(25))
        assertEquals(EpisodeResult("CLOSED", "POLICY_MISSING"), ep.result())
    }
}

/** The mock narrator unless told otherwise: [answer] replaces it, [block] heartbeats until cancelled. */
internal class ProgrammableNarrator : NarratorActivities {
    @Volatile var answer: ((RawJson) -> RawJson)? = null
    @Volatile var block = false
    @Volatile var cancelled = false

    override fun diagnose(request: RawJson): RawJson {
        if (block) {
            val ctx = Activity.getExecutionContext()
            try {
                while (true) {
                    ctx.heartbeat(null)
                    Thread.sleep(100)
                }
            } catch (e: ActivityCompletionException) {
                cancelled = true
                throw e
            }
        }
        return answer?.invoke(request) ?: MockNarratorActivities().diagnose(request)
    }
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew --no-daemon :episode-runtime:test --tests 'koshei.episode.runtime.EpisodeWorkflowTest'`
Expected: 컴파일 실패 — `Unresolved reference 'EpisodeWorkflow'`, `'EpisodeWorkflowImpl'`.

- [ ] **Step 3: 워크플로를 쓴다**

`episode-runtime/src/main/kotlin/koshei/episode/runtime/EpisodeWorkflow.kt`:

```kotlin
package koshei.episode.runtime

import io.temporal.activity.ActivityCancellationType
import io.temporal.activity.ActivityOptions
import io.temporal.activity.LocalActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.failure.TimeoutFailure
import io.temporal.workflow.Async
import io.temporal.workflow.CancellationScope
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.UpdateValidatorMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import koshei.episode.Command
import koshei.episode.DiagnosisBudget
import koshei.episode.DispatchResult
import koshei.episode.EpisodeEvent
import koshei.episode.EpisodeState
import koshei.episode.Phase
import koshei.episode.PolicyRead
import koshei.episode.Reply
import koshei.episode.Step
import koshei.episode.startEpisode
import koshei.episode.transition
import koshei.runtime.RawJson
import java.time.Duration
import java.time.Instant

/** One episode (design §5, §7). Started only by signalWithStart with its first `symptom` (design §7.1). */
@WorkflowInterface
interface EpisodeWorkflow {
    @WorkflowMethod
    fun run(start: EpisodeStart): EpisodeResult

    @SignalMethod
    fun symptom(signal: SymptomSignal)

    @SignalMethod
    fun evidence(signal: EvidenceSignal)

    @SignalMethod
    fun agentOff()

    @UpdateMethod
    fun decide(request: DecideRequest): String

    @UpdateValidatorMethod(updateName = "decide")
    fun validateDecide(request: DecideRequest)

    @UpdateMethod
    fun confirm(request: ConfirmRequest): String

    @UpdateValidatorMethod(updateName = "confirm")
    fun validateConfirm(request: ConfirmRequest)

    @UpdateMethod
    fun takeover(request: TakeoverRequest): String

    @UpdateValidatorMethod(updateName = "takeover")
    fun validateTakeover(request: TakeoverRequest)

    @UpdateMethod
    fun close(request: CloseRequest): String

    @UpdateValidatorMethod(updateName = "close")
    fun validateClose(request: CloseRequest)

    @QueryMethod
    fun view(): EpisodeView
}

/**
 * The imperative shell around the pure core (design §6). It decides nothing: every event goes through [transition] and
 * every command it returns is executed without blocking — activities and timers report back as events in [inbox].
 * Update handlers apply their event at once and return the core's reply; the core has no blocking call, so a handler
 * and the main loop never interleave inside one transition.
 */
class EpisodeWorkflowImpl : EpisodeWorkflow {
    private var state: EpisodeState? = null
    /** Symptoms that arrived before the episode opened; the first opens it (signalWithStart may deliver it first). */
    private val early = mutableListOf<SymptomSignal>()
    /** Events waiting for the main loop, each with whether it is a decision point that wants a fresh policy read. */
    private val inbox = ArrayDeque<Pair<EpisodeEvent, Boolean>>()
    private var diagnosis: CancellationScope? = null

    private val local: EpisodeActivities = Workflow.newLocalActivityStub(
        EpisodeActivities::class.java,
        LocalActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build(),
    )

    override fun run(start: EpisodeStart): EpisodeResult {
        Workflow.await { early.isNotEmpty() }
        val first = early.removeAt(0)
        apply(startEpisode(EpisodeEvent.Detected(instanceId(), start.manifestJson, first.toSymptom()), readPolicy(), now()))
        early.forEach { enqueue(EpisodeEvent.SymptomJoined(it.toSymptom()), decision = false) }
        early.clear()
        while (true) {
            val current = state ?: break
            if (current.phase.terminal) break
            Workflow.await { inbox.isNotEmpty() || state?.phase?.terminal == true }
            if (state?.phase?.terminal == true) break
            val (event, decision) = inbox.removeFirst()
            val phase = state?.phase
            val read = if (decision && phase != null && phase != Phase.ESCALATED && !phase.terminal) readPolicy() else null
            val latest = state ?: break
            if (latest.phase.terminal) break
            apply(transition(latest, event, read, now()))
        }
        val end = state
        return EpisodeResult(end?.phase?.name ?: "CLOSED", end?.escalation?.reason?.name)
    }

    override fun symptom(signal: SymptomSignal) {
        if (state == null) early += signal else enqueue(EpisodeEvent.SymptomJoined(signal.toSymptom()), decision = false)
    }

    override fun evidence(signal: EvidenceSignal) = enqueue(EpisodeEvent.EvidenceArrived(signal.toEvidence()), decision = false)

    override fun agentOff() = enqueue(EpisodeEvent.AgentOff, decision = false)

    override fun validateDecide(request: DecideRequest) = request.validate()
    override fun decide(request: DecideRequest): String = update(request.toEvent())

    override fun validateConfirm(request: ConfirmRequest) = request.validate()
    override fun confirm(request: ConfirmRequest): String = update(request.toEvent())

    override fun validateTakeover(request: TakeoverRequest) = request.validate()
    override fun takeover(request: TakeoverRequest): String = update(EpisodeEvent.TakenOver(koshei.episode.Approver(request.operatorId)))

    override fun validateClose(request: CloseRequest) = request.validate()
    override fun close(request: CloseRequest): String =
        update(EpisodeEvent.Closed(koshei.episode.Approver(request.operatorId), request.outcome))

    override fun view(): EpisodeView = state?.let { viewOf(it) } ?: EpisodeView(instanceId = instanceId(), phase = "OPENING")

    /** An Update applies its event now and answers with the core's reply (design §7.2: refusals are values). */
    private fun update(event: EpisodeEvent): String {
        Workflow.await { state != null }
        val current = state ?: return Reply.REFUSED_CLOSED.name
        val step = transition(current, event, null, now())
        apply(step)
        return (step.reply ?: Reply.ACCEPTED).name
    }

    private fun enqueue(event: EpisodeEvent, decision: Boolean) {
        inbox.addLast(event to decision)
    }

    private fun apply(step: Step) {
        state = step.state
        step.commands.forEach(::execute)
    }

    private fun execute(command: Command) {
        val id = instanceId()
        when (command) {
            is Command.Diagnose -> {
                val narrator = narrator(command.budget)
                val request = RawJson(command.request.toJson().toString())
                val scope = Workflow.newCancellationScope(Runnable {
                    Async.function { narrator.diagnose(request) }.handle { answer, failure ->
                        enqueue(
                            if (failure == null) EpisodeEvent.DiagnosisReturned(answer.text)
                            else EpisodeEvent.DiagnosisFailed(expired(failure), failure.message),
                            decision = true,
                        )
                    }
                })
                diagnosis = scope
                scope.run()
            }
            Command.CancelDiagnosis -> diagnosis?.cancel()
            is Command.Revalidate -> Async.function { activities(command.timeoutMs).revalidate(command.candidate.toJson().toString()) }
                .handle { result, failure -> enqueue(EpisodeEvent.Revalidated(triState(if (failure == null) result else null), command.token), decision = true) }
            is Command.SetTimer -> {
                val delay = maxOf(1L, command.at.toEpochMilli() - Workflow.currentTimeMillis())
                Workflow.newTimer(Duration.ofMillis(delay)).thenApply { enqueue(EpisodeEvent.DeadlineExpired(command.which, command.token), decision = true) }
            }
            is Command.Notify -> Async.procedure { activities(30_000).sendNotice(id, noticeJson(command.notice)) }.handle { _, _ -> }
            is Command.Record -> Async.procedure {
                activities(30_000).record(id, command.seq, command.entry.kind.name, command.entry.payload.toString())
            }.handle { _, _ -> }
            is Command.RecordIntent -> Async.procedure { activities(command.timeoutMs).recordIntent(id, command.seq, intentJson(command.intent)) }
                .handle { _, failure ->
                    enqueue(if (failure == null) EpisodeEvent.Recorded(command.seq) else EpisodeEvent.RecordFailed(command.seq, failure.message), decision = true)
                }
            is Command.Dispatch -> Async.function { activities(command.timeoutMs).dispatch(intentJson(command.intent), command.approverKind) }
                .handle { outcome, failure ->
                    val result = if (failure == null) outcome.toResult() else DispatchResult.Uncertain(failure.message)
                    enqueue(EpisodeEvent.DispatchReturned(result, command.token), decision = true)
                }
        }
    }

    private fun readPolicy(): PolicyRead = try {
        local.readPolicy().toRead()
    } catch (e: ActivityFailure) {
        PolicyRead.Unreadable("readPolicy failed: ${e.message}")
    }

    private fun activities(timeoutMs: Long): EpisodeActivities = Workflow.newActivityStub(
        EpisodeActivities::class.java,
        ActivityOptions.newBuilder()
            .setScheduleToCloseTimeout(Duration.ofMillis(timeoutMs))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build(),
    )

    /** narrator's activity with the policy's diagnosis budget (design §8.1, contract 0.6 §2). */
    private fun narrator(budget: DiagnosisBudget): NarratorActivities = Workflow.newActivityStub(
        NarratorActivities::class.java,
        ActivityOptions.newBuilder()
            .setTaskQueue(NARRATOR_TASK_QUEUE)
            .setScheduleToCloseTimeout(Duration.ofMillis(budget.scheduleToCloseMs))
            .setStartToCloseTimeout(Duration.ofMillis(budget.startToCloseMs))
            .setHeartbeatTimeout(Duration.ofMillis(budget.heartbeatMs))
            .setCancellationType(ActivityCancellationType.TRY_CANCEL)
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(budget.maxAttempts).build())
            .build(),
    )

    private fun expired(failure: RuntimeException): Boolean = (failure as? ActivityFailure)?.cause is TimeoutFailure

    private fun instanceId(): String = "${Workflow.getInfo().workflowId}/${Workflow.getInfo().runId}"

    private fun now(): Instant = Instant.ofEpochMilli(Workflow.currentTimeMillis())
}
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew --no-daemon :episode-runtime:test`
Expected: `BUILD SUCCESSFUL` — `ConversionsTest` 5, `EpisodeWorkflowTest` 8.

Temporal 이 예상과 다르게 움직이면(예: 시간 건너뛰기 중 하트비트 액티비티의 취소가 늦게 닿음, Kotlin 람다 → `Functions.Func` 변환, `handle` 의 반환 형) 구동부만 고친다. 시험의 기대(단계 · 사유 · 답)는 바꾸지 않는다. 고친 것을 모두 보고한다.

- [ ] **Step 5: 전체를 본다**

Run: `./gradlew --no-daemon :episode:test :episode-runtime:test` 그리고 `./gradlew --no-daemon :runtime:test --tests 'koshei.runtime.RawJsonConverterTest'`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add episode-runtime/src
git commit -m "feat(episode-runtime): the episode workflow drives the pure core; Mock narrator end to end" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
