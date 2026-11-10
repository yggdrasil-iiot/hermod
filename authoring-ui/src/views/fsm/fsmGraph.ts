import type { Node, Edge } from "@xyflow/react";
import type { FsmSpec, FsmState, FsmTransition } from "./fsmTypes";

// Node carries the logical stateId + code. Node ID is synthetic (n0,n1,…) so renaming a
// state in the inspector never forces edge rewiring — edges reference synthetic ids.
export interface StateNodeData {
  stateId: string;
  code: number;
  [key: string]: unknown; // React Flow node data is an index type
}

export interface FsmEdgeData {
  command: string | null;
  driver: string;
  workflow?: string;
  [key: string]: unknown;
}

export interface FsmMeta {
  name: string;
  unit: string;
  version: string;
  stateNode: string;
}

/** FsmSpec -> React Flow nodes/edges. Simple left-to-right layout (layout-only, not in the spec). */
export function toGraph(spec: FsmSpec): { nodes: Node<StateNodeData>[]; edges: Edge<FsmEdgeData>[] } {
  const nodeIdByState = new Map<string, string>();
  const nodes: Node<StateNodeData>[] = spec.states.map((s, i) => {
    const nid = `n${i}`;
    nodeIdByState.set(s.id, nid);
    return {
      id: nid,
      type: "state",
      position: { x: 60 + i * 220, y: 80 + (i % 2) * 140 },
      data: { stateId: s.id, code: s.code },
    };
  });
  const edges: Edge<FsmEdgeData>[] = spec.transitions.map((t) => ({
    id: t.id,
    source: nodeIdByState.get(t.from) ?? t.from,
    target: nodeIdByState.get(t.to) ?? t.to,
    label: t.command === null ? "(reactive)" : t.command,
    className: t.driver === "hermod" ? "fsm-edge-hermod" : "fsm-edge-field",
    data: { command: t.command, driver: t.driver, workflow: t.action?.workflow },
  }));
  return { nodes, edges };
}

/** React Flow nodes/edges -> FsmSpec. Resolves synthetic node ids back to logical stateIds. */
export function toSpec(meta: FsmMeta, nodes: Node<StateNodeData>[], edges: Edge<FsmEdgeData>[]): FsmSpec {
  const stateByNodeId = new Map<string, string>();
  const states: FsmState[] = nodes.map((n) => {
    stateByNodeId.set(n.id, n.data.stateId);
    return { id: n.data.stateId, code: n.data.code };
  });
  const transitions: FsmTransition[] = edges.map((e) => {
    const d = (e.data ?? { command: null, driver: "field" }) as FsmEdgeData;
    const tr: FsmTransition = {
      id: e.id,
      from: stateByNodeId.get(e.source) ?? e.source,
      to: stateByNodeId.get(e.target) ?? e.target,
      command: d.command ?? null,
      driver: d.driver,
    };
    if (d.driver === "hermod" && d.workflow) tr.action = { workflow: d.workflow };
    return tr;
  });
  return { ...meta, states, transitions };
}
