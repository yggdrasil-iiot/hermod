import { describe, it, expect } from "vitest";
import { toGraph, toSpec, type FsmEdgeData } from "./fsmGraph";
import type { FsmSpec } from "./fsmTypes";

const spec: FsmSpec = {
  name: "packml-line1", unit: "line1", version: "v1", stateNode: "line1.stateCurrent",
  states: [{ id: "Idle", code: 4 }, { id: "Execute", code: 6 }, { id: "Aborted", code: 9 }],
  transitions: [
    { id: "loadRecipe", from: "Idle", to: "Idle", command: "LoadRecipe", driver: "hermod", action: { workflow: "ot-recipe-stage-activate" } },
    { id: "start", from: "Idle", to: "Execute", command: "Start", driver: "field" },
    { id: "abort", from: "Execute", to: "Aborted", command: "Abort", driver: "field" },
  ],
};

describe("fsmGraph", () => {
  it("round-trips spec -> graph -> spec preserving order (incl. self-loop)", () => {
    const { nodes, edges } = toGraph(spec);
    expect(nodes).toHaveLength(3);
    expect(edges).toHaveLength(3);
    // self-loop: loadRecipe Idle -> Idle has source === target
    const self = edges.find((e) => e.id === "loadRecipe")!;
    expect(self.source).toBe(self.target);
    const back = toSpec(
      { name: spec.name, unit: spec.unit, version: spec.version, stateNode: spec.stateNode },
      nodes,
      edges,
    );
    expect(back).toEqual(spec);
  });

  it("carries hermod workflow onto the edge and drops action for field", () => {
    const { edges } = toGraph(spec);
    expect((edges.find((e) => e.id === "loadRecipe")!.data as FsmEdgeData).workflow).toBe("ot-recipe-stage-activate");
    expect((edges.find((e) => e.id === "start")!.data as FsmEdgeData).workflow).toBeUndefined();
  });
});
