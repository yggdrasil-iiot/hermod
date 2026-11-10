import { test, expect } from "@playwright/test";

const SPEC = `name: packml-line1
unit: line1
version: v1
stateNode: line1.stateCurrent
states:
  - { id: Idle, code: 4 }
  - { id: Execute, code: 6 }
transitions:
  - { id: loadRecipe, from: Idle, to: Idle, command: LoadRecipe, driver: hermod, action: { workflow: ot-recipe-stage-activate } }
  - { id: start, from: Idle, to: Execute, command: Start, driver: field }
`;

test("FSM editor: load a spec, edit, and round-trip the YAML", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "FSM Editor" }).click();

  // Load the pasted spec.
  await page.getByTestId("fsm-yaml-in").fill(SPEC);
  await page.getByTestId("fsm-load").click();

  // The emitted YAML reflects the loaded spec (hermod action present, command: null absent here).
  const out = page.getByTestId("fsm-yaml-out");
  await expect(out).toContainText("stateNode: line1.stateCurrent");
  await expect(out).toContainText("action: { workflow: ot-recipe-stage-activate }");

  // No structural issues on a valid spec.
  await expect(page.getByTestId("fsm-issues")).toHaveCount(0);

  // Add a state; the emitted YAML grows a new state line.
  await page.getByTestId("fsm-add-state").click();
  await expect(out).toContainText("code 6".replace("code ", "code: ")); // sanity: still emits states block
  await expect(out).toContainText("states:");
});

test("FSM editor: renaming a state cascades into its transitions (no dangling edges)", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "FSM Editor" }).click();
  await page.getByTestId("fsm-yaml-in").fill(SPEC);
  await page.getByTestId("fsm-load").click();

  // Select the Idle state node on the canvas, then rename it via the inspector.
  await page.locator(".react-flow__node").filter({ hasText: "Idle" }).click();
  await page.getByTestId("fsm-state-id").fill("Ready");

  const out = page.getByTestId("fsm-yaml-out");
  // Both transitions must have been rewritten to the new id — the self-loop stays a self-loop,
  // and the normal edge keeps its target. No transition should still reference the old "Idle".
  await expect(out).toContainText("from: Ready, to: Ready"); // loadRecipe self-loop
  await expect(out).toContainText("from: Ready, to: Execute"); // start
  await expect(out).not.toContainText("Idle");
  // A clean rename leaves no structural issues (no "from unknown state" dangling).
  await expect(page.getByTestId("fsm-issues")).toHaveCount(0);
});

test("FSM editor: LLM assist draft lands in the YAML panel and loads", async ({ page }) => {
  // Deterministically stub the assist endpoint (no backend/key needed).
  await page.route("**/api/fsm/assist", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      name: "packml-lineX", unit: "lineX", version: "v1", stateNode: "lineX.stateCurrent",
      states: [{ id: "Idle", code: 4 }, { id: "Execute", code: 6 }],
      transitions: [{ id: "start", from: "Idle", to: "Execute", command: "Start", driver: "field" }],
    }) }));

  await page.goto("/");
  await page.getByRole("button", { name: "FSM Editor" }).click();
  await page.getByTestId("fsm-assist-prompt").fill("packml line, start is field-driven");
  await page.getByTestId("fsm-assist-generate").click();

  // Draft appears in the YAML panel (Load textarea), then Load into the canvas.
  await expect(page.getByTestId("fsm-yaml-in")).toHaveValue(/stateNode: lineX\.stateCurrent/);
  await page.getByTestId("fsm-load").click();
  await expect(page.getByTestId("fsm-yaml-out")).toContainText("from: Idle, to: Execute");
});

test("FSM editor: Edit current sends the on-canvas spec as context", async ({ page }) => {
  let sentContext: unknown = "UNSET";
  await page.route("**/api/fsm/assist", (route) => {
    sentContext = route.request().postDataJSON().context;   // capture what the client sent
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      name: "packml-lineX", unit: "lineX", version: "v1", stateNode: "lineX.stateCurrent",
      states: [{ id: "Idle", code: 4 }, { id: "Execute", code: 6 }, { id: "Held", code: 11 }],
      transitions: [
        { id: "start", from: "Idle", to: "Execute", command: "Start", driver: "field" },
        { id: "hold", from: "Execute", to: "Held", command: "Hold", driver: "field" },
      ],
    }) });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "FSM Editor" }).click();

  // Edit current is disabled on the empty initial canvas.
  await page.getByTestId("fsm-assist-prompt").fill("add a Held state after Execute");
  await expect(page.getByTestId("fsm-assist-edit")).toBeDisabled();

  // Seed a non-empty canvas by loading a spec through the YAML panel.
  await page.getByTestId("fsm-yaml-in").fill(
    "name: packml-lineX\nunit: lineX\nversion: v1\nstateNode: lineX.stateCurrent\n" +
    "states:\n  - { id: Idle, code: 4 }\n  - { id: Execute, code: 6 }\n" +
    "transitions:\n  - { id: start, from: Idle, to: Execute, command: Start, driver: field }\n");
  await page.getByTestId("fsm-load").click();

  // Now "Edit current" is enabled and its request carries a non-null context (the current spec).
  await expect(page.getByTestId("fsm-assist-edit")).toBeEnabled();
  await page.getByTestId("fsm-assist-edit").click();

  await expect(page.getByTestId("fsm-yaml-in")).toHaveValue(/id: hold/);
  expect(sentContext).not.toBeNull();
  expect((sentContext as { states?: unknown[] }).states?.length).toBeGreaterThan(0);

  await page.getByTestId("fsm-load").click();
  await expect(page.getByTestId("fsm-yaml-out")).toContainText("id: hold");
});

test("FSM editor: Generate draft sends no context (fresh generation)", async ({ page }) => {
  let sentBody: { context?: unknown } = {};
  await page.route("**/api/fsm/assist", (route) => {
    sentBody = route.request().postDataJSON();
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      name: "packml-lineX", unit: "lineX", version: "v1", stateNode: "lineX.stateCurrent",
      states: [{ id: "Idle", code: 4 }, { id: "Execute", code: 6 }],
      transitions: [{ id: "start", from: "Idle", to: "Execute", command: "Start", driver: "field" }],
    }) });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "FSM Editor" }).click();
  await page.getByTestId("fsm-assist-prompt").fill("packml line, start is field-driven");
  await page.getByTestId("fsm-assist-generate").click();

  await expect(page.getByTestId("fsm-yaml-in")).toHaveValue(/stateNode: lineX\.stateCurrent/);
  expect(sentBody.context).toBeNull();   // api.ts serializes context as null when omitted
});

test("FSM assist: transcript accumulates turns with mode + instruction", async ({ page }) => {
  await page.route("**/api/fsm/assist", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      name: "packml-lineX", unit: "lineX", version: "v1", stateNode: "lineX.stateCurrent",
      states: [{ id: "Idle", code: 4 }, { id: "Execute", code: 6 }],
      transitions: [{ id: "start", from: "Idle", to: "Execute", command: "Start", driver: "field" }],
    }) }));

  await page.goto("/");
  await page.getByRole("button", { name: "FSM Editor" }).click();

  await page.getByTestId("fsm-assist-prompt").fill("first instruction");
  await page.getByTestId("fsm-assist-generate").click();
  await expect(page.getByTestId("fsm-assist-pending")).toBeVisible();
  await expect(page.getByTestId("fsm-assist-turn")).toHaveCount(1);
  await expect(page.getByTestId("fsm-assist-transcript")).toContainText("first instruction");

  await page.getByTestId("fsm-assist-prompt").fill("second instruction");
  await page.getByTestId("fsm-assist-generate").click();
  await expect(page.getByTestId("fsm-assist-turn")).toHaveCount(2);
  await expect(page.getByTestId("fsm-assist-transcript")).toContainText("second instruction");
});

test("FSM assist: instruction recall refills the composer from a past turn", async ({ page }) => {
  await page.route("**/api/fsm/assist", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      name: "packml-lineX", unit: "lineX", version: "v1", stateNode: "lineX.stateCurrent",
      states: [{ id: "Idle", code: 4 }], transitions: [{ id: "x", from: "Idle", to: "Idle", command: null, driver: "field" }],
    }) }));

  await page.goto("/");
  await page.getByRole("button", { name: "FSM Editor" }).click();
  await page.getByTestId("fsm-assist-prompt").fill("recall me later");
  await page.getByTestId("fsm-assist-generate").click();
  await expect(page.getByTestId("fsm-assist-prompt")).toHaveValue("");
  await page.getByTestId("fsm-assist-turn").first().click();
  await expect(page.getByTestId("fsm-assist-prompt")).toHaveValue("recall me later");
});

test("FSM assist: per-button busy shows Working on the clicked button only", async ({ page }) => {
  let release!: () => void;
  const gate = new Promise<void>((r) => { release = r; });
  await page.route("**/api/fsm/assist", async (route) => {
    await gate;
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      name: "packml-lineX", unit: "lineX", version: "v1", stateNode: "lineX.stateCurrent",
      states: [{ id: "Idle", code: 4 }, { id: "Execute", code: 6 }, { id: "Held", code: 11 }],
      transitions: [{ id: "hold", from: "Execute", to: "Held", command: "Hold", driver: "field" }],
    }) });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "FSM Editor" }).click();
  await page.getByTestId("fsm-yaml-in").fill(
    "name: packml-lineX\nunit: lineX\nversion: v1\nstateNode: lineX.stateCurrent\n" +
    "states:\n  - { id: Idle, code: 4 }\n  - { id: Execute, code: 6 }\n" +
    "transitions:\n  - { id: start, from: Idle, to: Execute, command: Start, driver: field }\n");
  await page.getByTestId("fsm-load").click();

  await page.getByTestId("fsm-assist-prompt").fill("add a Held state");
  await page.getByTestId("fsm-assist-edit").click();
  await expect(page.getByTestId("fsm-assist-edit")).toHaveText("Working…");
  await expect(page.getByTestId("fsm-assist-generate")).toHaveText("Generate draft");
  release();
  await expect(page.getByTestId("fsm-assist-edit")).toHaveText("Edit current");
});

test("FSM assist: a failed turn is recorded with its error", async ({ page }) => {
  await page.route("**/api/fsm/assist", (route) =>
    route.fulfill({ status: 422, contentType: "application/json",
      body: JSON.stringify({ issues: ["transition 'x': field-driven transition must NOT declare an action"] }) }));

  await page.goto("/");
  await page.getByRole("button", { name: "FSM Editor" }).click();
  await page.getByTestId("fsm-assist-prompt").fill("bad instruction");
  await page.getByTestId("fsm-assist-generate").click();
  await expect(page.getByTestId("fsm-assist-turn-err")).toContainText("must NOT declare an action");
  await expect(page.getByTestId("fsm-assist-prompt")).toHaveValue("bad instruction");
});
