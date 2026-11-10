// Mirrors the :authoring-api JSON DTOs (yggdrasil.hermod.authoring.Dto.kt) and the authored-contract
// shape that ManifestLoader (registry/ManifestLoader.kt) parses/serialises. Keep these in lockstep
// with the Kotlin side: a key mismatch silently drops fields server-side.

// ---- Palette / browse projection (read-only, server-derived) ---------------------------------

export interface PortCard {
  name: string;
  type: string;
  label: string;
}

export interface ParamCard {
  name: string;
  type: string;
  required: boolean;
  label: string;
  help: string;
  default: string | null;
  widget: string | null;
  enumValues: string[];
}

export type Risk = "red" | "amber" | "green";

export interface PaletteCard {
  id: string;
  latestVersion: string;
  versions: string[];
  category: string;
  displayName: string;
  description: string;
  risk: Risk;
  inputs: PortCard[];
  outputs: PortCard[];
  params: ParamCard[];
  complete: boolean;
}

export interface Diagnostic {
  code: string; // C1..C5
  message: string;
}

export interface BlockRow {
  card: PaletteCard;
  deprecated: boolean;
  // server sends List<Map<String,String>>; each has code+message.
  diagnostics: Diagnostic[];
}

// ---- Validate response (POST /api/contracts/validate) ----------------------------------------

export interface ValidationResponse {
  valid: boolean;
  errors: string[];
  readiness: Diagnostic[];
  complete: boolean;
  risk: Risk;
}

// ---- Publish response (POST /api/publish) ----------------------------------------------------

export interface PublishResponse {
  ok: boolean;
  errors: string[];
}

// ---- Authored contract (ManifestLoader-JSON / ManifestDto shape) -----------------------------
// This is what the editor EMITS for /contracts/validate and /publish. It is NOT the PaletteCard
// projection (no latestVersion/risk/complete). Field names must match ManifestDto exactly.

export type BlockCategory = "source" | "transform" | "sink" | "control" | "external";
export type IdempotencyStrategy = "NONE" | "KEY_DEDUP" | "UPSERT" | "NATURAL";
export type Reversibility = "REVERSIBLE" | "MITIGATABLE" | "IRREVERSIBLE";
export type CompensationKind = "STATIC" | "CONTEXTUAL" | "NONE";
export type SideEffect = "DB_WRITE" | "EXTERNAL_CALL" | "MESSAGE_SEND" | "ACTUATION" | "NONE";
export type Widget = "text" | "number" | "select" | "secret";

export interface ParamManifest {
  name: string;
  type: string;
  required: boolean;
  label: string;
  help: string;
  default: string | null;
  widget: string | null;
  enumValues: string[];
}

export interface IoManifest {
  name: string;
  type: string;
  label: string;
}

export interface AuthoredContract {
  id: string;
  version: string;
  category: BlockCategory;
  displayName: string;
  description: string;
  params: ParamManifest[];
  inputs: IoManifest[];
  outputs: IoManifest[];
  forward: { handler: string };
  idempotency: { strategy: IdempotencyStrategy; keyExpression: string | null };
  compensation: {
    reversibility: Reversibility;
    kind: CompensationKind;
    handler: string | null;
    requiresState: string[];
  };
  stateBinding: { key: string; description: string }[];
  retry: { maxAttempts: number; backoff: { initialMs: number; maxMs: number } };
  timeoutMs: number;
  sideEffects: SideEffect[];
  human: { requireApprovalBefore: boolean };
}

export const CATEGORIES: BlockCategory[] = ["source", "transform", "sink", "control", "external"];
export const IDEMPOTENCY: IdempotencyStrategy[] = ["NONE", "KEY_DEDUP", "UPSERT", "NATURAL"];
export const REVERSIBILITY: Reversibility[] = ["REVERSIBLE", "MITIGATABLE", "IRREVERSIBLE"];
export const COMP_KIND: CompensationKind[] = ["STATIC", "CONTEXTUAL", "NONE"];
export const SIDE_EFFECTS: SideEffect[] = ["DB_WRITE", "EXTERNAL_CALL", "MESSAGE_SEND", "ACTUATION", "NONE"];
export const WIDGETS: Widget[] = ["text", "number", "select", "secret"];

export function emptyContract(): AuthoredContract {
  return {
    id: "",
    version: "1.0.0",
    category: "transform",
    displayName: "",
    description: "",
    params: [],
    inputs: [],
    outputs: [],
    forward: { handler: "" },
    idempotency: { strategy: "NONE", keyExpression: null },
    compensation: { reversibility: "REVERSIBLE", kind: "NONE", handler: null, requiresState: [] },
    stateBinding: [],
    retry: { maxAttempts: 1, backoff: { initialMs: 100, maxMs: 1000 } },
    timeoutMs: 30000,
    sideEffects: ["NONE"],
    human: { requireApprovalBefore: false },
  };
}

// ---- Workflow composition (mirrors core/WorkflowDef.kt + v0.3e endpoints) ---------------------
export interface WorkflowStep {
  blockId: string;
  pinnedVersion: string;
  id: string | null;                // explicit step id == compiler nodeId (spec §3.5); Kotlin WorkflowStep.id is String? = null, so a server-loaded def may omit it (compiler falls back to "s$index")
  params: Record<string, string>;
  wiring: Record<string, string>;   // inputName -> "upstreamStepId.outputName"
}
export interface WorkflowDef {
  name: string;
  steps: WorkflowStep[];
}
export interface ValidateResult {
  valid: boolean;
  diagnostics: string[];
  nodeCount: number;
}
export interface WorkflowDefRow {
  name: string;
  version: string;
  deployed: boolean;   // mirrors WorkflowStore.Row exactly: (name, version, deployed) — no createdAt
}
export interface RunRequest {
  runId?: string;
  failAtBlockId?: string;
  slowMs?: number;
  interactive?: boolean;   // v0.4b: park on failure for operator retry/abort
}
export type NodeState = "PENDING" | "RUNNING" | "DONE" | "FAILED" | "COMPENSATED" | "PARKED" | "COMP_FAILED" | "AWAITING_APPROVAL";
export type NodeStates = Record<string, NodeState>;   // nodeId -> state

// ---- Operator console (v0.4c) — mirrors yggdrasil.hermod.runtime.CompensationEvent -----------------------
export interface CompensationEvent {
  index: number;
  nodeId: string;
  blockId: string;
  version: string;
  outcome: "COMPENSATED" | "FAILED";
  atMillis: number;
}

// ---- Operator console (v0.4a) — mirrors yggdrasil.hermod.authoring.RunSummary ---------------------------
export interface RunSummary {
  runId: string;
  name: string;
  version: string;
  startedAt: number; // epoch millis
  status: string;    // engine status (Temporal proto enum name, e.g. WORKFLOW_EXECUTION_STATUS_*), or "UNKNOWN"
  engine: string;    // v0.6a: "temporal" | "conductor" — which engine executed this run
  awaitingApproval: boolean; // true while a run is parked at the human gate (operator legibility fix, B1)
  compOutcome?: string;      // "COMPENSATED" | "COMP_FAILED" | "NONE" — whether the run ran compensation (B3)
}
