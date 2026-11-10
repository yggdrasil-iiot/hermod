import { describe, it, expect } from "vitest";
import { validateFsm } from "./fsmValidate";
import type { FsmSpec } from "./fsmTypes";

const good: FsmSpec = {
  name: "u", unit: "u1", version: "v1", stateNode: "u1.state",
  states: [{ id: "Idle", code: 4 }, { id: "Run", code: 6 }],
  transitions: [
    { id: "load", from: "Idle", to: "Idle", command: "LoadRecipe", driver: "hermod", action: { workflow: "ot-x" } },
    { id: "start", from: "Idle", to: "Run", command: "Start", driver: "field" },
  ],
};
const msgs = (s: FsmSpec) => validateFsm(s).map((i) => i.message);

describe("validateFsm", () => {
  it("passes a well-formed spec", () => {
    expect(validateFsm(good)).toEqual([]);
  });
  it("flags blank stateNode", () => {
    expect(msgs({ ...good, stateNode: "" })).toContainEqual(expect.stringMatching(/stateNode/));
  });
  it("flags duplicate state id and duplicate code", () => {
    const bad = { ...good, states: [{ id: "Idle", code: 4 }, { id: "Idle", code: 4 }] };
    const m = msgs(bad);
    expect(m).toContainEqual(expect.stringMatching(/duplicate state id/i));
    expect(m).toContainEqual(expect.stringMatching(/duplicate state code/i));
  });
  it("flags duplicate transition id", () => {
    const bad = { ...good, transitions: [good.transitions[0], { ...good.transitions[1], id: "load" }] };
    expect(msgs(bad)).toContainEqual(expect.stringMatching(/duplicate transition id/i));
  });
  it("flags from/to not a declared state", () => {
    const bad = { ...good, transitions: [{ ...good.transitions[1], from: "Ghost" }] };
    expect(msgs(bad)).toContainEqual(expect.stringMatching(/unknown state/i));
  });
  it("flags bad driver", () => {
    const bad = { ...good, transitions: [{ ...good.transitions[1], driver: "nope" }] };
    expect(msgs(bad)).toContainEqual(expect.stringMatching(/driver/i));
  });
  it("flags hermod without action.workflow", () => {
    const bad = { ...good, transitions: [{ id: "t", from: "Idle", to: "Run", command: "C", driver: "hermod" }] };
    expect(msgs(bad)).toContainEqual(expect.stringMatching(/hermod.*workflow/i));
  });
  it("flags field with an action", () => {
    const bad = { ...good, transitions: [{ id: "t", from: "Idle", to: "Run", command: "C", driver: "field", action: { workflow: "ot-x" } }] };
    expect(msgs(bad)).toContainEqual(expect.stringMatching(/field.*action/i));
  });
  it("flags a duplicate (from, command) pair", () => {
    const bad = { ...good, transitions: [good.transitions[1], { ...good.transitions[1], id: "start2" }] };
    expect(msgs(bad)).toContainEqual(expect.stringMatching(/duplicate transition from Idle/i));
  });
  it("does NOT confuse command null with the literal string 'null'", () => {
    const ok = {
      ...good,
      transitions: [
        { id: "a", from: "Idle", to: "Run", command: null, driver: "field" },
        { id: "b", from: "Idle", to: "Run", command: "null", driver: "field" },
      ],
    };
    expect(msgs(ok)).not.toContainEqual(expect.stringMatching(/duplicate transition/i));
  });
  it("flags empty states / empty transitions", () => {
    expect(msgs({ ...good, states: [], transitions: [] })).toEqual(
      expect.arrayContaining([
        expect.stringMatching(/at least one state/i),
        expect.stringMatching(/at least one transition/i),
      ]),
    );
  });
});
