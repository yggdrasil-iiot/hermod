# 계획 B2b-2c — 확인 불가 항목의 사람 확인과 속성 시험 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사람이 에피소드의 `unknowns` 항목 하나를 현장에서 판정하는 `confirm`(설계 §7.2 둘째 경우, §9.1)을 넣고, 설계 §17 의 무작위 사건 열 속성 시험으로 전이 함수 전체의 고정 규칙을 확인한다.

**Architecture:** 판정은 상태에 `UnknownConfirmation(subject, what, at, coversSymptoms)` 로 쌓는다. `currentUnknowns()` 는 사건 줄마다 확인 불가를 끌어내고, 판정 **전에 도착한** 줄이 끌어낸 것만 뺀다. 판정 뒤에 도착한 줄이 다시 모름을 말하면 그 항목은 되살아난다(§9.1). 도착 순서로 가르고 시각으로 가르지 않는다 — 사건 줄의 `observedAt` 은 감시자의 시계이고 판정 시각은 워크플로의 시계라 둘을 견주면 감시자 시계가 늦을 때 새 관측이 판정에 덮여 실행이 풀린다. 진단 요청도 같은 목록을 쓴다 — 공개 `diagnosisRequest` 는 그대로 두고, 이미 정리한 목록을 받는 내부 함수를 하나 더 둔다. 속성 시험은 그럴듯한 사건과 잡음(낡은 타이머 · 토큰, 다른 시도의 제안, 겹친 통보, 정책 뒤집기, 인수)을 섞은 무작위 열을 씨앗 1000개로 돌린다. 그리고 매 단계 고정 규칙을, 끝에는 종료를 본다.

**Tech Stack:** Kotlin 2.2 / JDK 21, Jackson databind 2.17, JUnit 5 / kotlin-test, `kotlin.random.Random`(씨앗 고정 — 결정적)

**작업 위치:** worktree `C:\Users\Eisen\Desktop\Labs\[projects] koshchei\koshei-episode-core`, 브랜치 `feat/episode-core` (지금 머리 `a6feb71`, 시험 277개). 원래 체크아웃(`koshei/`)은 건드리지 않는다. 파일은 Write/Edit 로만 쓴다(heredoc 금지). bare `git stash` 금지.

**커밋 규칙:** 모든 커밋은 `git commit -m "<제목>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`.

**범위 밖:** Temporal 구동부(B3), 실물 picasso · 담는 쪽(C), `SAGA_ACTION`(D).

---

## 설계에서 코드로 옮길 때 정한 것

| 설계 | 이 계획의 코드 |
|---|---|
| §7.2 `confirm(subject, result)` 둘째 경우 — `unknowns` 의 항목 | `ConfirmedUnknown(subject, what, by, note)`. `what` 까지 맞아야 받는다(같은 단위에 다른 종류의 모름이 있을 수 있다). `note` 는 감사 기록에만 간다 |
| §9.1 「판정은 판정 시각 이후의 새 관측이 다시 모름을 말하기 전까지 유효하다」 | 「이후」를 **도착 순서**로 읽는다: 판정은 그때까지 도착한 줄(`coversSymptoms` = 판정 때의 증상 수)만 덮고, 그 뒤에 도착한 줄은 `observedAt` 이 무엇이든 덮지 않는다. 두 시계(감시자 · 워크플로)를 견주지 않는다는 §12 의 규칙과 같다. 틀려도 안전한 쪽(모름이 남는 쪽)으로 틀린다 |
| §5.2 `*` 행 「(UNKNOWN 상태가 아님) → unknowns 에서 뺀다」 | ESCALATED · 종료를 뺀 모든 상태에서 받는다. ESCALATED 에서는 에이전트가 행동하지 않으므로 값으로 거절한다(`REFUSED_SUBJECT_NOT_UNKNOWN`) |
| 후보 자기의 OUTCOME 항목(`{candidateId}`, §9.4) | 여기서 받지 않는다. 그것은 실행 결과 확인(`ConfirmedOutcome`)이 시도를 끝내면서 지운다. 여기서 지우면 UNKNOWN(OUTCOME)에 머문 채 항목만 사라진다 |
| §17 「무작위 사건 열 속성 시험」 | 씨앗 1000개 × 사건 60개. 설계가 든 넷(UNKNOWN 중 Dispatch 0 · 같은 열쇠 ≤ 1 · Record 없이 Dispatch 0 · 유한 단계 안에 종료)에 더해 기록 번호의 연속, 실행 횟수, 기한 있는 대기, 이력 여덟 칸, ESCALATED · 종료에서 에이전트 명령 0, Update 마다 답, 겹친 `eventId` 무시를 본다. 「모름」은 시험이 **따로 계산한 목록**(사건 줄 + 받아들인 판정 + 에피소드 항목)으로 보고, 코드의 `currentUnknowns()` 가 그것과 같은지도 매 단계 본다 — 코드를 그 자신으로 재지 않게. 헛돌지 않게 13개 상태를 다 지나고 실행이 100번 넘게 나는지도 본다 |

---

## 파일 구조

| 파일 | 할 일 | 책임 |
|---|---|---|
| `episode/src/main/kotlin/koshei/episode/EpisodeEvents.kt` | 수정 | `ConfirmedUnknown` |
| `episode/src/main/kotlin/koshei/episode/EpisodeState.kt` | 수정 | `UnknownConfirmation`, `confirmations`, `currentUnknowns` |
| `episode/src/main/kotlin/koshei/episode/DiagnosisRequests.kt` | 수정 | 정리된 목록을 받는 내부 함수 |
| `episode/src/main/kotlin/koshei/episode/Diagnosing.kt` | 수정 | 진단 요청이 `currentUnknowns()` 를 쓴다 |
| `episode/src/main/kotlin/koshei/episode/Confirmations.kt` | 생성 | `confirmUnknown` |
| `episode/src/main/kotlin/koshei/episode/Transition.kt` | 수정 | 연결, 답 · 승인자 |
| `episode/src/test/kotlin/koshei/episode/UnknownConfirmationTest.kt` | 생성 | Task 1 |
| `episode/src/test/kotlin/koshei/episode/EpisodeFuzzer.kt` | 생성 | Task 2 무작위 사건 |
| `episode/src/test/kotlin/koshei/episode/EpisodePropertiesTest.kt` | 생성 | Task 2 |
| `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md` | 수정 | Task 3 |

---

## Chunk 1: 사람 확인과 속성 시험

### Task 1: 확인 불가 항목의 사람 확인

**Files:**
- Modify: `episode/src/main/kotlin/koshei/episode/EpisodeEvents.kt`
- Modify: `episode/src/main/kotlin/koshei/episode/EpisodeState.kt`
- Modify: `episode/src/main/kotlin/koshei/episode/DiagnosisRequests.kt`
- Modify: `episode/src/main/kotlin/koshei/episode/Diagnosing.kt`
- Modify: `episode/src/main/kotlin/koshei/episode/Transition.kt`
- Create: `episode/src/main/kotlin/koshei/episode/Confirmations.kt`
- Test: `episode/src/test/kotlin/koshei/episode/UnknownConfirmationTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/UnknownConfirmationTest.kt`:

```kotlin
package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode
import koshei.episode.Episodes.OPERATOR
import koshei.episode.Episodes.REMEDY
import koshei.episode.Episodes.T0
import koshei.episode.Episodes.incident
import koshei.episode.Episodes.open
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnknownConfirmationTest {
    /** CORRELATING with the LINK_BROKEN incident-6 joined, observed at T0+1s. */
    private fun withUnknown(): Step = open().on(EpisodeEvent.SymptomJoined(incident("incident-6", T0.plusSeconds(1))), T0.plusSeconds(1))

    private fun Step.confirmFirst(at: Instant, note: String? = "checked the link"): Step {
        val u = state.currentUnknowns().first()
        return on(EpisodeEvent.ConfirmedUnknown(u.subject, u.what, OPERATOR, note), at)
    }

    @Test fun `a person's confirmation removes the unknown and execution candidates come back`() {
        val joined = withUnknown()
        assertEquals(listOf(UnknownWhat.LINK_BROKEN), joined.state.currentUnknowns().map { it.what })
        val confirmed = joined.confirmFirst(T0.plusSeconds(2))
        assertEquals(Reply.ACCEPTED, confirmed.reply)
        assertTrue(confirmed.state.currentUnknowns().isEmpty())
        val diagnosing = confirmed.expireState()
        assertTrue(diagnosing.request.unknowns.isEmpty())
        assertTrue(REMEDY in diagnosing.request.candidates.map { it.candidateId })
        assertTrue(diagnosing.request.candidates.any { it.candidateId.endsWith(":REWORK") })
    }

    @Test fun `a line that arrives after the confirmation brings the unknown back, whatever its clock says`() {
        val confirmed = withUnknown().confirmFirst(T0.plusSeconds(2))
        val again = mapper.readTree(PicassoRun1.incident("incident-6")) as ObjectNode
        again.put("digest", "digest-later")
        // observed (by the watcher's clock) BEFORE the confirmation, but it arrived after it
        val later = confirmed.on(
            EpisodeEvent.SymptomJoined(Symptom("incident:again", SymptomKind.INCIDENT, again, T0.plusMillis(1500))),
            T0.plusSeconds(3),
        )
        assertEquals(listOf(UnknownWhat.LINK_BROKEN), later.state.currentUnknowns().map { it.what })
    }

    @Test fun `confirming one kind leaves another kind on the same unit unknown`() {
        val line = mapper.readTree(PicassoRun1.incident("incident-6")) as ObjectNode
        (line.get("observation") as ObjectNode).putNull("progressObservable")
        val joined = open().on(EpisodeEvent.SymptomJoined(Symptom("incident:two-kinds", SymptomKind.INCIDENT, line, T0)), T0.plusSeconds(1))
        assertEquals(listOf(UnknownWhat.LINK_BROKEN, UnknownWhat.PROGRESS_UNOBSERVED), joined.state.currentUnknowns().map { it.what })
        val link = joined.state.currentUnknowns().first()
        val s = joined.on(EpisodeEvent.ConfirmedUnknown(link.subject, UnknownWhat.LINK_BROKEN, OPERATOR, null), T0.plusSeconds(2))
        assertEquals(listOf(UnknownWhat.PROGRESS_UNOBSERVED), s.state.currentUnknowns().map { it.what })
        val diagnosing = s.expireState()
        assertTrue(REMEDY !in diagnosing.request.candidates.map { it.candidateId })   // still withheld
    }

    @Test fun `only what is unknown now can be confirmed, and not once escalated`() {
        val nothing = open().on(EpisodeEvent.ConfirmedUnknown(mapOf("unitId" to "u-9"), UnknownWhat.OUTCOME, OPERATOR, null), T0.plusSeconds(1))
        assertEquals(Reply.REFUSED_SUBJECT_NOT_UNKNOWN, nothing.reply)
        val escalated = withUnknown().on(EpisodeEvent.TakenOver(OPERATOR), T0.plusSeconds(2))
        val u = escalated.state.currentUnknowns().first()
        val late = escalated.on(EpisodeEvent.ConfirmedUnknown(u.subject, u.what, OPERATOR, null), T0.plusSeconds(3))
        assertEquals(Reply.REFUSED_SUBJECT_NOT_UNKNOWN, late.reply)
        assertEquals(1, late.state.currentUnknowns().size)
    }

    @Test fun `the candidate's own outcome is confirmed through the outcome check, not here`() {
        val u = dispatched().returned(DispatchResult.Answer(PicassoAnswers.refused("NO_PROPOSAL")))
        val own = u.state.episodeUnknowns.single()
        val s = u.on(EpisodeEvent.ConfirmedUnknown(own.subject, own.what, OPERATOR, null), u.state.enteredAt.plusSeconds(5))
        assertEquals(Reply.REFUSED_SUBJECT_NOT_UNKNOWN, s.reply)
        assertEquals(1, s.state.episodeUnknowns.size)
    }

    @Test fun `a unit a JobResponse left in doubt can be confirmed by a person`() {
        val u = dispatched().returned(DispatchResult.Answer(PicassoAnswers.approved()))
            .let { it.on(EpisodeEvent.EvidenceArrived(jobResponse("jr-1", inDoubt = listOf("u-1"))), it.state.enteredAt.plusSeconds(5)) }
        assertEquals(Phase.UNKNOWN_OUTCOME, u.state.phase)
        val unit = u.state.episodeUnknowns.single { it.subject["unitId"] == "u-1" }
        val s = u.on(EpisodeEvent.ConfirmedUnknown(unit.subject, unit.what, OPERATOR, "on site"), u.state.enteredAt.plusSeconds(10))
        assertEquals(Reply.ACCEPTED, s.reply)
        assertTrue(s.state.episodeUnknowns.none { it.subject["unitId"] == "u-1" })
        assertEquals(Phase.UNKNOWN_OUTCOME, s.state.phase)   // the attempt's own outcome is still the outcome check's to decide
        val record = s.records(RecordKind.CONFIRMATION).single().entry.payload
        assertEquals("UNKNOWN", record.get("kind").textValue())
        assertEquals("op-1", record.get("by").textValue())
        assertEquals("on site", record.get("note").textValue())
        assertEquals("u-1", record.get("subject").get("unitId").textValue())
    }

    @Test fun `a confirmation lets the gate pass`() {
        val revalidating = open().expireState().answer()
        val at = revalidating.state.enteredAt
        val joined = revalidating.on(EpisodeEvent.SymptomJoined(incident("incident-6", at)), at.plusMillis(100))
        val confirmed = joined.confirmFirst(at.plusMillis(200))
        assertEquals(Phase.DISPATCH_PENDING, confirmed.revalidated(TriState.TRUE, at.plusSeconds(1)).state.phase)
    }
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew --no-daemon :episode:test --tests 'koshei.episode.UnknownConfirmationTest'`
Expected: 컴파일 실패 — `Unresolved reference 'ConfirmedUnknown'`.

- [ ] **Step 3: `EpisodeEvents.kt` — 사건 하나**

```kotlin
    data class ConfirmedOutcome(val candidateId: String, val proposalId: String, val done: Boolean, val by: Approver) : EpisodeEvent
}
```

를

```kotlin
    data class ConfirmedOutcome(val candidateId: String, val proposalId: String, val done: Boolean, val by: Approver) : EpisodeEvent

    /**
     * `confirm` Update on an item of the episode's `unknowns` (design §7.2 second case, §9.1): the person found out on
     * site what the system could not observe. Valid until a later observation says unknown again. [note] is free text
     * for the audit record only.
     */
    data class ConfirmedUnknown(val subject: Map<String, String?>, val what: UnknownWhat, val by: Approver, val note: String?) : EpisodeEvent
}
```

로.

- [ ] **Step 4: `EpisodeState.kt` — 판정과 `currentUnknowns`**

`/** The attempt in progress (design §9.4). Closed attempts live in [EpisodeState.history]. */` 줄 **바로 위에** 넣는다:

```kotlin
/**
 * A person's confirmation of one unknown (design §9.1). It covers the lines that had arrived when it was given — the
 * first [coversSymptoms] of `EpisodeState.symptoms` — and none that arrive later, whatever their `observedAt` says: the
 * watcher's clock and the workflow's are never compared (§12). [at] is kept for the record.
 */
data class UnknownConfirmation(val subject: Map<String, String?>, val what: UnknownWhat, val at: Instant, val coversSymptoms: Int)

```

```kotlin
    val usedExecutionIds: Set<String> = emptySet(),
) {
```

를

```kotlin
    val usedExecutionIds: Set<String> = emptySet(),
    /** A person's confirmations of unknowns (design §7.2, §9.1). */
    val confirmations: List<UnknownConfirmation> = emptyList(),
) {
```

로.

```kotlin
    /** Unknowns right now (design §9.1): from every symptom so far, plus the episode's own. A person's confirmations: plan B2b-2c. */
    internal fun currentUnknowns(): List<Unknown> = deriveUnknowns(snapshot()) + episodeUnknowns
```

를

```kotlin
    /**
     * Unknowns right now (design §9.1): those each incident line derives, minus what a person confirmed after that line
     * had arrived, plus the episode's own. Without confirmations this equals `deriveUnknowns(snapshot()) +
     * episodeUnknowns`, in the same order. Throws on a malformed manifest; callers fail safe.
     */
    internal fun currentUnknowns(): List<Unknown> {
        val manifest = mapper.readTree(manifestJson) as? ObjectNode ?: throw IllegalArgumentException("manifest is not a JSON object")
        val derived = symptoms.withIndex().filter { it.value.kind == SymptomKind.INCIDENT }.flatMap { (index, s) ->
            deriveUnknowns(Snapshot(manifest, listOf(s.line), emptyList())).filterNot { u ->
                confirmations.any { it.subject == u.subject && it.what == u.what && index < it.coversSymptoms }
            }
        }
        return derived + episodeUnknowns
    }
```

로.

- [ ] **Step 5: `DiagnosisRequests.kt` — 정리된 목록을 받는 내부 함수**

```kotlin
): DiagnosisRequest {
    require(episodeId.isNotBlank()) { "episodeId must not be blank" }
    require(attempt >= 1) { "attempt starts at 1, was $attempt" }
    val unknowns = deriveUnknowns(snapshot) + episodeUnknowns
    val candidates = projectCandidates(snapshot, unknowns)
    return DiagnosisRequest(
        episodeId, attempt, snapshot, unknowns, candidates, candidatesVersion(candidates),
        history.map { it.deepCopy() },
    )
}
```

를

```kotlin
): DiagnosisRequest = diagnosisRequestWithUnknowns(episodeId, attempt, snapshot, deriveUnknowns(snapshot) + episodeUnknowns, history)

/**
 * The same request with [unknowns] already final — derived, the episode's own, minus a person's confirmations (the
 * transition function's `currentUnknowns()`). The projection withholds execution candidates from exactly this list.
 */
internal fun diagnosisRequestWithUnknowns(
    episodeId: String,
    attempt: Int,
    snapshot: Snapshot,
    unknowns: List<Unknown>,
    history: List<ObjectNode>,
): DiagnosisRequest {
    require(episodeId.isNotBlank()) { "episodeId must not be blank" }
    require(attempt >= 1) { "attempt starts at 1, was $attempt" }
    val candidates = projectCandidates(snapshot, unknowns)
    return DiagnosisRequest(
        episodeId, attempt, snapshot, unknowns, candidates, candidatesVersion(candidates),
        history.map { it.deepCopy() },
    )
}
```

로.

- [ ] **Step 6: `Diagnosing.kt` — 진단 요청이 판정을 반영한다**

```kotlin
        diagnosisRequest(state.instanceId, number, snapshot, episodeUnknowns = state.episodeUnknowns, history = state.history.map { it.toJson() })
```

를

```kotlin
        diagnosisRequestWithUnknowns(state.instanceId, number, snapshot, state.currentUnknowns(), state.history.map { it.toJson() })
```

로.

- [ ] **Step 7: `Confirmations.kt` 를 쓴다**

```kotlin
package koshei.episode

import java.time.Instant

/**
 * A person's confirmation of one item of the episode's `unknowns` (design §7.2, §9.1), in any phase but ESCALATED.
 * The candidate's own OUTCOME item is not confirmed here: the outcome check ([EpisodeEvent.ConfirmedOutcome]) decides
 * the attempt and clears it together — clearing it here would leave UNKNOWN(OUTCOME) waiting with nothing unknown.
 */
internal fun Out.confirmUnknown(event: EpisodeEvent.ConfirmedUnknown, now: Instant) {
    if ("candidateId" in event.subject) return ignore(event, "the candidate's own outcome is confirmed through its outcome")
    val current = try { state.currentUnknowns() } catch (e: Exception) { emptyList() }
    if (current.none { it.subject == event.subject && it.what == event.what }) return ignore(event, "not unknown now")
    reply = Reply.ACCEPTED
    state = state.copy(
        confirmations = state.confirmations + UnknownConfirmation(LinkedHashMap(event.subject), event.what, now, state.symptoms.size),
        episodeUnknowns = state.episodeUnknowns.filterNot { it.subject == event.subject && it.what == event.what },
    )
    record(RecordKind.CONFIRMATION) {
        put("kind", "UNKNOWN")
        putObject("subject").apply { event.subject.forEach { (k, v) -> put(k, v) } }
        put("what", event.what.name)
        put("by", event.by.id)
        put("assurance", event.by.assurance.name)
        put("note", event.note)
    }
}
```

- [ ] **Step 8: `Transition.kt` — 연결과 답**

```kotlin
        out.state.phase == Phase.ESCALATED -> out.onEscalated(event, now)
```

를

```kotlin
        event is EpisodeEvent.ConfirmedUnknown && out.state.phase != Phase.ESCALATED -> out.confirmUnknown(event, now)
        out.state.phase == Phase.ESCALATED -> out.onEscalated(event, now)
```

로.

```kotlin
    is EpisodeEvent.ConfirmedOutcome -> by
    else -> null
}
```

를

```kotlin
    is EpisodeEvent.ConfirmedOutcome -> by
    is EpisodeEvent.ConfirmedUnknown -> by
    else -> null
}
```

로.

```kotlin
        this is EpisodeEvent.TakenOver || this is EpisodeEvent.Closed || this is EpisodeEvent.ConfirmedOutcome
```

를

```kotlin
        this is EpisodeEvent.TakenOver || this is EpisodeEvent.Closed || this is EpisodeEvent.ConfirmedOutcome ||
        this is EpisodeEvent.ConfirmedUnknown
```

로.

```kotlin
    is EpisodeEvent.ConfirmedPrecondition, is EpisodeEvent.ConfirmedOutcome -> Reply.REFUSED_SUBJECT_NOT_UNKNOWN
```

를

```kotlin
    is EpisodeEvent.ConfirmedPrecondition, is EpisodeEvent.ConfirmedOutcome, is EpisodeEvent.ConfirmedUnknown -> Reply.REFUSED_SUBJECT_NOT_UNKNOWN
```

로.

- [ ] **Step 9: 통과를 본다**

Run: `./gradlew --no-daemon :episode:test --rerun`
Expected: `BUILD SUCCESSFUL` — `UnknownConfirmationTest` 7, 모듈 전체 277 + 7 = 284. 계약 예제 시험(`DiagnosisRequestFixturesTest`, `DiagnosisMeasurementFixturesTest`)이 그대로 초록이어야 한다 — 공개 `diagnosisRequest` 의 결과는 바뀌지 않았다. `git status --short -uall episode/src/test/resources` 는 비어 있다.

- [ ] **Step 10: Commit**

```bash
git add episode/src/main/kotlin/koshei/episode episode/src/test/kotlin/koshei/episode/UnknownConfirmationTest.kt
git commit -m "feat(episode): a person confirms an unknown; a later observation brings it back" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: 무작위 사건 열 속성 시험

이 시험은 이미 선 코드의 성질을 확인하는 시험이라 처음부터 초록이어야 한다. 빨갛게 나오면 **고치지 말고 멈춰서** 씨앗과 메시지를 보고한다 — 실제 결함이다. 헛돌지 않는지는 Step 4 의 돌연변이로 확인한다.

**Files:**
- Create: `episode/src/test/kotlin/koshei/episode/EpisodeFuzzer.kt`
- Test: `episode/src/test/kotlin/koshei/episode/EpisodePropertiesTest.kt`

- [ ] **Step 1: 무작위 사건 생성기를 쓴다**

`episode/src/test/kotlin/koshei/episode/EpisodeFuzzer.kt`:

```kotlin
package koshei.episode

import kotlin.random.Random

/**
 * Random but plausible events for one episode (design §17). Most events fit the phase the episode is in, so runs get
 * deep; the rest are noise — stale timers and tokens, another attempt's proposal, duplicated or unlinked reports,
 * policy flips, takeovers — that the machine must survive without breaking a fixed rule. Seeded: every run repeats.
 */
internal class EpisodeFuzzer(seed: Long) {
    private val r = Random(seed)

    fun first(): Symptom = when (r.nextInt(12)) {
        0 -> Episodes.incident("incident-6")
        1, 2 -> Episodes.search("search-4")
        3 -> Episodes.search("search-2")
        4 -> Episodes.search("search-3")
        else -> Episodes.search("search-1")
    }

    fun startPolicy(): PolicyRead = when (r.nextInt(12)) {
        0 -> Episodes.policyV1()
        1 -> PolicyRead.Missing
        else -> Episodes.policy()
    }

    /** Now and then a fresh policy read: valid, v1, rejected, or the layer off. */
    fun read(): PolicyRead? = when (r.nextInt(150)) {
        0 -> Episodes.policyV1()
        1 -> Episodes.policy { put("bogus", 1) }
        2 -> Episodes.policy { put("agentLayerEnabled", false) }
        3 -> Episodes.policy()
        else -> null
    }

    /** Seconds between two events: often quick, sometimes long enough to pass a deadline by time. */
    fun gap(): Long = r.nextLong(0, 120)

    fun next(s: EpisodeState, n: Int): EpisodeEvent = (if (r.nextInt(10) < 6) fitting(s, n) else null) ?: noise(s, n)

    private fun fitting(s: EpisodeState, n: Int): EpisodeEvent? {
        val a = s.attempt
        return when (s.phase) {
            Phase.CORRELATING -> EpisodeEvent.DeadlineExpired(Timer.STATE, s.token)
            Phase.DIAGNOSING -> a?.let {
                if (r.nextInt(8) == 0) EpisodeEvent.DiagnosisFailed(r.nextBoolean(), "fuzz") else EpisodeEvent.DiagnosisReturned(answer(it))
            }
            Phase.AWAITING_APPROVAL -> if (r.nextInt(4) == 0) EpisodeEvent.DeadlineExpired(Timer.STATE, s.token) else a?.let { decide(it, stale = false) }
            Phase.REVALIDATING -> EpisodeEvent.Revalidated(tri(), s.token)
            Phase.UNKNOWN_PRECONDITION -> when (r.nextInt(4)) {
                0 -> EpisodeEvent.DeadlineExpired(Timer.STATE, s.token)
                1 -> EpisodeEvent.DeadlineExpired(Timer.RECHECK, s.token)
                2 -> EpisodeEvent.Revalidated(tri(), s.token)
                else -> a?.let { confirmPrecondition(it, stale = false) }
            }
            Phase.DISPATCH_PENDING -> a?.let {
                when (r.nextInt(6)) {
                    0 -> EpisodeEvent.RecordFailed(it.intentSeq ?: 0, "fuzz")
                    // an unknown that appears while the intent is being recorded: the gate at Recorded must stop it
                    1 -> EpisodeEvent.SymptomJoined(if (r.nextBoolean()) twoKinds(n) else arrival("incident-6", n))
                    else -> EpisodeEvent.Recorded(it.intentSeq ?: 0)
                }
            }
            Phase.DISPATCHED -> if (r.nextInt(4) == 0) a?.let { evidence(it, n) } else EpisodeEvent.DispatchReturned(dispatchResult(), s.token)
            Phase.AWAITING_EVIDENCE, Phase.UNKNOWN_OUTCOME -> when (r.nextInt(4)) {
                0 -> EpisodeEvent.DeadlineExpired(Timer.STATE, s.token)
                1 -> a?.let { evidence(it, n) }
                else -> a?.let { confirmOutcome(it, stale = false) }
            }
            Phase.ESCALATED -> if (r.nextBoolean()) EpisodeEvent.Closed(Episodes.OPERATOR, "fuzz") else EpisodeEvent.DeadlineExpired(Timer.RETENTION, s.token)
            Phase.RESOLVED, Phase.SUPERSEDED, Phase.CLOSED -> null
        }
    }

    private fun noise(s: EpisodeState, n: Int): EpisodeEvent {
        val a = s.attempt
        val fallback = EpisodeEvent.DeadlineExpired(Timer.STATE, s.token - 1)
        return when (r.nextInt(14)) {
            0 -> EpisodeEvent.SymptomJoined(
                if (r.nextBoolean()) (if (r.nextInt(3) == 0) twoKinds(n) else if (r.nextBoolean()) arrival(INCIDENTS.random(r), n) else Episodes.incident(INCIDENTS.random(r)))
                else Episodes.search(SEARCHES.random(r)),
            )
            1 -> EpisodeEvent.DeadlineExpired(listOf(Timer.STATE, Timer.RECHECK, Timer.RETENTION).random(r), s.token - r.nextLong(0, 3))
            2 -> if (r.nextInt(4) == 0) EpisodeEvent.TakenOver(Episodes.OPERATOR) else EpisodeEvent.DeadlineExpired(Timer.RECHECK, s.token)
            3 -> if (r.nextInt(4) == 0) EpisodeEvent.AgentOff else fallback
            4 -> EpisodeEvent.Revalidated(tri(), s.token - r.nextLong(0, 2))
            5 -> EpisodeEvent.DispatchReturned(dispatchResult(), s.token - r.nextLong(0, 2))
            6 -> a?.let { decide(it, stale = r.nextBoolean()) } ?: fallback
            7 -> a?.let { confirmPrecondition(it, stale = r.nextBoolean()) } ?: fallback
            8 -> a?.let { confirmOutcome(it, stale = r.nextBoolean()) } ?: fallback
            9 -> a?.let { evidence(it, n) } ?: fallback
            10 -> EpisodeEvent.Recorded(a?.intentSeq?.let { it + r.nextLong(-1, 2) } ?: r.nextLong(1, 50))
            11 -> a?.let { EpisodeEvent.DiagnosisReturned(answer(it)) } ?: EpisodeEvent.DiagnosisReturned("{")
            12 -> confirmUnknown(s)
            else -> EpisodeEvent.Closed(Episodes.OPERATOR, "noise")
        }
    }

    /** incident-6 with its progress not observed: two kinds of unknown (LINK_BROKEN, PROGRESS_UNOBSERVED) on one unit. */
    private fun twoKinds(n: Int): Symptom = Episodes.incident("incident-6").let {
        val line = it.line
        (line.get("observation") as com.fasterxml.jackson.databind.node.ObjectNode).putNull("progressObservable")
        Symptom("incident:two-kinds@$n", it.kind, line, it.observedAt)
    }

    /** The same incident line arriving again under a fresh eventId — a new observation (design §9.1). */
    private fun arrival(incidentId: String, n: Int): Symptom =
        Episodes.incident(incidentId).let { Symptom("incident:$incidentId@$n", it.kind, it.line, it.observedAt) }

    private fun answer(a: Attempt): String {
        val offered = a.request.candidates.filter { it.kind != CandidateKind.ESCALATE }.map { it.candidateId }
            .ifEmpty { a.request.candidates.map { it.candidateId } }
        return when (r.nextInt(30)) {
            0 -> Responses.forRequest(a.request, "NO_GROUNDS", null).toString()
            1 -> Responses.forRequest(a.request, "UNCITED", null).toString()
            2 -> Responses.forRequest(a.request, "OUT_OF_CANDIDATES", null, picked = "elsewhere").toString()
            3 -> Responses.forRequest(a.request, candidateId = "APPROVE_REMEDY:hum-99:X:pick_place").toString()
            4 -> "{"
            5 -> Responses.forRequest(a.request, candidateId = offered.random(r)) { putArray("citations") }.toString()
            else -> Responses.forRequest(a.request, candidateId = offered.random(r)).toString()
        }
    }

    private fun decide(a: Attempt, stale: Boolean): EpisodeEvent = EpisodeEvent.ApprovalDecided(
        if (stale) "${a.request.episodeId}#0" else a.proposalId, a.request.candidatesVersion,
        r.nextInt(3) != 0, Episodes.OPERATOR, RejectReason.entries.random(r), null,
    )

    private fun confirmPrecondition(a: Attempt, stale: Boolean): EpisodeEvent = EpisodeEvent.ConfirmedPrecondition(
        a.candidate?.candidateId ?: "ESCALATE", if (stale) "${a.request.episodeId}#0" else a.proposalId, r.nextBoolean(), Episodes.OPERATOR,
    )

    private fun confirmOutcome(a: Attempt, stale: Boolean): EpisodeEvent = EpisodeEvent.ConfirmedOutcome(
        a.candidate?.candidateId ?: "ESCALATE", if (stale) "${a.request.episodeId}#0" else a.proposalId, r.nextBoolean(), Episodes.OPERATOR,
    )

    private fun evidence(a: Attempt, n: Int): EpisodeEvent = EpisodeEvent.EvidenceArrived(
        jobResponse(
            "jr-${r.nextInt(n + 2)}",
            executionId = if (r.nextInt(4) == 0) "exec-other" else (a.executionId ?: "exec-1"),
            completed = listOf("u-1"),
            inDoubt = listOf(emptyList<String>(), listOf("u-1"), null).random(r),
            unverified = listOf(emptyList<String>(), listOf("u-2"), null).random(r),
            operatorRequired = listOf(false, true, null).random(r),
        ),
    )

    private fun dispatchResult(): DispatchResult = when (r.nextInt(8)) {
        0, 1, 2 -> DispatchResult.Answer(PicassoAnswers.approved("exec-${r.nextInt(1, 3)}"))
        3 -> DispatchResult.Answer(PicassoAnswers.refused(PICASSO_REFUSALS_V2.toList().random(r)))
        4 -> DispatchResult.Answer(PicassoAnswers.refused("NEW_VALUE"))
        5 -> DispatchResult.Uncertain("fuzz")
        6 -> DispatchResult.PersonTaskIssued
        else -> DispatchResult.Answer(PicassoAnswers.refused("REFUSED_BY_GATE", schema = "3"))
    }

    private fun confirmUnknown(s: EpisodeState): EpisodeEvent {
        val unknowns = try { s.currentUnknowns() } catch (e: Exception) { emptyList() }
        if (unknowns.isEmpty()) return EpisodeEvent.ConfirmedUnknown(mapOf("unitId" to "nobody"), UnknownWhat.OUTCOME, Episodes.OPERATOR, null)
        val u = unknowns.random(r)
        return EpisodeEvent.ConfirmedUnknown(u.subject, u.what, Episodes.OPERATOR, null)
    }

    private fun tri(): TriState = TriState.entries.random(r)

    private companion object {
        val INCIDENTS = listOf("incident-1", "incident-2", "incident-4", "incident-6")
        val SEARCHES = listOf("search-1", "search-2", "search-3", "search-4")
    }
}
```

- [ ] **Step 2: 속성 시험을 쓴다**

`episode/src/test/kotlin/koshei/episode/EpisodePropertiesTest.kt`:

```kotlin
package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * design §17: whatever arrives, in whatever order, the fixed rules hold and every run ends. A failure names its seed;
 * rerun that one seed to reproduce it.
 */
class EpisodePropertiesTest {
    private val seeds = 0L until 1000L
    private val maxEvents = 60

    private class Stats {
        val phases = mutableSetOf<Phase>()
        var dispatches = 0
    }

    @Test fun `random event sequences keep the fixed rules and always end`() {
        for (seed in seeds) {
            try {
                run(seed, Stats())
            } catch (e: AssertionError) {
                throw AssertionError("seed $seed: ${e.message}", e)
            }
        }
    }

    @Test fun `the random runs reach every phase and dispatch often`() {
        val stats = Stats()
        for (seed in seeds) run(seed, stats)
        assertEquals(Phase.entries.toSet(), stats.phases)
        assertTrue(stats.dispatches > 100, "only ${stats.dispatches} dispatches")
    }

    private fun run(seed: Long, stats: Stats) {
        val f = EpisodeFuzzer(seed)
        var now = Episodes.T0
        var step = startEpisode(EpisodeEvent.Detected(Episodes.INSTANCE, PicassoRun1.manifest, f.first()), f.startPolicy(), now)
        val run = Run()
        var lastSeq = check(null, null, step, 0, run, stats)
        var n = 0
        while (n < maxEvents && !step.state.phase.terminal) {
            now = now.plusSeconds(f.gap())
            val before = step.state
            val event = f.next(before, n)
            step = transition(before, event, f.read(), now)
            lastSeq = check(before, event, step, lastSeq, run, stats)
            n++
        }
        // Termination (design §5.1, §17): timers alone end every run, in a few steps.
        var closing = 0
        while (!step.state.phase.terminal) {
            val s = step.state
            val deadline = s.stateDeadline
            val (event, at) = when {
                s.phase == Phase.ESCALATED -> EpisodeEvent.DeadlineExpired(Timer.RETENTION, s.token) to now.plusSeconds(172_800)
                deadline != null -> EpisodeEvent.DeadlineExpired(Timer.STATE, s.token) to maxOf(now, deadline)
                else -> EpisodeEvent.DeadlineExpired(Timer.EPISODE, EPISODE_TOKEN) to maxOf(now, s.episodeDeadline ?: now)
            }
            now = at
            step = transition(s, event, null, now)
            lastSeq = check(s, event, step, lastSeq, run, stats)
            closing++
            assertTrue(closing <= 6, "no end after $closing timers, still ${step.state.phase}")
        }
    }

    /** What the test itself remembers of one run: dispatched keys and counts, and the confirmations it saw accepted. */
    private class Run {
        val keys = mutableSetOf<String>()
        val counts = mutableMapOf<String, Int>()
        val confirmations = mutableListOf<Triple<Map<String, String?>, UnknownWhat, Int>>()
    }

    /**
     * The test's own account of what is unknown (design §9.1), computed without `currentUnknowns()`: each incident line's
     * unknowns, minus a confirmation accepted after that line had arrived, plus the episode's own items.
     */
    private fun oracle(s: EpisodeState, run: Run): List<Unknown> {
        val manifest = mapper.readTree(s.manifestJson) as com.fasterxml.jackson.databind.node.ObjectNode
        val derived = s.symptoms.withIndex().filter { it.value.kind == SymptomKind.INCIDENT }.flatMap { (i, sym) ->
            deriveUnknowns(Snapshot(manifest, listOf(sym.line), emptyList())).filterNot { u ->
                run.confirmations.any { (subject, what, covers) -> subject == u.subject && what == u.what && i < covers }
            }
        }
        return derived + s.episodeUnknowns
    }

    private fun check(before: EpisodeState?, event: EpisodeEvent?, step: Step, lastSeq: Long, run: Run, stats: Stats): Long {
        val s = step.state
        stats.phases += s.phase
        val keys = run.keys
        val counts = run.counts
        if (event is EpisodeEvent.ConfirmedUnknown && step.reply == Reply.ACCEPTED && before != null)
            run.confirmations += Triple(event.subject, event.what, before.symptoms.size)
        val unknownNow = oracle(s, run)
        assertEquals(unknownNow, s.currentUnknowns(), "currentUnknowns differs from the test's account after $event")
        // Every Update gets a value back (design §7.2); a duplicated eventId changes nothing (§6).
        if (event != null && event.isUpdateForTest()) assertNotNull(step.reply, "no reply to $event")
        if (event is EpisodeEvent.SymptomJoined && before != null && event.symptom.eventId in before.seenEventIds)
            assertEquals(before.symptoms, s.symptoms, "a duplicated eventId was joined")
        // Records are numbered 1, 2, 3, … across the run, the intent records included (design §13).
        var seq = lastSeq
        for (c in step.commands) {
            val n = when (c) {
                is Command.Record -> c.seq
                is Command.RecordIntent -> c.seq
                else -> continue
            }
            assertEquals(seq + 1, n, "record seq after $event")
            seq = n
        }
        assertEquals(seq, s.recordSeq, "state recordSeq after $event")
        // Dispatch only from DISPATCH_PENDING on this attempt's Recorded, once per key, never past an unknown (design §6).
        for (d in step.commands.filterIsInstance<Command.Dispatch>()) {
            stats.dispatches++
            assertEquals(Phase.DISPATCH_PENDING, before?.phase, "Dispatch outside DISPATCH_PENDING after $event")
            assertTrue(event is EpisodeEvent.Recorded && event.seq == before?.attempt?.intentSeq, "Dispatch without this attempt's Recorded: $event")
            assertTrue(keys.add(d.intent.idempotencyKey), "second Dispatch for ${d.intent.idempotencyKey}")
            if (d.intent.candidate.executionClass) assertTrue(unknownNow.isEmpty(), "execution Dispatch while unknown: $unknownNow")
            val id = d.intent.candidate.candidateId
            counts[id] = (counts[id] ?: 0) + 1
        }
        assertEquals(counts.toMap(), s.dispatchCounts, "dispatch counts after $event")
        // An intent is recorded only on leaving a precondition phase for DISPATCH_PENDING, never past an unknown.
        for (i in step.commands.filterIsInstance<Command.RecordIntent>()) {
            assertTrue(before?.phase == Phase.REVALIDATING || before?.phase == Phase.UNKNOWN_PRECONDITION, "intent from ${before?.phase}")
            assertEquals(Phase.DISPATCH_PENDING, s.phase)
            if (i.intent.candidate.executionClass) assertTrue(unknownNow.isEmpty(), "execution intent while unknown: $unknownNow")
        }
        // Every wait with a deadline of its own has one (design §5.1).
        if (s.phase in OWN_DEADLINE) assertNotNull(s.stateDeadline, "${s.phase} without a deadline after $event")
        // History entries carry their eight keys (contract §3.4).
        for (h in s.history) assertEquals(HISTORY_KEYS, h.toJson().fieldNames().asSequence().toList())
        // ESCALATED and the end states issue no agent commands.
        if (before != null && (before.phase == Phase.ESCALATED || before.phase.terminal))
            assertTrue(step.commands.none { it is Command.Diagnose || it is Command.Revalidate || it is Command.RecordIntent || it is Command.Dispatch }, "agent command from ${before.phase}")
        return seq
    }

    private fun EpisodeEvent.isUpdateForTest(): Boolean =
        this is EpisodeEvent.ApprovalDecided || this is EpisodeEvent.ConfirmedPrecondition || this is EpisodeEvent.ConfirmedOutcome ||
            this is EpisodeEvent.ConfirmedUnknown || this is EpisodeEvent.TakenOver || this is EpisodeEvent.Closed

    private companion object {
        val OWN_DEADLINE = setOf(Phase.CORRELATING, Phase.AWAITING_APPROVAL, Phase.UNKNOWN_PRECONDITION, Phase.AWAITING_EVIDENCE, Phase.UNKNOWN_OUTCOME)
        val HISTORY_KEYS = listOf("attempt", "candidatesVersion", "diagnosis", "approval", "dispatch", "evidence", "closedAs", "at")
    }
}
```

- [ ] **Step 3: 돌린다**

Run: `./gradlew --no-daemon :episode:test --tests 'koshei.episode.EpisodePropertiesTest'`
Expected: `BUILD SUCCESSFUL`, 2 tests, 둘 합쳐 수 초. 첫째 시험이 빨가면 **멈추고** 씨앗 · 메시지 · 그때까지의 사건을 보고한다. 둘째 시험이 어떤 상태에 못 닿거나 실행이 모자라 빨가면, 속성은 그대로 두고 생성기의 비중(`first` · `read` · `answer` · `fitting`)만 조정한 뒤 무엇을 바꿨는지 보고한다.

- [ ] **Step 4: 헛돌지 않는지 돌연변이로 본다 (커밋하지 않는다)**

하나씩 넣고 `EpisodePropertiesTest` 를 돌려 첫째 시험이 FAIL 하는지 본다. 보고 나서 되돌린다:

1. `Dispatching.kt` 의 `dispatch()` 첫 줄 `if (!passesDispatchGate(policy, now)) return` 을 지운다 → 「execution Dispatch while unknown」 같은 실패.
2. `Dispatching.kt` 의 `onDispatchPending` 에서 `if (event.seq == expected) dispatch(policy, now)` 를 `if (true || event.seq == expected) dispatch(policy, now)` 로 → 「Dispatch without this attempt's Recorded」 또는 「second Dispatch」.
3. `Dispatching.kt` 의 `dispatch()` 에서 `val count = (state.dispatchCounts[id] ?: 0) + 1` 을 `val count = (state.dispatchCounts[id] ?: 0)` 로 → 「dispatch counts」 실패.

4. `EpisodeState.kt` 의 `currentUnknowns()` 에서 `it.subject == u.subject && it.what == u.what && index < it.coversSymptoms` 를 `it.subject == u.subject && index < it.coversSymptoms` 로 → 「currentUnknowns differs」(한 종류의 판정이 다른 종류를 지운다).

넷 다 FAIL 한 것을 확인하고, `git diff --stat` 이 비어 있는 상태로 되돌린다. 넷째가 살아남으면(두 종류가 걸린 줄의 판정이 드물어서) 생성기의 `twoKinds` 비중을 올려 다시 보고, 무엇을 바꿨는지 보고한다 — 속성은 고치지 않는다.

- [ ] **Step 5: 전체를 돌린다**

Run: `./gradlew --no-daemon :episode:test --rerun`
Expected: `BUILD SUCCESSFUL`, 284 + 2 = 286.

- [ ] **Step 6: Commit**

```bash
git add episode/src/test/kotlin/koshei/episode/EpisodeFuzzer.kt episode/src/test/kotlin/koshei/episode/EpisodePropertiesTest.kt
git commit -m "test(episode): random event sequences keep the fixed rules and always end (design §17)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 3: 설계에 적는다

**Files:**
- Modify: `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md`

- [ ] **Step 1: §6 구현 모양**

옛: `` `ConfirmedOutcome(candidateId, proposalId, done, by)`. `Recorded` 를 받으면 ``
새: `` `ConfirmedOutcome(candidateId, proposalId, done, by)`, 확인 불가 항목을 사람이 판정하는 `ConfirmedUnknown(subject, what, by, note)`(계획 B2b-2c, §9.1). `Recorded` 를 받으면 ``

(앞뒤의 공백 하나씩은 마크다운 인용 때문이다. 바꿀 글에는 넣지 않는다.)

- [ ] **Step 2: §17 시험 표**

옛: ``JUnit. 무작위 사건 열 속성 시험: UNKNOWN 중 Dispatch 0, 같은 열쇠 Dispatch ≤ 1, Record 없이 Dispatch 0, 모든 열이 유한 단계 안에 종료 상태 도달 |``
새: ``JUnit. 무작위 사건 열 속성 시험: UNKNOWN 중 Dispatch 0, 같은 열쇠 Dispatch ≤ 1, Record 없이 Dispatch 0, 모든 열이 유한 단계 안에 종료 상태 도달. 계획 B2b-2c 의 `EpisodePropertiesTest`: 씨앗 1000개 × 사건 60개, 기록 번호 연속 · 실행 횟수 · 기한 있는 대기 · 이력 여덟 칸 · ESCALATED 와 종료에서 에이전트 명령 0 · Update 마다 답 · 겹친 `eventId` 무시도 보고, 「모름」은 시험이 따로 계산한 목록과 맞춘다. 13개 상태를 다 지나는지 따로 본다 |``

- [ ] **Step 3: §5.2 `*` 행**

옛: ``| `*` | `ConfirmedPrecondition` · `ConfirmedOutcome` (UNKNOWN 상태가 아님) | (그대로) | 해당 subject 를 에피소드의 `unknowns` 에서 뺀다(§9.1). 사람의 판정으로 기록 |``
새: ``| `*` | `ConfirmedUnknown` — `unknowns` 의 항목(subject · what) | (그대로) | 그 항목을 뺀다(§9.1). 사람의 판정으로 기록. ESCALATED 에서는 받지 않는다(에이전트가 행동하지 않는다). 후보 자기의 OUTCOME 항목은 받지 않는다 — `ConfirmedOutcome` 이 시도를 끝내며 지운다. 그 밖의 상태에서 온 `ConfirmedPrecondition` · `ConfirmedOutcome` 은 값으로 거절한다 |``

- [ ] **Step 4: §9.1 판정 문장 (두 줄 통째로)**

옛:
```
- 사람이 `confirm` 으로 판정한다(어느 비종료 상태에서든, §7.2). 판정은 그 subject 에 대해 판정 시각 이후의 새 관측이 다시 모름을
  말하기 전까지 유효하다. `CONFIRM_DONE` 후보는 "했다"만 말하므로, "안 했다"는 `confirm(NOT_DONE)` 으로 들어온다.
```
새:
```
- 사람이 `confirm`(`ConfirmedUnknown`)으로 판정한다(ESCALATED 와 종료를 뺀 상태에서, §7.2). 판정은 그 subject · 종류에 대해, 판정
  **뒤에 도착한** 사건 줄이 다시 모름을 말하기 전까지 유효하다 — 「뒤」는 도착 순서이고 `observedAt`(감시자의 시계)과 판정 시각(워크플로의
  시계)을 견주지 않는다(§12 와 같은 까닭). `CONFIRM_DONE` 후보는 "했다"만 말하므로, "안 했다"는 `confirm(NOT_DONE)` 으로 들어온다.
```

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md
git commit -m "docs: design records unknown confirmations and the property tests" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
