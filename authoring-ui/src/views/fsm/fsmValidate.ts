import type { FsmSpec } from "./fsmTypes";

// A validation issue. `stateId` / `transitionId` let the canvas highlight the offending element.
export interface FsmIssue {
  message: string;
  stateId?: string;
  transitionId?: string;
}

const DRIVERS = new Set(["hermod", "field"]);

/**
 * Structural validation — a client-side mirror of Kotlin yggdrasil.hermod.opcua.FsmValidator.
 * ADVISORY ONLY: cross-artifact checks (stateNode ∈ site, action.workflow ∈ ot-*) and the
 * final fail-closed authority live in the conformance gate. This never blocks; it guides.
 */
export function validateFsm(spec: FsmSpec): FsmIssue[] {
  const issues: FsmIssue[] = [];

  if (spec.stateNode.trim() === "") issues.push({ message: "stateNode must not be blank" });

  if (spec.states.length === 0) issues.push({ message: "must declare at least one state" });
  const seenStateId = new Set<string>();
  const seenCode = new Set<number>();
  for (const s of spec.states) {
    if (seenStateId.has(s.id)) issues.push({ message: `duplicate state id '${s.id}'`, stateId: s.id });
    seenStateId.add(s.id);
    if (seenCode.has(s.code)) issues.push({ message: `duplicate state code ${s.code}`, stateId: s.id });
    seenCode.add(s.code);
  }

  if (spec.transitions.length === 0) issues.push({ message: "must declare at least one transition" });
  const seenTid = new Set<string>();
  const seenFromCmd = new Set<string>();
  for (const t of spec.transitions) {
    if (t.id.trim() === "") issues.push({ message: "transition id must not be blank", transitionId: t.id });
    if (seenTid.has(t.id)) issues.push({ message: `duplicate transition id '${t.id}'`, transitionId: t.id });
    seenTid.add(t.id);

    if (!seenStateId.has(t.from)) issues.push({ message: `transition '${t.id}' from unknown state '${t.from}'`, transitionId: t.id });
    if (!seenStateId.has(t.to)) issues.push({ message: `transition '${t.id}' to unknown state '${t.to}'`, transitionId: t.id });

    if (!DRIVERS.has(t.driver)) issues.push({ message: `transition '${t.id}' has invalid driver '${t.driver}'`, transitionId: t.id });
    if (t.driver === "hermod" && !t.action?.workflow?.trim()) {
      issues.push({ message: `hermod transition '${t.id}' must declare action.workflow`, transitionId: t.id });
    }
    if (t.driver === "field" && t.action) {
      issues.push({ message: `field transition '${t.id}' must not declare an action`, transitionId: t.id });
    }

    // JSON-encode the (from, command) pair so null is unambiguous vs the literal string "null"
    // and no separator char can collide two distinct pairs (e.g. from="a b" vs command="b c").
    const key = JSON.stringify([t.from, t.command]);
    if (seenFromCmd.has(key)) issues.push({ message: `duplicate transition from ${t.from} on command ${t.command}`, transitionId: t.id });
    seenFromCmd.add(key);
  }

  return issues;
}
