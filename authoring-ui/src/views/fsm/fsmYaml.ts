import { parse as yamlParse } from "yaml";
import type { FsmSpec, FsmState, FsmTransition } from "./fsmTypes";

/** Parse Git-canonical FSM YAML into an FsmSpec. Throws on malformed YAML (caller = fail-safe). */
export function parseFsmYaml(text: string): FsmSpec {
  const raw = yamlParse(text) as Record<string, unknown> | null;
  if (raw == null || typeof raw !== "object" || Array.isArray(raw)) {
    throw new Error("FSM spec is empty or not a mapping");
  }
  const states: FsmState[] = Array.isArray(raw.states)
    ? (raw.states as Record<string, unknown>[]).map((s) => ({
        id: String(s.id ?? ""),
        code: Number(s.code ?? 0),
      }))
    : [];
  const transitions: FsmTransition[] = Array.isArray(raw.transitions)
    ? (raw.transitions as Record<string, unknown>[]).map((t) => {
        const action = t.action as Record<string, unknown> | undefined;
        const tr: FsmTransition = {
          id: String(t.id ?? ""),
          from: String(t.from ?? ""),
          to: String(t.to ?? ""),
          command: t.command == null ? null : String(t.command),
          driver: String(t.driver ?? ""),
        };
        if (action && action.workflow != null) {
          tr.action = { workflow: String(action.workflow) };
        }
        return tr;
      })
    : [];
  return {
    name: String(raw.name ?? ""),
    unit: String(raw.unit ?? ""),
    version: raw.version == null ? "" : String(raw.version),
    stateNode: String(raw.stateNode ?? ""),
    states,
    transitions,
  };
}

/**
 * Emit an FsmSpec as Git-canonical house-style YAML:
 *  - top-level keys block-style in canonical order (version omitted when "");
 *  - states/transitions as flow-style inline maps;
 *  - command: null preserved; action only for hermod transitions.
 * Values in this schema are NON-EMPTY simple identifiers (ids, dotted node names,
 * hyphenated workflow names, integer codes) — safe unquoted in YAML flow context.
 * Out of scope for this thin PoC (would corrupt the flow map / round-trip): empty-string
 * values (re-parse as null) and values containing YAML flow indicators (`,` `:` `{` `}`) or
 * a bare keyword (`null`/`true`/`false`). The identifier-only FSM schema never hits these.
 */
export function emitFsmYaml(spec: FsmSpec): string {
  const lines: string[] = [];
  lines.push(`name: ${spec.name}`);
  lines.push(`unit: ${spec.unit}`);
  if (spec.version !== "") lines.push(`version: ${spec.version}`);
  lines.push(`stateNode: ${spec.stateNode}`);

  lines.push("states:");
  for (const s of spec.states) {
    lines.push(`  - { id: ${s.id}, code: ${s.code} }`);
  }

  lines.push("transitions:");
  for (const t of spec.transitions) {
    const parts = [
      `id: ${t.id}`,
      `from: ${t.from}`,
      `to: ${t.to}`,
      `command: ${t.command === null ? "null" : t.command}`,
      `driver: ${t.driver}`,
    ];
    if (t.action) parts.push(`action: { workflow: ${t.action.workflow} }`);
    lines.push(`  - { ${parts.join(", ")} }`);
  }
  return lines.join("\n") + "\n";
}
