# 계획 B3c-2 — 에피소드 화면(authoring-ui) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** authoring-ui 에 「Episodes」 탭을 더한다. 운영자는 이 탭에서 다음을 한다(설계 §7.2 · §7.3 · §8.5 · §9.5, B3c 의 `/api/episodes…`).
- 에피소드 목록을 본다.
- 에피소드 상세를 본다: 운영자 카드, 시도 이력, 후보, 기록, 알림.
- 결정 네 가지(decide · confirm · takeover · close)를 보낸다.
- 전체 알림 흐름을 따라간다.

**Architecture:**
- **판단은 서버가 한다.** 화면은 카드와 뷰가 준 칸만 그린다.
  - 결정 버튼을 보일지는 카드의 `decision` 과 뷰의 단계로만 정한다.
  - 거절은 서버의 값(`reply`)을 사람의 말로 옮길 뿐이다.
  - 명제를 화면이 만들지도, 보내지도 않는다(§8.3 — 서버가 자기 카드에서 채운다).
  - `cause` 와, 거절되거나 낡은 narrator 답은 어디에도 그리지 않는다(§9.5). 기록을 펼쳐도 마찬가지다.
- **이 저장소의 방식을 따른다.**
  - 순수 함수(`episodes.ts`, `episodeApi.ts` 의 `describeFailure`)는 vitest(node 환경)로 시험한다.
  - 부품은 Console 탭(`views/console/`)과 같은 모양으로 짠다: 폴링 `useEffect` + `alive` 가드, `.banner` · `.chip` · `.btn` 클래스.
  - 부품 시험 라이브러리는 들이지 않는다.
  - 화면 전체는 Playwright e2e 로 본다. `page.route` 로 `/api/episodes…` 를 흉내 내므로 백엔드 없이 Vite 개발 서버만으로 돈다(`e2e/fsm.spec.ts` 방식).
- **운영자 이름**은 탭 위의 입력칸에 적는다.
  - 브라우저 `localStorage` 에 기억한다(이 화면을 쓰는 사람만의 편의, `try/catch`).
  - 모든 결정에 `X-Koshei-Operator` 머리글로 싣는다.
  - HTTP 머리글이라 ASCII 만 받는다. 비었거나, 128자를 넘거나, ASCII 가 아니면 결정 버튼을 막는다.
- **실패를 운영자의 말로 옮긴다.**

  | 실패 | 화면에 보일 것 |
  |---|---|
  | 409 `EPISODE_MOVED` | 「다른 에피소드로 넘어감」과 그 에피소드로 옮겨 가는 버튼 |
  | 409 `EPISODE_ENDED` | 「끝남」 |
  | 504 `EPISODE_TIMEOUT` / `outcome: UNKNOWN` | 「결과 모름 — 상세를 다시 읽은 뒤 판단」(§7.2 구현(B3c)) |
  | POST 가 응답 없이 실패 | 504 와 같이 「결과 모름」 |
  | 400 · 413 · 503 | 서버의 `error` 글자 |

- **알림 흐름:** `after = 마지막 id − 50` 로 겹쳐 읽고 id 로 중복을 지운다(§8.5 구현(B3c)). 한 번에 짧은 쪽이 나올 때까지 쪽을 넘겨 따라잡는다.

**Tech Stack:** React 18.3 · TypeScript 5.9(strict, `noUnusedLocals/Parameters`) · Vite 5.4 · vitest 2.1(node) · Playwright 1.61
- 이 기계의 Playwright 브라우저 캐시에는 1.61 이 요구하는 chromium(revision 1228)이 없다.
- 그래서 이 계획의 e2e 는 설치된 Google Chrome 을 쓴다(`test.use({ channel: "chrome" })`).
- `--no-deps` 로 warmup 프로젝트를 건너뛴다.
- `npx playwright install` 은 하지 않는다.

**작업 위치:** worktree `C:\Users\Eisen\Desktop\Labs\[projects] koshchei\koshei-episode-core`, 브랜치 `feat/episode-core`(머리 `292e7fd`).
- `npm` · `npx` 명령은 `authoring-ui/` 에서 돈다.
- **`git` 명령은 worktree 뿌리에서** 돈다. `git add authoring-ui/…` 경로가 뿌리 기준이다.
- `node_modules` 가 아직 없다. Task 1 에서 `npm ci` 로 깐다.
- 원래 체크아웃(`koshei/`)은 건드리지 않는다. 그쪽의 `node_modules` 를 복사하지 않는다.
- 파일은 Write/Edit 로만 쓴다.
- bare `git stash` 금지. 푸시하지 않는다.

**커밋 규칙:**
- 모든 커밋은 `git commit -m "<제목>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"` 로 한다.
- `git add` 는 경로를 밝혀서 한다.
- `package-lock.json` 은 바뀌면 안 된다.

**범위 밖:**
- **인증:** 운영자 이름은 스스로 밝힌 값이다(`SELF_ASSERTED`, §14). 화면도 그렇다고 보인다.
- **사가 실행(Console 탭)과의 연결:** 계획 D.
- **실시간 밀어 주기(WebSocket · SSE):** 쓰지 않고 폴링으로 한다.
- **실제 백엔드를 띄운 e2e:** `scripts/run-e2e.sh` 에 끼우지 않는다. 이 계획의 e2e 는 흉내 낸 API 로만 돈다.
- **에이전트 끄기(`agentOff`):** 제어면에 경로가 없다(CLI 몫).

---

## 서버 응답의 모양 (B3c, 코드 기준)

### 조회

**`GET /api/episodes?limit=`(1..500)**

```
[{instanceId, workflowId, openedAt, lastAt, lastPhase: string|null, notices: number, recordLag: boolean}]
```

시각(`at`)은 ISO 문자열이다(마이크로초가 붙을 수 있다).

**`GET /api/episodes/{workflowId}/{run}`**

```
{instanceId, view: EpisodeView|null, viewError: string|null, card: OperatorCard|null,
 events: [{seq, kind, payload, at}], notices: [{id, at, notice}]}
```

- 경로 조각은 `encodeURIComponent` 로 감싼다. `:` 는 `%3A` 가 되고, 서버가 되돌린다.
- `EpisodeView` 의 칸:
  - `instanceId`, `phase`, `escalationReason`, `escalationDetail`
  - `attempt`, `proposalId`, `candidateId`, `candidatesVersion`
  - `stateDeadlineMillis`, `episodeDeadlineMillis`
  - `historyJson`(§9.4 시도 이력 배열 — `cause` 없음), `unknownsJson`
  - `symptoms`
  - `candidateJson`, `candidatesJson`, `diagnosisJson`(화면은 그리지 않는다)
  - `joinedAfterSnapshot`
- `OperatorCard` 의 칸:
  - 사실: `unknowns[{subject, what, since, source}]`, `symptoms`, `factsBehind`
  - 제안: `proposal{candidateId, kind, ref}`
  - 진단: `rationale`, `rationaleMissing`, `guidance[{label, text}]`, `citations`, `uncitedSentences`
  - 확인 못 한 주장: `unverifiedCitations[{text, foundIn}]`, `unlocatedCitations`, `unverifiedNumbers`, `unlocatedNumbers`
  - 진단의 출처: `responseUnreadable`, `diagnosisFromRecord`, `diagnosisRefused`, `diagnosisRefusedDetail`, `diagnosisAttempt`
  - 전제: `precondition`, `joinedAfterSnapshot`, `joinedAfterSnapshotCount`
  - 결정: `decision{update: "decide"|"confirm", proposalId, candidatesVersion, deadlineMillis}`, `identityAssurance`

**`GET /api/episodes/notices?after=&limit=`(1..500)**

```
[{id, instanceId, at, notice}]
```

`notice` 의 모양은 둘이다.
- `{kind, phase, reason, detail}` — `kind` ∈ APPROVAL_NEEDED · CONFIRM_PRECONDITION · ESCALATED · POLICY_REJECTED · CONFIRM_OUTCOME · RECORD_LAG
- `{kind: "PERSON_TASK", idempotencyKey, intent}`

### 결정

| 경로 | 본문 |
|---|---|
| `POST …/decide` | `{proposalId, sawCandidatesVersion, approve, reason?, note?}` — `reason` ∈ WRONG_TARGET · WRONG_ACTION · PRECONDITION_NOT_MET · NOT_NOW · OTHER |
| `POST …/confirm` | `{kind: "PRECONDITION"\|"OUTCOME"\|"UNKNOWN", candidateId?, proposalId?, holds, subject?, what?, note?}` |
| `POST …/takeover` | 본문 없음 |
| `POST …/close` | `{outcome}` |

- confirm 의 `holds` 는 늘 필수다.
- 모르는 칸(`operatorId`, `proposition` 포함)은 400 이다.
- 코어는 다음 확인을 늘 값으로 거절한다(REFUSED_SUBJECT_NOT_UNKNOWN). 화면은 이것들을 내지 않는다.
  - 후보 자신의 OUTCOME 항목(`subject` 에 `candidateId`)의 UNKNOWN 확인
  - 값이 모두 null 인 `subject`
- `confirm(OUTCOME)` 은 `AWAITING_EVIDENCE` 와 `UNKNOWN_OUTCOME` 에서 받는다(`Transition.kt` · `Outcome.kt`).
- 결정의 응답:

  | 상태 | 본문 |
  |---|---|
  | 200 | `{reply}` — ACCEPTED · REFUSED_NOT_AWAITING · REFUSED_STALE · REFUSED_SUBJECT_NOT_UNKNOWN · REFUSED_NOT_ESCALATED · REFUSED_CLOSED · REFUSED_NO_PROPOSITION |
  | 400 · 404 · 413 · 503 | `{error}` |
  | 409 | `{error: "EPISODE_MOVED", currentInstanceId}` 또는 `{error: "EPISODE_ENDED"}` |
  | 504 | `{error: "EPISODE_TIMEOUT", outcome: "UNKNOWN", instanceId}` |

---

## 파일 구조

| 파일 | 할 일 | 책임 |
|---|---|---|
| `authoring-ui/src/views/episodes/episodeTypes.ts` | 생성 | 서버 응답의 형 |
| `authoring-ui/src/views/episodes/episodes.ts` | 생성 | 순수 함수 |
| `authoring-ui/src/views/episodes/episodes.test.ts` | 생성 | vitest |
| `authoring-ui/src/views/episodes/episodeApi.ts` | 생성 | fetch 감싸기, `EpisodeApiError`, `describeFailure` |
| `authoring-ui/src/views/episodes/episodeApi.test.ts` | 생성 | `describeFailure` 시험 |
| `authoring-ui/src/views/episodes/OperatorCardView.tsx` | 생성 | 카드(§9.5 순서) |
| `authoring-ui/src/views/episodes/EpisodeList.tsx` | 생성 | 목록(폴링) |
| `authoring-ui/src/views/episodes/NoticeFeed.tsx` | 생성 | 알림 흐름(폴링, 겹쳐 읽기, 쪽 넘기기) |
| `authoring-ui/src/views/episodes/EpisodeDetail.tsx` | 생성 | 상세(폴링) + 결정 |
| `authoring-ui/src/views/episodes/EpisodesView.tsx` | 생성 | 탭 전체 |
| `authoring-ui/src/App.tsx` | 수정 | 탭 「Episodes」(07) |
| `authoring-ui/src/styles.css` | 수정 | `.episodes-*` |
| `authoring-ui/e2e/episodes.spec.ts` | 생성 | 흉내 낸 API 로 화면 전체 |
| `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md` | 수정 | §16 주석 한 줄 |

---

## Chunk 1: 설치 · 형 · 순수 함수 · API

### Task 1: 설치와 기준, 계획 커밋

- [ ] **Step 1: 깐다**

Run: `cd authoring-ui && npm ci`
Expected: 성공. 뿌리에서 `git status --short` 를 보면 `package-lock.json` 변화가 없어야 한다. `node_modules/` 가 gitignore 에 없으면 보고하고, 커밋하지 않는다.

- [ ] **Step 2: 기준을 잰다**

Run: `npm test` 와 `npx tsc`
Expected: 둘 다 통과. vitest 의 시험 수를 적어 둔다.

Run: `test -f "/c/Program Files/Google/Chrome/Application/chrome.exe" && echo CHROME_OK`
Expected: `CHROME_OK`. Task 7 이 이 브라우저를 쓴다. 없으면 보고한다.

Run: `netstat -ano | grep ":5173 "`
Expected: 아무것도 없음. `webServer` 가 5173 을 새로 띄워야 하기 때문이다. 이미 쓰이고 있으면 Task 7 전에 보고한다.

- [ ] **Step 3: 계획을 커밋한다**(뿌리에서)

```bash
git add docs/superpowers/plans/2026-10-02-plan-b3c2-episodes-ui.md
git commit -m "docs: plan B3c-2 - the Episodes tab" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: 형과 순수 함수

**Files:**
- Create: `authoring-ui/src/views/episodes/episodeTypes.ts`
- Create: `authoring-ui/src/views/episodes/episodes.ts`
- Test: `authoring-ui/src/views/episodes/episodes.test.ts`

- [ ] **Step 1: 형을 쓴다 — `episodeTypes.ts`**

```ts
// DTO mirrors of the episode control plane (authoring-api EpisodeController, episode-runtime Wire.kt / OperatorCards.kt).
// Keep these in lockstep with the Kotlin side. Times named `at` are ISO strings; *Millis are epoch milliseconds.

export type Reply =
  | "ACCEPTED" | "REFUSED_NOT_AWAITING" | "REFUSED_STALE" | "REFUSED_SUBJECT_NOT_UNKNOWN"
  | "REFUSED_NOT_ESCALATED" | "REFUSED_CLOSED" | "REFUSED_NO_PROPOSITION";

export const REJECT_REASONS = ["WRONG_TARGET", "WRONG_ACTION", "PRECONDITION_NOT_MET", "NOT_NOW", "OTHER"] as const;
export type RejectReason = (typeof REJECT_REASONS)[number];

export interface EpisodeSummary {
  instanceId: string;
  workflowId: string;
  openedAt: string;
  lastAt: string;
  lastPhase: string | null;
  notices: number;
  recordLag: boolean;
}

export interface EpisodeView {
  instanceId: string;
  phase: string;
  escalationReason: string | null;
  escalationDetail: string | null;
  attempt: number | null;
  proposalId: string | null;
  candidateId: string | null;
  candidatesVersion: string | null;
  stateDeadlineMillis: number | null;
  episodeDeadlineMillis: number | null;
  historyJson: string;
  unknownsJson: string;
  symptoms: number;
  candidateJson: string | null;
  candidatesJson: string | null;
  /** The live diagnosis. The screen never renders it: the card carries what may be shown (§9.5). */
  diagnosisJson: string | null;
  joinedAfterSnapshot: number | null;
}

export interface Unknown {
  subject: Record<string, string | null>;
  what: string;
  since: string | null;
  source: string;
}

export interface CardProposal { candidateId: string; kind: string; ref: Record<string, string | null> }
/** One offered candidate (`candidatesJson`). ESCALATE has no ref: `ref` is null there (Candidates.kt). */
export interface CandidateRow { candidateId: string; kind: string; ref: Record<string, string | null> | null }
export interface GuidanceLine { label: string; text: string }
export interface CardClaim { text: string; foundIn: string[] | null }
export interface CardDecision { update: "decide" | "confirm"; proposalId: string; candidatesVersion: string; deadlineMillis: number | null }

export interface OperatorCard {
  unknowns: Unknown[];
  symptoms: Record<string, unknown>[];
  proposal: CardProposal | null;
  rationale: string | null;
  rationaleMissing: boolean;
  guidance: GuidanceLine[];
  citations: Record<string, unknown>[];
  uncitedSentences: string[];
  unverifiedCitations: CardClaim[];
  unlocatedCitations: number;
  unverifiedNumbers: CardClaim[];
  unlocatedNumbers: number;
  precondition: string | null;
  joinedAfterSnapshot: Record<string, unknown>[];
  decision: CardDecision | null;
  identityAssurance: string;
  responseUnreadable: boolean;
  diagnosisFromRecord: boolean;
  diagnosisRefused: string | null;
  factsBehind: boolean;
  joinedAfterSnapshotCount: number | null;
  diagnosisRefusedDetail: string | null;
  diagnosisAttempt: number | null;
}

export interface EpisodeEvent { seq: number; kind: string; payload: unknown; at: string | null }

/** Either shape (design §8.5): a state notice, or a person task. */
export type Notice =
  | { kind: string; phase: string; reason: string | null; detail: string | null }
  | { kind: "PERSON_TASK"; idempotencyKey: string; intent: { candidate?: { kind?: string; candidateId?: string } } };

export interface DetailNotice { id: number; at: string; notice: Notice }
export interface FeedNotice extends DetailNotice { instanceId: string }

export interface EpisodeDetailDto {
  instanceId: string;
  view: EpisodeView | null;
  viewError: string | null;
  card: OperatorCard | null;
  events: EpisodeEvent[];
  notices: DetailNotice[];
}

/** One attempt of the episode's history (design §9.4), as `historyJson` carries it. */
export interface HistoryItem {
  attempt: number;
  candidatesVersion: string | null;
  diagnosis: { outcome?: string | null } | null;
  approval: { result?: string | null; reason?: string | null } | null;
  dispatch: unknown;
  evidence: { outcome?: string | null } | null;
  closedAs: string | null;
  at: string | null;
}
```

`HistoryItem` 의 칸 이름은 `episode/src/main/kotlin/koshei/episode/EpisodeHistory.kt` 의 `toJson()` 과 대조한다. 다른 이름이 있으면 그쪽에 맞추고 보고한다.

- [ ] **Step 2: 실패하는 시험을 쓴다 — `episodes.test.ts`**

```ts
import { describe, it, expect } from "vitest";
import {
  splitInstance, episodePath, phaseChip, isTerminalPhase, replyText, failureText, mergeNotices, overlapAfter,
  noticeText, remainingText, operatorProblem, decideBody, preconditionBody, outcomeBody, unknownBody, closeBody,
  availableActions, confirmableUnknown, recordPayload, foundInText, parseHistory, parseCandidates,
} from "./episodes";
import type { EpisodeView, OperatorCard, FeedNotice } from "./episodeTypes";

const view = (over: Partial<EpisodeView> = {}): EpisodeView => ({
  instanceId: "ep:a/r1", phase: "AWAITING_APPROVAL", escalationReason: null, escalationDetail: null, attempt: 1,
  proposalId: "ep:a/r1#1", candidateId: "c", candidatesVersion: "v", stateDeadlineMillis: null, episodeDeadlineMillis: null,
  historyJson: "[]", unknownsJson: "[]", symptoms: 1, candidateJson: null, candidatesJson: null, diagnosisJson: null,
  joinedAfterSnapshot: null, ...over,
});

const card = (over: Partial<OperatorCard> = {}): OperatorCard => ({
  unknowns: [], symptoms: [], proposal: { candidateId: "c", kind: "APPROVE_REMEDY", ref: { robotId: "r" } },
  rationale: null, rationaleMissing: false, guidance: [], citations: [], uncitedSentences: [], unverifiedCitations: [],
  unlocatedCitations: 0, unverifiedNumbers: [], unlocatedNumbers: 0, precondition: null, joinedAfterSnapshot: [],
  decision: { update: "decide", proposalId: "ep:a/r1#1", candidatesVersion: "v", deadlineMillis: null },
  identityAssurance: "SELF_ASSERTED", responseUnreadable: false, diagnosisFromRecord: false, diagnosisRefused: null,
  factsBehind: false, joinedAfterSnapshotCount: null, diagnosisRefusedDetail: null, diagnosisAttempt: 1, ...over,
});

describe("splitInstance / episodePath", () => {
  it("splits at the last slash: the run part never contains one", () => {
    expect(splitInstance("ep:k-1/run-9")).toEqual({ workflowId: "ep:k-1", run: "run-9" });
  });
  it("encodes each segment", () => {
    expect(episodePath("ep:a b/r 1")).toBe("/api/episodes/ep%3Aa%20b/r%201");
    expect(episodePath("ep:a/r1", "decide")).toBe("/api/episodes/ep%3Aa/r1/decide");
  });
});

describe("phaseChip / isTerminalPhase", () => {
  it("marks phases that wait for a person", () => {
    for (const p of ["AWAITING_APPROVAL", "UNKNOWN_PRECONDITION", "UNKNOWN_OUTCOME", "AWAITING_EVIDENCE"]) {
      expect(phaseChip(p).cls).toBe("await");
    }
  });
  it("marks escalation, resolution and the ends", () => {
    expect(phaseChip("ESCALATED").cls).toBe("err");
    expect(phaseChip("RESOLVED").cls).toBe("ok");
    expect(phaseChip("CLOSED").cls).toBe("muted");
    expect(phaseChip("SUPERSEDED").cls).toBe("muted");
    expect(phaseChip("DIAGNOSING").cls).toBe("run");
    expect(phaseChip(null).label).toBe("—");
  });
  it("knows the terminal phases", () => {
    expect(["RESOLVED", "SUPERSEDED", "CLOSED"].every(isTerminalPhase)).toBe(true);
    expect(isTerminalPhase("ESCALATED")).toBe(false);
  });
});

describe("replyText", () => {
  it("says every reply in the operator's words", () => {
    for (const r of ["ACCEPTED", "REFUSED_NOT_AWAITING", "REFUSED_STALE", "REFUSED_SUBJECT_NOT_UNKNOWN",
      "REFUSED_NOT_ESCALATED", "REFUSED_CLOSED", "REFUSED_NO_PROPOSITION"]) {
      expect(replyText(r)).not.toBe(r);
    }
    expect(replyText("REFUSED_STALE")).toMatch(/다시 읽/);
  });
  it("shows an unknown reply as it came", () => expect(replyText("REFUSED_SOMETHING_NEW")).toBe("REFUSED_SOMETHING_NEW"));
});

describe("failureText", () => {
  it("names the episode an operator was moved to", () => {
    const f = failureText(409, { error: "EPISODE_MOVED", currentInstanceId: "ep:a/r2" });
    expect(f.movedTo).toBe("ep:a/r2");
    expect(f.text).toMatch(/다른 에피소드/);
  });
  it("says an ended episode has ended", () => expect(failureText(409, { error: "EPISODE_ENDED" }).text).toMatch(/끝/));
  it("says a timeout's outcome is unknown and asks to re-read", () => {
    const f = failureText(504, { error: "EPISODE_TIMEOUT", outcome: "UNKNOWN", instanceId: "ep:a/r1" });
    expect(f.outcomeUnknown).toBe(true);
    expect(f.text).toMatch(/결과 모름/);
  });
  it("passes the server's error through for 400, 413 and 503", () => {
    expect(failureText(400, { error: "proposalId is required" }).text).toContain("proposalId is required");
    expect(failureText(413, { error: "request body over 65536 bytes" }).text).toContain("65536");
    expect(failureText(503, { error: "episode tables not found" }).text).toContain("episode tables not found");
  });
  it("copes with a body that is not ours", () => expect(failureText(500, "boom").text).toContain("500"));
});

describe("mergeNotices / overlapAfter", () => {
  const n = (id: number): FeedNotice => ({ id, instanceId: "ep:a/r1", at: "2026-10-02T00:00:00Z", notice: { kind: "ESCALATED", phase: "ESCALATED", reason: null, detail: null } });
  it("drops ids already seen and keeps id order", () => {
    expect(mergeNotices([n(1), n(3)], [n(3), n(2), n(4)]).map((x) => x.id)).toEqual([1, 2, 3, 4]);
  });
  it("keeps only the newest when over the cap", () => {
    expect(mergeNotices([n(1), n(2)], [n(3)], 2).map((x) => x.id)).toEqual([2, 3]);
  });
  it("re-reads an overlap below the last id, never below zero", () => {
    expect(overlapAfter([])).toBe(0);
    expect(overlapAfter([n(10)])).toBe(0);
    expect(overlapAfter([n(70), n(80)])).toBe(30);
  });
});

describe("noticeText", () => {
  it("says a person task with the candidate kind", () => {
    expect(noticeText({ kind: "PERSON_TASK", idempotencyKey: "k", intent: { candidate: { kind: "CHOOSE_SOURCE" } } })).toMatch(/CHOOSE_SOURCE/);
  });
  it("says a state notice with its phase and reason", () => {
    expect(noticeText({ kind: "ESCALATED", phase: "ESCALATED", reason: "TAKEN_OVER", detail: null })).toBe("ESCALATED · ESCALATED · TAKEN_OVER");
    expect(noticeText({ kind: "APPROVAL_NEEDED", phase: "AWAITING_APPROVAL", reason: null, detail: null })).toBe("APPROVAL_NEEDED · AWAITING_APPROVAL");
  });
});

describe("remainingText", () => {
  it("counts down and says when it is past", () => {
    expect(remainingText(null, 0)).toBeNull();
    expect(remainingText(252_000, 0)).toBe("4m 12s");
    expect(remainingText(5_000, 0)).toBe("5s");
    expect(remainingText(1_000, 2_000)).toBe("기한 지남 · overdue");
  });
});

describe("operatorProblem", () => {
  it("requires a name of at most 128 ASCII characters (it goes in an HTTP header)", () => {
    expect(operatorProblem("")).not.toBeNull();
    expect(operatorProblem("   ")).not.toBeNull();
    expect(operatorProblem("x".repeat(129))).not.toBeNull();
    expect(operatorProblem("김철수")).not.toBeNull();
    expect(operatorProblem(" op-1 ")).toBeNull();
  });
});

describe("decision bodies", () => {
  it("decide carries what the card showed", () => {
    expect(decideBody(card().decision!, true)).toEqual({ proposalId: "ep:a/r1#1", sawCandidatesVersion: "v", approve: true });
    expect(decideBody(card().decision!, false, "NOT_NOW", " later ")).toEqual(
      { proposalId: "ep:a/r1#1", sawCandidatesVersion: "v", approve: false, reason: "NOT_NOW", note: "later" });
  });
  it("a precondition confirmation names the card's candidate and proposal, never a proposition", () => {
    const c = card({ decision: { update: "confirm", proposalId: "ep:a/r1#1", candidatesVersion: "v", deadlineMillis: null }, precondition: "명제" });
    const b = preconditionBody(c, true);
    expect(b).toEqual({ kind: "PRECONDITION", candidateId: "c", proposalId: "ep:a/r1#1", holds: true });
    expect(Object.keys(b)).not.toContain("proposition");
  });
  it("an outcome confirmation comes from the view", () => {
    expect(outcomeBody(view({ phase: "AWAITING_EVIDENCE" }), false)).toEqual({ kind: "OUTCOME", candidateId: "c", proposalId: "ep:a/r1#1", holds: false });
  });
  it("an unknown confirmation names its subject and what", () => {
    expect(unknownBody({ subject: { robotId: "r" }, what: "OBSERVATION_ABSENT", since: null, source: "x" }, " seen ")).toEqual(
      { kind: "UNKNOWN", subject: { robotId: "r" }, what: "OBSERVATION_ABSENT", holds: true, note: "seen" });
  });
  it("close needs an outcome", () => {
    expect(closeBody(" handled on site ")).toEqual({ outcome: "handled on site" });
    expect(closeBody("  ")).toBeNull();
  });
});

describe("confirmableUnknown", () => {
  it("leaves out what the core always refuses: the candidate's own item and an anonymous subject", () => {
    expect(confirmableUnknown({ subject: { candidateId: "c" }, what: "OUTCOME", since: null, source: "s" })).toBe(false);
    expect(confirmableUnknown({ subject: { robotId: null, executionId: null, unitId: null }, what: "OUTCOME", since: null, source: "s" })).toBe(false);
    expect(confirmableUnknown({ subject: { robotId: "r" }, what: "OBSERVATION_ABSENT", since: null, source: "s" })).toBe(true);
  });
});

describe("availableActions", () => {
  it("offers decide only where the card has a decide decision", () => {
    expect(availableActions(view(), card()).decide).toBe(true);
    expect(availableActions(view(), card({ decision: null })).decide).toBe(false);
  });
  it("offers the precondition confirmation only with a confirm decision (it requires a proposition)", () => {
    const c = card({ decision: { update: "confirm", proposalId: "p", candidatesVersion: "v", deadlineMillis: null }, precondition: "명제" });
    expect(availableActions(view({ phase: "UNKNOWN_PRECONDITION" }), c).confirmPrecondition).toBe(true);
    expect(availableActions(view({ phase: "UNKNOWN_PRECONDITION" }), card({ decision: null })).confirmPrecondition).toBe(false);
  });
  it("offers the outcome confirmation while evidence is awaited or the outcome is unknown", () => {
    expect(availableActions(view({ phase: "AWAITING_EVIDENCE" }), card({ decision: null })).confirmOutcome).toBe(true);
    expect(availableActions(view({ phase: "UNKNOWN_OUTCOME" }), card({ decision: null })).confirmOutcome).toBe(true);
    expect(availableActions(view({ phase: "AWAITING_EVIDENCE", proposalId: null }), card({ decision: null })).confirmOutcome).toBe(false);
  });
  it("offers unknown confirmations only for items the core can take, outside ESCALATED and the ends", () => {
    const own = { subject: { candidateId: "c" }, what: "OUTCOME", since: null, source: "s" };
    const robot = { subject: { robotId: "r" }, what: "OBSERVATION_ABSENT", since: null, source: "s" };
    expect(availableActions(view({ phase: "UNKNOWN_OUTCOME" }), card({ unknowns: [own] })).confirmUnknowns).toBe(false);
    expect(availableActions(view({ phase: "UNKNOWN_OUTCOME" }), card({ unknowns: [own, robot] })).confirmUnknowns).toBe(true);
    expect(availableActions(view({ phase: "ESCALATED" }), card({ unknowns: [robot] })).confirmUnknowns).toBe(false);
    expect(availableActions(view({ phase: "CLOSED" }), card({ unknowns: [robot] })).confirmUnknowns).toBe(false);
  });
  it("offers takeover until a person has the episode, and close only after", () => {
    expect(availableActions(view({ phase: "DIAGNOSING" }), null).takeover).toBe(true);
    expect(availableActions(view({ phase: "ESCALATED" }), null).takeover).toBe(false);
    expect(availableActions(view({ phase: "ESCALATED" }), null).close).toBe(true);
    expect(availableActions(view({ phase: "RESOLVED" }), null).takeover).toBe(false);
    expect(availableActions(view({ phase: "AWAITING_APPROVAL" }), null).close).toBe(false);
  });
  it("offers nothing without a live view", () => {
    expect(Object.values(availableActions(null, null)).some(Boolean)).toBe(false);
  });
});

describe("recordPayload", () => {
  it("drops the narrator answer from a diagnosis record: its cause stays recorded only (§9.5)", () => {
    const shown = recordPayload("DIAGNOSIS_RESULT", { attempt: 1, verdict: "PROPOSED", response: { cause: "SECRET_CAUSE", rationale: "r" } });
    expect(JSON.stringify(shown)).not.toContain("SECRET_CAUSE");
    expect(shown).toEqual({ attempt: 1, verdict: "PROPOSED" });
  });
  it("drops a late narrator answer kept verbatim in an IGNORED record (§9.5, §13)", () => {
    const shown = recordPayload("IGNORED", { event: "DiagnosisReturned", why: "late", json: '{"cause":{"text":"LATE_SECRET_CAUSE"}}' });
    expect(JSON.stringify(shown)).not.toContain("LATE_SECRET_CAUSE");
    expect(shown).toEqual({ event: "DiagnosisReturned", why: "late" });
  });
  it("leaves other records as they are", () => {
    expect(recordPayload("OPENED", { eventId: "e" })).toEqual({ eventId: "e" });
    expect(recordPayload("IGNORED", { event: "DispatchReturned", answer: "{}" })).toEqual({ event: "DispatchReturned", answer: "{}" });
  });
});

describe("foundInText", () => {
  it("tells nowhere, only-the-query and a list apart (§9.5)", () => {
    expect(foundInText(null)).toBe("");
    expect(foundInText([])).toMatch(/어디에도 없음/);
    expect(foundInText(["query"])).toMatch(/질의에만/);
    expect(foundInText(["SOP-01", "query"])).toBe("SOP-01, query");
  });
});

describe("parseHistory / parseCandidates", () => {
  it("reads the view's JSON fields, and nothing when unreadable", () => {
    expect(parseHistory(view({ historyJson: '[{"attempt":1,"closedAs":"REDIAGNOSED"}]' }))[0].attempt).toBe(1);
    expect(parseHistory(view({ historyJson: "not json" }))).toEqual([]);
    expect(parseCandidates(view({ candidatesJson: '[{"candidateId":"ESCALATE","kind":"ESCALATE","ref":null}]' }))[0].kind).toBe("ESCALATE");
    expect(parseCandidates(view({ candidatesJson: null }))).toEqual([]);
  });
});
```

- [ ] **Step 3: 실패를 확인한다**

Run: `npm test`
Expected: FAIL — `./episodes` 를 풀 수 없다.

- [ ] **Step 4: 구현한다 — `episodes.ts`**

```ts
// Pure helpers for the Episodes tab (plan B3c-2). Pure — unit-tested in episodes.test.ts.
// The server decides; these only say its answers in the operator's words and build the bodies it expects.
import type {
  CandidateRow, CardDecision, EpisodeView, FeedNotice, HistoryItem, Notice, OperatorCard, RejectReason, Unknown,
} from "./episodeTypes";

/** The instance id is "<workflowId>/<run>"; the run part never contains a slash (design §7.1). */
export function splitInstance(instanceId: string): { workflowId: string; run: string } {
  const i = instanceId.lastIndexOf("/");
  return { workflowId: instanceId.slice(0, i), run: instanceId.slice(i + 1) };
}

export function episodePath(instanceId: string, action?: "decide" | "confirm" | "takeover" | "close"): string {
  const { workflowId, run } = splitInstance(instanceId);
  const base = `/api/episodes/${encodeURIComponent(workflowId)}/${encodeURIComponent(run)}`;
  return action ? `${base}/${action}` : base;
}

const TERMINAL = new Set(["RESOLVED", "SUPERSEDED", "CLOSED"]);
const WAITING = new Set(["AWAITING_APPROVAL", "UNKNOWN_PRECONDITION", "UNKNOWN_OUTCOME", "AWAITING_EVIDENCE"]);

export function isTerminalPhase(phase: string | null | undefined): boolean {
  return phase != null && TERMINAL.has(phase);
}

/** A chip for a phase; `cls` is a `.chip` modifier from styles.css. */
export function phaseChip(phase: string | null | undefined): { label: string; cls: string } {
  if (!phase) return { label: "—", cls: "muted" };
  if (WAITING.has(phase)) return { label: phase, cls: "await" };
  if (phase === "ESCALATED") return { label: phase, cls: "err" };
  if (phase === "RESOLVED") return { label: phase, cls: "ok" };
  if (TERMINAL.has(phase)) return { label: phase, cls: "muted" };
  return { label: phase, cls: "run" };
}

const REPLIES: Record<string, string> = {
  ACCEPTED: "받아들여짐 · accepted",
  REFUSED_NOT_AWAITING: "지금 기다리는 결정이 아님 · not awaiting this decision",
  REFUSED_STALE: "낡은 카드 — 다시 읽은 뒤 판단 · stale card, re-read first",
  REFUSED_SUBJECT_NOT_UNKNOWN: "확인할 대상이 아님 · nothing to confirm here",
  REFUSED_NOT_ESCALATED: "사람에게 넘어간 에피소드만 닫음 · only an escalated episode can be closed",
  REFUSED_CLOSED: "이미 닫힘 · already closed",
  REFUSED_NO_PROPOSITION: "확인할 명제가 없음 · no proposition to confirm",
};

export function replyText(reply: string): string {
  return REPLIES[reply] ?? reply;
}

export interface Failure { text: string; movedTo?: string; outcomeUnknown?: boolean }

/** A failed call in the operator's words (design §7.2 구현(B3c) response table). */
export function failureText(status: number, body: unknown): Failure {
  const b = (body && typeof body === "object" ? body : {}) as { error?: string; currentInstanceId?: string };
  if (status === 409 && b.error === "EPISODE_MOVED") {
    return { text: `다른 에피소드로 넘어감 · moved on to ${b.currentInstanceId ?? "?"}`, movedTo: b.currentInstanceId };
  }
  if (status === 409 && b.error === "EPISODE_ENDED") return { text: "에피소드가 끝남 · the episode has ended" };
  if (status === 504) {
    return { text: "결과 모름 — 상세를 다시 읽은 뒤 판단 · outcome unknown, re-read before deciding again", outcomeUnknown: true };
  }
  return { text: `${status}${b.error ? ` · ${b.error}` : ""}` };
}

/** Joins a notice page into what was seen: ids are not commit-ordered, so pages overlap (design §8.5). */
export function mergeNotices(seen: FeedNotice[], incoming: FeedNotice[], cap = 500): FeedNotice[] {
  const byId = new Map<number, FeedNotice>();
  for (const n of seen) byId.set(n.id, n);
  for (const n of incoming) if (!byId.has(n.id)) byId.set(n.id, n);
  return [...byId.values()].sort((a, b) => a.id - b.id).slice(-cap);
}

export function overlapAfter(seen: FeedNotice[], overlap = 50): number {
  const last = seen.length ? seen[seen.length - 1].id : 0;
  return Math.max(0, last - overlap);
}

export function noticeText(n: Notice): string {
  if (n.kind === "PERSON_TASK") {
    const kind = (n as Extract<Notice, { kind: "PERSON_TASK" }>).intent?.candidate?.kind ?? "?";
    return `사람 과업 · person task · ${kind}`;
  }
  const s = n as { kind: string; phase: string; reason: string | null };
  return [s.kind, s.phase, s.reason].filter(Boolean).join(" · ");
}

export function remainingText(deadlineMillis: number | null, now: number = Date.now()): string | null {
  if (deadlineMillis == null) return null;
  const left = deadlineMillis - now;
  if (left < 0) return "기한 지남 · overdue";
  const s = Math.floor(left / 1000);
  const m = Math.floor(s / 60);
  return m > 0 ? `${m}m ${s % 60}s` : `${s}s`;
}

/** Why a name cannot be sent as X-Koshei-Operator, or null (design §7.2: trimmed, 1..128; an HTTP header is ASCII). */
export function operatorProblem(name: string): string | null {
  const t = name.trim();
  if (!t) return "운영자 이름이 필요함 · operator name required";
  if (t.length > 128) return "운영자 이름은 128자까지 · at most 128 characters";
  if (/[^\x20-\x7E]/.test(t)) return "운영자 이름은 영문 · 숫자 · 기호만(HTTP 머리글) · ASCII only";
  return null;
}

const trimmed = (s?: string) => (s && s.trim() ? s.trim() : undefined);

export function decideBody(d: CardDecision, approve: boolean, reason?: RejectReason, note?: string) {
  const body: Record<string, unknown> = { proposalId: d.proposalId, sawCandidatesVersion: d.candidatesVersion, approve };
  if (reason) body.reason = reason;
  const n = trimmed(note);
  if (n) body.note = n;
  return body;
}

/** The server fills the proposition from its own card (design §7.2); the screen never sends one. */
export function preconditionBody(card: OperatorCard, holds: boolean) {
  return { kind: "PRECONDITION", candidateId: card.proposal?.candidateId ?? "", proposalId: card.decision?.proposalId ?? "", holds };
}

export function outcomeBody(view: EpisodeView, holds: boolean) {
  return { kind: "OUTCOME", candidateId: view.candidateId ?? "", proposalId: view.proposalId ?? "", holds };
}

export function unknownBody(u: Unknown, note?: string) {
  const body: Record<string, unknown> = { kind: "UNKNOWN", subject: u.subject, what: u.what, holds: true };
  const n = trimmed(note);
  if (n) body.note = n;
  return body;
}

export function closeBody(outcome: string): { outcome: string } | null {
  const o = trimmed(outcome);
  return o ? { outcome: o } : null;
}

/** Items the core always refuses (design §5.2): the candidate's own OUTCOME item, and a subject with no value. */
export function confirmableUnknown(u: Unknown): boolean {
  return !("candidateId" in u.subject) && Object.values(u.subject).some((v) => v != null);
}

export interface Actions {
  decide: boolean;
  confirmPrecondition: boolean;
  confirmOutcome: boolean;
  confirmUnknowns: boolean;
  takeover: boolean;
  close: boolean;
}

/** Which decisions to offer: from the card's decision and the live phase only (the core still decides). */
export function availableActions(view: EpisodeView | null, card: OperatorCard | null): Actions {
  if (!view) return { decide: false, confirmPrecondition: false, confirmOutcome: false, confirmUnknowns: false, takeover: false, close: false };
  const live = !isTerminalPhase(view.phase);
  const escalated = view.phase === "ESCALATED";
  return {
    decide: card?.decision?.update === "decide",
    confirmPrecondition: card?.decision?.update === "confirm",
    confirmOutcome: (view.phase === "AWAITING_EVIDENCE" || view.phase === "UNKNOWN_OUTCOME") && !!view.proposalId && !!view.candidateId,
    confirmUnknowns: live && !escalated && (card?.unknowns.some(confirmableUnknown) ?? false),
    takeover: live && !escalated,
    close: escalated,
  };
}

/**
 * A record as the screen shows it: the narrator's answer stays recorded only (§9.5) — `cause`, refused and superseded
 * answers included. A diagnosis record loses `response` and `rawText`; a late answer (an IGNORED `DiagnosisReturned`)
 * loses its verbatim `json`. The control plane already redacts both; this is defence in depth.
 */
export function recordPayload(kind: string, payload: unknown): unknown {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return payload;
  const p = payload as Record<string, unknown>;
  if (kind === "DIAGNOSIS_RESULT") {
    const rest = { ...p };
    delete rest.response;
    delete rest.rawText;
    return rest;
  }
  if (kind === "IGNORED" && p.event === "DiagnosisReturned") {
    const rest = { ...p };
    delete rest.json;
    return rest;
  }
  return payload;
}

/** Where an unchecked number was found (§9.5): null says nothing, [] is "nowhere", ["query"] is "only in the query". */
export function foundInText(foundIn: string[] | null): string {
  if (foundIn === null) return "";
  if (foundIn.length === 0) return "어디에도 없음 · found nowhere";
  if (foundIn.length === 1 && foundIn[0] === "query") return "질의에만 있음 · only in the query";
  return foundIn.join(", ");
}

function parseArray<T>(text: string | null): T[] {
  if (!text) return [];
  try {
    const v: unknown = JSON.parse(text);
    return Array.isArray(v) ? (v as T[]) : [];
  } catch {
    return [];
  }
}

export const parseHistory = (v: EpisodeView): HistoryItem[] => parseArray<HistoryItem>(v.historyJson);
export const parseCandidates = (v: EpisodeView): CandidateRow[] => parseArray<CandidateRow>(v.candidatesJson);
```

- [ ] **Step 5: 통과를 확인한다**

Run: `npm test` 와 `npx tsc`
Expected: 기준 + 새 시험이 모두 PASS 하고, 형 검사가 통과한다.

- [ ] **Step 6: 커밋한다**(뿌리에서)

```bash
git add authoring-ui/src/views/episodes/episodeTypes.ts authoring-ui/src/views/episodes/episodes.ts authoring-ui/src/views/episodes/episodes.test.ts
git commit -m "feat(ui): episode types and pure helpers - replies and failures in the operator's words, notice overlap, decision bodies" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 3: API 감싸기

**Files:**
- Create: `authoring-ui/src/views/episodes/episodeApi.ts`
- Test: `authoring-ui/src/views/episodes/episodeApi.test.ts`

- [ ] **Step 1: 실패하는 시험을 쓴다**

```ts
import { describe, it, expect } from "vitest";
import { EpisodeApiError, describeFailure } from "./episodeApi";

describe("describeFailure", () => {
  it("says an HTTP failure with the server's body", () => {
    expect(describeFailure(new EpisodeApiError(503, { error: "episode tables not found" })).text).toContain("episode tables not found");
    expect(describeFailure(new EpisodeApiError(504, { error: "EPISODE_TIMEOUT", outcome: "UNKNOWN" }), true).outcomeUnknown).toBe(true);
  });
  it("calls a POST that got no answer an unknown outcome", () => {
    const f = describeFailure(new TypeError("Failed to fetch"), true);
    expect(f.outcomeUnknown).toBe(true);
    expect(f.text).toMatch(/결과 모름/);
  });
  it("says a GET that got no answer as it is", () => {
    const f = describeFailure(new TypeError("Failed to fetch"));
    expect(f.outcomeUnknown).toBeUndefined();
    expect(f.text).toContain("Failed to fetch");
  });
});
```

- [ ] **Step 2: 실패를 확인한다**

Run: `npm test`
Expected: FAIL — `./episodeApi` 없음

- [ ] **Step 3: 쓴다**

```ts
// The Episodes tab's calls to /api/episodes (authoring-api EpisodeController, plan B3c).
import type { EpisodeDetailDto, EpisodeSummary, FeedNotice } from "./episodeTypes";
import { episodePath, failureText, type Failure } from "./episodes";

/** A failed call. [body] is the server's JSON ({error, …}) when it sent one, else its text. */
export class EpisodeApiError extends Error {
  constructor(public status: number, public body: unknown) {
    super(`${status}`);
    this.name = "EpisodeApiError";
  }
}

/** Any failure in the operator's words. A POST with no HTTP answer may still have landed: its outcome is unknown. */
export function describeFailure(e: unknown, sentPost = false): Failure {
  if (e instanceof EpisodeApiError) return failureText(e.status, e.body);
  return sentPost
    ? { text: `응답 없음 — 결과 모름, 상세를 다시 읽은 뒤 판단 · no answer, outcome unknown (${String(e)})`, outcomeUnknown: true }
    : { text: String(e) };
}

async function json<T>(res: Response): Promise<T> {
  const text = await res.text();
  let parsed: unknown = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = text;
  }
  if (!res.ok) throw new EpisodeApiError(res.status, parsed);
  return parsed as T;
}

export const listEpisodes = (limit = 50) => fetch(`/api/episodes?limit=${limit}`).then((r) => json<EpisodeSummary[]>(r));

export const getEpisode = (instanceId: string) => fetch(episodePath(instanceId)).then((r) => json<EpisodeDetailDto>(r));

export const noticesAfter = (after: number, limit = 500) =>
  fetch(`/api/episodes/notices?after=${after}&limit=${limit}`).then((r) => json<FeedNotice[]>(r));

/** One decision (design §7.2). The operator goes in the header, never in the body. */
export async function sendDecision(
  instanceId: string,
  action: "decide" | "confirm" | "takeover" | "close",
  operator: string,
  body?: object,
): Promise<string> {
  const headers: Record<string, string> = { "X-Koshei-Operator": operator.trim() };
  if (body) headers["Content-Type"] = "application/json";
  const res = await fetch(episodePath(instanceId, action), { method: "POST", headers, body: body ? JSON.stringify(body) : undefined });
  return (await json<{ reply: string }>(res)).reply;
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `npm test` 와 `npx tsc`
Expected: 통과

- [ ] **Step 5: 커밋한다**(뿌리에서)

```bash
git add authoring-ui/src/views/episodes/episodeApi.ts authoring-ui/src/views/episodes/episodeApi.test.ts
git commit -m "feat(ui): episode API calls - the operator in X-Koshei-Operator, failures described, an unanswered POST is an unknown outcome" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Chunk 2: 화면

이 묶음의 부품들은 아래 규칙을 함께 따른다.
- **폴링:** `useEffect` 안에서 `let alive = true` 를 두고, 정리할 때 `alive = false; clearInterval(id)` 한다. `RunList.tsx` 와 같다.
- **오류:** 모든 `catch (e)` 는 `describeFailure(e)` 의 `text` 를 `.banner err` 로 보인다. POST 에서는 `describeFailure(e, true)` 를 쓴다.
- **`data-testid`:** 이 계획에 적힌 이름 그대로 쓴다. e2e 가 그 이름을 쓴다.
- **글자:** 운영자에게 보이는 글은 「한국어 · English」 두 겹이다(Console 의 안내와 같다).

### Task 4: 운영자 카드

**Files:**
- Create: `authoring-ui/src/views/episodes/OperatorCardView.tsx`
- Modify: `authoring-ui/src/styles.css`

- [ ] **Step 1: 쓴다**

`export function OperatorCardView({ card }: { card: OperatorCard })` 의 바깥 요소는 `<div className="panel episodes-card" data-testid="operator-card">` 이다. 안쪽은 §9.5 순서대로(§19 D 가 정해질 때까지), **비어 있는 칸은 그리지 않고** 아래를 그린다.

1. **확인 불가**(`unknowns`)
   - 맨 위에 `.banner await`(`card-unknowns`) 로 그린다.
   - 항목마다 `what` 과 `subject` 의 열쇠=값을 쓴다.
2. **사실이 늦음**(`factsBehind`)
   - `.banner info`(`card-facts-behind`): 「기록이 아직 다 오지 않음 · records still arriving」
3. **사실**(`symptoms`)
   - `card-symptoms`: 「사실 · facts (n)」 아래에 증상 줄마다 `JSON.stringify` 한 줄. `.mono`, 넘치면 줄바꿈한다.
4. **제안**(`proposal`)
   - `card-proposal`: `kind` 와 `ref` 의 열쇠 · 값을 `<dl>` 로 그린다. id 를 분해하지 않는다.
5. **진단의 출처** — 해당할 때만 그린다.
   - `diagnosisFromRecord`: 「기록에서 읽은 진단 · diagnosis read from the record」
   - `diagnosisAttempt`: 「시도 n · attempt n」
   - `diagnosisRefused`: `.banner err`(`card-refused`) 「판정이 거절한 답 · refused by the judge: <reason> — <detail>」. 이 경우 이유 · 지침 · 인용 칸은 서버가 비워 보낸다.
   - `responseUnreadable`: `.banner err`(`card-unreadable`) 「읽을 수 없는 답 · unreadable answer」
6. **이유**(`rationale`)
   - `card-rationale`: 있으면 그 글자를 그린다.
   - `rationaleMissing` 이면 「이유 없음 · no rationale」을 그린다.
   - 둘 다 아니면 이 칸을 그리지 않는다.
7. **지침**(`guidance`)
   - `card-guidance`: `label: text` 를 순서대로 그린다.
8. **인용**(`citations`)
   - `card-citations` 의 머리글: 「근거 꾸러미에 있던 문서 · documents in the evidence bundle (not facts)」(R13).
   - 항목마다 `title` · `section` 을 그린다.
9. **인용 없는 문장**(`uncitedSentences`)
   - `card-uncited`
10. **확인 못 한 주장** — `card-unverified` 아래에 넷을 그린다.
    - 인용 `text`
    - 「본문 없는 인용 n개 · n citations without text」
    - 숫자 `text` + `foundInText(foundIn)`
    - 「위치 모르는 숫자 n개 · n numbers, location unknown」
11. **확인할 전제**(`precondition`)
    - `card-precondition`: 글자 그대로, `.episodes-precondition`(굵게).
    - 그 아래에 「스냅샷 뒤 합류한 증상 · symptoms joined after the snapshot (n)」과 `joinedAfterSnapshot` 줄을 그린다. n 은 `joinedAfterSnapshotCount ?? 줄 수` 이다.
    - 줄 수가 n 보다 적으면 「기록이 늦음 · records lagging」을 덧붙인다.
12. **신원**(`identityAssurance`)
    - `card-identity`: `SELF_ASSERTED` 이면 「스스로 밝힌 신원 · self-asserted identity (no authentication)」을 그린다.
    - 그 밖의 값은 값 그대로 그린다.

`cause` 는 카드에 없다(서버가 싣지 않는다).

`styles.css` 끝에 더한다.

```css
/* Episodes tab (plan B3c-2) */
.episodes-grid { display: grid; grid-template-columns: 300px 1fr 300px; gap: var(--sp-4); min-height: 0; }
.episodes-card dl { display: grid; grid-template-columns: max-content 1fr; gap: var(--sp-1) var(--sp-3); margin: 0; }
.episodes-card dt { color: var(--ink-dim); font-family: var(--mono); }
.episodes-card .mono, .episodes-detail .mono, .episodes-row .mono { font-family: var(--mono); overflow-wrap: anywhere; }
.episodes-precondition { font-weight: 600; }
.episodes-decisions { display: flex; flex-wrap: wrap; gap: var(--sp-2); align-items: center; }
.episodes-feed { max-height: 70vh; overflow: auto; }
.episodes-hint { color: var(--risk-amber); font-size: var(--fs-sm); }
.episodes-row { width: 100%; text-align: left; background: transparent; border: 0; border-bottom: 1px solid var(--line-soft); color: inherit; font: inherit; cursor: pointer; }
```

- [ ] **Step 2: 형 검사**

Run: `npx tsc`
Expected: 통과

- [ ] **Step 3: 커밋한다**(뿌리에서)

```bash
git add authoring-ui/src/views/episodes/OperatorCardView.tsx authoring-ui/src/styles.css
git commit -m "feat(ui): the operator card in §9.5 order - unknowns first, facts, no rationale said, refused answers named" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 5: 목록 · 알림 흐름 · 탭

**Files:**
- Create: `authoring-ui/src/views/episodes/EpisodeList.tsx`
- Create: `authoring-ui/src/views/episodes/NoticeFeed.tsx`
- Create: `authoring-ui/src/views/episodes/EpisodesView.tsx`
- Modify: `authoring-ui/src/App.tsx`

- [ ] **Step 1: 쓴다**

**`EpisodeList({ selectedId, onSelect })`:** 2000 ms 마다 `listEpisodes()` 를 폴링한다.
- 각 줄은 `<button type="button" className={`episodes-row console-run ${active}`} data-testid={"episode-row-" + instanceId} onClick={() => onSelect(instanceId)}>` 이다.
- 줄에 보일 것:
  - 워크플로 id(`.mono`)
  - `phaseChip(lastPhase)` 칩
  - 알림 수
  - `recordLag` 이면 `.chip err` 「기록 지연 · record lag」
  - `lastAt`
- 비면 「에피소드 없음 · no episodes yet」을 보인다.
- 오류는 `describeFailure(e).text` 를 `.banner err` 로 보인다.

**`NoticeFeed({ onSelect })`:** 3000 ms 마다 폴링한다. `useRef<FeedNotice[]>([])` 로 본 것을 든다. 한 번의 tick 은 다음과 같다.

```ts
let after = overlapAfter(seen.current);
for (let i = 0; i < 10; i++) {
  const rows = await noticesAfter(after, 500);
  if (!alive) return;
  seen.current = mergeNotices(seen.current, rows);
  if (rows.length < 500) break;
  after = rows[rows.length - 1].id;
}
if (alive) setItems([...seen.current].reverse());
```

- 새것을 위로 보인다.
- 각 줄은 `data-testid={"notice-" + id}` 이고, `noticeText(n.notice)` 와 `at`, 그리고 인스턴스 id 버튼(`data-testid={"notice-open-" + id}`, 누르면 `onSelect(instanceId)`)을 보인다.
- `PERSON_TASK` 줄은 `.chip await` 를 함께 보인다.
- 바깥은 `.episodes-feed` 다.

**`EpisodesView`:**
- 위쪽 `.toolbar` 에 운영자 이름 입력칸을 둔다.
  - `<label htmlFor={id}>운영자 · operator</label><input id={id} data-testid="operator-name" …>`(`useId()`)
  - `localStorage` 열쇠는 `koshei.operator` 다. 처음 읽을 때와 바뀔 때 쓰는 것 모두 `try { … } catch { /* private window etc. */ }` 로 감싼다.
  - `operatorProblem(name)` 이 null 이 아니면 `<span className="episodes-hint" data-testid="operator-problem">` 로 보인다.
- 아래는 `.episodes-grid` 세 칸이다.
  - 가운데는 이 Task 에서 `{selected ? <div className="console-detail empty" data-testid="detail-pending">{selected}</div> : <div className="console-detail empty">왼쪽에서 에피소드를 고르십시오 · select an episode</div>}` 다.
  - Task 6 이 이 자리를 `<EpisodeDetail key={selected} instanceId={selected} operator={name} onSelect={setSelected} />` 로 바꾼다.
  - `key` 가 바뀌면 상세가 새로 마운트되어, 입력과 결과가 다른 에피소드로 새지 않는다.
- 선택은 `instanceId` 문자열로 든다.

**`App.tsx`:**
- `Tab` 에 `"episodes"` 를 더한다.
- `TABS` 에 `{ id: "episodes", idx: "07", label: "Episodes" }` 를 더한다.
- `HEADERS` 에 다음을 더한다.

  ```
  { crumb: "Operator · episodes", title: "Episodes",
    blurb: "Incidents the outer loop is handling: the operator card, decisions and notices. Identity is self-asserted (no authentication)." }
  ```

- `{tab === "episodes" && <EpisodesView />}` 를 더한다.

- [ ] **Step 2: 형 검사와 시험**

Run: `npx tsc` 와 `npm test`
Expected: 통과

- [ ] **Step 3: 커밋한다**(뿌리에서)

```bash
git add authoring-ui/src/views/episodes/EpisodeList.tsx authoring-ui/src/views/episodes/NoticeFeed.tsx authoring-ui/src/views/episodes/EpisodesView.tsx authoring-ui/src/App.tsx
git commit -m "feat(ui): Episodes tab - the list, the notice feed read with an overlap, the operator's name" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 6: 상세와 결정

**Files:**
- Create: `authoring-ui/src/views/episodes/EpisodeDetail.tsx`
- Modify: `authoring-ui/src/views/episodes/EpisodesView.tsx`(필요하면)

- [ ] **Step 1: 쓴다**

`export function EpisodeDetail({ instanceId, operator, onSelect })`:

**상태와 폴링:**
- 상태는 `detail: EpisodeDetailDto | null`, `loadError`, `busy`, `result: { text: string; ok: boolean; movedTo?: string } | null`, 그리고 입력값(거절 사유 · 메모 · 닫기 결과 · 확인 불가 항목별 메모)이다.
- 1500 ms 마다 `getEpisode(instanceId)` 를 폴링한다.
  - 성공하면 `detail` 을 바꾸고 `loadError` 를 지운다.
  - 실패하면 `loadError = describeFailure(e).text` 로 두고, 마지막으로 잘 읽은 `detail` 은 그대로 둔다.
- `refresh()` 는 같은 읽기를 즉시 한 번 한다.
- 1초마다 `now` 를 바꾸는 시계를 하나 둔다(남은 시간용).

**머리**(`.episodes-detail` 안):
- `detail === null` 이면 「읽는 중 · loading」만 그린다.
- 인스턴스 id(`.mono`)와 단계 칩을 그린다. 단계는 `view?.phase ?? "—"` 다.
- `escalationReason` 이 있으면 `.banner err` 로 이유와 `escalationDetail` 을 그린다.
- 남은 시간 둘(`data-testid="deadlines"`):
  - 「상태 · state」: `remainingText(card?.decision?.deadlineMillis ?? view?.stateDeadlineMillis ?? null, now)`
  - 「에피소드 · episode」: `remainingText(view?.episodeDeadlineMillis ?? null, now)`
- `viewError` 이면 `.banner info`: 「지금 상태를 읽지 못함 — 기록만 보임 · live state unavailable, records only: <viewError>」
- `view == null && !viewError` 이면 `.banner info`: 「끝난 에피소드 — 기록만 보임 · ended, records only」
- `loadError` 이면 `.banner err`.

**카드:** `card` 가 있으면 `<OperatorCardView card={card} />` 를 그린다. `view.diagnosisJson` 은 어디에도 그리지 않는다.

**명제 없음:** `view?.phase === "UNKNOWN_PRECONDITION"` 이고 `!card?.precondition` 이면 `.banner info`(`no-proposition`)를 그린다: 「확인할 명제가 없음 — 사람이 확인할 수 없음, 기한에 끝나거나 인수 · no proposition to confirm: it ends at its deadline, or take over」.

**결정 영역**(`.episodes-decisions`)은 `availableActions(view, card)` 를 따른다.
- `operatorProblem(operator) != null` 이거나 `busy` 이면 모든 결정 버튼이 `disabled` 다.
- `busy` 이면 누른 버튼 글자가 「보내는 중… · sending…」이 된다.

| 결정 | 그릴 것 | 보낼 것 |
|---|---|---|
| `decide` | 「✓ 승인 · Approve」(`decide-approve`). 거절 사유 `<select data-testid="reject-reason">`(빈 선택 + `REJECT_REASONS`, 상태 형은 `RejectReason \| ""`), 메모 `<input data-testid="reject-note">`, 「✕ 거절 · Reject」(`decide-reject`, 사유를 고르기 전에는 `disabled`) | `decideBody(card.decision!, approve, reason \|\| undefined, note)` |
| `confirmPrecondition` | 「전제 맞음 · holds」(`confirm-precondition-holds`), 「전제 아님 · does not hold」(`confirm-precondition-not`) | `preconditionBody(card!, holds)` |
| `confirmOutcome`(AWAITING_EVIDENCE · UNKNOWN_OUTCOME) | 「완료됨 · DONE」(`confirm-outcome-done`), 「안 됨 · NOT DONE」(`confirm-outcome-not`) | `outcomeBody(view!, holds)` |
| `confirmUnknowns` | `card.unknowns` 중 `confirmableUnknown(u)` 인 항목마다(`i` 는 카드 안의 순번) 메모 `<input data-testid={"unknown-note-" + i}>` 와 「확인함 · confirmed」(`confirm-unknown-` + i) | `unknownBody(u, note)` |
| `takeover` | 「■ 인수 · Take over」(`takeover`). 누르면 `window.confirm("이 에피소드를 사람이 넘겨받습니까? · Take this episode over?")` 이 참일 때만 보낸다 | 본문 없음 |
| `close` | 결과 `<input data-testid="close-outcome">`, 「닫기 · Close」(`close`, `closeBody` 가 null 이면 `disabled`) | `closeBody(outcome)!` |

보내기는 하나의 함수가 한다.

```ts
async function send(action: "decide" | "confirm" | "takeover" | "close", body?: object) {
  setBusy(true);
  setResult(null);
  try {
    const reply = await sendDecision(instanceId, action, operator, body);
    setResult({ text: replyText(reply), ok: reply === "ACCEPTED" });
  } catch (e) {
    const f = describeFailure(e, true);
    setResult({ text: f.text, ok: false, movedTo: f.movedTo });
  } finally {
    setBusy(false);
    void refresh();
  }
}
```

결과는 `data-testid="decision-result"` 로 그린다.
- `ok` 이면 `.banner ok`, 아니면 `.banner err` 다.
- `movedTo` 가 있으면 「지금 에피소드 보기 · open the current episode」(`open-moved`)를 둔다. 누르면 `onSelect(movedTo)`.

**접힌 칸**(`<details>`) 셋을 그린다.
- **시도 이력:** 「시도 이력 · attempts (n)」(`history`).
  - 원본은 `parseHistory(view)`. 줄마다 `attempt` · `closedAs` · `diagnosis?.outcome` · `approval?.result`/`reason` · `evidence?.outcome` · `at` 을 그린다.
- **후보:** 「후보 · candidates (n)」(`candidates`).
  - 원본은 `parseCandidates(view)`. 줄마다 `kind` 와 `Object.entries(c.ref ?? {})` 의 열쇠=값을 그린다. ESCALATE 후보는 `ref` 가 null 이고, 늘 목록에 있다.
- **기록:** 「기록 · records (n)」(`records`).
  - 줄마다 `seq · kind · at`, 그리고 `<pre>{JSON.stringify(recordPayload(kind, payload), null, 2)}</pre>`.

**이 에피소드의 알림:** `noticeText` 줄들(`episode-notices`).

- [ ] **Step 2: 형 검사와 시험**

Run: `npx tsc` 와 `npm test`
Expected: 통과

- [ ] **Step 3: 커밋한다**(뿌리에서)

```bash
git add authoring-ui/src/views/episodes/EpisodeDetail.tsx authoring-ui/src/views/episodes/EpisodesView.tsx
git commit -m "feat(ui): episode detail and decisions - offered from the card, refusals and failures said, records without the narrator answer" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Chunk 3: e2e 와 마무리

### Task 7: 흉내 낸 API 로 화면 전체를 본다

**Files:**
- Create: `authoring-ui/e2e/episodes.spec.ts`

- [ ] **Step 1: 쓴다**

```ts
// Episodes tab end to end against a stubbed /api/episodes (plan B3c-2). Needs only the Vite dev server.
// Uses the installed Google Chrome: this machine's Playwright cache lacks the chromium revision 1.61 wants.
import { test, expect, type Page } from "@playwright/test";

test.use({ channel: "chrome" });

type Call = { method: string; path: string; headers: Record<string, string>; body?: unknown };
type Answer = { status: number; body: unknown };

const ID = "ep:k-1/r1";
const P = "/api/episodes/ep%3Ak-1/r1";
const VERSION = "sha256:" + "a".repeat(64);
const LIST = [{ instanceId: ID, workflowId: "ep:k-1", openedAt: "2026-10-02T00:00:00Z", lastAt: "2026-10-02T00:01:00Z", lastPhase: "AWAITING_APPROVAL", notices: 1, recordLag: false }];

function view(over: Record<string, unknown> = {}) {
  return {
    instanceId: ID, phase: "AWAITING_APPROVAL", escalationReason: null, escalationDetail: null, attempt: 1,
    proposalId: ID + "#1", candidateId: "APPROVE_REMEDY:r:j:pick_place", candidatesVersion: VERSION,
    stateDeadlineMillis: null, episodeDeadlineMillis: null, historyJson: "[]", unknownsJson: "[]", symptoms: 1,
    candidateJson: null, candidatesJson: JSON.stringify([{ candidateId: "ESCALATE", kind: "ESCALATE", ref: null }]),
    diagnosisJson: null, joinedAfterSnapshot: 0, ...over,
  };
}

function card(over: Record<string, unknown> = {}) {
  return {
    unknowns: [{ subject: { robotId: "r" }, what: "OBSERVATION_ABSENT", since: null, source: "s" }],
    symptoms: [{ searchId: "s-1" }],
    proposal: { candidateId: "APPROVE_REMEDY:r:j:pick_place", kind: "APPROVE_REMEDY", ref: { robotId: "r", jobOrderId: "j", searchId: "s-1" } },
    rationale: "다시 한다 [출처: SOP-01, §5.1].", rationaleMissing: false,
    guidance: [{ label: "절차", text: "먼저 본다." }, { label: "금지", text: "반복하지 않는다." }],
    citations: [{ title: "SOP-01", section: "§5.1", verified: true }], uncitedSentences: [],
    unverifiedCitations: [], unlocatedCitations: 0, unverifiedNumbers: [], unlocatedNumbers: 0,
    precondition: null, joinedAfterSnapshot: [],
    decision: { update: "decide", proposalId: ID + "#1", candidatesVersion: VERSION, deadlineMillis: null },
    identityAssurance: "SELF_ASSERTED", responseUnreadable: false, diagnosisFromRecord: false, diagnosisRefused: null,
    factsBehind: false, joinedAfterSnapshotCount: null, diagnosisRefusedDetail: null, diagnosisAttempt: 1, ...over,
  };
}

function detail(id: string, v: Record<string, unknown> | null, c: Record<string, unknown> | null, events: unknown[] = []) {
  return { instanceId: id, view: v, viewError: null, card: c, events, notices: [] };
}

/** Stubs /api/episodes**: GETs from [details], POSTs from [onPost]; every call is remembered in [calls]. */
async function stub(page: Page, details: Record<string, unknown>, onPost: (path: string) => Answer, notices: unknown[] = []) {
  const calls: Call[] = [];
  await page.route("**/api/episodes**", async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname;   // keeps %3A
    calls.push({ method: req.method(), path, headers: req.headers(), body: req.method() === "POST" ? req.postDataJSON() : undefined });
    const json = (status: number, body: unknown) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (req.method() === "GET" && path === "/api/episodes") return json(200, LIST);
    if (req.method() === "GET" && path === "/api/episodes/notices") return json(200, notices);
    if (req.method() === "GET" && details[path]) return json(200, details[path]);
    if (req.method() === "POST") { const a = onPost(path); return json(a.status, a.body); }
    return json(404, { error: `no stub ${req.method()} ${path}` });
  });
  return calls;
}

async function open(page: Page, operator = "op-1") {
  await page.goto("/");
  await page.getByRole("button", { name: /episodes/i }).click();
  await page.getByTestId("operator-name").fill(operator);
  await page.getByTestId("episode-row-" + ID).click();
  await expect(page.getByTestId("operator-card")).toBeVisible();
}
```

시험(각 `test(...)`, 위 보조 함수를 쓴다):

1. **카드 순서와 문구**
   - `card-unknowns` 의 `boundingBox().y` 가 `card-proposal` 보다 작다.
   - `card-symptoms` · `card-rationale` · `card-guidance` 가 보인다.
   - 「근거 꾸러미에 있던 문서」가 보인다.
   - `card-identity` 에 「스스로 밝힌 신원」이 있다.
   - `candidates` 를 펼치면 「ESCALATE」가 보인다(`ref: null` 인 후보에서도 화면이 깨지지 않는다).
2. **승인:** `decide-approve` 를 누르면 POST 가 하나 나가고, 응답 `{reply:"ACCEPTED"}` 에 `decision-result` 가 「받아들여짐」을 보인다.
   - 그 POST 의 `path` 는 `P + "/decide"`, `headers["x-koshei-operator"]` 는 `"op-1"` 이다.
   - `body` 는 `{proposalId: ID+"#1", sawCandidatesVersion: VERSION, approve: true}` 이고, `operatorId` 키가 없다.
3. **거절 값:** 응답이 `{reply:"REFUSED_STALE"}` 이면 「낡은 카드」가 보인다.
4. **다른 에피소드:** 409 `{error:"EPISODE_MOVED", currentInstanceId:"ep:k-1/r2"}` 이면 「다른 에피소드」와 `open-moved` 가 보인다.
   - `open-moved` 를 누르면 `GET /api/episodes/ep%3Ak-1/r2` 가 나간다(그 상세도 `details` 에 둔다).
5. **시간 초과는 다시 읽는다**
   - `page.clock.install()` 을 `goto` 전에 부른다.
   - 카드가 보인 뒤 `await page.clock.pauseAt(await page.evaluate(() => Date.now() + 50))` 를 부르고, `await page.waitForTimeout(500)` 으로 진행 중인 GET 이 비워지길 기다린다.
   - 그때의 상세 GET 수를 센다.
   - 504 `{error:"EPISODE_TIMEOUT", outcome:"UNKNOWN", instanceId: ID}` 응답에 「결과 모름」이 보이고, `expect.poll` 로 상세 GET 수가 늘었음을 확인한다.
6. **이름 없음 · 너무 김 · ASCII 아님:** 운영자 이름을 비우거나, 129자로 하거나, `"김철수"` 로 하면 `decide-approve` 가 `disabled` 이고 `operator-problem` 이 보인다.
7. **전제 확인:** 상세는 `view({phase:"UNKNOWN_PRECONDITION"})` 와 `card({decision:{update:"confirm", proposalId: ID+"#1", candidatesVersion: VERSION, deadlineMillis: null}, precondition: "(r, j)에 searchId s-1 뒤로 더 새 탐색 줄이 없다"})` 다.
   - `card-precondition` 이 그 글자를 `toContainText` 로 가진다.
   - `confirm-precondition-holds` 를 누르면 본문이 `{kind:"PRECONDITION", candidateId: "APPROVE_REMEDY:r:j:pick_place", proposalId: ID+"#1", holds: true}` 이고, `proposition` 키가 없다.
8. **결과를 모를 때의 결과 확인:** 상세는 `view({phase:"UNKNOWN_OUTCOME"})` 와 `card({decision: null, unknowns: [{subject:{candidateId:"APPROVE_REMEDY:r:j:pick_place"}, what:"OUTCOME", since:null, source:"s"}]})` 다.
   - `confirm-unknown-0` 이 없다(코어가 늘 거절하는 항목).
   - `confirm-outcome-done` 을 누르면 본문이 `{kind:"OUTCOME", candidateId: "APPROVE_REMEDY:r:j:pick_place", proposalId: ID+"#1", holds: true}` 다.
9. **사람에게 넘어간 뒤의 인수와 닫기:** 상세는 `view({phase:"ESCALATED", escalationReason:"TAKEN_OVER"})` 와 `card({decision:null, unknowns: []})` 다.
   - `takeover` 가 없다.
   - `close-outcome` 이 비면 `close` 가 `disabled` 다.
   - 「현장 처리」를 넣고 누르면 본문이 `{outcome:"현장 처리"}` 다.
10. **인수(진행 중):** 상세는 `view({phase:"DIAGNOSING"})` 와 `card({decision:null})` 다.
    - `page.once("dialog", d => d.accept())` 를 건 뒤 `takeover` 를 누르면, `P + "/takeover"` 로 본문 없는 POST 가 나간다.
11. **카드의 규칙들**
    - `card({diagnosisRefused:"DIAGNOSIS_FAILED", diagnosisRefusedDetail:"no verified citation", rationale:null, guidance:[], citations:[]})` 이면 `card-refused` 에 「판정이 거절한 답」과 "no verified citation" 이 있고, `card-rationale` 은 없다.
    - `card({rationale:null, rationaleMissing:true})` 이면 `card-rationale` 에 「이유 없음」이 있다.
12. **기록에 `cause` 가 나오지 않는다:** 상세 `events` 에 `{seq:5, kind:"DIAGNOSIS_RESULT", payload:{attempt:1, verdict:"PROPOSED", response:{cause:"SECRET_CAUSE", rationale:"r"}}, at:"2026-10-02T00:00:00Z"}` 를 넣는다.
    - `records` 를 펼친 뒤 페이지에 "SECRET_CAUSE" 가 없다(`expect(page.getByText("SECRET_CAUSE")).toHaveCount(0)`).
13. **사람 과업 알림:** `open()` 을 부르지 않는다(이미 열린 상세를 다시 여는 것이 되기 때문이다).
    - 알림 흐름에 `{id:7, instanceId:"ep:k-2/r1", at:"2026-10-02T00:00:00Z", notice:{kind:"PERSON_TASK", idempotencyKey:"k", intent:{candidate:{kind:"CHOOSE_SOURCE"}}}}` 를 넣는다.
    - `details["/api/episodes/ep%3Ak-2/r1"]` 도 둔다.
    - 탭을 연 뒤 `notice-7` 에 「사람 과업」과 `CHOOSE_SOURCE` 가 보인다.
    - `notice-open-7` 을 누르면 `GET /api/episodes/ep%3Ak-2/r1` 이 나가고, 상세 머리에 `ep:k-2/r1` 이 보인다.

- [ ] **Step 2: 돌린다**

Run: `npx playwright test e2e/episodes.spec.ts --project=chromium --no-deps`
Expected: 13개 PASS

실패하면 시험이 아니라 화면 쪽을 먼저 의심한다. 시험을 바꾼 경우에는 무엇을 왜 바꿨는지 보고한다.

- [ ] **Step 3: 커밋한다**(뿌리에서)

```bash
git add authoring-ui/e2e/episodes.spec.ts
git commit -m "test(ui): the Episodes tab end to end against a stubbed /api/episodes" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 8: 빌드와 문서

- [ ] **Step 1: 빌드한다**

Run: `npm run build`(= `tsc && vite build`)
Expected: 성공. `dist/` 는 gitignore 에 있으므로 커밋하지 않는다.

- [ ] **Step 2: 설계 문서 §16 에 한 줄을 더한다**

계획 B 줄 아래의 B3c 주석 다음에 「B3c-2(화면) 끝(2026-10-02)」을 더한다. 끝에 다음을 짧게 덧붙인다.
- 탭 이름
- e2e 는 흉내 낸 API 와 설치된 Chrome 으로 돈다는 것
- 실제 백엔드 e2e 는 아직 없다는 것

- [ ] **Step 3: 커밋한다**(뿌리에서)

```bash
git add docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md
git commit -m "docs(design): plan B3c-2 done - the Episodes tab" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
