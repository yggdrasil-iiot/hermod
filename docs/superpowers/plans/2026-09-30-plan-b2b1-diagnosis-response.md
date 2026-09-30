# 계획 B2b-1 — 진단 응답 읽기와 판정 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** narrator `diagnose` 응답(계약 0.6 §4)을 엄격하게 읽고, 보낸 요청과 대조해 전이 함수(B2b-2)가 받을 판정 하나로 줄인다.

**Architecture:** 순수 모듈 `:episode` 에 두 파일을 더한다. `DiagnosisResponses.kt` 는 응답 JSON 을 형 있는 값으로 읽고 계약 위반을
모두 모아 돌려준다(모르는 키는 무시 — 제공자가 칸을 더해도 깨지지 않게). `DiagnosisVerdicts.kt` 는 (요청, 읽은 결과) → `Proposed`
또는 `Escalate(사유)` 를 설계 §5.2 의 DIAGNOSING 행 순서대로 정한다. 응답 원문은 기록용으로 그대로 들고 있다(R11).

**Tech Stack:** Kotlin 2.2 / JDK 21, Jackson databind 2.17 (트리), JUnit 5 / kotlin-test

**작업 위치:** worktree `C:\Users\Eisen\Desktop\Labs\[projects] koshchei\koshei-episode-core`, 브랜치 `feat/episode-core`.
원래 체크아웃(`koshei/`)은 건드리지 않는다. 파일 내용은 Write/Edit 로만 쓴다(heredoc 금지). 역슬래시 이스케이프가 든 줄은 Edit 가
문자로 바꿀 수 있으니 쓴 뒤 `cat -A` 로 확인한다.

**커밋 규칙:** 모든 커밋 메시지 끝에 빈 줄 하나와 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

**범위 밖:** 상태 · 사건 · 전이 함수 · 운영자 카드 조립(B2b-2).

---

## 읽기 규칙 (계약 0.6 §2 · §4)

| 칸 | 규칙 |
|---|---|
| 모든 필수 칸 | **키가 있어야 한다** — 값이 없으면 `null` 이지 키를 빼지 않는다(§2). 없으면 위반 |
| `contractVersion` · `episodeId` | 비지 않은 글자 |
| `attempt` | 1 이상의 정수 |
| `outcome` | `RECOMMENDED` · `NO_GROUNDS` · `UNCITED` · `OUT_OF_CANDIDATES` |
| `candidateId` | `RECOMMENDED` 면 비지 않은 글자, 그 밖에는 `null` |
| `picked` | `OUT_OF_CANDIDATES` 면 `null` 또는 글자(빈 글자 `""` 도 값 — 표지 뒤가 비었음), 그 밖에는 `null` |
| `sawCandidatesVersion` | `sha256:` + 16진 소문자 64자 |
| `rationale` · `cause` | `null` 또는 비지 않은 글자 — 빈 글자로 적지 않는다(§4) |
| `card` | `{ label, text }`(둘 다 글자) 의 목록 |
| `citations` | 객체의 목록 — 원문 그대로 둔다 |
| `unverifiedClaims` | `{ kind: CITATION \| NUMBER, text: 글자 \| null, foundIn: null \| 글자 목록 }` 의 목록, CITATION 이면 `foundIn` 은 `null`. **목록 자체는 `null` 이 아니다** |
| `uncitedSentences` | 글자 목록. **`null` 이 아니다** |
| `versions` | 객체, 다섯 칸(`modelId` · `promptVersion` · `corpusVersion` · `searchFingerprint` · `narratorCommit`) 각각 글자 또는 `null` |
| `elapsedSeconds` | 0 이상의 수 |
| 그 밖의 키 | 무시한다(원문에는 남는다) |

**깨끗함(`clean`)**: `rationale != null` ∧ `uncitedSentences` 빔 ∧ `unverifiedClaims` 빔 — 설계 §9.5 의 `requireClean` 조건.

## 판정 순서 (설계 §5.2 DIAGNOSING 행)

1. 읽기 위반 → `Escalate(DIAGNOSIS_FAILED)` (위반 목록을 `detail` 에)
2. `contractVersion` ≠ `"0.6"` · `episodeId` 나 `attempt` 가 요청과 다름 → `Escalate(DIAGNOSIS_FAILED)`
3. `sawCandidatesVersion` ≠ 요청의 `candidatesVersion` → `Escalate(CANDIDATES_VERSION_MISMATCH)`
4. `NO_GROUNDS` · `UNCITED` → 같은 이름의 사유. `OUT_OF_CANDIDATES` → 같은 이름, `picked` 를 싣는다
5. `RECOMMENDED`:
   - `candidateId == "ESCALATE"` → `Escalate(ESCALATE_RECOMMENDED)`
   - 요청의 후보에 없음 → `Escalate(OUT_OF_CANDIDATES)`, `picked` = 그 식별자(판정 주인이 둘이어도 결과가 같다, 계약 §4)
   - 실행 계열인데 요청의 `unknowns` 가 비지 않음 → `Escalate(UNKNOWN_BLOCKS_EXECUTION)` — 투영이 이미 뺐어야 하므로 투영 결함 신호
   - 그 밖 → `Proposed(후보, clean)`

---

## 파일 구조

| 파일 | 할 일 | 책임 |
|---|---|---|
| `episode/src/main/kotlin/koshei/episode/DiagnosisResponses.kt` | 생성 | 응답 모양 + 엄격한 읽기 |
| `episode/src/main/kotlin/koshei/episode/DiagnosisVerdicts.kt` | 생성 | 요청 대조 판정 |
| `episode/src/test/kotlin/koshei/episode/Responses.kt` | 생성 | 시험용 응답 짓개 |
| `episode/src/test/kotlin/koshei/episode/DiagnosisResponsesTest.kt` | 생성 | 읽기 시험 |
| `episode/src/test/kotlin/koshei/episode/DiagnosisVerdictsTest.kt` | 생성 | 판정 시험 |

---

## Chunk 1: 읽기와 판정

### Task 1: 응답 읽기

**Files:**
- Create: `episode/src/main/kotlin/koshei/episode/DiagnosisResponses.kt`
- Create: `episode/src/test/kotlin/koshei/episode/Responses.kt`
- Test: `episode/src/test/kotlin/koshei/episode/DiagnosisResponsesTest.kt`

- [ ] **Step 1: 시험용 응답 짓개를 만든다**

`episode/src/test/kotlin/koshei/episode/Responses.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Builds a contract-0.6 response for a given request, every key present. Tests edit the result to break one rule at a
 * time. The default is a clean RECOMMENDED answer pointing at the request's first non-ESCALATE candidate.
 */
internal object Responses {
    fun forRequest(
        request: DiagnosisRequest,
        outcome: String = "RECOMMENDED",
        candidateId: String? = request.candidates.first { it.kind != CandidateKind.ESCALATE }.candidateId,
        picked: String? = null,
        edit: ObjectNode.() -> Unit = {},
    ): ObjectNode = mapper.createObjectNode().apply {
        put("contractVersion", DIAGNOSIS_CONTRACT_VERSION)
        put("episodeId", request.episodeId)
        put("attempt", request.attempt)
        put("outcome", outcome)
        put("candidateId", if (outcome == "RECOMMENDED") candidateId else null)
        put("picked", picked)
        put("sawCandidatesVersion", request.candidatesVersion)
        put("rationale", "재고는 있고 경로만 막혔다 [출처: 결품 대응 절차, 3.2]")
        putArray("card").addObject().put("label", "절차").put("text", "대체 위치에서 집는다")
        put("cause", "AMR 이송 지연")
        putArray("citations").addObject().put("title", "결품 대응 절차").put("section", "3.2").put("verified", true)
        putArray("unverifiedClaims")
        putArray("uncitedSentences")
        putObject("versions").apply {
            put("modelId", "claude-sonnet-5"); putNull("promptVersion"); putNull("corpusVersion")
            putNull("searchFingerprint"); put("narratorCommit", "0000000")
        }
        put("elapsedSeconds", 12.5)
        edit()
    }

    /** Request 01 of the contract fixtures: APPROVE_REMEDY + ESCALATE, no unknowns. */
    fun request01(): DiagnosisRequest =
        diagnosisRequest("ep:fixture-01-recommended", 1, PicassoRun1.snapshot(listOf("incident-1"), listOf("search-1")))

    /** Request 04 of the contract fixtures: LINK_BROKEN, ESCALATE + CONFIRM_DONE only. */
    fun request04(): DiagnosisRequest =
        diagnosisRequest("ep:fixture-04-unknown", 1, PicassoRun1.snapshot(incidentIds = listOf("incident-6")))
}
```

- [ ] **Step 2: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/DiagnosisResponsesTest.kt`:

```kotlin
package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosisResponsesTest {
    private val req = Responses.request01()

    private fun errors(edit: com.fasterxml.jackson.databind.node.ObjectNode.() -> Unit): List<String> {
        val r = parseDiagnosisResponse(Responses.forRequest(req, edit = edit))
        assertIs<ResponseParse.Invalid>(r)
        return r.errors
    }
    private fun List<String>.has(fragment: String) = assertTrue(any { fragment in it }, "no error containing '$fragment' in $this")

    @Test fun `a complete response reads with every field`() {
        val r = parseDiagnosisResponse(Responses.forRequest(req))
        assertIs<ResponseParse.Valid>(r)
        val d = r.response
        assertEquals(DIAGNOSIS_CONTRACT_VERSION, d.contractVersion)
        assertEquals("ep:fixture-01-recommended", d.episodeId)
        assertEquals(1, d.attempt)
        assertEquals(DiagnosisOutcome.RECOMMENDED, d.outcome)
        assertNull(d.picked)
        assertEquals("재고는 있고 경로만 막혔다 [출처: 결품 대응 절차, 3.2]", d.rationale)
        assertEquals("AMR 이송 지연", d.cause)
        assertEquals(emptyList(), d.uncitedSentences)
        assertEquals(emptyList(), d.unverifiedClaims)
        assertEquals("APPROVE_REMEDY:hum-02:PATROL-1:pick_place", d.candidateId)
        assertEquals(req.candidatesVersion, d.sawCandidatesVersion)
        assertEquals(listOf(CardLine("절차", "대체 위치에서 집는다")), d.card)
        assertEquals("claude-sonnet-5", d.versions.modelId)
        assertNull(d.versions.promptVersion)
        assertEquals(12.5, d.elapsedSeconds)
        assertEquals(1, d.citations.size)
        assertTrue(d.clean)
    }

    @Test fun `an unreadable response keeps its wire form too`() {
        val node = Responses.forRequest(req) { put("outcome", "MAYBE") }
        val r = parseDiagnosisResponse(node)
        assertIs<ResponseParse.Invalid>(r)
        assertEquals(node, r.raw)
        node.put("outcome", "NO_GROUNDS")
        assertEquals("MAYBE", r.raw.get("outcome").textValue())
    }

    @Test fun `the raw response is kept verbatim, extra keys included`() {
        val node = Responses.forRequest(req) { put("futureField", 1) }
        val d = (parseDiagnosisResponse(node) as ResponseParse.Valid).response
        assertEquals(node, d.raw)
        node.put("outcome", "UNCITED")
        assertEquals("RECOMMENDED", d.raw.get("outcome").textValue())
    }

    @Test fun `clean needs a reason and nothing unchecked`() {
        fun clean(edit: com.fasterxml.jackson.databind.node.ObjectNode.() -> Unit) =
            (parseDiagnosisResponse(Responses.forRequest(req, edit = edit)) as ResponseParse.Valid).response.clean
        assertFalse(clean { putNull("rationale") })
        assertFalse(clean { putArray("uncitedSentences").add("근거 없는 문장") })
        assertFalse(clean { putArray("unverifiedClaims").addObject().put("kind", "NUMBER").putNull("text").putNull("foundIn") })
    }

    @Test fun `keys are never omitted`() {
        errors { remove("picked") }.has("picked: missing")
        errors { remove("uncitedSentences") }.has("uncitedSentences: missing")
        errors { remove("versions") }.has("versions: missing")
    }

    @Test fun `the two lists are never null`() {
        errors { putNull("uncitedSentences") }.has("uncitedSentences: expected a list")
        errors { putNull("unverifiedClaims") }.has("unverifiedClaims: expected a list")
    }

    @Test fun `candidateId belongs to RECOMMENDED only and picked to OUT_OF_CANDIDATES only`() {
        errors { put("candidateId", null as String?) }.has("candidateId: RECOMMENDED needs a candidate id")
        errors { put("outcome", "NO_GROUNDS") }.has("candidateId: must be null unless RECOMMENDED")
        errors { put("picked", "B-07") }.has("picked: must be null unless OUT_OF_CANDIDATES")
        val oc = parseDiagnosisResponse(Responses.forRequest(req, outcome = "OUT_OF_CANDIDATES", picked = ""))
        assertIs<ResponseParse.Valid>(oc)
        assertEquals("", oc.response.picked)
    }

    @Test fun `values have to have their shapes`() {
        errors { put("outcome", "MAYBE") }.has("outcome: expected one of")
        errors { put("attempt", 0) }.has("attempt: expected an integer >= 1")
        errors { put("sawCandidatesVersion", "sha256:ABC") }.has("sawCandidatesVersion: expected sha256:")
        errors { put("rationale", "") }.has("rationale: expected null or a non-blank string")
        errors { putArray("card").addObject().put("label", "절차") }.has("card[0].text")
        errors { putArray("unverifiedClaims").addObject().put("kind", "GUESS").putNull("text").putNull("foundIn") }
            .has("unverifiedClaims[0].kind")
        errors { putArray("unverifiedClaims").addObject().put("kind", "NUMBER").putNull("text").put("foundIn", "evidence") }
            .has("unverifiedClaims[0].foundIn: expected null or a list of strings")
        errors { putArray("unverifiedClaims").addObject().put("kind", "CITATION").put("text", "SOP-07").putArray("foundIn").add("evidence") }
            .has("unverifiedClaims[0].foundIn: a CITATION claim has foundIn null")
        errors { putArray("citations").add("SOP-07") }.has("citations[0]: expected an object")
        errors { put("outcome", "OUT_OF_CANDIDATES"); putNull("candidateId"); put("picked", 7) }.has("picked: expected null or a string")
        errors { (get("versions") as com.fasterxml.jackson.databind.node.ObjectNode).remove("narratorCommit") }
            .has("versions.narratorCommit: missing")
        errors { put("elapsedSeconds", -1) }.has("elapsedSeconds: expected a number >= 0")
    }

    @Test fun `every violation is reported and a non-object is rejected`() {
        val e = errors { put("outcome", "MAYBE"); put("attempt", 0) }
        e.has("outcome"); e.has("attempt")
        val r = parseDiagnosisResponse(mapper.readTree("[1]"))
        assertIs<ResponseParse.Invalid>(r)
        r.errors.has("response: expected an object")
        assertEquals(mapper.readTree("[1]"), r.raw)
    }
}
```

- [ ] **Step 3: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.DiagnosisResponsesTest'`
Expected: 컴파일 실패 — `Unresolved reference 'parseDiagnosisResponse'` (그리고 `ResponseParse`, `DiagnosisOutcome`, `CardLine`)

- [ ] **Step 4: 구현한다**

`episode/src/main/kotlin/koshei/episode/DiagnosisResponses.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** The four outcomes of a diagnosis (contract 0.6 §4). All four are values; failures arrive as activity exceptions. */
enum class DiagnosisOutcome { RECOMMENDED, NO_GROUNDS, UNCITED, OUT_OF_CANDIDATES }

data class CardLine(val label: String, val text: String)

/** An unchecked claim in the rationale: a CITATION not in the evidence bundle, or a NUMBER not found there. */
data class UnverifiedClaim(val kind: String, val text: String?, val foundIn: List<String>?)

/** The five version fields of a diagnosis (contract §4, design §13) — investigation material, not a guarantee. */
data class DiagnosisVersions(
    val modelId: String?,
    val promptVersion: String?,
    val corpusVersion: String?,
    val searchFingerprint: String?,
    val narratorCommit: String?,
)

/** A narrator `diagnose` response (contract 0.6 §4), read by [parseDiagnosisResponse]. [raw] keeps the wire form for the record (R11). */
class DiagnosisResponse internal constructor(
    val contractVersion: String,
    val episodeId: String,
    val attempt: Int,
    val outcome: DiagnosisOutcome,
    val candidateId: String?,
    val picked: String?,
    val sawCandidatesVersion: String,
    val rationale: String?,
    val card: List<CardLine>,
    val cause: String?,
    val citations: List<JsonNode>,
    val unverifiedClaims: List<UnverifiedClaim>,
    val uncitedSentences: List<String>,
    val versions: DiagnosisVersions,
    val elapsedSeconds: Double,
    raw: ObjectNode,
) {
    val raw: ObjectNode = raw.deepCopy()

    /**
     * Clean enough for auto-approval under `requireClean` (design §9.5): there is a reason, and nothing in it is unchecked.
     * A null rationale is never clean — with no reason there are no sentences to count, so the lists are trivially empty.
     */
    val clean: Boolean get() = rationale != null && uncitedSentences.isEmpty() && unverifiedClaims.isEmpty()
}

sealed interface ResponseParse {
    data class Valid(val response: DiagnosisResponse) : ResponseParse
    /** [raw] is a deep copy of what arrived, so an unreadable answer can still be recorded verbatim (design §13). */
    class Invalid(val errors: List<String>, raw: JsonNode) : ResponseParse {
        val raw: JsonNode = raw.deepCopy()
        override fun toString() = "Invalid($errors)"
    }
}

private val SHA256_VERSION = Regex("sha256:[0-9a-f]{64}")
private val VERSION_KEYS = listOf("modelId", "promptVersion", "corpusVersion", "searchFingerprint", "narratorCommit")

/**
 * Reads a response strictly (contract 0.6 §2, §4): every required key must be present (null, never omitted), and all
 * violations are reported together. Keys this module does not know are ignored so that the provider can add fields;
 * they stay in [DiagnosisResponse.raw].
 */
fun parseDiagnosisResponse(node: JsonNode): ResponseParse {
    if (!node.isObject) return ResponseParse.Invalid(listOf("response: expected an object"), node)
    val e = mutableListOf<String>()
    fun present(key: String): JsonNode? = node.get(key).also { if (it == null) e += "$key: missing (keys are never omitted)" }
    fun text(key: String): String? = present(key)?.let {
        if (it.isTextual && it.textValue().isNotBlank()) it.textValue() else { e += "$key: expected a non-blank string"; null }
    }
    fun nullableText(key: String): String? = present(key)?.let {
        when {
            it.isNull -> null
            it.isTextual && it.textValue().isNotBlank() -> it.textValue()
            else -> { e += "$key: expected null or a non-blank string"; null }
        }
    }
    fun list(key: String): JsonNode? = present(key)?.let { if (it.isArray) it else { e += "$key: expected a list"; null } }

    val contractVersion = text("contractVersion")
    val episodeId = text("episodeId")
    val attempt = present("attempt")?.let {
        if (it.isIntegralNumber && it.canConvertToInt() && it.intValue() >= 1) it.intValue() else { e += "attempt: expected an integer >= 1"; null }
    }
    val outcome = present("outcome")?.let { o ->
        DiagnosisOutcome.entries.firstOrNull { o.isTextual && it.name == o.textValue() }
            ?: run { e += "outcome: expected one of ${DiagnosisOutcome.entries}"; null }
    }

    val candidateIdNode = present("candidateId")
    val candidateId = candidateIdNode?.takeIf { it.isTextual }?.textValue()
    if (candidateIdNode != null && outcome != null) {
        if (outcome == DiagnosisOutcome.RECOMMENDED && candidateId.isNullOrBlank()) e += "candidateId: RECOMMENDED needs a candidate id"
        if (outcome != DiagnosisOutcome.RECOMMENDED && !candidateIdNode.isNull) e += "candidateId: must be null unless RECOMMENDED"
    }
    val pickedNode = present("picked")
    val picked = pickedNode?.takeIf { it.isTextual }?.textValue()
    if (pickedNode != null && outcome != null) {
        if (outcome == DiagnosisOutcome.OUT_OF_CANDIDATES && !pickedNode.isNull && !pickedNode.isTextual) e += "picked: expected null or a string"
        if (outcome != DiagnosisOutcome.OUT_OF_CANDIDATES && !pickedNode.isNull) e += "picked: must be null unless OUT_OF_CANDIDATES"
    }

    val saw = present("sawCandidatesVersion")?.let {
        if (it.isTextual && SHA256_VERSION.matches(it.textValue())) it.textValue() else { e += "sawCandidatesVersion: expected sha256: + 64 lowercase hex"; null }
    }
    val rationale = nullableText("rationale")
    val cause = nullableText("cause")

    val card = list("card")?.mapIndexedNotNull { i, c ->
        val label = c.get("label")?.takeIf { it.isTextual }?.textValue()
        val t = c.get("text")?.takeIf { it.isTextual }?.textValue()
        if (label == null) e += "card[$i].label: expected a string"
        if (t == null) e += "card[$i].text: expected a string"
        if (label != null && t != null) CardLine(label, t) else null
    }
    val citations = list("citations")?.let { l ->
        l.forEachIndexed { i, c -> if (!c.isObject) e += "citations[$i]: expected an object" }
        l.map { it.deepCopy<JsonNode>() }
    }
    val unverified = list("unverifiedClaims")?.mapIndexedNotNull { i, c ->
        val kind = c.get("kind")?.takeIf { it.isTextual && it.textValue() in setOf("CITATION", "NUMBER") }?.textValue()
        if (kind == null) e += "unverifiedClaims[$i].kind: expected CITATION or NUMBER"
        val t = c.get("text")
        if (t == null || !(t.isNull || t.isTextual)) e += "unverifiedClaims[$i].text: expected null or a string"
        val f = c.get("foundIn")
        if (f == null || !(f.isNull || (f.isArray && f.all { it.isTextual }))) e += "unverifiedClaims[$i].foundIn: expected null or a list of strings"
        if (kind == "CITATION" && f != null && !f.isNull) e += "unverifiedClaims[$i].foundIn: a CITATION claim has foundIn null"
        if (kind != null) UnverifiedClaim(kind, t?.takeIf { it.isTextual }?.textValue(), f?.takeIf { it.isArray }?.map { it.textValue() }) else null
    }
    val uncited = list("uncitedSentences")?.let { l ->
        l.forEachIndexed { i, s -> if (!s.isTextual) e += "uncitedSentences[$i]: expected a string" }
        l.filter { it.isTextual }.map { it.textValue() }
    }
    val versions = present("versions")?.let { v ->
        if (!v.isObject) { e += "versions: expected an object"; null } else {
            val values = VERSION_KEYS.map { k ->
                val x = v.get(k)
                when {
                    x == null -> { e += "versions.$k: missing (keys are never omitted)"; null }
                    x.isNull -> null
                    x.isTextual -> x.textValue()
                    else -> { e += "versions.$k: expected null or a string"; null }
                }
            }
            DiagnosisVersions(values[0], values[1], values[2], values[3], values[4])
        }
    }
    val elapsed = present("elapsedSeconds")?.let {
        if (it.isNumber && it.doubleValue() >= 0) it.doubleValue() else { e += "elapsedSeconds: expected a number >= 0"; null }
    }

    if (e.isNotEmpty()) return ResponseParse.Invalid(e.toList(), node)
    return ResponseParse.Valid(
        DiagnosisResponse(
            contractVersion!!, episodeId!!, attempt!!, outcome!!, candidateId, picked, saw!!, rationale, card!!, cause,
            citations!!, unverified!!, uncited!!, versions!!, elapsed!!, node as ObjectNode,
        ),
    )
}
```

- [ ] **Step 5: 시험이 통과하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.DiagnosisResponsesTest'`
Expected: `BUILD SUCCESSFUL` (9 tests)

- [ ] **Step 6: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/DiagnosisResponses.kt episode/src/test/kotlin/koshei/episode/Responses.kt episode/src/test/kotlin/koshei/episode/DiagnosisResponsesTest.kt
git commit -m "feat(episode): read narrator diagnosis responses strictly (contract 0.6 §4)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: 요청 대조 판정

**Files:**
- Create: `episode/src/main/kotlin/koshei/episode/DiagnosisVerdicts.kt`
- Test: `episode/src/test/kotlin/koshei/episode/DiagnosisVerdictsTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/DiagnosisVerdictsTest.kt`:

```kotlin
package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosisVerdictsTest {
    private val req01 = Responses.request01()
    private val req04 = Responses.request04()

    private fun judge(req: DiagnosisRequest, node: com.fasterxml.jackson.databind.node.ObjectNode) =
        judgeDiagnosis(req, parseDiagnosisResponse(node))

    private fun escalation(v: DiagnosisVerdict): DiagnosisEscalation {
        assertIs<DiagnosisVerdict.Escalate>(v)
        return v.reason
    }

    @Test fun `a clean recommendation of an offered candidate is proposed`() {
        val v = judge(req01, Responses.forRequest(req01))
        assertIs<DiagnosisVerdict.Proposed>(v)
        assertEquals("APPROVE_REMEDY:hum-02:PATROL-1:pick_place", v.candidate.candidateId)
        assertTrue(v.clean)
    }

    @Test fun `a recommendation without a reason is proposed but not clean`() {
        val v = judge(req01, Responses.forRequest(req01) { putNull("rationale") })
        assertIs<DiagnosisVerdict.Proposed>(v)
        assertFalse(v.clean)
    }

    @Test fun `CONFIRM_DONE under an unknown is proposed - it resolves the unknown`() {
        val v = judge(req04, Responses.forRequest(req04, candidateId = "OPERATOR_DECISION:exec-8:RACK-204.S06:CONFIRM_DONE"))
        assertIs<DiagnosisVerdict.Proposed>(v)
        assertEquals(CandidateKind.OPERATOR_DECISION, v.candidate.kind)
    }

    @Test fun `ESCALATE recommended goes to a person`() {
        assertEquals(DiagnosisEscalation.ESCALATE_RECOMMENDED, escalation(judge(req01, Responses.forRequest(req01, candidateId = "ESCALATE"))))
    }

    @Test fun `NO_GROUNDS and UNCITED escalate under their own names`() {
        assertEquals(DiagnosisEscalation.NO_GROUNDS, escalation(judge(req01, Responses.forRequest(req01, outcome = "NO_GROUNDS"))))
        assertEquals(DiagnosisEscalation.UNCITED, escalation(judge(req01, Responses.forRequest(req01, outcome = "UNCITED"))))
    }

    @Test fun `OUT_OF_CANDIDATES carries what was picked, even an empty or null pick`() {
        val v = judge(req01, Responses.forRequest(req01, outcome = "OUT_OF_CANDIDATES", picked = "B-07"))
        assertIs<DiagnosisVerdict.Escalate>(v)
        assertEquals(DiagnosisEscalation.OUT_OF_CANDIDATES, v.reason)
        assertEquals("B-07", v.picked)
        val none = judge(req01, Responses.forRequest(req01, outcome = "OUT_OF_CANDIDATES", picked = null))
        assertIs<DiagnosisVerdict.Escalate>(none)
        assertNull(none.picked)
        val empty = judge(req01, Responses.forRequest(req01, outcome = "OUT_OF_CANDIDATES", picked = ""))
        assertIs<DiagnosisVerdict.Escalate>(empty)
        assertEquals("", empty.picked)
    }

    @Test fun `a recommended id koshei did not offer is out of candidates - both judges agree`() {
        val v = judge(req01, Responses.forRequest(req01, candidateId = "APPROVE_REMEDY:hum-99:X:pick_place"))
        assertIs<DiagnosisVerdict.Escalate>(v)
        assertEquals(DiagnosisEscalation.OUT_OF_CANDIDATES, v.reason)
        assertEquals("APPROVE_REMEDY:hum-99:X:pick_place", v.picked)
    }

    @Test fun `a different candidates version is a contract violation, not a retry`() {
        val v = judge(req01, Responses.forRequest(req01) { put("sawCandidatesVersion", "sha256:" + "0".repeat(64)) })
        assertEquals(DiagnosisEscalation.CANDIDATES_VERSION_MISMATCH, escalation(v))
    }

    @Test fun `an unreadable or foreign response is a failed diagnosis`() {
        val broken = judge(req01, Responses.forRequest(req01) { remove("versions") })
        assertIs<DiagnosisVerdict.Escalate>(broken)
        assertEquals(DiagnosisEscalation.DIAGNOSIS_FAILED, broken.reason)
        assertTrue(broken.detail!!.contains("versions"))
        assertEquals(DiagnosisEscalation.DIAGNOSIS_FAILED, escalation(judge(req01, Responses.forRequest(req01) { put("episodeId", "ep:other") })))
        assertEquals(DiagnosisEscalation.DIAGNOSIS_FAILED, escalation(judge(req01, Responses.forRequest(req01) { put("attempt", 2) })))
        assertEquals(DiagnosisEscalation.DIAGNOSIS_FAILED, escalation(judge(req01, Responses.forRequest(req01) { put("contractVersion", "0.5") })))
    }

    @Test fun `an execution candidate recommended under an unknown is a projection defect signal`() {
        // Projection never offers this combination; build the request by hand to exercise the second line of defence.
        val base = Responses.request04()
        val remedy = Candidate.approveRemedy("hum-07", "SEQ-6", listOf("pick_place"), "search-x")
        val candidates = (base.candidates + remedy).sortedBy { it.candidateId }
        val req = DiagnosisRequest(
            base.episodeId, base.attempt, base.snapshot, base.unknowns, candidates, candidatesVersion(candidates), emptyList(),
        )
        val v = judge(req, Responses.forRequest(req, candidateId = remedy.candidateId))
        assertEquals(DiagnosisEscalation.UNKNOWN_BLOCKS_EXECUTION, escalation(v))
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :episode:test --tests 'koshei.episode.DiagnosisVerdictsTest'`
Expected: 컴파일 실패 — `Unresolved reference 'judgeDiagnosis'` (그리고 `DiagnosisVerdict`, `DiagnosisEscalation`)

- [ ] **Step 3: 구현한다**

`episode/src/main/kotlin/koshei/episode/DiagnosisVerdicts.kt`:

```kotlin
package koshei.episode

/**
 * Why a diagnosis sends the episode to a person: the subset of design §5.3 that the DIAGNOSING rows of §5.2 produce from a
 * response. DIAGNOSIS_EXPIRED is not here (it comes from the activity failing, not from a response); B2b-2 maps these
 * onto the episode's single escalation-reason type.
 */
enum class DiagnosisEscalation {
    ESCALATE_RECOMMENDED, NO_GROUNDS, UNCITED, OUT_OF_CANDIDATES, CANDIDATES_VERSION_MISMATCH, UNKNOWN_BLOCKS_EXECUTION, DIAGNOSIS_FAILED,
}

sealed interface DiagnosisVerdict {
    /** An offered candidate the transition function may propose; [clean] feeds `requireClean` (design §9.5). */
    data class Proposed(val candidate: Candidate, val clean: Boolean) : DiagnosisVerdict

    /**
     * [picked] is what narrator pointed at: the text or id outside the list for OUT_OF_CANDIDATES (null when there was no
     * recommendation marker), or the blocked id for UNKNOWN_BLOCKS_EXECUTION. The transition never branches on it. [detail]
     * explains a DIAGNOSIS_FAILED.
     */
    data class Escalate(val reason: DiagnosisEscalation, val picked: String? = null, val detail: String? = null) : DiagnosisVerdict
}

/**
 * Checks a diagnosis against the request that was sent (design §5.2, DIAGNOSING rows, in order). The response never
 * decides alone: koshei re-checks that the id was offered (both judges agree on OUT_OF_CANDIDATES) and that no
 * execution-class candidate slipped past the unknowns (a projection defect, reported as UNKNOWN_BLOCKS_EXECUTION).
 */
fun judgeDiagnosis(request: DiagnosisRequest, parse: ResponseParse): DiagnosisVerdict {
    val response = when (parse) {
        is ResponseParse.Invalid -> return DiagnosisVerdict.Escalate(DiagnosisEscalation.DIAGNOSIS_FAILED, detail = parse.errors.joinToString("; "))
        is ResponseParse.Valid -> parse.response
    }
    if (response.contractVersion != DIAGNOSIS_CONTRACT_VERSION || response.episodeId != request.episodeId || response.attempt != request.attempt)
        return DiagnosisVerdict.Escalate(
            DiagnosisEscalation.DIAGNOSIS_FAILED,
            detail = "response is for contract ${response.contractVersion} / ${response.episodeId} attempt ${response.attempt}, " +
                "request was $DIAGNOSIS_CONTRACT_VERSION / ${request.episodeId} attempt ${request.attempt}",
        )
    if (response.sawCandidatesVersion != request.candidatesVersion)
        return DiagnosisVerdict.Escalate(DiagnosisEscalation.CANDIDATES_VERSION_MISMATCH)

    return when (response.outcome) {
        DiagnosisOutcome.NO_GROUNDS -> DiagnosisVerdict.Escalate(DiagnosisEscalation.NO_GROUNDS)
        DiagnosisOutcome.UNCITED -> DiagnosisVerdict.Escalate(DiagnosisEscalation.UNCITED)
        DiagnosisOutcome.OUT_OF_CANDIDATES -> DiagnosisVerdict.Escalate(DiagnosisEscalation.OUT_OF_CANDIDATES, picked = response.picked)
        DiagnosisOutcome.RECOMMENDED -> {
            val id = response.candidateId!!
            val candidate = request.candidates.firstOrNull { it.candidateId == id }
            when {
                id == Candidate.ESCALATE_ID -> DiagnosisVerdict.Escalate(DiagnosisEscalation.ESCALATE_RECOMMENDED)
                candidate == null -> DiagnosisVerdict.Escalate(DiagnosisEscalation.OUT_OF_CANDIDATES, picked = id)
                candidate.executionClass && request.unknowns.isNotEmpty() ->
                    DiagnosisVerdict.Escalate(DiagnosisEscalation.UNKNOWN_BLOCKS_EXECUTION, picked = id)
                else -> DiagnosisVerdict.Proposed(candidate, response.clean)
            }
        }
    }
}
```

- [ ] **Step 4: 시험이 통과하는지 본다**

Run: `./gradlew :episode:test`
Expected: `BUILD SUCCESSFUL` — DiagnosisVerdictsTest 10 포함 전부 초록, `git status --short -uall episode/src/test/resources` 비어 있음

- [ ] **Step 5: 설계 §5.2 에 한 행을 더한다**

`docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md` 의 DIAGNOSING 행 가운데
`| DIAGNOSING | 액티비티 기한 초과 / 비재시도 실패 | ESCALATED | ...` 행 **바로 위에**:

```markdown
| DIAGNOSING | 응답이 계약에 맞지 않음(읽기 위반 · `contractVersion` · `episodeId` · `attempt` 가 요청과 다름) | ESCALATED | `DIAGNOSIS_FAILED`, 위반 목록과 응답 원문 기록 |
```

- [ ] **Step 6: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode/DiagnosisVerdicts.kt episode/src/test/kotlin/koshei/episode/DiagnosisVerdictsTest.kt docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md
git commit -m "feat(episode): judge a diagnosis against the request it answers" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Chunk 2: narrator 응답 예제 대조

### Task 3: 응답 넷을 복사해 판정한다

narrator 가 요청 넷에 대한 응답 넷을 자기 저장소에 지었다(계약 §8):
`C:\Users\Eisen\Desktop\Labs\[projects] narrator\tests\fixtures\contract\diagnosis\responses\` (2026-09-30 현재 narrator 에서
아직 커밋 전이라 커밋 해시 대신 sha256 을 적는다). 기대 결과:
01 → `Proposed(APPROVE_REMEDY)`, 02 → `Escalate(NO_GROUNDS)`, 03 → `Escalate(OUT_OF_CANDIDATES)`, 04 → `Proposed(CONFIRM_DONE)`.

**Files:**
- Create: `episode/src/test/resources/contract/diagnosis/responses/01-recommended.json` … `04-unknown.json` (narrator 파일의 바이트 사본)
- Test: `episode/src/test/kotlin/koshei/episode/DiagnosisResponseFixturesTest.kt`
- Modify: `episode/src/test/resources/contract/diagnosis/README.md`

- [ ] **Step 1: 넷을 바이트 그대로 복사한다** (`cp`, 원본은 읽기만). 복사 뒤 `sha256sum` 이 아래와 같아야 한다 — 다르면 narrator 가
  그 사이 고친 것이니 멈추고 보고한다:

```
f999cb72e955c4705030650a8c74c4ad237982937b49f076ce17034be9364297  01-recommended.json
bfab4031687c22c3c2f18b7db97d6b18b883722a2b902c5300557a8205a2ffc0  02-no-grounds.json
3fd2063542c32a0912ac2d6c3f98f9c3c69b53a969623d80a75d12e7904de280  03-out-of-candidates.json
b79bd06141536e5dbea249ffb6b9d5885dcaa1e01634585fb65d6af909a0053a  04-unknown.json
```

- [ ] **Step 2: 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/DiagnosisResponseFixturesTest.kt`:

```kotlin
package koshei.episode

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * narrator's four response fixtures (contract §8; narrator owns them, koshei keeps byte copies) read and judged against
 * koshei's own request fixtures. If narrator changes a response, recopy it; if koshei changes a request, narrator
 * rebuilds the response.
 */
class DiagnosisResponseFixturesTest {
    private fun dir(): File {
        val root = System.getProperty("koshei.contractFixturesDir")
        assertTrue(!root.isNullOrBlank(), "system property koshei.contractFixturesDir is not set")
        return File(root)
    }

    // Same inputs as DiagnosisRequestFixturesTest, which pins these requests byte for byte against requests/.
    private val inputs = mapOf(
        "01-recommended.json" to (listOf("incident-1") to listOf("search-1")),
        "02-no-grounds.json" to (emptyList<String>() to listOf("search-2")),
        "03-out-of-candidates.json" to (emptyList<String>() to listOf("search-4")),
        "04-unknown.json" to (listOf("incident-6") to emptyList<String>()),
    )

    private fun verdict(name: String): DiagnosisVerdict {
        val (incidents, searches) = inputs.getValue(name)
        val request = diagnosisRequest("ep:fixture-" + name.removeSuffix(".json"), 1, PicassoRun1.snapshot(incidents, searches))
        val response = parseDiagnosisResponse(mapper.readTree(File(dir(), "responses/$name")))
        assertIs<ResponseParse.Valid>(response, "$name does not read: $response")
        return judgeDiagnosis(request, response)
    }

    @Test fun `01 recommends the found remedy`() {
        val v = verdict("01-recommended.json")
        assertIs<DiagnosisVerdict.Proposed>(v)
        assertEquals(CandidateKind.APPROVE_REMEDY, v.candidate.kind)
        assertTrue(v.clean)
    }

    @Test fun `02 has no grounds`() {
        val v = verdict("02-no-grounds.json")
        assertIs<DiagnosisVerdict.Escalate>(v)
        assertEquals(DiagnosisEscalation.NO_GROUNDS, v.reason)
    }

    @Test fun `03 picks outside the candidates`() {
        val v = verdict("03-out-of-candidates.json")
        assertIs<DiagnosisVerdict.Escalate>(v)
        assertEquals(DiagnosisEscalation.OUT_OF_CANDIDATES, v.reason)
        assertEquals("SEQ-IN-05.BIN-A 로 옮겨 집는다", v.picked)
    }

    @Test fun `04 recommends the physical check under the unknown`() {
        val v = verdict("04-unknown.json")
        assertIs<DiagnosisVerdict.Proposed>(v)
        assertEquals("OPERATOR_DECISION:exec-8:RACK-204.S06:CONFIRM_DONE", v.candidate.candidateId)
    }
}
```

- [ ] **Step 3: 돌린다**

Run: `./gradlew :episode:test --tests 'koshei.episode.DiagnosisResponseFixturesTest'`
Expected: `BUILD SUCCESSFUL` (4 tests). 하나라도 FAIL 이면 **어느 쪽 코드를 고칠지 정하기 전에** 멈추고 보고한다 — 계약 해석이 두
저장소에서 어긋난 것이고, 그 결정은 narrator 와 함께 한다.

- [ ] **Step 4: README 에 적는다**

`contract/diagnosis/README.md` 의 `- Responses are **owned by narrator** and live in the narrator repo.` 줄을 **바꾼다**(뒤에 더하지 않는다):

```markdown
- `responses/` — **owned by narrator**; byte copies of narrator's four response fixtures (narrator
  `tests/fixtures/contract/diagnosis/responses/`, copied 2026-09-30 before narrator committed them; sha256 in plan B2b-1),
  checked by `DiagnosisResponseFixturesTest` against the requests above. Recopy when narrator changes them.
```

- [ ] **Step 5: Commit**

```bash
git add episode/src/test/resources/contract/diagnosis episode/src/test/kotlin/koshei/episode/DiagnosisResponseFixturesTest.kt
git commit -m "test(episode): judge narrator's four response fixtures against koshei's requests" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
