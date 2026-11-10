import { execFileSync } from "node:child_process";
import { mkdirSync } from "node:fs";
import type { APIRequestContext, Page, TestInfo } from "@playwright/test";

const API = "http://127.0.0.1:18090";

// Trigger a run via the control plane; returns the runId (also recorded in run_index → Console RunList).
export async function startRun(
  request: APIRequestContext,
  opts: { runId: string; engine?: "temporal" | "conductor"; failAtBlockId?: string; slowMs?: number; interactive?: boolean },
): Promise<string> {
  const res = await request.post(`${API}/api/workflows/ot-recipe-apply/1.0.0/run`, { data: opts });
  if (!res.ok()) throw new Error(`startRun ${res.status()} ${await res.text()}`);
  return (await res.json()).runId ?? opts.runId;
}

// fault_inject is a test-only DB toggle (worker honors it when HERMOD_FAULT_INJECT=1). psql via docker.
function psql(sql: string): string {
  return execFileSync(
    "docker",
    ["compose", "exec", "-T", "postgres", "psql", "-U", "hermod", "-d", "hermod", "-tAc", sql],
    { encoding: "utf8" },
  ).trim();
}
export const armForwardFault = (blockId: string) =>
  psql(`INSERT INTO fault_inject(block_id) VALUES ('${blockId}') ON CONFLICT DO NOTHING`);
export const disarmFault = (blockId: string) =>
  psql(`DELETE FROM fault_inject WHERE block_id='${blockId}'`);

// Playwright 1.61.1: video is finalized when the browser context is closed, which happens AFTER
// afterEach hooks complete (fixture teardown order). page.video()?.saveAs() deadlocks if awaited
// inside afterEach because saveAs() waits for context-close which waits for afterEach to return.
//
// Fix: fire-and-forget the saveAs. Context closes after afterEach returns, finalizing the video,
// then saveAs() copies it. All Playwright tests in this suite are serialized (workers:1, serial),
// so gen-demo-gifs.sh (called by run-e2e.sh after npm run test:e2e) always sees the completed file.
//
// MUST be called from `test.afterEach` (with `page` in the fixture list), NOT from the test body.
export function saveSectionVideo(page: Page, _testInfo: TestInfo, ordinalName: string): void {
  const dest = `test-results/gifsrc/${ordinalName}.webm`;
  mkdirSync(`test-results/gifsrc`, { recursive: true });
  // intentionally not awaited: saveAs() finishes after context closes (post-afterEach fixture teardown)
  void page.video()?.saveAs(dest);
}
