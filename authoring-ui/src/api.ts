// Typed fetch wrappers for the five :authoring-api endpoints. All paths are relative ("/api/...")
// so the Vite dev proxy (→ http://127.0.0.1:18090) handles them transparently.

import type {
  AuthoredContract,
  BlockRow,
  PaletteCard,
  PublishResponse,
  ValidationResponse,
  WorkflowDef,
  ValidateResult,
  WorkflowDefRow,
  RunRequest,
  NodeStates,
  RunSummary,
  CompensationEvent,
} from "./types";
import type { FsmSpec } from "./views/fsm/fsmTypes";

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public body?: unknown,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function asJson<T>(res: Response): Promise<T> {
  const text = await res.text();
  const parsed = text ? JSON.parse(text) : null;
  if (!res.ok) {
    throw new ApiError(`${res.status} ${res.statusText}`, res.status, parsed);
  }
  return parsed as T;
}

/** GET /api/palette → operator's-eye palette (canvas-ready, non-deprecated, SemVer-latest per id). */
export async function getPalette(): Promise<PaletteCard[]> {
  return asJson<PaletteCard[]>(await fetch("/api/palette"));
}

/** GET /api/blocks → every block (incl. incomplete + deprecated) with readiness diagnostics. */
export async function getBlocks(): Promise<BlockRow[]> {
  return asJson<BlockRow[]>(await fetch("/api/blocks"));
}

/** POST /api/contracts/validate — raw authored-contract JSON body → runtime + readiness verdict. */
export async function validate(contract: AuthoredContract): Promise<ValidationResponse> {
  const res = await fetch("/api/contracts/validate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(contract),
  });
  return asJson<ValidationResponse>(res);
}

/** POST /api/publish — multipart: `contract` (JSON part) + `jar` (file part). */
export async function publish(
  contract: AuthoredContract,
  jar: File,
): Promise<PublishResponse> {
  const form = new FormData();
  // `contract` is read server-side as MultipartFile bytes → ManifestLoader.fromJson, so send it as a
  // JSON blob (a named part), NOT a plain string field.
  form.append(
    "contract",
    new Blob([JSON.stringify(contract)], { type: "application/json" }),
    "contract.json",
  );
  form.append("jar", jar, jar.name);
  const res = await fetch("/api/publish", { method: "POST", body: form });
  // PublishController returns 200 {ok:true} on success, 400 {ok:false, errors:[...]} on a gating
  // rejection, and 403 {ok:false, errors:[...]} when publish is disabled (KOSHEI_PUBLISH_DISABLED).
  // All three carry a JSON PublishResponse body that the UI renders as a value, so parse the body for
  // any of them rather than throwing. Only genuine transport / 5xx / non-JSON errors throw.
  if (res.status === 200 || res.status === 400 || res.status === 403) {
    const text = await res.text();
    try {
      const parsed = JSON.parse(text) as PublishResponse;
      // A 400 must carry the {ok:false,errors} gating body; a malformed/empty 400 is a real error.
      if (parsed && typeof parsed.ok === "boolean") return parsed;
    } catch {
      // fall through to the thrown error below
    }
    throw new ApiError(`${res.status} ${res.statusText}`, res.status, text || null);
  }
  throw new ApiError(`${res.status} ${res.statusText}`, res.status);
}

/** POST /api/blocks/{id}/{version}/deprecate → 204 (flipped) | 404 (no such id/version). */
export async function deprecate(id: string, version: string): Promise<void> {
  const res = await fetch(
    `/api/blocks/${encodeURIComponent(id)}/${encodeURIComponent(version)}/deprecate`,
    { method: "POST" },
  );
  if (res.status === 204) return;
  if (res.status === 404) throw new ApiError("no such block (id/version)", 404);
  throw new ApiError(`${res.status} ${res.statusText}`, res.status);
}

/** POST /api/workflows/validate — compile-only verdict (no persist). */
export async function validateWorkflow(def: WorkflowDef): Promise<ValidateResult> {
  return asJson<ValidateResult>(await fetch("/api/workflows/validate", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(def),
  }));
}

/** POST /api/workflows?version= — save = deploy. 200 {name,version} | 400 ValidateResult. */
export async function saveWorkflow(def: WorkflowDef, version: string): Promise<{ name: string; version: string }> {
  const res = await fetch(`/api/workflows?version=${encodeURIComponent(version)}`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(def),
  });
  return asJson<{ name: string; version: string }>(res);   // 400 throws ApiError carrying the ValidateResult body
}

/** GET /api/workflows → every saved (name, version, deployed) row. */
export async function listWorkflows(): Promise<WorkflowDefRow[]> {
  return asJson<WorkflowDefRow[]>(await fetch("/api/workflows"));
}

/** GET /api/workflows/{name}/{version} → the stored WorkflowDef (steps may carry null ids). */
export async function getWorkflow(name: string, version: string): Promise<WorkflowDef> {
  return asJson<WorkflowDef>(await fetch(`/api/workflows/${encodeURIComponent(name)}/${encodeURIComponent(version)}`));
}

/** POST /api/workflows/{name}/{version}/run → starts a run; returns {runId}. RunRequest tunes a demo (failAt/slowMs). */
export async function runWorkflow(name: string, version: string, req: RunRequest): Promise<{ runId: string }> {
  return asJson<{ runId: string }>(await fetch(`/api/workflows/${encodeURIComponent(name)}/${encodeURIComponent(version)}/run`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(req),
  }));
}

/** GET /api/runs/{runId}[?wait=true] → run status; `wait` long-polls for terminal state. */
export async function getRunStatus(runId: string, wait = false): Promise<{ status?: string; completed?: boolean; compensatedInReverseOrder?: string[] }> {
  return asJson(await fetch(`/api/runs/${encodeURIComponent(runId)}${wait ? "?wait=true" : ""}`));
}

/** GET /api/runs/{runId}/nodes → live nodeId→NodeState snapshot for canvas overlay. */
export async function getNodeStates(runId: string): Promise<NodeStates> {
  return asJson<NodeStates>(await fetch(`/api/runs/${encodeURIComponent(runId)}/nodes`));
}

/** GET /api/runs → recorded runs (newest-first), each with a best-effort live status. */
export async function listRuns(): Promise<RunSummary[]> {
  return asJson<RunSummary[]>(await fetch("/api/runs"));
}

/** GET /api/runs/{runId}/compensation → ordered per-step compensation results (best-effort; [] if none/aged). */
export async function getCompensationTimeline(runId: string): Promise<CompensationEvent[]> {
  return asJson<CompensationEvent[]>(await fetch(`/api/runs/${encodeURIComponent(runId)}/compensation`));
}

/** POST /api/runs/{runId}/approve → releases a human-gate (no body). Throws ApiError on non-2xx. */
export async function approveRun(runId: string): Promise<void> {
  const res = await fetch(`/api/runs/${encodeURIComponent(runId)}/approve`, { method: "POST" });
  if (!res.ok) throw new ApiError(`${res.status} ${res.statusText}`, res.status);
}

/** POST /api/runs/{runId}/reject → rejects a human-gate with {reason}. Throws ApiError on non-2xx. */
export async function rejectRun(runId: string, reason: string): Promise<void> {
  const res = await fetch(`/api/runs/${encodeURIComponent(runId)}/reject`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ reason }),
  });
  if (!res.ok) throw new ApiError(`${res.status} ${res.statusText}`, res.status);
}

/** POST /api/runs/{runId}/retry → re-attempt a PARKED node (interactive run). Throws ApiError on non-2xx. */
export async function retryNode(runId: string, nodeId: string): Promise<void> {
  const res = await fetch(`/api/runs/${encodeURIComponent(runId)}/retry`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ nodeId }),
  });
  if (!res.ok) throw new ApiError(`${res.status} ${res.statusText}`, res.status);
}

/** POST /api/runs/{runId}/abort → graceful reverse-topo compensation of the run. Throws ApiError on non-2xx. */
export async function abortRun(runId: string): Promise<void> {
  const res = await fetch(`/api/runs/${encodeURIComponent(runId)}/abort`, { method: "POST" });
  if (!res.ok) throw new ApiError(`${res.status} ${res.statusText}`, res.status);
}

/** POST /api/fsm/assist — NL prompt -> draft FsmSpec. 200 FsmSpec | 422 {issues} | 502/503 {error} (throws ApiError). */
export async function assistFsm(prompt: string, context?: FsmSpec): Promise<FsmSpec> {
  return asJson<FsmSpec>(await fetch("/api/fsm/assist", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ prompt, context: context ?? null }),
  }));
}
