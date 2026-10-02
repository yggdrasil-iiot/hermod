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

/** A record as the screen shows it: a diagnosis record loses its narrator answer — `cause`, refused and superseded answers stay recorded only (§9.5). */
export function recordPayload(kind: string, payload: unknown): unknown {
  if (kind !== "DIAGNOSIS_RESULT" || !payload || typeof payload !== "object") return payload;
  const rest: Record<string, unknown> = { ...(payload as Record<string, unknown>) };
  delete rest.response;
  delete rest.rawText;
  return rest;
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
