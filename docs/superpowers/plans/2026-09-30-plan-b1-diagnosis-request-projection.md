# 계획 B1 — 진단 요청 투영 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** picasso 내보내기 줄에서 narrator 진단 요청(계약 0.6)을 결정적으로 만드는 순수 Kotlin 모듈 `:episode` 의 첫 조각을 세우고,
narrator 가 기다리는 요청 고정 예제 넷을 그 코드로 생성한다.

**Architecture:** 설계 문서 `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md` §9.0 · §9.1 · §9.2 · §9.3 과 narrator
진단 계약 0.6 §3 을 구현한다. 새 모듈 `:episode` 는 Temporal · Spring · JDBC 를 모른다(Jackson 트리만 쓴다). 스냅샷 줄은 가공 없이
`ObjectNode` 로 들고 다니고, 확인 불가 도출 → 후보 투영 → 후보 판 계산 → 요청 조립이 각각 한 파일의 순수 함수다. 고정 예제는
시험이 생성하고, 같은 시험이 이후 표류를 막는다.

**Tech Stack:** Kotlin 2.2 / JDK 21, Jackson databind 2.17 (`JsonNode` 트리), JUnit 5 / kotlin-test

**범위 밖 (다음 계획):** 전이 함수·정책 표(B2), Temporal 구동부·Mock 진단·제어면(B3). 병합 규칙(에피소드에 어떤 줄이 들어가는지)도
B1 이 정하지 않는다 — B1 은 "이미 모인 줄"을 받는다.

**작업 위치:** worktree `C:\Users\Eisen\Desktop\Labs\[projects] koshchei\koshei-episode-core`, 브랜치 `feat/episode-core`
(`design/episode-outer-loop` 에서 갈라짐). 원래 체크아웃(`koshei/`)은 다른 세션이 쓰고 있으니 **건드리지 않는다.**

**커밋 규칙:** 모든 커밋 메시지 끝에 빈 줄 하나와 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

## 확정한 v1 규칙 (이 계획이 정하는 것)

설계와 계약이 열어 둔 곳을 v1 에서 이렇게 정한다. picasso 의 답(설계 §19)이 오면 바뀔 수 있다.

**확인 불가(`unknowns`) — 명시적으로 안전한 값이 아니면 확인 불가로 본다.** 사건 줄마다 `observation` 을 본다.

| 조건 | `what` |
|---|---|
| `observation` 이 없거나 `null` | `OBSERVATION_ABSENT` |
| `linkBroken` 이 `false` 가 아님 (참 · `null` · 없음) | `LINK_BROKEN` |
| `lateEvents` 가 빈 목록이 아님 | `LATE_EVENTS` |
| `progressObservable` 이 `true` 가 아님 (`null` = 아직 못 봄, `false` = 볼 수 없음) | `PROGRESS_UNOBSERVED` |
| `progressStalled` 가 `false` 가 아님 | `PROGRESS_STALLED` |

항목: `{ subject: { robotId, executionId, unitId }, what, since: 사건 줄의 at, source: "picasso" }`. `since` 는 사건 줄의 `at` 이다 — picasso
사건 줄에 관측 자체의 시각 칸이 따로 없기 때문이다(설계 §9.1 의 "관측 시각"을 v1 에서 이렇게 읽는다). `OBSERVATION_ABSENT` 와
`PROGRESS_STALLED` 를 포함한 `what` 목록 전체가 koshei 의 v1 제안이다(계약 §9 가 koshei 와 picasso 의 열린 항목으로 둔 것). `OUTCOME` 은 열거에 두지만
B1 에서는 호출자가 넘긴 에피소드 수준 확인 불가로만 들어온다(B2 가 `NO_PROPOSAL` 때 쓴다).

**후보 투영.**

| 입력 | 후보 |
|---|---|
| 탐색 `outcome = FOUND`, `steps` 가 비지 않고 모든 단계에 `skillType` 이 있음 (하나라도 없으면 후보 없음 — 일부만 적힌 조치 열은 picasso 가 승인할 수 없다) | `APPROVE_REMEDY` — 재료 (기체, 주문, 조치 열 `+`), `ref { robotId, jobOrderId, searchId }`, `sawSkillTypes` |
| 탐색 `outcome = SOURCE_MISSING`, `alternatives` 가 목록 | 대체 위치마다 `CHOOSE_SOURCE` — 재료 (주문, 자재, 대체 위치), `ref { jobOrderId, material, source, searchId }`. `alternatives` 가 `null` 이면 셀에 못 물은 것이라 후보 없음, `[]` 면 자리가 없다는 답이라 후보 없음 |
| 탐색 `NONE` · `WITHHELD` · 그 밖 | 없음 |
| 사건 `unresolved = true` 이고 `resolution` 이 `null` (운영자 판단 대기) | `OPERATOR_DECISION` 둘 — `CONFIRM_DONE`, `REWORK`. 재료 (실행, 단위, 판단), `ref { executionId, unitId, decision }` |
| 늘 | `ESCALATE` (식별자 `ESCALATE`, `ref` 는 `null`) |

- 확인 불가가 하나라도 있으면(사건에서 나온 것이든 호출자가 넘긴 것이든) 실행 계열 — `APPROVE_REMEDY`, `CHOOSE_SOURCE`,
  `OPERATOR_DECISION/REWORK` — 을 뺀다. `CONFIRM_DONE` 과 `ESCALATE` 는 남는다(설계 §9.1).
- 식별자: `<kind>:<재료…>`, 재료 안의 `%` 는 `%25`, `:` 는 `%3A` 로 바꾼다. 같은 식별자는 한 번만(먼저 나온 것). 결과는 식별자 순.
- `sawSkillTypes` 칸은 `APPROVE_REMEDY` 에만 있고 다른 종류에서는 **키 자체가 없다**(`null` 이 아님). 계약 §3 예제와 "있으면 글자의
  목록"을 따른 의도된 선택이고, 키 집합이 `candidatesVersion` 해시에 들어가므로 바꾸면 판이 바뀐다.

**후보 판.** `"sha256:" + hex(sha256(JCS({ projectionVersion, candidates })))`, 후보는 식별자 순, 16진 소문자 64자.
`projectionVersion` 은 `"1"`. 정규화는 RFC 8785 의 부분집합 — 객체 · 배열 · 글자 · `null` · 불(bool)만 받는다. 후보에 수(number)는
없으므로 수를 만나면 예외로 멈춘다(모르는 것을 대충 정규화하지 않는다).

**요청.** 키 순서 `contractVersion("0.6")`, `episodeId`, `attempt`, `snapshot{ manifest, incidents, searches }`, `candidates`,
`candidatesVersion`, `unknowns`, `history`. 스냅샷 줄은 picasso 줄 그대로(칸 순서 포함).

---

## 파일 구조

| 파일 | 할 일 | 책임 |
|---|---|---|
| `settings.gradle.kts` | 수정 | 모듈 `episode` 포함 |
| `episode/build.gradle.kts` | 생성 | 순수 Kotlin 모듈 (jackson 만), 고정 예제 갱신 속성 전달 |
| `episode/src/main/kotlin/koshei/episode/Json.kt` | 생성 | 공유 `ObjectMapper`, `JsonNode.text(key)` |
| `episode/src/main/kotlin/koshei/episode/Jcs.kt` | 생성 | RFC 8785 부분집합 정규화 + sha256 |
| `episode/src/main/kotlin/koshei/episode/Snapshot.kt` | 생성 | 가공 없는 스냅샷 + 계약의 읽기 규칙 검사 |
| `episode/src/main/kotlin/koshei/episode/Unknowns.kt` | 생성 | 확인 불가 도출 (v1 규칙) |
| `episode/src/main/kotlin/koshei/episode/Candidates.kt` | 생성 | 후보 모양 · 식별자 · 투영 |
| `episode/src/main/kotlin/koshei/episode/CandidatesVersion.kt` | 생성 | 후보 판 |
| `episode/src/main/kotlin/koshei/episode/DiagnosisRequests.kt` | 생성 | 요청 조립 |
| `episode/src/test/resources/picasso/run-1/*` | 생성 | picasso 인계본 run-1 사본 (읽기 전용 원본에서 복사) |
| `episode/src/test/kotlin/koshei/episode/PicassoRun1.kt` | 생성 | 시험용 줄 읽개 |
| `episode/src/test/kotlin/koshei/episode/*Test.kt` | 생성 | 파일마다 시험 하나 |
| `episode/src/test/resources/contract/diagnosis/requests/*.json` | 생성 | **narrator 에 넘길 요청 고정 예제 넷** |
| `episode/src/test/resources/contract/diagnosis/README.md` | 생성 | 예제의 주인·생성법·재료 |

---

## Chunk 1: 모듈, 정규화, 스냅샷, 확인 불가

### Task 1: 모듈과 JCS 정규화

**Files:**
- Modify: `settings.gradle.kts`
- Create: `episode/build.gradle.kts`, `episode/src/main/kotlin/koshei/episode/Json.kt`, `episode/src/main/kotlin/koshei/episode/Jcs.kt`
- Test: `episode/src/test/kotlin/koshei/episode/JcsTest.kt`

- [ ] **Step 1: 모듈을 만든다**

`settings.gradle.kts` 의 `include(...)` 목록 끝에 `"episode"` 를 더한다(`"delegation"` 뒤).

`episode/build.gradle.kts`:

```kotlin
plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
tasks.test {
    useJUnitPlatform()
    // `-Dkoshei.updateFixtures=true` regenerates the committed diagnosis-request fixtures (see DiagnosisRequestFixturesTest).
    systemProperty("koshei.updateFixtures", System.getProperty("koshei.updateFixtures") ?: "false")
}
// Pure core of the episode outer loop (design §4.1): no Temporal, no Spring, no JDBC — only Jackson's JSON tree.
dependencies {
    implementation(libs.jackson.kotlin)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}
```

`episode/src/main/kotlin/koshei/episode/Json.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** One mapper for the module. Plain ObjectMapper: the core works on JSON trees, never on data-class binding. */
internal val mapper: ObjectMapper = ObjectMapper()

/** The field as a string, or null when absent, JSON null, or not a string. */
internal fun JsonNode.text(key: String): String? = get(key)?.takeIf { it.isTextual }?.textValue()
```

- [ ] **Step 2: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/JcsTest.kt`:

```kotlin
package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JcsTest {
    @Test fun `objects are key-sorted, compact, and nested values keep their order`() {
        val node = mapper.readTree("""{"b":"2","a":["x",null,true],"c":{"z":"é","y":"line\nbreak"}}""")
        assertEquals("""{"a":["x",null,true],"b":"2","c":{"y":"line\nbreak","z":"é"}}""", Jcs.canonical(node))
    }

    @Test fun `control characters use the short escapes or lowercase u-escapes`() {
        val node = mapper.readTree("\"a\\t\\u0001\\\"\\\\\"")
        assertEquals("\"a\\t\\u0001\\\"\\\\\"", Jcs.canonical(node))
    }

    @Test fun `numbers are outside the supported subset`() {
        assertFailsWith<IllegalArgumentException> { Jcs.canonical(mapper.readTree("""{"n":1}""")) }
    }

    @Test fun `sha256 is lowercase hex of the UTF-8 bytes`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Jcs.sha256Hex("abc"))
    }
}
```

- [ ] **Step 3: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.JcsTest'`
Expected: 컴파일 실패 — `Unresolved reference 'Jcs'`

- [ ] **Step 4: 구현한다**

`episode/src/main/kotlin/koshei/episode/Jcs.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import java.security.MessageDigest

/**
 * RFC 8785 (JCS) canonical JSON for the subset koshei hashes: objects (keys sorted by UTF-16 code units, which is
 * Kotlin's String order), arrays, strings, null and booleans. Numbers are rejected rather than approximated —
 * candidate lists carry none, and a hash over a guessed number format would silently disagree with any other
 * implementation (design §9.3).
 */
object Jcs {
    fun canonical(node: JsonNode): String = StringBuilder().also { write(node, it) }.toString()

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun write(n: JsonNode, sb: StringBuilder) {
        when {
            n.isNull -> sb.append("null")
            n.isBoolean -> sb.append(if (n.booleanValue()) "true" else "false")
            n.isTextual -> writeString(n.textValue(), sb)
            n.isArray -> {
                sb.append('[')
                n.forEachIndexed { i, e -> if (i > 0) sb.append(','); write(e, sb) }
                sb.append(']')
            }
            n.isObject -> {
                sb.append('{')
                n.fieldNames().asSequence().sorted().forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    writeString(k, sb); sb.append(':'); write(n.get(k), sb)
                }
                sb.append('}')
            }
            else -> throw IllegalArgumentException("JCS subset: ${n.nodeType} is not supported (numbers are out of scope)")
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }
}
```

- [ ] **Step 5: 시험이 통과하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.JcsTest'`
Expected: `BUILD SUCCESSFUL` (4 tests)

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts episode/build.gradle.kts episode/src/main/kotlin/koshei/episode/Json.kt episode/src/main/kotlin/koshei/episode/Jcs.kt episode/src/test/kotlin/koshei/episode/JcsTest.kt
git commit -m "feat(episode): add the pure :episode module with RFC 8785 canonical JSON"
```

### Task 2: 가공 없는 스냅샷

**Files:**
- Create: `episode/src/test/resources/picasso/run-1/manifest.json`, `incidents.jsonl`, `remedy-searches.jsonl` (복사)
- Create: `episode/src/test/kotlin/koshei/episode/PicassoRun1.kt`
- Create: `episode/src/main/kotlin/koshei/episode/Snapshot.kt`
- Test: `episode/src/test/kotlin/koshei/episode/SnapshotTest.kt`

- [ ] **Step 1: picasso 인계본 run-1 을 복사한다 (원본은 읽기만)**

```bash
SRC="/c/Users/Eisen/Desktop/Labs/[projects] picasso/handoff/narrator/run-1"
mkdir -p episode/src/test/resources/picasso/run-1
cp "$SRC/manifest.json" "$SRC/incidents.jsonl" "$SRC/remedy-searches.jsonl" episode/src/test/resources/picasso/run-1/
```

⛔ 같은 폴더의 `ground-truth.jsonl` 은 채점용 정답표다. **열지도, 복사하지도 않는다.**

- [ ] **Step 2: 시험용 줄 읽개를 만든다**

`episode/src/test/kotlin/koshei/episode/PicassoRun1.kt`:

```kotlin
package koshei.episode

/** Verbatim lines of the picasso narrator handoff run-1 (copied into test resources; the picasso repo is read-only). */
object PicassoRun1 {
    private fun res(name: String): String =
        requireNotNull(javaClass.getResource("/picasso/run-1/$name")) { "missing test resource $name" }.readText(Charsets.UTF_8)

    val manifest: String get() = res("manifest.json")
    private val incidents: List<String> get() = res("incidents.jsonl").lines().filter { it.isNotBlank() }
    private val searches: List<String> get() = res("remedy-searches.jsonl").lines().filter { it.isNotBlank() }

    fun incident(id: String): String = incidents.single { mapper.readTree(it).text("incidentId") == id }
    fun search(id: String): String = searches.single { mapper.readTree(it).text("searchId") == id }

    fun snapshot(incidentIds: List<String> = emptyList(), searchIds: List<String> = emptyList()): Snapshot =
        Snapshot.parse(manifest, incidentIds.map(::incident), searchIds.map(::search))
}
```

- [ ] **Step 3: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/SnapshotTest.kt`:

```kotlin
package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SnapshotTest {
    private val manifest = PicassoRun1.manifest

    @Test fun `lines stay verbatim, field order included`() {
        val line = PicassoRun1.incident("incident-6")
        val s = Snapshot.parse(manifest, listOf(line), emptyList())
        val out = s.toJson().get("incidents").get(0)
        val original = mapper.readTree(line)
        assertEquals(original, out)
        assertEquals(original.fieldNames().asSequence().toList(), out.fieldNames().asSequence().toList())
    }

    @Test fun `manifest keeps every field and schemaVersion stays the string 5`() {
        val m = PicassoRun1.snapshot(searchIds = listOf("search-1")).toJson().get("manifest")
        assertEquals(mapper.readTree(manifest), m)
        assertTrue(m.get("schemaVersion").isTextual)
    }

    @Test fun `a numeric schemaVersion is rejected`() {
        val numeric = manifest.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":5")
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(numeric, emptyList(), listOf(PicassoRun1.search("search-1"))) }
    }

    @Test fun `at least one line is required`() {
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(manifest, emptyList(), emptyList()) }
    }

    @Test fun `an incident line needs its digest and a search line its searchId`() {
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(manifest, listOf("""{"incidentId":"x"}"""), emptyList()) }
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(manifest, emptyList(), listOf("""{"outcome":"FOUND"}""")) }
    }

    @Test fun `a line that is not an object is rejected`() {
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(manifest, emptyList(), listOf("[1,2]")) }
    }
}
```

- [ ] **Step 4: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.SnapshotTest'`
Expected: 컴파일 실패 — `Unresolved reference 'Snapshot'`

- [ ] **Step 5: 구현한다**

`episode/src/main/kotlin/koshei/episode/Snapshot.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The picasso export lines an episode diagnoses, kept VERBATIM (design §9.0): the manifest with every field picasso
 * wrote, and the incident / remedy-search lines with the episode-opening line FIRST in its list (narrator puts the
 * first of each list into its query). The checks mirror the diagnosis contract's request-reading rules (contract 0.6
 * §3), so koshei never builds a request narrator would reject as a non-retryable contract violation.
 */
class Snapshot(val manifest: ObjectNode, val incidents: List<ObjectNode>, val searches: List<ObjectNode>) {
    // "Verbatim" means tree-equal with field order kept, not byte-equal: a float in a future picasso line would be
    // re-rendered by Jackson. run-1 has none, and the canonical hash (Jcs) refuses numbers outright.
    init {
        val sv = manifest.get("schemaVersion")
        require(sv != null && sv.isTextual && sv.textValue() == SCHEMA_VERSION) {
            "manifest.schemaVersion must be the string \"$SCHEMA_VERSION\", was $sv"
        }
        require(incidents.isNotEmpty() || searches.isNotEmpty()) { "a snapshot needs at least one incident or search line" }
        incidents.forEach { require(!it.text("digest").isNullOrBlank()) { "incident line without digest: ${it.get("incidentId")}" } }
        searches.forEach { require(!it.text("searchId").isNullOrBlank()) { "search line without searchId" } }
    }

    fun toJson(): ObjectNode = mapper.createObjectNode().apply {
        set<JsonNode>("manifest", manifest.deepCopy())
        putArray("incidents").apply { incidents.forEach { add(it.deepCopy()) } }
        putArray("searches").apply { searches.forEach { add(it.deepCopy()) } }
    }

    companion object {
        /** The only picasso export schema narrator reads (contract 0.6 §3). */
        const val SCHEMA_VERSION = "5"

        fun parse(manifestJson: String, incidentLines: List<String>, searchLines: List<String>): Snapshot =
            Snapshot(obj(manifestJson), incidentLines.map(::obj), searchLines.map(::obj))

        private fun obj(json: String): ObjectNode =
            mapper.readTree(json) as? ObjectNode ?: throw IllegalArgumentException("not a JSON object: ${json.take(60)}")
    }
}
```

- [ ] **Step 6: 시험이 통과하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.SnapshotTest'`
Expected: `BUILD SUCCESSFUL` (6 tests)

- [ ] **Step 7: Commit**

```bash
git add episode/src/test/resources/picasso episode/src/test/kotlin/koshei/episode/PicassoRun1.kt episode/src/main/kotlin/koshei/episode/Snapshot.kt episode/src/test/kotlin/koshei/episode/SnapshotTest.kt
git commit -m "feat(episode): verbatim diagnosis snapshot with the contract's reading rules"
```

### Task 3: 확인 불가 도출

**Files:**
- Create: `episode/src/main/kotlin/koshei/episode/Unknowns.kt`
- Test: `episode/src/test/kotlin/koshei/episode/UnknownsTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/UnknownsTest.kt`:

```kotlin
package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnknownsTest {
    private fun incidentWith(observation: String?): String {
        val base = mapper.readTree(PicassoRun1.incident("incident-1")) as com.fasterxml.jackson.databind.node.ObjectNode
        if (observation == null) base.remove("observation") else base.set<com.fasterxml.jackson.databind.JsonNode>("observation", mapper.readTree(observation))
        return mapper.writeValueAsString(base)
    }

    private fun whats(incident: String) =
        deriveUnknowns(Snapshot.parse(PicassoRun1.manifest, listOf(incident), emptyList())).map { it.what }

    @Test fun `a broken link is an unknown with the incident's subject and time`() {
        val u = deriveUnknowns(PicassoRun1.snapshot(incidentIds = listOf("incident-6")))
        assertEquals(1, u.size)
        assertEquals(UnknownWhat.LINK_BROKEN, u[0].what)
        assertEquals(mapOf("robotId" to "hum-07", "executionId" to "exec-8", "unitId" to "RACK-204.S06"), u[0].subject)
        assertEquals("2026-09-06T00:02:31Z", u[0].since)
        assertEquals("picasso", u[0].source)
    }

    @Test fun `a fully observed healthy incident has no unknowns`() {
        assertTrue(deriveUnknowns(PicassoRun1.snapshot(incidentIds = listOf("incident-1"))).isEmpty())
    }

    @Test fun `progressObservable null is an unknown, not a safe value`() {
        assertEquals(listOf(UnknownWhat.PROGRESS_UNOBSERVED), whats(PicassoRun1.incident("incident-5")))
    }

    @Test fun `anything other than an explicit safe value is an unknown`() {
        assertEquals(listOf(UnknownWhat.OBSERVATION_ABSENT), whats(incidentWith(null)))
        assertEquals(listOf(UnknownWhat.OBSERVATION_ABSENT), whats(incidentWith("null")))
        assertEquals(listOf(UnknownWhat.LINK_BROKEN),
            whats(incidentWith("""{"lateEvents":[],"progressObservable":true,"progressStalled":false}""")))
        assertEquals(listOf(UnknownWhat.LATE_EVENTS),
            whats(incidentWith("""{"linkBroken":false,"lateEvents":["e1"],"progressObservable":true,"progressStalled":false}""")))
        assertEquals(listOf(UnknownWhat.PROGRESS_UNOBSERVED),
            whats(incidentWith("""{"linkBroken":false,"lateEvents":[],"progressObservable":false,"progressStalled":false}""")))
        assertEquals(listOf(UnknownWhat.PROGRESS_STALLED),
            whats(incidentWith("""{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":true}""")))
    }

    @Test fun `search lines carry no observation and produce no unknowns`() {
        assertTrue(deriveUnknowns(PicassoRun1.snapshot(searchIds = listOf("search-1", "search-4"))).isEmpty())
    }

    @Test fun `the JSON shape keeps all four fields`() {
        val json = deriveUnknowns(PicassoRun1.snapshot(incidentIds = listOf("incident-6")))[0].toJson()
        assertEquals(listOf("subject", "what", "since", "source"), json.fieldNames().asSequence().toList())
        assertEquals("LINK_BROKEN", json.get("what").textValue())
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.UnknownsTest'`
Expected: 컴파일 실패 — `Unresolved reference 'deriveUnknowns'`

- [ ] **Step 3: 구현한다**

`episode/src/main/kotlin/koshei/episode/Unknowns.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** What is unknown (contract 0.6 §3.3). `OUTCOME` is episode-level (e.g. picasso NO_PROPOSAL) and is passed in by the caller. */
enum class UnknownWhat { OBSERVATION_ABSENT, LINK_BROKEN, LATE_EVENTS, PROGRESS_UNOBSERVED, PROGRESS_STALLED, OUTCOME }

data class Unknown(val subject: Map<String, String?>, val what: UnknownWhat, val since: String?, val source: String) {
    fun toJson(): ObjectNode = mapper.createObjectNode().apply {
        putObject("subject").apply { subject.forEach { (k, v) -> put(k, v) } }
        put("what", what.name)
        put("since", since)
        put("source", source)
    }
}

/**
 * v1 rule (design §9.1, contract 0.6 §3.3): anything other than an explicitly SAFE observation value is an unknown.
 * picasso's three-valued fields are never folded — `progressObservable: null` ("not yet observed") is as unknown as
 * `false`. Fail-safe on purpose until picasso settles the list (design §19): an unknown only removes execution-class
 * candidates, while a missed unknown could double an irreversible action.
 */
fun deriveUnknowns(snapshot: Snapshot): List<Unknown> = snapshot.incidents.flatMap { inc ->
    val subject = linkedMapOf("robotId" to inc.text("robotId"), "executionId" to inc.text("executionId"), "unitId" to inc.text("unitId"))
    fun unknown(what: UnknownWhat) = Unknown(subject, what, inc.text("at"), "picasso")

    val obs = inc.get("observation")
    if (obs == null || obs.isNull) return@flatMap listOf(unknown(UnknownWhat.OBSERVATION_ABSENT))
    buildList {
        if (!obs.isExactly("linkBroken", false)) add(unknown(UnknownWhat.LINK_BROKEN))
        val late = obs.get("lateEvents")
        if (late == null || !late.isArray || late.size() > 0) add(unknown(UnknownWhat.LATE_EVENTS))
        if (!obs.isExactly("progressObservable", true)) add(unknown(UnknownWhat.PROGRESS_UNOBSERVED))
        if (!obs.isExactly("progressStalled", false)) add(unknown(UnknownWhat.PROGRESS_STALLED))
    }
}

private fun JsonNode.isExactly(key: String, value: Boolean): Boolean = get(key)?.let { it.isBoolean && it.booleanValue() == value } ?: false
```

- [ ] **Step 4: 시험이 통과하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.UnknownsTest'`
Expected: `BUILD SUCCESSFUL` (6 tests)

- [ ] **Step 5: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/Unknowns.kt episode/src/test/kotlin/koshei/episode/UnknownsTest.kt
git commit -m "feat(episode): derive unknowns fail-safe from picasso observations"
```

---

## Chunk 2: 후보 투영과 후보 판

### Task 4: 후보와 투영

**Files:**
- Create: `episode/src/main/kotlin/koshei/episode/Candidates.kt`
- Test: `episode/src/test/kotlin/koshei/episode/CandidatesTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/CandidatesTest.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CandidatesTest {
    private fun ids(snapshot: Snapshot, unknowns: List<Unknown> = deriveUnknowns(snapshot)) =
        projectCandidates(snapshot, unknowns).map { it.candidateId }

    private fun edited(line: String, edit: ObjectNode.() -> Unit): String =
        mapper.writeValueAsString((mapper.readTree(line) as ObjectNode).apply(edit))

    @Test fun `a found remedy becomes APPROVE_REMEDY next to ESCALATE`() {
        val s = PicassoRun1.snapshot(searchIds = listOf("search-1"))
        val c = projectCandidates(s, emptyList())
        assertEquals(listOf("APPROVE_REMEDY:hum-02:PATROL-1:pick_place", "ESCALATE"), c.map { it.candidateId })
        assertEquals(mapOf("robotId" to "hum-02", "jobOrderId" to "PATROL-1", "searchId" to "search-1"), c[0].ref)
        assertEquals(listOf("pick_place"), c[0].sawSkillTypes)
    }

    @Test fun `NONE and WITHHELD searches offer nothing but ESCALATE`() {
        assertEquals(listOf("ESCALATE"), ids(PicassoRun1.snapshot(searchIds = listOf("search-2"))))
        assertEquals(listOf("ESCALATE"), ids(PicassoRun1.snapshot(searchIds = listOf("search-3"))))
    }

    @Test fun `a missing source offers each alternative`() {
        assertEquals(listOf("CHOOSE_SOURCE:SEQ-RELOCATE:ENGINE-COVER-B:SEQ-IN-03.BIN-B", "ESCALATE"),
            ids(PicassoRun1.snapshot(searchIds = listOf("search-4"))))
    }

    @Test fun `alternatives null and alternatives empty both offer no source, for different reasons`() {
        val nullAlts = edited(PicassoRun1.search("search-4")) { putNull("alternatives") }
        val noAlts = edited(PicassoRun1.search("search-4")) { putArray("alternatives") }
        assertEquals(listOf("ESCALATE"), ids(Snapshot.parse(PicassoRun1.manifest, emptyList(), listOf(nullAlts))))
        assertEquals(listOf("ESCALATE"), ids(Snapshot.parse(PicassoRun1.manifest, emptyList(), listOf(noAlts))))
    }

    @Test fun `an unresolved incident awaiting a decision offers CONFIRM_DONE and REWORK`() {
        val s = PicassoRun1.snapshot(incidentIds = listOf("incident-6"))
        assertEquals(
            listOf("ESCALATE", "OPERATOR_DECISION:exec-8:RACK-204.S06:CONFIRM_DONE", "OPERATOR_DECISION:exec-8:RACK-204.S06:REWORK"),
            ids(s, unknowns = emptyList()),
        )
    }

    @Test fun `unknowns remove every execution-class candidate but keep CONFIRM_DONE and ESCALATE`() {
        val s = PicassoRun1.snapshot(incidentIds = listOf("incident-6"), searchIds = listOf("search-1", "search-4"))
        assertEquals(listOf("ESCALATE", "OPERATOR_DECISION:exec-8:RACK-204.S06:CONFIRM_DONE"), ids(s))
    }

    @Test fun `an episode-level unknown passed by the caller blocks execution too`() {
        val outcome = Unknown(mapOf("robotId" to "hum-02"), UnknownWhat.OUTCOME, null, "picasso")
        assertEquals(listOf("ESCALATE"), ids(PicassoRun1.snapshot(searchIds = listOf("search-1")), listOf(outcome)))
    }

    @Test fun `a resolved or already-decided incident offers no decision`() {
        assertEquals(listOf("ESCALATE"), ids(PicassoRun1.snapshot(incidentIds = listOf("incident-1")), emptyList()))
        val decided = edited(PicassoRun1.incident("incident-6")) {
            putObject("resolution").put("decision", "REWORK")
        }
        assertEquals(listOf("ESCALATE"), ids(Snapshot.parse(PicassoRun1.manifest, listOf(decided), emptyList()), emptyList()))
    }

    @Test fun `several steps join with plus, and colons or percent signs in values are encoded`() {
        val line = edited(PicassoRun1.search("search-1")) {
            put("jobOrderId", "A:B%C")
            putArray("steps").apply { addObject().put("skillType", "pick_place"); addObject().put("skillType", "navigate_to") }
        }
        assertEquals(listOf("APPROVE_REMEDY:hum-02:A%3AB%25C:pick_place+navigate_to", "ESCALATE"),
            ids(Snapshot.parse(PicassoRun1.manifest, emptyList(), listOf(line)), emptyList()))
    }

    @Test fun `a step without a skillType withholds the remedy instead of offering a partial one`() {
        val line = edited(PicassoRun1.search("search-1")) {
            putArray("steps").apply { addObject().put("skillType", "pick_place"); addObject().put("expectedHold", "HOLD_KIND_EMPTY") }
        }
        assertEquals(listOf("ESCALATE"), ids(Snapshot.parse(PicassoRun1.manifest, emptyList(), listOf(line)), emptyList()))
    }

    @Test fun `the same action seen twice is one candidate, the first line wins`() {
        val again = edited(PicassoRun1.search("search-1")) { put("searchId", "search-9") }
        val s = Snapshot.parse(PicassoRun1.manifest, emptyList(), listOf(PicassoRun1.search("search-1"), again))
        val c = projectCandidates(s, emptyList())
        assertEquals(2, c.size)
        assertEquals("search-1", c[0].ref!!["searchId"])
    }

    @Test fun `JSON shape follows the contract example`() {
        val c = projectCandidates(PicassoRun1.snapshot(searchIds = listOf("search-1", "search-4")), emptyList())
        val remedy = c.single { it.kind == CandidateKind.APPROVE_REMEDY }.toJson()
        assertEquals(listOf("candidateId", "kind", "ref", "sawSkillTypes"), remedy.fieldNames().asSequence().toList())
        val source = c.single { it.kind == CandidateKind.CHOOSE_SOURCE }.toJson()
        assertEquals(listOf("candidateId", "kind", "ref"), source.fieldNames().asSequence().toList())
        val escalate = c.single { it.kind == CandidateKind.ESCALATE }.toJson()
        assertEquals("ESCALATE", escalate.get("candidateId").textValue())
        assertEquals(true, escalate.get("ref").isNull)
        assertNull(escalate.get("sawSkillTypes"))
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.CandidatesTest'`
Expected: 컴파일 실패 — `Unresolved reference 'projectCandidates'`

- [ ] **Step 3: 구현한다**

`episode/src/main/kotlin/koshei/episode/Candidates.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode

enum class CandidateKind { APPROVE_REMEDY, CHOOSE_SOURCE, OPERATOR_DECISION, ESCALATE }

/**
 * One option the diagnosis may point at (design §9.1, contract 0.6 §3.1). The id is derived from CONTENT, never from
 * list position, so the same action on the same target keeps its id across re-diagnoses. picasso's searchId /
 * incidentId repeat across runs of the same seed, so they are never id material — only traced in [ref].
 */
class Candidate private constructor(
    val kind: CandidateKind,
    val ref: Map<String, String>?,
    idParts: List<String>,
    val sawSkillTypes: List<String>?,
) {
    val candidateId: String =
        if (kind == CandidateKind.ESCALATE) ESCALATE_ID else (listOf(kind.name) + idParts.map(::encodePart)).joinToString(":")

    /** Execution class (design §9.1): moves the field, so it is withheld while anything is unknown. */
    val executionClass: Boolean
        get() = when (kind) {
            CandidateKind.APPROVE_REMEDY, CandidateKind.CHOOSE_SOURCE -> true
            CandidateKind.OPERATOR_DECISION -> ref?.get("decision") == REWORK
            CandidateKind.ESCALATE -> false
        }

    fun toJson(): ObjectNode = mapper.createObjectNode().apply {
        put("candidateId", candidateId)
        put("kind", kind.name)
        if (ref == null) putNull("ref") else putObject("ref").apply { ref.forEach { (k, v) -> put(k, v) } }
        if (kind == CandidateKind.APPROVE_REMEDY) putArray("sawSkillTypes").apply { sawSkillTypes.orEmpty().forEach { add(it) } }
    }

    companion object {
        const val ESCALATE_ID = "ESCALATE"
        const val CONFIRM_DONE = "CONFIRM_DONE"
        const val REWORK = "REWORK"

        val ESCALATE = Candidate(CandidateKind.ESCALATE, null, emptyList(), null)

        fun approveRemedy(robotId: String, jobOrderId: String, skills: List<String>, searchId: String) = Candidate(
            CandidateKind.APPROVE_REMEDY,
            linkedMapOf("robotId" to robotId, "jobOrderId" to jobOrderId, "searchId" to searchId),
            listOf(robotId, jobOrderId, skills.joinToString("+")),
            skills,
        )

        fun chooseSource(jobOrderId: String, material: String, source: String, searchId: String) = Candidate(
            CandidateKind.CHOOSE_SOURCE,
            linkedMapOf("jobOrderId" to jobOrderId, "material" to material, "source" to source, "searchId" to searchId),
            listOf(jobOrderId, material, source),
            null,
        )

        fun operatorDecision(executionId: String, unitId: String, decision: String) = Candidate(
            CandidateKind.OPERATOR_DECISION,
            linkedMapOf("executionId" to executionId, "unitId" to unitId, "decision" to decision),
            listOf(executionId, unitId, decision),
            null,
        )

        /** `%` first, then `:`, so the encoding stays injective. */
        private fun encodePart(s: String): String = s.replace("%", "%25").replace(":", "%3A")
    }
}

/**
 * Pure projection of picasso lines into candidates (design §9.1). ESCALATE is always present; with any unknown
 * (from the snapshot or passed by the caller) every execution-class candidate is withheld. Result ordered by id;
 * an id seen twice keeps its first occurrence.
 */
fun projectCandidates(snapshot: Snapshot, unknowns: List<Unknown>): List<Candidate> {
    val byId = LinkedHashMap<String, Candidate>()
    fun add(c: Candidate) { byId.putIfAbsent(c.candidateId, c) }

    add(Candidate.ESCALATE)
    for (s in snapshot.searches) {
        val searchId = requireNotNull(s.text("searchId"))
        when (s.text("outcome")) {
            "FOUND" -> {
                // Every step must name its skill: picasso approves only if its steps ⊆ sawSkillTypes, so a partial list
                // would be a candidate that can never be approved as seen. No complete list, no candidate.
                val steps = s.path("steps")
                val skills = steps.mapNotNull { it.text("skillType") }
                if (skills.isNotEmpty() && skills.size == steps.size())
                    add(Candidate.approveRemedy(s.req("robotId"), s.req("jobOrderId"), skills, searchId))
            }
            "SOURCE_MISSING" -> {
                // alternatives null = the cell was not asked; [] = the cell answered "no place". Neither offers a source.
                val alts = s.get("alternatives")
                if (alts != null && alts.isArray) alts.filter { it.isTextual }.forEach {
                    add(Candidate.chooseSource(s.req("jobOrderId"), s.req("material"), it.textValue(), searchId))
                }
            }
            else -> Unit
        }
    }
    for (i in snapshot.incidents) {
        val unresolved = i.get("unresolved")?.let { it.isBoolean && it.booleanValue() } ?: false
        val undecided = i.get("resolution")?.isNull ?: true
        if (unresolved && undecided) {
            add(Candidate.operatorDecision(i.req("executionId"), i.req("unitId"), Candidate.CONFIRM_DONE))
            add(Candidate.operatorDecision(i.req("executionId"), i.req("unitId"), Candidate.REWORK))
        }
    }
    val blocked = unknowns.isNotEmpty()
    return byId.values.filterNot { blocked && it.executionClass }.sortedBy { it.candidateId }
}

private fun ObjectNode.req(key: String): String = requireNotNull(text(key)) { "picasso line without $key: ${get("searchId") ?: get("incidentId")}" }
```

- [ ] **Step 4: 시험이 통과하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.CandidatesTest'`
Expected: `BUILD SUCCESSFUL` (12 tests)

- [ ] **Step 5: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/Candidates.kt episode/src/test/kotlin/koshei/episode/CandidatesTest.kt
git commit -m "feat(episode): project picasso lines into content-addressed candidates"
```

### Task 5: 후보 판

**Files:**
- Create: `episode/src/main/kotlin/koshei/episode/CandidatesVersion.kt`
- Test: `episode/src/test/kotlin/koshei/episode/CandidatesVersionTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/CandidatesVersionTest.kt`:

```kotlin
package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CandidatesVersionTest {
    private val two = projectCandidates(PicassoRun1.snapshot(searchIds = listOf("search-1")), emptyList())

    @Test fun `format is sha256 then 64 lowercase hex`() {
        assertTrue(Regex("sha256:[0-9a-f]{64}").matches(candidatesVersion(two)))
    }

    @Test fun `it hashes the canonical projectionVersion plus id-sorted candidates`() {
        val canonical = """{"candidates":[{"candidateId":"ESCALATE","kind":"ESCALATE","ref":null}],"projectionVersion":"1"}"""
        assertEquals("sha256:" + Jcs.sha256Hex(canonical), candidatesVersion(listOf(Candidate.ESCALATE)))
    }

    @Test fun `input order does not matter`() {
        assertEquals(candidatesVersion(two), candidatesVersion(two.reversed()))
    }

    @Test fun `a new projection version changes the version`() {
        assertNotEquals(candidatesVersion(two), candidatesVersion(two, projectionVersion = "2"))
    }

    @Test fun `different candidate sets differ`() {
        val other = projectCandidates(PicassoRun1.snapshot(searchIds = listOf("search-4")), emptyList())
        assertNotEquals(candidatesVersion(two), candidatesVersion(other))
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.CandidatesVersionTest'`
Expected: 컴파일 실패 — `Unresolved reference 'candidatesVersion'`

- [ ] **Step 3: 구현한다**

`episode/src/main/kotlin/koshei/episode/CandidatesVersion.kt`:

```kotlin
package koshei.episode

/** Bump when the projection rules change, so an approval taken on the old set reads as stale (design §9.3). */
const val PROJECTION_VERSION = "1"

/**
 * "sha256:" + lowercase hex of sha256(JCS({ projectionVersion, candidates sorted by id })) — design §9.3,
 * contract 0.6 §3.2. Only koshei computes it; narrator echoes it back as `sawCandidatesVersion`. The snapshot is
 * deliberately NOT hashed: whether the world moved is REVALIDATING's question; this only answers "is the set of
 * options the approver saw the same?".
 */
fun candidatesVersion(candidates: List<Candidate>, projectionVersion: String = PROJECTION_VERSION): String {
    val root = mapper.createObjectNode().apply {
        put("projectionVersion", projectionVersion)
        putArray("candidates").apply { candidates.sortedBy { it.candidateId }.forEach { add(it.toJson()) } }
    }
    return "sha256:" + Jcs.sha256Hex(Jcs.canonical(root))
}
```

- [ ] **Step 4: 시험이 통과하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.CandidatesVersionTest'`
Expected: `BUILD SUCCESSFUL` (5 tests)

- [ ] **Step 5: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/CandidatesVersion.kt episode/src/test/kotlin/koshei/episode/CandidatesVersionTest.kt
git commit -m "feat(episode): candidatesVersion over the canonical candidate set"
```

---

## Chunk 3: 요청 조립과 고정 예제

### Task 6: 진단 요청

**Files:**
- Create: `episode/src/main/kotlin/koshei/episode/DiagnosisRequests.kt`
- Test: `episode/src/test/kotlin/koshei/episode/DiagnosisRequestsTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/DiagnosisRequestsTest.kt`:

```kotlin
package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiagnosisRequestsTest {
    private val snapshot = PicassoRun1.snapshot(incidentIds = listOf("incident-1"), searchIds = listOf("search-1"))

    @Test fun `top-level keys follow the contract order`() {
        val r = diagnosisRequest("ep:t", 1, snapshot)
        assertEquals(
            listOf("contractVersion", "episodeId", "attempt", "snapshot", "candidates", "candidatesVersion", "unknowns", "history"),
            r.fieldNames().asSequence().toList(),
        )
        assertEquals("0.6", r.get("contractVersion").textValue())
        assertEquals("ep:t", r.get("episodeId").textValue())
        assertEquals(1, r.get("attempt").intValue())
    }

    @Test fun `snapshot is carried verbatim`() {
        assertEquals(snapshot.toJson(), diagnosisRequest("ep:t", 1, snapshot).get("snapshot"))
    }

    @Test fun `candidates and candidatesVersion agree`() {
        val r = diagnosisRequest("ep:t", 1, snapshot)
        val ids = r.get("candidates").map { it.get("candidateId").textValue() }
        assertEquals(listOf("APPROVE_REMEDY:hum-02:PATROL-1:pick_place", "ESCALATE"), ids)
        assertEquals(candidatesVersion(projectCandidates(snapshot, emptyList())), r.get("candidatesVersion").textValue())
    }

    @Test fun `the contract's reading rules hold on every request`() {
        val r = diagnosisRequest("ep:t", 1, PicassoRun1.snapshot(incidentIds = listOf("incident-6"), searchIds = listOf("search-4")))
        val ids = r.get("candidates").map { it.get("candidateId").textValue() }
        assertTrue(ids.isNotEmpty() && "ESCALATE" in ids)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(Regex("sha256:[0-9a-f]{64}").matches(r.get("candidatesVersion").textValue()))
        r.get("unknowns").forEach { assertEquals(listOf("subject", "what", "since", "source"), it.fieldNames().asSequence().toList()) }
        assertTrue(r.get("history").isArray && r.get("history").size() == 0)
    }

    @Test fun `episode-level unknowns are reported and block execution`() {
        val outcome = Unknown(mapOf("robotId" to "hum-02"), UnknownWhat.OUTCOME, null, "picasso")
        val r = diagnosisRequest("ep:t", 2, snapshot, episodeUnknowns = listOf(outcome))
        assertEquals(listOf("ESCALATE"), r.get("candidates").map { it.get("candidateId").textValue() })
        assertEquals("OUTCOME", r.get("unknowns").get(0).get("what").textValue())
    }

    @Test fun `a blank episode id or an attempt below 1 is rejected`() {
        assertFailsWith<IllegalArgumentException> { diagnosisRequest(" ", 1, snapshot) }
        assertFailsWith<IllegalArgumentException> { diagnosisRequest("ep:t", 0, snapshot) }
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.DiagnosisRequestsTest'`
Expected: 컴파일 실패 — `Unresolved reference 'diagnosisRequest'`

- [ ] **Step 3: 구현한다**

`episode/src/main/kotlin/koshei/episode/DiagnosisRequests.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** The narrator diagnosis contract version this module builds (narrator repo, docs/superpowers/specs/2026-09-27-진단-계약-초안.md). */
const val DIAGNOSIS_CONTRACT_VERSION = "0.6"

/**
 * Builds the `diagnose` activity request (contract 0.6 §3): the verbatim snapshot, the candidates projected from it,
 * their version, the unknowns (derived from the snapshot plus [episodeUnknowns], e.g. OUTCOME after a picasso
 * NO_PROPOSAL), and the attempt history. [history] entries are passed through as given; their eight-field shape
 * (contract §3.4) is the transition function's job (plan B2).
 */
fun diagnosisRequest(
    episodeId: String,
    attempt: Int,
    snapshot: Snapshot,
    episodeUnknowns: List<Unknown> = emptyList(),
    history: List<ObjectNode> = emptyList(),
): ObjectNode {
    require(episodeId.isNotBlank()) { "episodeId must not be blank" }
    require(attempt >= 1) { "attempt starts at 1, was $attempt" }
    val unknowns = deriveUnknowns(snapshot) + episodeUnknowns
    val candidates = projectCandidates(snapshot, unknowns)
    return mapper.createObjectNode().apply {
        put("contractVersion", DIAGNOSIS_CONTRACT_VERSION)
        put("episodeId", episodeId)
        put("attempt", attempt)
        set<JsonNode>("snapshot", snapshot.toJson())
        putArray("candidates").apply { candidates.forEach { add(it.toJson()) } }
        put("candidatesVersion", candidatesVersion(candidates))
        putArray("unknowns").apply { unknowns.forEach { add(it.toJson()) } }
        putArray("history").apply { history.forEach { add(it.deepCopy()) } }
    }
}
```

- [ ] **Step 4: 시험이 통과하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.DiagnosisRequestsTest'`
Expected: `BUILD SUCCESSFUL` (6 tests)

- [ ] **Step 5: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/DiagnosisRequests.kt episode/src/test/kotlin/koshei/episode/DiagnosisRequestsTest.kt
git commit -m "feat(episode): assemble the narrator diagnosis request (contract 0.6)"
```

### Task 7: 요청 고정 예제 넷

**Files:**
- Test: `episode/src/test/kotlin/koshei/episode/DiagnosisRequestFixturesTest.kt`
- Create (생성됨): `episode/src/test/resources/contract/diagnosis/requests/01-recommended.json`, `02-no-grounds.json`,
  `03-out-of-candidates.json`, `04-unknown.json`
- Create: `episode/src/test/resources/contract/diagnosis/README.md`

예제 이름은 narrator 가 쓸 **응답**의 결과로 붙였다. 요청만 보면 01 과 03 은 실행 후보가 있고, 02 는 `ESCALATE` 뿐이고, 04 는
확인 불가라 실행 후보가 빠진다. 03 의 "후보 밖"은 narrator 응답이 목록에 없는 자리를 고르는 예제라는 뜻이다(계약 §8).

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/DiagnosisRequestFixturesTest.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The four diagnosis-request fixtures of the contract (§8), built by the real projection from picasso run-1 lines.
 * koshei owns the requests; narrator writes the matching responses. Regenerate with
 * `./gradlew :episode:test --tests 'koshei.episode.DiagnosisRequestFixturesTest' -Dkoshei.updateFixtures=true`.
 */
class DiagnosisRequestFixturesTest {
    private class Example(val file: String, val incidents: List<String>, val searches: List<String>) {
        val episodeId = "ep:fixture-" + file.removeSuffix(".json")
    }

    private val examples = listOf(
        Example("01-recommended.json", incidents = listOf("incident-1"), searches = listOf("search-1")),
        Example("02-no-grounds.json", incidents = emptyList(), searches = listOf("search-2")),
        Example("03-out-of-candidates.json", incidents = emptyList(), searches = listOf("search-4")),
        Example("04-unknown.json", incidents = listOf("incident-6"), searches = emptyList()),
    )

    private fun request(e: Example): ObjectNode =
        diagnosisRequest(e.episodeId, 1, PicassoRun1.snapshot(e.incidents, e.searches))

    private fun ids(e: Example) = request(e).get("candidates").map { it.get("candidateId").textValue() }
    private fun whats(e: Example) = request(e).get("unknowns").map { it.get("what").textValue() }

    @Test fun `01 recommended offers the found remedy and ESCALATE`() {
        assertEquals(listOf("APPROVE_REMEDY:hum-02:PATROL-1:pick_place", "ESCALATE"), ids(examples[0]))
        assertEquals(emptyList(), whats(examples[0]))
    }

    @Test fun `02 no-grounds offers only ESCALATE`() {
        assertEquals(listOf("ESCALATE"), ids(examples[1]))
    }

    @Test fun `03 out-of-candidates offers the single alternative source`() {
        assertEquals(listOf("CHOOSE_SOURCE:SEQ-RELOCATE:ENGINE-COVER-B:SEQ-IN-03.BIN-B", "ESCALATE"), ids(examples[2]))
    }

    @Test fun `04 unknown keeps only CONFIRM_DONE and ESCALATE and reports the broken link`() {
        assertEquals(listOf("ESCALATE", "OPERATOR_DECISION:exec-8:RACK-204.S06:CONFIRM_DONE"), ids(examples[3]))
        assertEquals(listOf("LINK_BROKEN"), whats(examples[3]))
    }

    @Test fun `every fixture obeys the contract's request-reading rules`() {
        for (e in examples) {
            val r = request(e)
            val m = r.get("snapshot").get("manifest")
            assertTrue(m.get("schemaVersion").isTextual && m.get("schemaVersion").textValue() == "5", e.file)
            assertTrue(r.get("snapshot").get("incidents").size() + r.get("snapshot").get("searches").size() > 0, e.file)
            val cs = r.get("candidates")
            val ids = cs.map { it.get("candidateId").textValue() }
            assertTrue(ids.isNotEmpty() && ids.size == ids.toSet().size && "ESCALATE" in ids, e.file)
            cs.forEach { c ->
                val escalate = c.get("kind").textValue() == "ESCALATE"
                assertEquals(escalate, c.get("candidateId").textValue() == "ESCALATE", e.file)
                assertEquals(escalate, c.get("ref").isNull, e.file)
                c.get("sawSkillTypes")?.let { st -> assertTrue(st.isArray && st.all { it.isTextual }, e.file) }
            }
            assertTrue(Regex("sha256:[0-9a-f]{64}").matches(r.get("candidatesVersion").textValue()), e.file)
            r.get("unknowns").forEach { assertEquals(listOf("subject", "what", "since", "source"), it.fieldNames().asSequence().toList(), e.file) }
            assertTrue(r.get("history").isArray, e.file)
        }
    }

    @Test fun `committed fixtures match the projection`() {
        val dir = File("src/test/resources/contract/diagnosis/requests")
        val update = System.getProperty("koshei.updateFixtures") == "true"
        val lf = DefaultIndenter("  ", "\n")
        val writer = mapper.writer(DefaultPrettyPrinter().withObjectIndenter(lf).withArrayIndenter(lf))
        for (e in examples) {
            val actual = request(e)
            val f = File(dir, e.file)
            if (update) {
                dir.mkdirs()
                f.writeText(writer.writeValueAsString(actual) + "\n", Charsets.UTF_8)
            } else {
                assertTrue(f.isFile, "missing fixture ${f.path}: run with -Dkoshei.updateFixtures=true")
                assertEquals(mapper.readTree(f), actual, "${e.file} no longer matches the projection")
            }
        }
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.DiagnosisRequestFixturesTest'`
Expected: 앞의 다섯은 PASS, `committed fixtures match the projection` 만 FAIL — `missing fixture src\test\resources\contract\diagnosis\requests\01-recommended.json`
(Windows 에서는 경로가 역슬래시로 찍힌다)

- [ ] **Step 3: 예제를 생성한다**

Run: `./gradlew :episode:test --tests 'koshei.episode.DiagnosisRequestFixturesTest' -Dkoshei.updateFixtures=true`
Expected: `BUILD SUCCESSFUL`, 파일 넷 생성

생성된 파일을 눈으로 확인한다: `contractVersion` 이 `"0.6"`, `snapshot.manifest.schemaVersion` 이 글자 `"5"`, 01 의 `incidents[0]` 이
`incident-1` 이고 `searches[0]` 이 `search-1`, 04 의 `unknowns[0].what` 이 `LINK_BROKEN`, 모든 파일의 줄바꿈이 LF.

- [ ] **Step 4: README 를 쓴다**

`episode/src/test/resources/contract/diagnosis/README.md`:

```markdown
# Diagnosis contract fixtures

Fixtures for the narrator diagnosis contract (narrator repo `docs/superpowers/specs/2026-09-27-진단-계약-초안.md`, §8).

- `requests/` — **owned by koshei.** Generated by the real projection (`koshei.episode.diagnosisRequest`) from picasso
  narrator-handoff run-1 lines copied under `src/test/resources/picasso/run-1/`. Do not hand-edit: regenerate with
  `./gradlew :episode:test --tests 'koshei.episode.DiagnosisRequestFixturesTest' -Dkoshei.updateFixtures=true`.
  The same test fails when the projection drifts from the committed files.
- Responses are **owned by narrator** and live in the narrator repo.

| file | picasso run-1 lines (first line opened the episode) | request shape |
|---|---|---|
| `01-recommended.json` | incident-1, search-1 (FOUND) | `APPROVE_REMEDY` + `ESCALATE` |
| `02-no-grounds.json` | search-2 (NONE) | `ESCALATE` only |
| `03-out-of-candidates.json` | search-4 (SOURCE_MISSING, one alternative) | `CHOOSE_SOURCE` + `ESCALATE`; the response picks a place not in the list |
| `04-unknown.json` | incident-6 (linkBroken) | `unknowns: LINK_BROKEN`; only `CONFIRM_DONE` + `ESCALATE` |

Which lines form each example is **hand-chosen** (plan B1 has no merge rule yet). 01 pairs incident-1 — the failed
execution of search-1's own remedy — with search-1, so it re-offers the remedy that just failed; it exercises the
request shape, not a realistic merge.

`sawSkillTypes` exists only on `APPROVE_REMEDY` candidates (the key is absent, not null, elsewhere). The key set is part
of the `candidatesVersion` hash.

`ground-truth.jsonl` from the handoff is a scoring key and is deliberately NOT copied here.
```

- [ ] **Step 5: 줄바꿈을 고정한다**

이 저장소는 `core.autocrlf=true` 로 체크아웃될 수 있고 `.gitattributes` 가 없다. 예제와 복사한 `.jsonl` 이 다음 체크아웃에서 CRLF 로
바뀌지 않게, 저장소 뿌리의 `.gitattributes` (없으면 생성)에 한 줄을 넣는다.

```gitattributes
episode/src/test/resources/** text eol=lf
```

- [ ] **Step 6: 시험이 모두 통과하는지 본다**

Run: `./gradlew :episode:test`
Expected: `BUILD SUCCESSFUL` — JcsTest 4, SnapshotTest 6, UnknownsTest 6, CandidatesTest 12, CandidatesVersionTest 5,
DiagnosisRequestsTest 6, DiagnosisRequestFixturesTest 6.

- [ ] **Step 7: Commit**

```bash
git add .gitattributes episode/src/test/kotlin/koshei/episode/DiagnosisRequestFixturesTest.kt episode/src/test/resources/contract
git commit -m "test(episode): generate the four diagnosis-request fixtures for narrator"
```

- [ ] **Step 8: 인계**

narrator 세션에 보낸다.
- 경로: worktree `koshei-episode-core`, 브랜치 `feat/episode-core`, `episode/src/test/resources/contract/diagnosis/requests/`.
- 소유: **요청은 koshei, 응답은 narrator.** 계약 §8 은 "narrator 가 요청과 응답을 다 내고 koshei 가 복사한다"로 적혀 있어
  2026-09-27 합의("요청은 koshei 투영 함수로, 응답은 narrator 가")와 어긋난다 — §8 을 고쳐 달라고 요청한다.
- v1 규칙: 이 문서 머리의 두 표. `what` 목록 전체(`OBSERVATION_ABSENT`, `LINK_BROKEN`, `LATE_EVENTS`, `PROGRESS_UNOBSERVED`,
  `PROGRESS_STALLED`, `OUTCOME`)를 koshei 의 v1 제안으로, "명시적으로 안전한 값이 아니면 확인 불가" 원칙과 함께.
  `OPERATOR_DECISION` 은 `unresolved=true` 이고 `resolution=null` 인 사건에서만 나온다. 둘 다 picasso 의 답을 기다린다.
- `sawSkillTypes` 는 `APPROVE_REMEDY` 에만 있고 다른 종류에서는 키가 없다(의도, 해시에 들어감).
