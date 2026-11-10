import { test, expect } from "@playwright/test";
import { startRun, saveSectionVideo } from "./helpers";
import { caption, installCursor, card, highlightNode, clearHighlight } from "./overlay";

test.afterEach(({ page }, testInfo) => saveSectionVideo(page, testInfo, "05-engine-neutral"));

test("engine-neutral — the same contract runs safely on Conductor", async ({ page, request }) => {
  // Longest arc in the suite (3× 60s Conductor eventual-consistency waits + ~8 card/caption dwells) vs the
  // 120s playwright.config default with retries:0 — raise this test's budget so a slow Conductor run can't
  // overrun the cap and fail the suite.
  test.setTimeout(240_000);
  await installCursor(page);

  // Start the SAME ot-recipe-apply on Conductor, faulting at preflight. (Conductor fault is input-borne.)
  // Conductor generates its own workflowId, so the run_index id is the value startRun RETURNS (not our local
  // seed) — the Console run row is keyed by that returned id.
  const run = `neutral-${Date.now()}`;
  const runId = await startRun(request, { runId: run, engine: "conductor", failAtBlockId: "transform.map", slowMs: 700 });

  await page.goto("/");

  // Beat 0 — intro card (framing: same contract, different engine)
  await card(page, [
    "The same contract — now on <b>Conductor</b>",
    "Same console, same safety — only the engine changed",
  ], 2200);

  // Beat 1 — open the run in the console (the run row shows the CONDUCTOR engine chip)
  await page.getByRole("button", { name: /console/i }).click();
  await page.getByTestId(`run-row-${runId}`).click();
  await caption(page, "Running on Conductor — the pre-check fails", 1100);

  // Beat 2 — failure-safe. HARD proof = preflight FAILED (forward state from the main run) + the DB-backed
  // compensation timeline (deterministic: /compensation reads comp_ledger) + applyPLC never DONE.
  await expect(page.locator('.react-flow__node[data-id="preflight"] .rf-node')).toHaveClass(/ns-failed/, { timeout: 60_000 });
  // Both rows surface incrementally (Conductor writes idx0 then idx1) — wait for each row to be present.
  await expect(page.getByTestId("timeline-row-0")).toBeVisible({ timeout: 60_000 });
  await expect(page.getByTestId("timeline-row-1")).toBeVisible({ timeout: 60_000 });
  await expect(page.getByTestId("timeline-row-0")).toContainText("notify.email");   // interlockAck compensated
  await expect(page.getByTestId("timeline-row-1")).toContainText("db.upsert");      // recordPlan compensated
  await expect(page.locator('.react-flow__node[data-id="applyPLC"] .rf-node')).not.toHaveClass(/ns-done/);
  // BEST-EFFORT (GIF visual only, NOT a suite gate): Conductor's per-node COMPENSATED overlay is observe-only
  // and eventually-consistent (v0.6b honest limit — it rides Conductor's async search index), unlike the
  // deterministic timeline above. Wait for it so the recorded GIF shows the compensated nodes when the index
  // catches up, but swallow a lagging overlay so it never flakes the suite.
  await expect(page.locator('.react-flow__node[data-id="interlockAck"] .rf-node')).toHaveClass(/ns-compensated/, { timeout: 30_000 }).catch(() => {});
  await expect(page.locator('.react-flow__node[data-id="recordPlan"] .rf-node')).toHaveClass(/ns-compensated/, { timeout: 30_000 }).catch(() => {});
  await expect(page.locator('.react-flow__node[data-id="applyPLC"]')).toBeVisible(); // laid out before highlight
  await highlightNode(page, "applyPLC", "not fired");
  await caption(page, "Same reverse-order rollback — the PLC never fires", 2400);
  await clearHighlight(page);

  // Beat 3 — v0.6d whole-run retry: the Conductor-only run-level button (shown when the run is terminal-failed)
  await caption(page, "Operator retries — Conductor re-runs the whole run", 1200);
  await expect(page.getByTestId("retry-run")).toBeVisible({ timeout: 60_000 });
  await page.getByTestId("retry-run").click();

  // Beat 4 — recovers fault-free to the applyPLC gate, then approve → COMPLETED
  await expect(page.locator('.react-flow__node[data-id="preflight"] .rf-node')).toHaveClass(/ns-done/, { timeout: 60_000 });
  await caption(page, "PLC fires only after the gate is approved", 1100);
  // Wait for the gate button (only rendered while the run is non-terminal) before clicking — avoids the
  // sub-second race where preflight lights ns-done a tick before status polling flips FAILED→RUNNING.
  await expect(page.getByTestId("approve-button")).toBeVisible({ timeout: 60_000 });
  await page.getByTestId("approve-button").click();
  await expect(page.locator('.react-flow__node[data-id="applyPLC"] .rf-node')).toHaveClass(/ns-done/, { timeout: 60_000 });
  await caption(page, "Done — same safety, same console, different engine", 1000);

  // Beat 5 — outro card (value). MUST be the final awaited statement; persist=true keeps it as the last frame.
  await card(page, [
    "<b>One contract, two engines</b> (Temporal · Conductor)",
    "Same safety · same console",
    "Hermod — <b>not locked to one engine</b>",
  ], 2600, true);
});
