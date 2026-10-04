# 계획 B2a — narrator 후속, 정책 표, 기한 계산 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** narrator 가 요청한 두 가지(CHOOSE_SOURCE `ref` 이름 정리, 권고 측정용 요청 22개)를 실제 투영으로 내고, 전이 함수(B2b)가
기대는 정책 표 · 정책 판정 · 기한 계산을 순수 함수로 세운다. B1 검토에서 B2 로 넘긴 두 가지도 닫는다.

**Architecture:** 모두 순수 모듈 `:episode` 안이다(설계 `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md` §5.1, §8.2,
§9.1, §10.1–§10.2). 정책 표는 JSON 트리에서 읽고(YAML 을 트리로 읽는 것은 B3 구동부의 일) 모든 위반을 한꺼번에 돌려준다. 정책
판정은 "없음 · 만료 · 꺼짐 · 거절 시 마지막 유효 판"을 한 함수로 가른다. 기한은 `Instant` 산술 네 개다.

**Tech Stack:** Kotlin 2.2 / JDK 21, Jackson databind 2.17 (트리), `java.time.Instant`, JUnit 5 / kotlin-test

**작업 위치:** worktree `C:\Users\Eisen\Desktop\Labs\[projects] koshchei\koshei-episode-core`, 브랜치 `feat/episode-core`.
원래 체크아웃(`koshei/`)은 다른 세션이 쓰니 건드리지 않는다. 역슬래시가 든 파일은 Write/Edit 로만 쓴다(heredoc 금지).
⛔ picasso 인계본 폴더의 `ground-truth.jsonl` 은 열지도 복사하지도 않는다.

**커밋 규칙:** 모든 커밋 메시지 끝에 빈 줄 하나와 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

**범위 밖:** 상태 · 사건 · 전이 함수 · 진단 응답 파서(B2b), 구동부(B3), `actionCatalog` 항목(계획 D — v1 은 빈 목록만 받는다),
`correlation` 규칙의 뜻(계획 C — v1 은 목록이면 받는다).

---

## 파일 구조

| 파일 | 할 일 | 책임 |
|---|---|---|
| `episode/src/main/kotlin/koshei/episode/Candidates.kt` | 수정 | CHOOSE_SOURCE `ref` 이름, 잘못된 UTF-16 값의 후보 제외 |
| `episode/src/main/kotlin/koshei/episode/DiagnosisRequests.kt` | 수정 | 생성자 `internal` |
| `episode/src/main/kotlin/koshei/episode/Jcs.kt` | 수정 | `isWellFormed` 공개 |
| `episode/src/main/kotlin/koshei/episode/Policy.kt` | 생성 | 정책 표 모양 + 읽기 + 검증 |
| `episode/src/main/kotlin/koshei/episode/PolicyResolution.kt` | 생성 | 없음 · 만료 · 꺼짐 · 마지막 유효 판 |
| `episode/src/main/kotlin/koshei/episode/Deadlines.kt` | 생성 | 대기 기한 계산 |
| `episode/build.gradle.kts` | 수정 | 고정 예제 뿌리 경로 속성 |
| `episode/src/test/resources/picasso/run-2..4/*` | 생성 | picasso 인계본 사본 |
| `episode/src/test/kotlin/koshei/episode/PicassoRun1.kt` | 수정 | 구동 번호를 받는 `PicassoRun` 으로 일반화 |
| `episode/src/test/kotlin/koshei/episode/ContractFixtures.kt` | 생성 | 고정 예제 대조 · 갱신 · 읽기 규칙 검사 공통 |
| `episode/src/test/kotlin/koshei/episode/DiagnosisMeasurementFixturesTest.kt` | 생성 | 측정 요청 22개 |
| `episode/src/test/resources/contract/diagnosis/measurement/R01..R22.json` | 생성 | narrator 권고 측정용 요청 |
| `episode/src/test/kotlin/koshei/episode/Policies.kt` | 생성 | 시험용 유효 정책 표 |
| `episode/src/test/kotlin/koshei/episode/{Policy,PolicyResolution,Deadlines}Test.kt` | 생성 | 시험 |

---

## Chunk 1: narrator 후속 — `ref` 이름과 측정 요청 22개

### Task 1: CHOOSE_SOURCE 의 `ref` — `missingSource` 와 `alternative`

picasso 탐색 줄의 `source` 는 결품이 난 **원래 슬롯**이다. 지금 후보의 `ref.source` 는 **고를 대체 자리**라 같은 이름이 반대 뜻을
가진다(narrator 지적). `ref` 를 `{ jobOrderId, material, missingSource, alternative, searchId }` 로 바꾼다. 식별자 재료(주문 · 자재 ·
대체 자리)는 그대로라 `candidateId` 는 바뀌지 않는다. `ref` 모양이 바뀌므로 요청 예제 03 의 바이트와 `candidatesVersion` 은 바뀐다.
`missingSource` 는 picasso 가 `null` 로 주면 `null` 그대로다.

**Files:**
- Modify: `episode/src/main/kotlin/koshei/episode/Candidates.kt`
- Test: `episode/src/test/kotlin/koshei/episode/CandidatesTest.kt`
- Regenerate: `episode/src/test/resources/contract/diagnosis/requests/03-out-of-candidates.json`
- Modify: `episode/src/test/resources/contract/diagnosis/README.md`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`CandidatesTest` 에 둘을 더한다(`assertTrue` 를 아직 import 하지 않았으면 `import kotlin.test.assertTrue`).

```kotlin
    @Test fun `a missing source's ref names both the missing slot and the alternative`() {
        val c = projectCandidates(PicassoRun1.snapshot(searchIds = listOf("search-4")), emptyList())
            .single { it.kind == CandidateKind.CHOOSE_SOURCE }
        assertEquals(
            mapOf(
                "jobOrderId" to "SEQ-RELOCATE", "material" to "ENGINE-COVER-B", "missingSource" to "SEQ-IN-03.BIN-A",
                "alternative" to "SEQ-IN-03.BIN-B", "searchId" to "search-4",
            ),
            c.ref,
        )
        assertEquals(
            listOf("jobOrderId", "material", "missingSource", "alternative", "searchId"),
            c.toJson().get("ref").fieldNames().asSequence().toList(),
        )
        assertEquals("CHOOSE_SOURCE:SEQ-RELOCATE:ENGINE-COVER-B:SEQ-IN-03.BIN-B", c.candidateId)
    }

    @Test fun `a missing slot picasso left null stays null in the ref`() {
        val line = edited(PicassoRun1.search("search-4")) { putNull("source") }
        val c = projectCandidates(Snapshot.parse(PicassoRun1.manifest, emptyList(), listOf(line)), emptyList())
            .single { it.kind == CandidateKind.CHOOSE_SOURCE }
        assertEquals(null, c.ref!!["missingSource"])
        assertTrue(c.toJson().get("ref").get("missingSource").isNull)
    }
```

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.CandidatesTest'`
Expected: 새 시험 둘이 FAIL — 첫째는 `ref` 에 `source` 키가 있고 `missingSource`/`alternative` 가 없어서, 둘째는 `missingSource` 가 없어서
(`assertEquals(null, …)` 는 통과해도 `get("missingSource")` 가 `null` 이라 `.isNull` 에서 NullPointerException).

- [ ] **Step 3: 구현한다**

`Candidates.kt` 에서:

1. `Candidate` 생성자 매개변수와 속성의 `ref` 형을 `Map<String, String?>?` 로 바꾼다(값에 `null` 을 허용):

```kotlin
class Candidate private constructor(
    val kind: CandidateKind,
    ref: Map<String, String?>?,
    /** Already-encoded id segments (see [encodePart]); joined with `:` after the kind. */
    idParts: List<String>,
    sawSkillTypes: List<String>?,
) {
    val ref: Map<String, String?>? = ref?.let { LinkedHashMap(it) }
```

2. `chooseSource` 를 바꾼다:

```kotlin
        /**
         * [missingSource] is picasso's `source` (the slot that ran out); [alternative] is the place to pick instead.
         * Only the alternative is id material; the missing slot is carried for the operator card.
         */
        fun chooseSource(jobOrderId: String, material: String, missingSource: String?, alternative: String, searchId: String) = Candidate(
            CandidateKind.CHOOSE_SOURCE,
            linkedMapOf(
                "jobOrderId" to jobOrderId, "material" to material, "missingSource" to missingSource,
                "alternative" to alternative, "searchId" to searchId,
            ),
            listOf(jobOrderId, material, alternative).map(::encodePart),
            null,
        )
```

3. `projectCandidates` 의 `SOURCE_MISSING` 갈래에서 호출을 바꾼다:

```kotlin
                    add(Candidate.chooseSource(jobOrderId, material, s.text("source"), it.textValue(), searchId))
```

4. `CandidatesTest` 의 `candidates with the same content are equal, different ones are not` 안에 있는 옛 호출
   `Candidate.chooseSource("j", "m", "s", "id")` 를 `Candidate.chooseSource("j", "m", "s", "a", "id")` 로 바꾼다(매개변수가 다섯이 됐다).

`toJson` 의 `put(k, v)` 는 `v` 가 `null` 이면 JSON `null` 을 쓴다 — 고칠 곳이 없다.

`PROJECTION_VERSION` 은 올리지 않는다. `ref` 모양이 바뀌면 그 후보가 든 집합의 판(해시)은 이미 달라지고, 올리면 CHOOSE_SOURCE 가
없는 예제 01 · 02 · 04 까지 까닭 없이 바뀐다. `projectionVersion` 은 같은 입력에서 다른 후보를 내게 바꿀 때 올린다.

- [ ] **Step 4: 시험을 돌리고 예제 03 을 갱신한다**

Run: `./gradlew :episode:test --tests 'koshei.episode.CandidatesTest'`
Expected: `BUILD SUCCESSFUL`

Run: `./gradlew :episode:test`
Expected: `DiagnosisRequestFixturesTest > committed fixtures match the projection` 만 FAIL — `03-out-of-candidates.json no longer matches`

Run: `./gradlew :episode:test --tests 'koshei.episode.DiagnosisRequestFixturesTest' -Dkoshei.updateFixtures=true`
Expected: FAIL — `fixtures regenerated; rerun without -Dkoshei.updateFixtures` (의도된 실패)

Run: `./gradlew :episode:test`
Expected: `BUILD SUCCESSFUL`

Run: `git diff --stat -- episode/src/test/resources/contract/diagnosis/requests`
Expected: `03-out-of-candidates.json` 한 파일만 바뀜. 01 · 02 · 04 는 그대로.

- [ ] **Step 5: README 의 03 줄을 고친다**

`contract/diagnosis/README.md` 표의 03 행 마지막 칸을 이렇게 바꾼다:

```markdown
| `03-out-of-candidates.json` | search-4 (SOURCE_MISSING, one alternative) | `CHOOSE_SOURCE` (`ref.missingSource` = SEQ-IN-03.BIN-A, `ref.alternative` = SEQ-IN-03.BIN-B) + `ESCALATE`; the response picks a place not in the list |
```

그리고 표 아래 `sawSkillTypes` 문단 뒤에 한 문단을 더한다:

```markdown
`CHOOSE_SOURCE.ref.missingSource` is picasso's `source` (the slot that ran out); `ref.alternative` is the place to pick
instead. picasso's search line uses `source` for the missing slot, so the candidate never reuses that name.
```

- [ ] **Step 6: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/Candidates.kt episode/src/test/kotlin/koshei/episode/CandidatesTest.kt episode/src/test/resources/contract/diagnosis
git commit -m "feat(episode): CHOOSE_SOURCE ref names the missing slot and the alternative"
```

### Task 2: picasso 구동 1–4 시험 자료

**Files:**
- Create: `episode/src/test/resources/picasso/run-2/`, `run-3/`, `run-4/` (각각 `manifest.json`, `incidents.jsonl`, `remedy-searches.jsonl`)
- Modify: `episode/src/test/kotlin/koshei/episode/PicassoRun1.kt`
- Test: `episode/src/test/kotlin/koshei/episode/PicassoRunsTest.kt`

- [ ] **Step 1: 원본에서 복사한다 (원본은 읽기만)**

```bash
SRC="/c/Users/Eisen/Desktop/Labs/[projects] picasso/handoff/narrator"
for r in run-2 run-3 run-4; do
  mkdir -p "episode/src/test/resources/picasso/$r"
  cp "$SRC/$r/manifest.json" "$SRC/$r/incidents.jsonl" "$SRC/$r/remedy-searches.jsonl" "episode/src/test/resources/picasso/$r/"
done
```

`run-3/remedy-searches.jsonl` 은 빈 파일이다(원본도 0바이트). ⛔ `ground-truth.jsonl` 은 복사하지 않는다.

- [ ] **Step 2: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/PicassoRunsTest.kt`:

```kotlin
package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals

class PicassoRunsTest {
    @Test fun `every copied run carries schema 5 and its own runId`() {
        val runIds = (1..4).map { n ->
            val m = mapper.readTree(PicassoRun(n).manifest)
            assertEquals("5", m.text("schemaVersion"))
            m.text("runId")
        }
        assertEquals(4, runIds.toSet().size)
    }

    @Test fun `run-3 has incidents but no searches`() {
        assertEquals("incident-3", mapper.readTree(PicassoRun(3).incident("incident-3")).text("incidentId"))
        assertEquals(0, PicassoRun(3).searchCount)
    }
}
```

- [ ] **Step 3: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.PicassoRunsTest'`
Expected: 컴파일 실패 — `Unresolved reference 'PicassoRun'`

- [ ] **Step 4: 읽개를 일반화한다**

`episode/src/test/kotlin/koshei/episode/PicassoRun1.kt` 전체를 바꾼다(파일 이름은 그대로 둔다 — 기존 시험이 쓰는 `PicassoRun1` 이
여기 있다):

```kotlin
package koshei.episode

/** Verbatim lines of one picasso narrator-handoff run (copied into test resources; the picasso repo is read-only). */
class PicassoRun(private val run: Int) {
    private fun res(name: String): String =
        requireNotNull(javaClass.getResource("/picasso/run-$run/$name")) { "missing test resource run-$run/$name" }
            .readText(Charsets.UTF_8)

    val manifest: String get() = res("manifest.json")
    private val incidents: List<String> get() = res("incidents.jsonl").lines().filter { it.isNotBlank() }
    private val searches: List<String> get() = res("remedy-searches.jsonl").lines().filter { it.isNotBlank() }
    val searchCount: Int get() = searches.size

    fun incident(id: String): String = incidents.single { mapper.readTree(it).text("incidentId") == id }
    fun search(id: String): String = searches.single { mapper.readTree(it).text("searchId") == id }

    fun snapshot(incidentIds: List<String> = emptyList(), searchIds: List<String> = emptyList()): Snapshot =
        Snapshot.parse(manifest, incidentIds.map(::incident), searchIds.map(::search))
}

/** Run 1, which the four contract fixtures and most unit tests use. */
val PicassoRun1 = PicassoRun(1)
```

- [ ] **Step 5: 시험이 통과하는지 본다**

Run: `./gradlew :episode:test`
Expected: `BUILD SUCCESSFUL` (기존 시험 전부 + PicassoRunsTest 2)

- [ ] **Step 6: Commit**

```bash
git add episode/src/test/resources/picasso episode/src/test/kotlin/koshei/episode/PicassoRun1.kt episode/src/test/kotlin/koshei/episode/PicassoRunsTest.kt
git commit -m "test(episode): picasso handoff runs 2-4 as test data"
```

### Task 3: 측정 요청 22개

narrator 권고 측정(계약 §7)의 사례 22개를 **koshei 의 실제 투영**으로 짓는다. 줄 묶음은 narrator 가 골랐고(코퍼스의 합성
절차서에서만 정함, 답은 보지 않음), 각 목록의 첫 줄이 에피소드를 연 줄이다. 파일은 요청 예제 넷과 같은 방식으로 바이트 대조한다.

**Files:**
- Modify: `episode/build.gradle.kts`
- Create: `episode/src/test/kotlin/koshei/episode/ContractFixtures.kt`
- Modify: `episode/src/test/kotlin/koshei/episode/DiagnosisRequestFixturesTest.kt`
- Test: `episode/src/test/kotlin/koshei/episode/DiagnosisMeasurementFixturesTest.kt`
- Create (생성됨): `episode/src/test/resources/contract/diagnosis/measurement/R01.json` … `R22.json`
- Modify: `episode/src/test/resources/contract/diagnosis/README.md`

- [ ] **Step 1: 고정 예제 뿌리 경로를 바꾼다**

`episode/build.gradle.kts` 의 `systemProperty("koshei.fixturesDir", …)` 호출을 아래로 바꾼다(요청 폴더가 아니라 `contract/diagnosis`
뿌리를 가리키게):

```kotlin
    systemProperty(
        "koshei.contractFixturesDir",
        layout.projectDirectory.dir("src/test/resources/contract/diagnosis").asFile.absolutePath,
    )
```

- [ ] **Step 2: 공통 도우미를 만든다**

`episode/src/test/kotlin/koshei/episode/ContractFixtures.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Shared machinery for the committed diagnosis-contract fixtures under `src/test/resources/contract/diagnosis/<sub>/`.
 * Files must equal the serialized JSON byte for byte (UTF-8, LF; CRLF is normalised on the read side only as a
 * defence). With `-Dkoshei.updateFixtures=true` the files are rewritten and the check then FAILS on purpose, so an
 * update run can never pass for a green one.
 */
internal object ContractFixtures {
    private val lf = DefaultIndenter("  ", "\n")
    private val writer = mapper.writer(DefaultPrettyPrinter().withObjectIndenter(lf).withArrayIndenter(lf))

    fun check(sub: String, files: Map<String, ObjectNode>) {
        val root = System.getProperty("koshei.contractFixturesDir")
        assertTrue(!root.isNullOrBlank(), "system property koshei.contractFixturesDir is not set (episode/build.gradle.kts sets it)")
        val dir = File(root, sub)
        val update = System.getProperty("koshei.updateFixtures") == "true"
        for ((name, json) in files) {
            val expected = writer.writeValueAsString(json) + "\n"
            val f = File(dir, name)
            if (update) {
                dir.mkdirs()
                f.writeText(expected, Charsets.UTF_8)
            } else {
                assertTrue(f.isFile, "missing fixture ${f.path}: run with -Dkoshei.updateFixtures=true")
                assertEquals(expected, f.readText(Charsets.UTF_8).replace("\r\n", "\n"), "$sub/$name no longer matches the projection byte for byte")
            }
        }
        if (update) fail("fixtures regenerated; rerun without -Dkoshei.updateFixtures")
    }

    /** The diagnosis contract's request-reading rules (contract 0.6 §3). */
    fun assertReadingRules(r: ObjectNode, label: String) {
        val m = r.get("snapshot").get("manifest")
        assertTrue(m.get("schemaVersion").isTextual && m.get("schemaVersion").textValue() == "5", label)
        assertTrue(r.get("snapshot").get("incidents").size() + r.get("snapshot").get("searches").size() > 0, label)
        val cs = r.get("candidates")
        val ids = cs.map { it.get("candidateId").textValue() }
        assertTrue(ids.isNotEmpty() && ids.size == ids.toSet().size && "ESCALATE" in ids, label)
        cs.forEach { c ->
            val escalate = c.get("kind").textValue() == "ESCALATE"
            assertEquals(escalate, c.get("candidateId").textValue() == "ESCALATE", label)
            assertEquals(escalate, c.get("ref").isNull, label)
            c.get("sawSkillTypes")?.let { st -> assertTrue(st.isArray && st.all { it.isTextual }, label) }
        }
        assertTrue(Regex("sha256:[0-9a-f]{64}").matches(r.get("candidatesVersion").textValue()), label)
        r.get("unknowns").forEach { assertEquals(listOf("subject", "what", "since", "source"), it.fieldNames().asSequence().toList(), label) }
        assertTrue(r.get("history").isArray, label)
    }
}
```

- [ ] **Step 3: 요청 예제 시험이 도우미를 쓰게 한다**

`DiagnosisRequestFixturesTest` 에서:
- `every fixture obeys the contract's request-reading rules` 의 본문을 `for (e in examples) ContractFixtures.assertReadingRules(request(e), e.file)` 로 바꾼다.
- `committed fixtures match the projection` 의 본문을 `ContractFixtures.check("requests", examples.associate { it.file to request(it) })` 로 바꾼다.
- 더 쓰지 않는 import(`DefaultIndenter`, `DefaultPrettyPrinter`, `File`, `fail`, `assertTrue` — 남은 본문이 쓰지 않으면)를 지운다.
  KDoc 의 갱신 설명은 그대로 둔다.
- `build.gradle.kts` 의 `tasks.test` 안 주석을 "regenerates the committed diagnosis-contract fixtures (requests and
  measurement)" 로 고친다.

Run: `./gradlew :episode:test --tests 'koshei.episode.DiagnosisRequestFixturesTest'`
Expected: `BUILD SUCCESSFUL` (6 tests) — 파일은 바뀌지 않는다

- [ ] **Step 4: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/DiagnosisMeasurementFixturesTest.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * narrator's recommendation-measurement requests (contract §7), built by koshei's real projection. The line sets are
 * narrator's choice (from the corpus's synthetic procedures, not from answers); the first line of each list opened the
 * episode. Regenerate with `./gradlew :episode:test --tests 'koshei.episode.DiagnosisMeasurementFixturesTest'
 * -Dkoshei.updateFixtures=true` (that run fails on purpose; rerun without the property).
 */
class DiagnosisMeasurementFixturesTest {
    private class Case(val id: String, val run: Int, val incidents: List<String> = emptyList(), val searches: List<String> = emptyList())

    private val cases = listOf(
        Case("R01", 1, listOf("incident-1"), listOf("search-1")),
        Case("R02", 2, listOf("incident-1"), listOf("search-1")),
        Case("R03", 1, searches = listOf("search-1")),
        Case("R04", 1, searches = listOf("search-2")),
        Case("R05", 1, searches = listOf("search-3")),
        Case("R06", 1, searches = listOf("search-4")),
        Case("R07", 1, listOf("incident-6")),
        Case("R08", 1, listOf("incident-5")),
        Case("R09", 1, listOf("incident-5"), listOf("search-4")),
        Case("R10", 1, searches = listOf("search-2", "search-4")),
        Case("R11", 1, listOf("incident-2")),
        Case("R12", 1, listOf("incident-2"), listOf("search-3")),
        Case("R13", 1, listOf("incident-9")),
        Case("R14", 1, listOf("incident-4")),
        Case("R15", 1, listOf("incident-4", "incident-7", "incident-8", "incident-9")),
        Case("R16", 1, listOf("incident-4", "incident-6", "incident-7", "incident-8", "incident-9")),
        Case("R17", 3, listOf("incident-1", "incident-2", "incident-3")),
        Case("R18", 3, listOf("incident-3")),
        Case("R19", 4, listOf("incident-1"), listOf("search-1")),
        Case("R20", 4, listOf("incident-2"), listOf("search-2")),
        Case("R21", 4, listOf("incident-3")),
        Case("R22", 1, listOf("incident-3")),
    )

    private fun request(id: String): ObjectNode {
        val c = cases.single { it.id == id }
        return diagnosisRequest("ep:measure-${c.id}", 1, PicassoRun(c.run).snapshot(c.incidents, c.searches)).toJson()
    }

    private fun ids(id: String) = request(id).get("candidates").map { it.get("candidateId").textValue() }
    private fun whats(id: String) = request(id).get("unknowns").map { it.get("what").textValue() }

    @Test fun `there are 22 cases with distinct ids`() {
        assertEquals((1..22).map { "R%02d".format(it) }, cases.map { it.id })
    }

    @Test fun `every case obeys the contract's request-reading rules`() {
        cases.forEach { ContractFixtures.assertReadingRules(request(it.id), it.id) }
    }

    @Test fun `R02 repeats R01 on another run - same candidates, same version`() {
        assertEquals(ids("R01"), ids("R02"))
        assertEquals(request("R01").get("candidatesVersion"), request("R02").get("candidatesVersion"))
    }

    @Test fun `R09 - the unobserved incident withholds the alternative source`() {
        assertEquals(listOf("ESCALATE"), ids("R09"))
        assertEquals(listOf("PROGRESS_UNOBSERVED"), whats("R09"))
    }

    @Test fun `R15 - four pending units offer both decisions each`() {
        val ids = ids("R15")
        assertEquals(9, ids.size)
        assertEquals(4, ids.count { it.endsWith(":CONFIRM_DONE") })
        assertEquals(4, ids.count { it.endsWith(":REWORK") })
    }

    @Test fun `R16 - an unknown from a joined line withholds every REWORK`() {
        val ids = ids("R16")
        assertEquals(listOf("LINK_BROKEN"), whats("R16"))
        assertTrue(ids.none { it.endsWith(":REWORK") })
        assertEquals(5, ids.count { it.endsWith(":CONFIRM_DONE") })
    }

    @Test fun `R17 - only the undecided unit offers decisions`() {
        assertEquals(
            listOf("ESCALATE", "OPERATOR_DECISION:exec-2:RACK-204.S02:CONFIRM_DONE", "OPERATOR_DECISION:exec-2:RACK-204.S02:REWORK"),
            ids("R17"),
        )
    }

    @Test fun `committed measurement requests match the projection`() {
        ContractFixtures.check("measurement", cases.associate { "${it.id}.json" to request(it.id) })
    }
}
```

- [ ] **Step 5: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.DiagnosisMeasurementFixturesTest'`
Expected: 앞의 일곱은 PASS, `committed measurement requests match the projection` 만 FAIL —
`missing fixture …measurement…R01.json: run with -Dkoshei.updateFixtures=true`

앞의 일곱 가운데 하나라도 FAIL 이면 멈추고 보고한다 — 그 기대값은 이 계획이 run 1·3 의 실제 줄에서 읽은 것이다.

- [ ] **Step 6: 파일을 생성하고 확인한다**

Run: `./gradlew :episode:test --tests 'koshei.episode.DiagnosisMeasurementFixturesTest' -Dkoshei.updateFixtures=true`
Expected: FAIL — `fixtures regenerated; rerun without -Dkoshei.updateFixtures`, 파일 22개 생성

Run: `./gradlew :episode:test`
Expected: `BUILD SUCCESSFUL`

Run: `git status --short -uall episode/src/test/resources/contract`
Expected: `measurement/` 아래 새 파일 22개만(`??` 22줄). `requests/` 는 바뀌지 않음.

- [ ] **Step 7: README 에 측정 요청을 적는다**

`contract/diagnosis/README.md` 의 `requests/` 항목 뒤에 더한다:

```markdown
- `measurement/` — **owned by koshei**, 22 requests for narrator's recommendation measurement (contract §7), built by
  the same projection from picasso handoff runs 1–4 (`src/test/resources/picasso/run-N/`). The line sets were chosen
  by narrator; the first line of each list opened the episode; `episodeId` is `ep:measure-Rnn`, `attempt` 1. Same
  regeneration switch (`DiagnosisMeasurementFixturesTest`).
```

- [ ] **Step 8: Commit**

```bash
git add episode/build.gradle.kts episode/src/test/kotlin/koshei/episode/ContractFixtures.kt episode/src/test/kotlin/koshei/episode/DiagnosisRequestFixturesTest.kt episode/src/test/kotlin/koshei/episode/DiagnosisMeasurementFixturesTest.kt episode/src/test/resources/contract
git commit -m "test(episode): generate narrator's 22 measurement requests from the real projection"
```

- [ ] **Step 9: 인계 (컨트롤러 몫)**

narrator 세션에 새 03 과 `measurement/` 경로, 커밋을 알린다. 구현자는 하지 않는다.

---

## Chunk 2: B1 에서 넘어온 둘, 정책 표

### Task 4: 요청 생성자 봉인과 잘못된 UTF-16 값

B1 검토가 B2 시작 때 닫으라고 넘긴 둘이다. (1) `DiagnosisRequest` 의 공개 생성자로는 `candidates` 와 맞지 않는 `candidatesVersion` 을
넣을 수 있다. (2) 후보 값에 짝 없는 서로게이트가 있으면 `candidatesVersion` 의 JCS 가 예외를 던져, "투영은 예외를 던지지 않는다"는
약속이 깨진다.

**Files:**
- Modify: `episode/src/main/kotlin/koshei/episode/DiagnosisRequests.kt`, `Jcs.kt`, `Candidates.kt`
- Test: `episode/src/test/kotlin/koshei/episode/JcsTest.kt`, `CandidatesTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`JcsTest` 에 더한다:

```kotlin
    @Test fun `isWellFormed accepts pairs and rejects lone surrogates`() {
        assertEquals(true, Jcs.isWellFormed("a\uD83D\uDE00b"))
        assertEquals(false, Jcs.isWellFormed("a\uD800"))
        assertEquals(false, Jcs.isWellFormed("\uDC00a"))
        assertEquals(false, Jcs.isWellFormed("\uD800\uD800"))
    }
```

`CandidatesTest` 에 더한다(`ObjectNode` 는 이미 import 되어 있다):

```kotlin
    @Test fun `a value with a lone surrogate offers no candidate instead of breaking the version hash`() {
        val line = mapper.readTree(PicassoRun1.search("search-1")) as ObjectNode
        line.put("jobOrderId", "PATROL-\uD800")
        val s = Snapshot(mapper.readTree(PicassoRun1.manifest) as ObjectNode, emptyList(), listOf(line))
        assertEquals(listOf("ESCALATE"), projectCandidates(s, emptyList()).map { it.candidateId })
        assertEquals(listOf("ESCALATE"), diagnosisRequest("ep:t", 1, s).candidates.map { it.candidateId })
    }
```

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.JcsTest' --tests 'koshei.episode.CandidatesTest'`
Expected: 컴파일 실패 — `Unresolved reference 'isWellFormed'`

- [ ] **Step 3: 구현한다**

`Jcs.kt` 의 `object Jcs` 안에 더한다(지금의 짝 없는 서로게이트 검사가 따로 있으면 그것도 이 함수를 쓰게 바꿔 규칙을 한 곳에 둔다):

```kotlin
    /** True when [s] is well-formed UTF-16: every high surrogate is followed by a low one, and no low surrogate stands alone. */
    fun isWellFormed(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return false
            i++
        }
        return true
    }
```

`Candidates.kt` 의 `Candidate` 안에 더한다:

```kotlin
    /** Every string the candidate carries can be hashed (JCS refuses lone surrogates). */
    internal fun wellFormed(): Boolean =
        (ref?.values.orEmpty().filterNotNull() + sawSkillTypes.orEmpty()).all(Jcs::isWellFormed)
```

`projectCandidates` 의 지역 함수 `add` 를 바꾼다:

```kotlin
    // A candidate whose strings cannot be hashed is withheld (fail-safe) rather than breaking candidatesVersion.
    fun add(c: Candidate) { if (c.wellFormed()) byId.putIfAbsent(c.candidateId, c) }
```

`DiagnosisRequests.kt` 의 클래스 선언을 `class DiagnosisRequest internal constructor(` 로 바꾸고, KDoc 끝에 한 줄을 더한다:
`Build it only through [diagnosisRequest], which keeps candidates, their version and the unknowns consistent.`

- [ ] **Step 4: 시험이 통과하는지 본다**

Run: `./gradlew :episode:test`
Expected: `BUILD SUCCESSFUL`, 고정 예제 파일은 바뀌지 않음(`git status --short episode/src/test/resources` 가 비어 있음)

- [ ] **Step 5: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/DiagnosisRequests.kt episode/src/main/kotlin/koshei/episode/Jcs.kt episode/src/main/kotlin/koshei/episode/Candidates.kt episode/src/test/kotlin/koshei/episode/JcsTest.kt episode/src/test/kotlin/koshei/episode/CandidatesTest.kt
git commit -m "fix(episode): seal DiagnosisRequest and withhold unhashable candidates"
```

### Task 5: 정책 표 — 모양, 읽기, 검증

**Files:**
- Create: `episode/src/main/kotlin/koshei/episode/Policy.kt`
- Create: `episode/src/test/kotlin/koshei/episode/Policies.kt`
- Test: `episode/src/test/kotlin/koshei/episode/PolicyTest.kt`

규칙(설계 §10.1–§10.2):
- 모든 위반을 한꺼번에 돌려준다(첫 위반에서 멈추지 않는다).
- **모르는 키는 오류다** — 정책 키의 오타가 기본값으로 조용히 넘어가면 안 된다(R14).
- `autoApprove` 는 `APPROVE_REMEDY` 만 받는다. `CHOOSE_SOURCE` · `OPERATOR_DECISION` 은 사람 과업이라 자동 승인이 없고, `ESCALATE` 는
  승인 대상이 아니다. 규칙의 `requireClean` 은 없으면 `true`.
- `actionCatalog` 는 v1 에서 빈 목록만(계획 D 전). `correlation` 은 목록이면 받는다(계획 C 전). 둘 다 없으면 빈 목록.
- `expiresAt` 은 없거나 `null` 이거나 ISO-8601 시각.
- 기한 12칸과 진단 예산 4칸은 모두 있어야 하고 1 이상의 정수다.
- 교차 규칙: `scheduleToCloseMs ≥ maxAttempts × startToCloseMs` · `heartbeatMs < startToCloseMs` · `scheduleToCloseMs ≤ episodeMs` ·
  `unknownRecheckMs < unknownMs` · `escalatedRetentionMs` 와 `episodeMs` 를 뺀 기한 칸은 `≤ episodeMs`.
- `snapshotMaxBytes` 는 1 이상이고 Temporal 페이로드 경고선 512 KiB 보다 작다.
- 모든 기한 · 예산 칸은 상한 `MAX_DURATION_MS`(365일) 이하 — 터무니없는 값이 `Instant` 산술에서 예외로 터지지 않게 한다.
  `maxAttempts × startToCloseMs` 는 넘침 없이 계산한다(`Math.multiplyExact`, 넘치면 위반).
- `autoApprove.<kind>.skills` 는 허용 목록이다 — `[]` 는 아무 조치 열도 자동 승인하지 않는다.
- UNKNOWN 의 실행 차단은 **칸이 없다** — 정책으로 풀 수 있는 구조 자체가 없다(R8).

- [ ] **Step 1: 시험용 유효 정책 표를 만든다**

`episode/src/test/kotlin/koshei/episode/Policies.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode

/** A policy table that satisfies every rule of design §10.2; tests edit a copy to break one rule at a time. */
internal object Policies {
    private val validJson = """
        {
          "version": "2026-09-30.1",
          "expiresAt": null,
          "agentLayerEnabled": true,
          "autoApprove": { "APPROVE_REMEDY": { "allowed": true, "requireClean": true, "skills": ["pick_place"] } },
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

    fun node(edit: ObjectNode.() -> Unit = {}): ObjectNode = (mapper.readTree(validJson) as ObjectNode).apply(edit)

    fun valid(edit: ObjectNode.() -> Unit = {}): PolicyTable =
        (parsePolicy(node(edit)) as? PolicyParse.Valid)?.policy ?: error("test policy is not valid: ${parsePolicy(node(edit))}")

    fun errors(edit: ObjectNode.() -> Unit): List<String> =
        (parsePolicy(node(edit)) as? PolicyParse.Invalid)?.errors ?: error("expected an invalid policy")

    fun ObjectNode.obj(name: String): ObjectNode = get(name) as ObjectNode
}
```

- [ ] **Step 2: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/PolicyTest.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import koshei.episode.Policies.obj
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyTest {
    private fun List<String>.has(fragment: String) = assertTrue(any { fragment in it }, "no error containing '$fragment' in $this")

    @Test fun `the valid table parses with every field`() {
        val p = Policies.valid()
        assertEquals("2026-09-30.1", p.version)
        assertNull(p.expiresAt)
        assertTrue(p.agentLayerEnabled)
        assertEquals(AutoApproveRule(allowed = true, requireClean = true, skills = listOf("pick_place")), p.autoApprove[CandidateKind.APPROVE_REMEDY])
        assertEquals(3_600_000, p.deadlines.episodeMs)
        assertEquals(86_400_000, p.deadlines.escalatedRetentionMs)
        assertEquals(DiagnosisBudget(1_200_000, 540_000, 30_000, 2), p.diagnosis)
        assertEquals(3, p.maxAttemptsPerEpisode)
        assertEquals(2, p.repeatedRemedyThreshold)
        assertEquals(262_144, p.snapshotMaxBytes)
    }

    @Test fun `requireClean defaults to true and absent lists default to empty`() {
        val p = Policies.valid {
            (obj("autoApprove").get("APPROVE_REMEDY") as ObjectNode).remove("requireClean")
            remove("actionCatalog"); remove("correlation"); remove("expiresAt")
        }
        assertEquals(true, p.autoApprove[CandidateKind.APPROVE_REMEDY]!!.requireClean)
        assertEquals(emptyList(), p.correlation)
        assertNull(p.expiresAt)
    }

    @Test fun `expiresAt reads an ISO instant and rejects anything else`() {
        assertEquals(Instant.parse("2026-12-31T00:00:00Z"), Policies.valid { put("expiresAt", "2026-12-31T00:00:00Z") }.expiresAt)
        Policies.errors { put("expiresAt", "next week") }.has("expiresAt")
    }

    @Test fun `an unknown key is an error, never a silent default`() {
        Policies.errors { put("maxAttemptsPerEpisod", 3) }.has("maxAttemptsPerEpisod: unknown key")
        Policies.errors { obj("deadlines").put("approvalMS", 1) }.has("deadlines.approvalMS: unknown key")
    }

    @Test fun `every violation is reported, not only the first`() {
        val errors = Policies.errors { put("version", ""); put("repeatedRemedyThreshold", 0) }
        errors.has("version")
        errors.has("repeatedRemedyThreshold")
    }

    @Test fun `missing sections and non-integers are errors`() {
        Policies.errors { remove("deadlines") }.has("deadlines: expected an object")
        Policies.errors { obj("deadlines").put("approvalMs", "5m") }.has("deadlines.approvalMs: expected an integer")
        Policies.errors { obj("deadlines").put("recordMs", 0) }.has("deadlines.recordMs: must be >= 1")
        Policies.errors { obj("diagnosis").remove("heartbeatMs") }.has("diagnosis.heartbeatMs")
    }

    @Test fun `the diagnosis budget must cover every attempt`() {
        Policies.errors { obj("diagnosis").put("scheduleToCloseMs", 1_000_000) }.has("maxAttempts")
    }

    @Test fun `the heartbeat must be shorter than one attempt`() {
        Policies.errors { obj("diagnosis").put("heartbeatMs", 540_000) }.has("heartbeatMs")
    }

    @Test fun `the diagnosis budget must fit the episode`() {
        Policies.errors { obj("diagnosis").put("scheduleToCloseMs", 4_000_000) }.has("episodeMs")
    }

    @Test fun `the unknown recheck must come before the unknown limit`() {
        Policies.errors { obj("deadlines").put("unknownRecheckMs", 900_000) }.has("unknownRecheckMs")
    }

    @Test fun `state deadlines fit the episode but escalated retention may outlast it`() {
        Policies.errors { obj("deadlines").put("approvalMs", 4_000_000) }.has("deadlines.approvalMs must be <= deadlines.episodeMs")
        Policies.valid()   // escalatedRetentionMs (86_400_000) > episodeMs (3_600_000) is fine
    }

    @Test fun `the snapshot limit stays under the payload warning line`() {
        Policies.errors { put("snapshotMaxBytes", 524_288) }.has("snapshotMaxBytes")
        Policies.errors { put("snapshotMaxBytes", 0) }.has("snapshotMaxBytes")
    }

    @Test fun `durations have a sane upper bound and the budget product cannot overflow`() {
        Policies.errors { obj("deadlines").put("escalatedRetentionMs", MAX_DURATION_MS + 1) }.has("deadlines.escalatedRetentionMs")
        // maxAttempts × startToCloseMs ≈ 6.8e19 would wrap negative in plain Long multiplication and pass the check.
        Policies.errors {
            obj("deadlines").put("episodeMs", MAX_DURATION_MS)
            obj("diagnosis").put("scheduleToCloseMs", MAX_DURATION_MS)
            obj("diagnosis").put("startToCloseMs", MAX_DURATION_MS)
            obj("diagnosis").put("maxAttempts", Int.MAX_VALUE)
        }.has("maxAttempts")
    }

    @Test fun `only APPROVE_REMEDY can be auto-approved`() {
        Policies.errors { obj("autoApprove").putObject("CHOOSE_SOURCE").put("allowed", true).putArray("skills") }
            .has("autoApprove.CHOOSE_SOURCE")
        Policies.errors { obj("autoApprove").putObject("OPERATOR_DECISION").put("allowed", true).putArray("skills") }
            .has("autoApprove.OPERATOR_DECISION")
        Policies.errors { obj("autoApprove").putObject("FOO").put("allowed", true) }.has("autoApprove.FOO: unknown key")
    }

    @Test fun `actionCatalog entries wait for plan D`() {
        Policies.errors { putArray("actionCatalog").addObject().put("saga", "ot-safe-hold@1.0.0") }.has("actionCatalog")
    }
}
```

- [ ] **Step 3: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.PolicyTest'`
Expected: 컴파일 실패 — `Unresolved reference 'parsePolicy'` (그리고 `PolicyTable`, `AutoApproveRule`, …)

- [ ] **Step 4: 구현한다**

`episode/src/main/kotlin/koshei/episode/Policy.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * One auto-approval rule (design §10.1). Only APPROVE_REMEDY carries one; person tasks have no auto-approval.
 * [skills] is an allowlist: `[]` auto-approves nothing.
 */
data class AutoApproveRule(val allowed: Boolean, val requireClean: Boolean, val skills: List<String>)

/** Wait budgets in milliseconds (design §5.1, §10.1). */
data class DeadlinesMs(
    val correlatingMs: Long,
    val approvalMs: Long,
    val approvalValidityMs: Long,
    val revalidateMs: Long,
    val recordMs: Long,
    val dispatchMs: Long,
    val childMaxMs: Long,
    val evidenceMs: Long,
    val unknownRecheckMs: Long,
    val unknownMs: Long,
    val escalatedRetentionMs: Long,
    val episodeMs: Long,
)

/** The `diagnose` activity budget (design §8.1, narrator contract §2). */
data class DiagnosisBudget(val scheduleToCloseMs: Long, val startToCloseMs: Long, val heartbeatMs: Long, val maxAttempts: Int)

/**
 * A validated episode policy table (design §10). It can only be obtained from [parsePolicy], so holding one means every
 * rule of §10.2 held. There is deliberately no field for UNKNOWN's execution block: no policy can lift it (R8).
 */
class PolicyTable internal constructor(
    val version: String,
    val expiresAt: Instant?,
    val agentLayerEnabled: Boolean,
    val autoApprove: Map<CandidateKind, AutoApproveRule>,
    val deadlines: DeadlinesMs,
    val diagnosis: DiagnosisBudget,
    val maxAttemptsPerEpisode: Int,
    val repeatedRemedyThreshold: Int,
    val snapshotMaxBytes: Long,
    /** Merge rules; their meaning is plan C's. v1 only checks that this is a list. */
    val correlation: List<JsonNode>,
) {
    override fun toString(): String = "PolicyTable($version)"
}

sealed interface PolicyParse {
    data class Valid(val policy: PolicyTable) : PolicyParse
    data class Invalid(val errors: List<String>) : PolicyParse
}

/** Temporal's payload warning line: a larger snapshot would bloat workflow history (design §10.2). */
const val SNAPSHOT_MAX_BYTES_CEILING: Long = 512L * 1024

/** Upper bound for every duration in the table (365 days), so deadline arithmetic on `Instant` can never overflow. */
const val MAX_DURATION_MS: Long = 365L * 24 * 60 * 60 * 1000

private val TOP_KEYS = setOf(
    "version", "expiresAt", "agentLayerEnabled", "autoApprove", "actionCatalog", "deadlines", "diagnosis",
    "maxAttemptsPerEpisode", "repeatedRemedyThreshold", "snapshotMaxBytes", "correlation",
)
private val DEADLINE_KEYS = listOf(
    "correlatingMs", "approvalMs", "approvalValidityMs", "revalidateMs", "recordMs", "dispatchMs", "childMaxMs",
    "evidenceMs", "unknownRecheckMs", "unknownMs", "escalatedRetentionMs", "episodeMs",
)
private val DIAGNOSIS_KEYS = setOf("scheduleToCloseMs", "startToCloseMs", "heartbeatMs", "maxAttempts")
private val RULE_KEYS = setOf("allowed", "requireClean", "skills")

/**
 * Reads AND validates a policy table from a JSON tree (the runtime reads the YAML file into the tree). All violations
 * of design §10.2 are reported together. An unknown key is an error: a typo must never fall back to a default (R14).
 */
fun parsePolicy(root: JsonNode): PolicyParse {
    val r = PolicyReader()
    val top = r.obj(root, "policy", TOP_KEYS) ?: return PolicyParse.Invalid(r.errors)

    val version = r.text(top, "version", "")
    val expiresAt = r.instantOrNull(top, "expiresAt", "")
    val agentLayerEnabled = r.bool(top, "agentLayerEnabled", "")

    val autoApprove = LinkedHashMap<CandidateKind, AutoApproveRule>()
    r.obj(top.get("autoApprove"), "autoApprove", CandidateKind.entries.map { it.name }.toSet())?.let { aa ->
        aa.fieldNames().forEach { k ->
            val kind = CandidateKind.entries.firstOrNull { it.name == k } ?: return@forEach   // reported as unknown key
            val path = "autoApprove.$k"
            if (kind != CandidateKind.APPROVE_REMEDY) {
                r.errors += "$path: $k has no auto-approval (person task or ESCALATE)"
                return@forEach
            }
            r.obj(aa.get(k), path, RULE_KEYS)?.let { rule ->
                val allowed = r.bool(rule, "allowed", path)
                val requireClean = if (rule.has("requireClean")) r.bool(rule, "requireClean", path) else true
                val skills = r.textList(rule, "skills", path)
                if (allowed != null && requireClean != null && skills != null) autoApprove[kind] = AutoApproveRule(allowed, requireClean, skills)
            }
        }
    }

    r.listOrEmpty(top, "actionCatalog", "")?.let {
        if (it.isNotEmpty()) r.errors += "actionCatalog: SAGA_ACTION entries are not supported before plan D"
    }
    val correlation = r.listOrEmpty(top, "correlation", "")

    val deadlines = r.obj(top.get("deadlines"), "deadlines", DEADLINE_KEYS.toSet())?.let { d ->
        val v = DEADLINE_KEYS.map { r.duration(d, it, "deadlines") }
        if (v.any { it == null }) null else v.map { it!! }.let {
            DeadlinesMs(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7], it[8], it[9], it[10], it[11])
        }
    }
    val diagnosis = r.obj(top.get("diagnosis"), "diagnosis", DIAGNOSIS_KEYS)?.let { d ->
        val s = r.duration(d, "scheduleToCloseMs", "diagnosis")
        val st = r.duration(d, "startToCloseMs", "diagnosis")
        val hb = r.duration(d, "heartbeatMs", "diagnosis")
        val ma = r.int(d, "maxAttempts", "diagnosis")
        if (s == null || st == null || hb == null || ma == null) null else DiagnosisBudget(s, st, hb, ma)
    }
    val maxAttemptsPerEpisode = r.int(top, "maxAttemptsPerEpisode", "")
    val repeatedRemedyThreshold = r.int(top, "repeatedRemedyThreshold", "")
    val snapshotMaxBytes = r.long(top, "snapshotMaxBytes", "")?.also {
        if (it >= SNAPSHOT_MAX_BYTES_CEILING) r.errors += "snapshotMaxBytes: must be < $SNAPSHOT_MAX_BYTES_CEILING, was $it"
    }

    if (diagnosis != null) {
        val needed = try { Math.multiplyExact(diagnosis.maxAttempts.toLong(), diagnosis.startToCloseMs) } catch (e: ArithmeticException) { Long.MAX_VALUE }
        if (diagnosis.scheduleToCloseMs < needed)
            r.errors += "diagnosis.scheduleToCloseMs (${diagnosis.scheduleToCloseMs}) must cover maxAttempts × startToCloseMs " +
                "(${diagnosis.maxAttempts} × ${diagnosis.startToCloseMs})"
        if (diagnosis.heartbeatMs >= diagnosis.startToCloseMs)
            r.errors += "diagnosis.heartbeatMs (${diagnosis.heartbeatMs}) must be < startToCloseMs (${diagnosis.startToCloseMs})"
    }
    if (deadlines != null) {
        if (diagnosis != null && diagnosis.scheduleToCloseMs > deadlines.episodeMs)
            r.errors += "diagnosis.scheduleToCloseMs (${diagnosis.scheduleToCloseMs}) must be <= deadlines.episodeMs (${deadlines.episodeMs})"
        if (deadlines.unknownRecheckMs >= deadlines.unknownMs)
            r.errors += "deadlines.unknownRecheckMs (${deadlines.unknownRecheckMs}) must be < deadlines.unknownMs (${deadlines.unknownMs})"
        val bounded = DEADLINE_KEYS.zip(
            listOf(
                deadlines.correlatingMs, deadlines.approvalMs, deadlines.approvalValidityMs, deadlines.revalidateMs,
                deadlines.recordMs, deadlines.dispatchMs, deadlines.childMaxMs, deadlines.evidenceMs,
                deadlines.unknownRecheckMs, deadlines.unknownMs, deadlines.escalatedRetentionMs, deadlines.episodeMs,
            ),
        ).filter { (k, _) -> k != "escalatedRetentionMs" && k != "episodeMs" }
        bounded.filter { (_, v) -> v > deadlines.episodeMs }.forEach { (k, v) ->
            r.errors += "deadlines.$k must be <= deadlines.episodeMs ($v > ${deadlines.episodeMs})"
        }
    }

    if (r.errors.isNotEmpty()) return PolicyParse.Invalid(r.errors.toList())
    return PolicyParse.Valid(
        PolicyTable(
            version!!, expiresAt, agentLayerEnabled!!, autoApprove, deadlines!!, diagnosis!!,
            maxAttemptsPerEpisode!!, repeatedRemedyThreshold!!, snapshotMaxBytes!!, correlation!!,
        ),
    )
}

/** Collects every violation; each read returns null when its field is invalid (and records why). */
private class PolicyReader {
    val errors = mutableListOf<String>()

    private fun at(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    fun obj(node: JsonNode?, path: String, allowed: Set<String>): JsonNode? {
        if (node == null || !node.isObject) { errors += "$path: expected an object"; return null }
        node.fieldNames().forEach { if (it !in allowed) errors += "${if (path == "policy") it else "$path.$it"}: unknown key" }
        return node
    }

    fun text(parent: JsonNode, key: String, path: String): String? {
        val v = parent.get(key)
        if (v == null || !v.isTextual || v.textValue().isBlank()) { errors += "${at(path, key)}: expected a non-blank string"; return null }
        return v.textValue()
    }

    fun bool(parent: JsonNode, key: String, path: String): Boolean? {
        val v = parent.get(key)
        if (v == null || !v.isBoolean) { errors += "${at(path, key)}: expected true or false"; return null }
        return v.booleanValue()
    }

    fun long(parent: JsonNode, key: String, path: String): Long? {
        val v = parent.get(key)
        if (v == null || !v.isIntegralNumber || !v.canConvertToLong()) { errors += "${at(path, key)}: expected an integer"; return null }
        val n = v.longValue()
        if (n < 1) { errors += "${at(path, key)}: must be >= 1, was $n"; return null }
        return n
    }

    /** A duration in milliseconds: an integer from 1 to [MAX_DURATION_MS]. */
    fun duration(parent: JsonNode, key: String, path: String): Long? {
        val n = long(parent, key, path) ?: return null
        if (n > MAX_DURATION_MS) { errors += "${at(path, key)}: must be <= $MAX_DURATION_MS (365 days), was $n"; return null }
        return n
    }

    fun int(parent: JsonNode, key: String, path: String): Int? {
        val n = long(parent, key, path) ?: return null
        if (n > Int.MAX_VALUE) { errors += "${at(path, key)}: too large"; return null }
        return n.toInt()
    }

    fun instantOrNull(parent: JsonNode, key: String, path: String): Instant? {
        val v = parent.get(key)
        if (v == null || v.isNull) return null
        if (!v.isTextual) { errors += "${at(path, key)}: expected an ISO-8601 instant or null"; return null }
        return try { Instant.parse(v.textValue()) } catch (e: DateTimeParseException) {
            errors += "${at(path, key)}: expected an ISO-8601 instant or null, was '${v.textValue()}'"; null
        }
    }

    fun textList(parent: JsonNode, key: String, path: String): List<String>? {
        val v = parent.get(key)
        if (v == null || !v.isArray || v.any { !it.isTextual || it.textValue().isBlank() }) {
            errors += "${at(path, key)}: expected a list of non-blank strings"; return null
        }
        return v.map { it.textValue() }
    }

    fun listOrEmpty(parent: JsonNode, key: String, path: String): List<JsonNode>? {
        val v = parent.get(key) ?: return emptyList()
        if (!v.isArray) { errors += "${at(path, key)}: expected a list"; return null }
        return v.toList()
    }
}
```

- [ ] **Step 5: 시험이 통과하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.PolicyTest'`
Expected: `BUILD SUCCESSFUL` (15 tests)

- [ ] **Step 6: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/Policy.kt episode/src/test/kotlin/koshei/episode/Policies.kt episode/src/test/kotlin/koshei/episode/PolicyTest.kt
git commit -m "feat(episode): parse and validate the episode policy table"
```

---

## Chunk 3: 정책 판정과 기한

### Task 6: 정책 판정

`readPolicy` 가 읽어 온 것(있음 · 없음)과 이력에 남은 마지막 유효 판으로 "지금 쓸 정책"을 정한다(설계 §8.2, §5.3).

| 읽은 것 | 마지막 유효 판 | 결과 |
|---|---|---|
| 없음 | 무엇이든 | `Off(POLICY_MISSING)` — 명시적 부재는 검증 실패가 아니다 |
| 있음, 유효 | — | 그 판 |
| 있음, 무효 | 있음 | 마지막 유효 판 + 거절 사유(알림 `POLICY_REJECTED` 거리) |
| 있음, 무효 | 없음 | `Off(POLICY_MISSING)` + 거절 사유 |
| 읽을 수 없음(YAML 문법 · 입출력 오류) | 있음 / 없음 | "있음, 무효"와 같다 — 파일이 깨졌다고 모든 에피소드가 사람에게 넘어가면 §8.2 의 뜻이 없다 |

**끄기 스위치는 거절된 판에서도 먹는다(R5).** 거절된 판(무효)의 트리에 `agentLayerEnabled: false` 가 글자 그대로 있으면, 마지막 유효
판으로 돌아가지 않고 `Off(AGENT_LAYER_OFF)` 다. 운영자가 끄려고 고친 파일에 다른 오류가 하나 섞였다고 에이전트가 계속 돌면 안 된다.

쓸 판이 정해지면: `expiresAt ≤ now` → `Off(POLICY_EXPIRED)`, `agentLayerEnabled = false` → `Off(AGENT_LAYER_OFF)`, 아니면 `Active`.
`Off` 도 쓸 판을 들고 있다 — ESCALATED 의 보존 기한(`escalatedRetentionMs`)에 필요하다.

**Files:**
- Create: `episode/src/main/kotlin/koshei/episode/PolicyResolution.kt`
- Test: `episode/src/test/kotlin/koshei/episode/PolicyResolutionTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/PolicyResolutionTest.kt`:

```kotlin
package koshei.episode

import koshei.episode.Policies.obj
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PolicyResolutionTest {
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private val broken = Policies.node { obj("deadlines").put("recordMs", 0) }

    @Test fun `no active policy is POLICY_MISSING even with a last valid one`() {
        val r = resolvePolicy(PolicyRead.Missing, lastValid = Policies.valid(), now = now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.POLICY_MISSING, r.reason)
        assertNull(r.policy)
        assertTrue(r.rejectedErrors.isEmpty())
    }

    @Test fun `a valid, live, enabled policy is active`() {
        val r = resolvePolicy(PolicyRead.Found(Policies.node()), lastValid = null, now = now)
        assertIs<PolicyResolution.Active>(r)
        assertEquals("2026-09-30.1", r.policy.version)
        assertTrue(r.rejectedErrors.isEmpty())
    }

    @Test fun `a switched-off policy is AGENT_LAYER_OFF and keeps the table`() {
        val r = resolvePolicy(PolicyRead.Found(Policies.node { put("agentLayerEnabled", false) }), null, now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.AGENT_LAYER_OFF, r.reason)
        assertEquals("2026-09-30.1", r.policy!!.version)
    }

    @Test fun `a policy expiring now is already expired`() {
        val r = resolvePolicy(PolicyRead.Found(Policies.node { put("expiresAt", now.toString()) }), null, now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.POLICY_EXPIRED, r.reason)
    }

    @Test fun `an invalid read keeps the last valid policy and reports why`() {
        val last = Policies.valid()
        val r = resolvePolicy(PolicyRead.Found(broken), lastValid = last, now = now)
        assertIs<PolicyResolution.Active>(r)
        assertSame(last, r.policy)
        assertTrue(r.rejectedErrors.any { "recordMs" in it })
    }

    @Test fun `an invalid read with no last valid policy is POLICY_MISSING with the reasons`() {
        val r = resolvePolicy(PolicyRead.Found(broken), lastValid = null, now = now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.POLICY_MISSING, r.reason)
        assertTrue(r.rejectedErrors.any { "recordMs" in it })
    }

    @Test fun `an unreadable file keeps the last valid policy like an invalid one`() {
        val last = Policies.valid()
        val r = resolvePolicy(PolicyRead.Unreadable("line 3: mapping values are not allowed"), lastValid = last, now = now)
        assertIs<PolicyResolution.Active>(r)
        assertSame(last, r.policy)
        assertTrue(r.rejectedErrors.single().contains("unreadable"))
        assertEquals(PolicyOffReason.POLICY_MISSING, (resolvePolicy(PolicyRead.Unreadable("x"), null, now) as PolicyResolution.Off).reason)
    }

    @Test fun `an explicit off switch wins even in a table rejected for another reason`() {
        val offButBroken = Policies.node { put("agentLayerEnabled", false); obj("deadlines").put("recordMs", 0) }
        val r = resolvePolicy(PolicyRead.Found(offButBroken), lastValid = Policies.valid(), now = now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.AGENT_LAYER_OFF, r.reason)
        assertTrue(r.rejectedErrors.any { "recordMs" in it })
    }

    @Test fun `falling back to an expired last valid policy is POLICY_EXPIRED`() {
        val last = Policies.valid { put("expiresAt", "2026-09-01T00:00:00Z") }
        val r = resolvePolicy(PolicyRead.Found(broken), lastValid = last, now = now)
        assertIs<PolicyResolution.Off>(r)
        assertEquals(PolicyOffReason.POLICY_EXPIRED, r.reason)
        assertSame(last, r.policy)
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.PolicyResolutionTest'`
Expected: 컴파일 실패 — `Unresolved reference 'resolvePolicy'`

- [ ] **Step 3: 구현한다**

`episode/src/main/kotlin/koshei/episode/PolicyResolution.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/** What the runtime's `readPolicy` activity found (design §8.2). */
sealed interface PolicyRead {
    data class Found(val node: JsonNode) : PolicyRead
    /** The file exists but could not become a tree (syntax or I/O error). Resolved like an invalid table. */
    data class Unreadable(val reason: String) : PolicyRead
    data object Missing : PolicyRead
}

/** Why the agent layer is off for this decision (design §5.3). MISSING and EXPIRED behave alike but are never folded. */
enum class PolicyOffReason { POLICY_MISSING, POLICY_EXPIRED, AGENT_LAYER_OFF }

sealed interface PolicyResolution {
    /** Why the freshly read table was rejected, if it was (notify `POLICY_REJECTED`); empty otherwise. */
    val rejectedErrors: List<String>

    data class Active(val policy: PolicyTable, override val rejectedErrors: List<String>) : PolicyResolution

    /** [policy] is the table in force when there is one — ESCALATED still needs its retention. */
    data class Off(val reason: PolicyOffReason, val policy: PolicyTable?, override val rejectedErrors: List<String>) : PolicyResolution
}

/**
 * Decides the policy for one decision (design §8.2, §5.3). An explicit absence is POLICY_MISSING; an invalid read keeps
 * [lastValid] (and reports why) so one bad edit cannot silently escalate every episode; the table in force is then
 * checked for expiry (`expiresAt <= now`) and for the agent-layer switch.
 */
fun resolvePolicy(read: PolicyRead, lastValid: PolicyTable?, now: Instant): PolicyResolution {
    val (inForce, rejected) = when (read) {
        PolicyRead.Missing -> return PolicyResolution.Off(PolicyOffReason.POLICY_MISSING, null, emptyList())
        is PolicyRead.Unreadable -> lastValid to listOf("policy: unreadable (${read.reason})")
        is PolicyRead.Found -> when (val parsed = parsePolicy(read.node)) {
            is PolicyParse.Valid -> parsed.policy to emptyList()
            is PolicyParse.Invalid -> {
                // The off switch must work even in a table that fails validation for another reason (R5).
                val off = read.node.get("agentLayerEnabled")
                if (off != null && off.isBoolean && !off.booleanValue())
                    return PolicyResolution.Off(PolicyOffReason.AGENT_LAYER_OFF, lastValid, parsed.errors)
                lastValid to parsed.errors
            }
        }
    }
    if (inForce == null) return PolicyResolution.Off(PolicyOffReason.POLICY_MISSING, null, rejected)
    val expiresAt = inForce.expiresAt
    if (expiresAt != null && !now.isBefore(expiresAt)) return PolicyResolution.Off(PolicyOffReason.POLICY_EXPIRED, inForce, rejected)
    if (!inForce.agentLayerEnabled) return PolicyResolution.Off(PolicyOffReason.AGENT_LAYER_OFF, inForce, rejected)
    return PolicyResolution.Active(inForce, rejected)
}
```

- [ ] **Step 4: 시험이 통과하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.PolicyResolutionTest'`
Expected: `BUILD SUCCESSFUL` (9 tests)

- [ ] **Step 5: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/PolicyResolution.kt episode/src/test/kotlin/koshei/episode/PolicyResolutionTest.kt
git commit -m "feat(episode): resolve the policy in force (missing, expired, off, last valid)"
```

### Task 7: 기한 계산

설계 §5.1: ESCALATED 를 뺀 대기는 `min(들어간 시각 + 상태 기한, 에피소드 기한)`. ESCALATED 는 에피소드 기한과 무관하게 들어간 순간부터
`escalatedRetentionMs`. 유효한 정책을 한 번도 읽지 못한 채(시작 때 POLICY_MISSING) ESCALATED 로 가면 쓸 보존 기한이 없으므로 고정
값 24시간을 쓴다. 승인은 기준 시각 + `approvalValidityMs` 전까지만 유효하다(그 순간이 되면 만료).

**Files:**
- Create: `episode/src/main/kotlin/koshei/episode/Deadlines.kt`
- Test: `episode/src/test/kotlin/koshei/episode/DeadlinesTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/DeadlinesTest.kt`:

```kotlin
package koshei.episode

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class DeadlinesTest {
    private val p = Policies.valid()   // episodeMs 3_600_000, escalatedRetentionMs 86_400_000, approvalValidityMs 600_000
    private val start = Instant.parse("2026-09-30T00:00:00Z")

    @Test fun `the episode deadline is start plus episodeMs`() {
        assertEquals(Instant.parse("2026-09-30T01:00:00Z"), episodeDeadline(start, p))
    }

    @Test fun `a wait ends at its own budget when that comes first`() {
        val entered = start.plusSeconds(60)
        assertEquals(entered.plusMillis(300_000), waitUntil(entered, 300_000, episodeDeadline(start, p)))
    }

    @Test fun `a wait never outlives the episode`() {
        val entered = start.plusSeconds(3_500)
        assertEquals(episodeDeadline(start, p), waitUntil(entered, 300_000, episodeDeadline(start, p)))
    }

    @Test fun `ESCALATED counts its retention from entry, past the episode deadline`() {
        val entered = episodeDeadline(start, p)
        assertEquals(entered.plusMillis(86_400_000), escalatedUntil(entered, p))
    }

    @Test fun `ESCALATED without any valid policy falls back to 24 hours`() {
        assertEquals(start.plusMillis(FALLBACK_ESCALATED_RETENTION_MS), escalatedUntil(start, null))
        assertEquals(86_400_000L, FALLBACK_ESCALATED_RETENTION_MS)
    }

    @Test fun `an approval is valid until, and not at, basis plus approvalValidityMs`() {
        assertEquals(true, approvalStillValid(start, start.plusMillis(599_999), p))
        assertEquals(false, approvalStillValid(start, start.plusMillis(600_000), p))
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.DeadlinesTest'`
Expected: 컴파일 실패 — `Unresolved reference 'episodeDeadline'`

- [ ] **Step 3: 구현한다**

`episode/src/main/kotlin/koshei/episode/Deadlines.kt`:

```kotlin
package koshei.episode

import java.time.Instant

/**
 * Retention for an episode escalated before any valid policy was ever read (POLICY_MISSING at start), when there is no
 * `escalatedRetentionMs` to use. Long enough for a person to pick the episode up; the episode still always closes.
 */
const val FALLBACK_ESCALATED_RETENTION_MS: Long = 24L * 60 * 60 * 1000

/** The whole-episode deadline (design §5.1). */
fun episodeDeadline(startedAt: Instant, policy: PolicyTable): Instant = startedAt.plusMillis(policy.deadlines.episodeMs)

/** Every wait except ESCALATED wakes at `min(entered + its own budget, episode deadline)` (design §5.1). */
fun waitUntil(enteredAt: Instant, stateBudgetMs: Long, episodeDeadline: Instant): Instant =
    minOf(enteredAt.plusMillis(stateBudgetMs), episodeDeadline)

/** ESCALATED counts `escalatedRetentionMs` from its own entry, regardless of the episode deadline (design §5.1). */
fun escalatedUntil(enteredAt: Instant, policy: PolicyTable?): Instant =
    enteredAt.plusMillis(policy?.deadlines?.escalatedRetentionMs ?: FALLBACK_ESCALATED_RETENTION_MS)

/**
 * An approval (or, for person-task kinds, a proposal) is valid strictly before `basis + approvalValidityMs`
 * (design §5.1).
 */
fun approvalStillValid(basis: Instant, now: Instant, policy: PolicyTable): Boolean =
    now.isBefore(basis.plusMillis(policy.deadlines.approvalValidityMs))
```

- [ ] **Step 4: 시험이 통과하는지 본다**

Run: `./gradlew :episode:test`
Expected: `BUILD SUCCESSFUL` — 모든 시험 초록, `git status --short episode/src/test/resources` 가 비어 있음

- [ ] **Step 5: 설계 문서에 v1 판단을 적는다**

`docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md` 의 §8.2 끝에 문단 하나를 더한다:

```markdown
v1 판단(계획 B2a): 활성 판이 **없다**고 읽히면(명시적 부재) 마지막 유효 판이 있어도 `POLICY_MISSING` 이다 — 판을 내린 것을
의도로 읽는다. 파일을 **읽을 수 없으면**(문법 · 입출력 오류) 무효 판과 같게 마지막 유효 판을 쓴다. 거절된 판에 `agentLayerEnabled:
false` 가 글자 그대로 있으면 마지막 유효 판으로 돌아가지 않고 끈다(R5). 모르는 키는 오류다(R14). `requireClean` 은 없으면 `true`.
유효한 판을 한 번도 읽지 못한 채 ESCALATED 로 가면 보존 기한은 고정 24시간이다(§5.1 의 "기한 없는 대기는 없다"). 모든 기한 칸은
365일 이하.
```

- [ ] **Step 6: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/Deadlines.kt episode/src/test/kotlin/koshei/episode/DeadlinesTest.kt docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md
git commit -m "feat(episode): wait-deadline arithmetic; record the v1 policy choices"
```
