import { describe, it, expect } from "vitest";
import {
  splitInstance, episodePath, phaseChip, isTerminalPhase, replyText, failureText, mergeNotices, overlapAfter,
  noticeText, remainingText, operatorProblem, decideBody, preconditionBody, outcomeBody, unknownBody, closeBody,
  availableActions, confirmableUnknown, recordPayload, foundInText, parseHistory, parseCandidates, escalationDetailText,
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

describe("escalationDetailText", () => {
  it("names the narrator's out-of-list pick as the narrator's, and keeps it short (R13)", () => {
    expect(escalationDetailText("picked: robot-9")).toBe("narrator 가 고른 후보 밖 값 · narrator's out-of-list pick: robot-9");
    const long = escalationDetailText("picked: " + "x".repeat(500));
    expect(long.endsWith("x".repeat(200) + "…")).toBe(true);
    expect(long).not.toContain("x".repeat(201));
  });
  it("leaves any other detail as it came", () => {
    expect(escalationDetailText("saw v1, sent v2")).toBe("saw v1, sent v2");
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
