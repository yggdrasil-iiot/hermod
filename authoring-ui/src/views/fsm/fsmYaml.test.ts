import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { parseFsmYaml, emitFsmYaml } from "./fsmYaml";

const FSM_DIR = resolve(__dirname, "../../../../model/fsm");
const read = (f: string) => readFileSync(resolve(FSM_DIR, f), "utf8");

describe("parseFsmYaml", () => {
  it("parses packml-line1.v1.yaml with all fields", () => {
    const spec = parseFsmYaml(read("packml-line1.v1.yaml"));
    expect(spec.name).toBe("packml-line1");
    expect(spec.unit).toBe("line1");
    expect(spec.version).toBe("v1");
    expect(spec.stateNode).toBe("line1.stateCurrent");
    expect(spec.states).toContainEqual({ id: "Idle", code: 4 });
    expect(spec.states).toHaveLength(5);
    // hermod transition carries an action
    const load = spec.transitions.find((t) => t.id === "loadRecipe")!;
    expect(load.driver).toBe("hermod");
    expect(load.command).toBe("LoadRecipe");
    expect(load.action).toEqual({ workflow: "ot-recipe-stage-activate" });
    // reactive transition: command null, no action
    const complete = spec.transitions.find((t) => t.id === "complete")!;
    expect(complete.command).toBeNull();
    expect(complete.driver).toBe("field");
    expect(complete.action).toBeUndefined();
  });

  it("treats an omitted version as empty string (legacy base)", () => {
    const spec = parseFsmYaml(read("packml-line1.yaml"));
    expect(spec.version).toBe("");
  });
});

describe("emitFsmYaml round-trip", () => {
  // parse -> emit -> re-parse yields a structurally equal spec, for every real fixture.
  for (const f of [
    "packml-line1.yaml",
    "packml-line1.v1.yaml",
    "packml-line1.v2.yaml",
    "packml-line2.v1.yaml",
  ]) {
    it(`round-trips ${f}`, () => {
      const spec = parseFsmYaml(read(f));
      const reparsed = parseFsmYaml(emitFsmYaml(spec));
      expect(reparsed).toEqual(spec);
    });
  }

  it("preserves command: null and omits version when empty", () => {
    const spec = parseFsmYaml(read("packml-line1.yaml"));
    const out = emitFsmYaml(spec);
    expect(out).not.toMatch(/^version:/m); // legacy base: no version line
    expect(out).toMatch(/command: null/); // reactive transition kept literally
    expect(out).toMatch(/action: \{ workflow: ot-recipe-stage-activate \}/);
  });

  it("emits a version line when version is set", () => {
    const spec = parseFsmYaml(read("packml-line1.v1.yaml"));
    expect(emitFsmYaml(spec)).toMatch(/^version: v1$/m);
  });
});
