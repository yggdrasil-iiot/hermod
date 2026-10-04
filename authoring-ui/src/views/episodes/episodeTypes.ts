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
