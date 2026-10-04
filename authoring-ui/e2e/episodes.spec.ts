// Episodes tab end to end against a stubbed /api/episodes (plan B3c-2). Needs only the Vite dev server.
// Uses the installed Google Chrome: this machine's Playwright cache lacks the chromium revision 1.61 wants.
import { test, expect, type Page } from "@playwright/test";

test.use({ channel: "chrome" });

/** One stubbed call. [answered] turns true once its answer has been sent back to the page. */
type Call = { method: string; path: string; headers: Record<string, string>; body?: unknown; answered: boolean };
type Answer = { status: number; body: unknown };

const ID = "ep:k-1/r1";
const P = "/api/episodes/ep%3Ak-1/r1";
const VERSION = "sha256:" + "a".repeat(64);
const LIST = [{ instanceId: ID, workflowId: "ep:k-1", openedAt: "2026-10-02T00:00:00Z", lastAt: "2026-10-02T00:01:00Z", lastPhase: "AWAITING_APPROVAL", notices: 1, recordLag: false }];

function view(over: Record<string, unknown> = {}) {
  return {
    instanceId: ID, phase: "AWAITING_APPROVAL", escalationReason: null, escalationDetail: null, attempt: 1,
    proposalId: ID + "#1", candidateId: "APPROVE_REMEDY:r:j:pick_place", candidatesVersion: VERSION,
    stateDeadlineMillis: null, episodeDeadlineMillis: null, historyJson: "[]", unknownsJson: "[]", symptoms: 1,
    candidateJson: null, candidatesJson: JSON.stringify([{ candidateId: "ESCALATE", kind: "ESCALATE", ref: null }]),
    // The live diagnosis: the screen must never render it (the card carries what may be shown, §9.5).
    diagnosisJson: '{"cause":"DIAG_SENTINEL"}', joinedAfterSnapshot: 0, ...over,
  };
}

function card(over: Record<string, unknown> = {}) {
  return {
    unknowns: [{ subject: { robotId: "r" }, what: "OBSERVATION_ABSENT", since: null, source: "s" }],
    symptoms: [{ searchId: "s-1" }],
    proposal: { candidateId: "APPROVE_REMEDY:r:j:pick_place", kind: "APPROVE_REMEDY", ref: { robotId: "r", jobOrderId: "j", searchId: "s-1" } },
    rationale: "다시 한다 [출처: SOP-01, §5.1].", rationaleMissing: false,
    guidance: [{ label: "절차", text: "먼저 본다." }, { label: "금지", text: "반복하지 않는다." }],
    citations: [{ title: "SOP-01", section: "§5.1", verified: true }], uncitedSentences: [],
    unverifiedCitations: [], unlocatedCitations: 0, unverifiedNumbers: [], unlocatedNumbers: 0,
    precondition: null, joinedAfterSnapshot: [],
    decision: { update: "decide", proposalId: ID + "#1", candidatesVersion: VERSION, deadlineMillis: null },
    identityAssurance: "SELF_ASSERTED", responseUnreadable: false, diagnosisFromRecord: false, diagnosisRefused: null,
    factsBehind: false, joinedAfterSnapshotCount: null, diagnosisRefusedDetail: null, diagnosisAttempt: 1, ...over,
  };
}

function detail(id: string, v: Record<string, unknown> | null, c: Record<string, unknown> | null, events: unknown[] = []) {
  return { instanceId: id, view: v, viewError: null, card: c, events, notices: [] };
}

const FAIL = Symbol("fail");
/** A detail GET that fails with [status] and [body] (put in `details` in place of a detail). */
const failing = (status: number, body: unknown) => ({ [FAIL]: true, status, body });

/**
 * Stubs /api/episodes**: GETs from [details], POSTs from [onPost]; every call is remembered in [calls]. The answer is
 * decided when the request arrives; [delay] may then hold it back (ms) before it is sent.
 */
async function stub(
  page: Page, details: Record<string, unknown>, onPost: (path: string) => Answer, notices: unknown[] = [],
  delay: (call: Call) => number = () => 0,
) {
  const calls: Call[] = [];
  await page.route("**/api/episodes**", async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname;   // keeps %3A
    const call: Call = { method: req.method(), path, headers: req.headers(), body: req.method() === "POST" ? req.postDataJSON() : undefined, answered: false };
    calls.push(call);
    const answer = ((): Answer => {
      if (req.method() === "GET" && path === "/api/episodes") return { status: 200, body: LIST };
      if (req.method() === "GET" && path === "/api/episodes/notices") return { status: 200, body: notices };
      const d = details[path];
      if (req.method() === "GET" && d && typeof d === "object" && FAIL in d) return d as Answer;
      if (req.method() === "GET" && d) return { status: 200, body: d };
      if (req.method() === "POST") return onPost(path);
      return { status: 404, body: { error: `no stub ${req.method()} ${path}` } };
    })();
    const ms = delay(call);
    if (ms > 0) await new Promise((r) => setTimeout(r, ms));
    await route.fulfill({ status: answer.status, contentType: "application/json", body: JSON.stringify(answer.body) });
    call.answered = true;
  });
  return calls;
}

async function open(page: Page, operator = "op-1") {
  await page.goto("/");
  await page.getByRole("button", { name: /episodes/i }).click();
  await page.getByTestId("operator-name").fill(operator);
  await page.getByTestId("episode-row-" + ID).click();
  await expect(page.getByTestId("operator-card")).toBeVisible();
}

const posts = (calls: Call[]) => calls.filter((c) => c.method === "POST");
const gets = (calls: Call[], path: string) => calls.filter((c) => c.method === "GET" && c.path === path);
const noPost = (): Answer => ({ status: 500, body: { error: "no POST expected" } });
const accepted = (): Answer => ({ status: 200, body: { reply: "ACCEPTED" } });

/** The one POST this test sent: it has gone out, its answer is shown, and still no second one followed. */
async function onePost(page: Page, calls: Call[]): Promise<Call> {
  await expect.poll(() => posts(calls).length).toBe(1);
  await expect(page.getByTestId("decision-result")).toBeVisible();
  expect(posts(calls)).toHaveLength(1);
  return posts(calls)[0];
}

test("Episodes: the card in §9.5 order and wording", async ({ page }) => {
  await stub(page, { [P]: detail(ID, view(), card()) }, noPost);
  await open(page);

  const unknowns = await page.getByTestId("card-unknowns").boundingBox();
  const proposal = await page.getByTestId("card-proposal").boundingBox();
  expect(unknowns).not.toBeNull();
  expect(proposal).not.toBeNull();
  expect(unknowns!.y).toBeLessThan(proposal!.y);

  await expect(page.getByTestId("card-symptoms")).toBeVisible();
  await expect(page.getByTestId("card-rationale")).toBeVisible();
  await expect(page.getByTestId("card-guidance")).toBeVisible();
  await expect(page.getByText("근거 꾸러미에 있던 문서", { exact: false })).toBeVisible();
  await expect(page.getByTestId("card-identity")).toContainText("스스로 밝힌 신원");
  await expect(page.getByText("DIAG_SENTINEL")).toHaveCount(0);

  // A candidate with ref: null still renders.
  await page.getByTestId("candidates").locator("summary").click();
  await expect(page.getByTestId("candidates")).toContainText("ESCALATE");
});

test("Episodes: approve sends one POST with the operator in the header and no operatorId in the body", async ({ page }) => {
  const calls = await stub(page, { [P]: detail(ID, view(), card()) }, accepted);
  await open(page);

  // Close is only for an escalated episode.
  await expect(page.getByTestId("close-outcome")).toHaveCount(0);
  await expect(page.getByTestId("close")).toHaveCount(0);

  await page.getByTestId("decide-approve").click();
  await expect(page.getByTestId("decision-result")).toContainText("받아들여짐");

  const post = await onePost(page, calls);
  expect(post.path).toBe(P + "/decide");
  expect(post.headers["x-koshei-operator"]).toBe("op-1");
  expect(post.body).toEqual({ proposalId: ID + "#1", sawCandidatesVersion: VERSION, approve: true });
  expect(post.body).not.toHaveProperty("operatorId");
});

test("Episodes: reject needs a reason and sends it with the note", async ({ page }) => {
  const calls = await stub(page, { [P]: detail(ID, view(), card()) }, accepted);
  await open(page);

  const reject = page.getByTestId("decide-reject");
  await expect(reject).toBeDisabled();
  await page.getByTestId("reject-reason").selectOption("NOT_NOW");
  await page.getByTestId("reject-note").fill("later");
  await expect(reject).toBeEnabled();
  await reject.click();

  const post = await onePost(page, calls);
  expect(post.path).toBe(P + "/decide");
  expect(post.body).toEqual({ proposalId: ID + "#1", sawCandidatesVersion: VERSION, approve: false, reason: "NOT_NOW", note: "later" });
});

test("Episodes: an unknown the core can take is confirmed with its subject and note", async ({ page }) => {
  const calls = await stub(page, { [P]: detail(ID, view(), card()) }, accepted);
  await open(page);

  await page.getByTestId("unknown-note-0").fill("seen");
  await page.getByTestId("confirm-unknown-0").click();

  const post = await onePost(page, calls);
  expect(post.path).toBe(P + "/confirm");
  expect(post.body).toEqual({ kind: "UNKNOWN", subject: { robotId: "r" }, what: "OBSERVATION_ABSENT", holds: true, note: "seen" });
});

test("Episodes: a failed re-read keeps the last detail and says the failure", async ({ page }) => {
  const details: Record<string, unknown> = { [P]: detail(ID, view(), card()) };
  await stub(page, details, noPost);
  await open(page);

  details[P] = failing(503, { error: "episode tables not found" });
  await expect(page.locator(".episodes-detail .banner.err")).toContainText("episode tables not found");
  await expect(page.getByTestId("operator-card")).toBeVisible();
  await expect(page.getByTestId("decide-approve")).toBeVisible();
});

test("Episodes: a poll sent before a decision never overwrites the re-read after it", async ({ page }) => {
  const details: Record<string, unknown> = { [P]: detail(ID, view(), card()) };
  let holdNext = false;
  const calls = await stub(
    page, details,
    () => {
      details[P] = detail(ID, view({ phase: "RESOLVED" }), card({ decision: null, unknowns: [] }));
      return accepted();
    },
    [],
    // The next detail poll is answered late, with the AWAITING_APPROVAL detail it read when it arrived.
    (c) => {
      if (holdNext && c.method === "GET" && c.path === P) { holdNext = false; return 2500; }
      return 0;
    },
  );
  await open(page);

  holdNext = true;
  await expect.poll(() => holdNext).toBe(false);
  const held = gets(calls, P)[gets(calls, P).length - 1];

  await page.evaluate(() => {
    const w = window as unknown as { phases: string[] };
    w.phases = [];
    new MutationObserver(() => {
      const t = document.querySelector('[data-testid="detail-phase"]')?.textContent;
      if (t) w.phases.push(t);
    }).observe(document.body, { subtree: true, childList: true, characterData: true });
  });

  await page.getByTestId("decide-approve").click();
  await expect(page.getByTestId("detail-phase")).toHaveText("RESOLVED");
  expect(held.answered).toBe(false);   // the re-read landed while the older poll was still out

  await expect.poll(() => held.answered).toBe(true);
  await page.waitForTimeout(500);
  const phases = await page.evaluate(() => (window as unknown as { phases: string[] }).phases);
  expect(phases.slice(phases.indexOf("RESOLVED"))).not.toContain("AWAITING_APPROVAL");
  await expect(page.getByTestId("detail-phase")).toHaveText("RESOLVED");
});

test("Episodes: the decision buttons come back only after the re-read lands", async ({ page }) => {
  let posted = false;
  let held = false;
  const calls = await stub(
    page, { [P]: detail(ID, view(), card()) },
    () => { posted = true; return accepted(); },
    [],
    // The first detail read after the POST answers 1.5 s late.
    (c) => {
      if (posted && !held && c.method === "GET" && c.path === P) { held = true; return 1500; }
      return 0;
    },
  );
  await open(page);

  const approve = page.getByTestId("decide-approve");
  await approve.click();
  await expect(page.getByTestId("decision-result")).toContainText("받아들여짐");
  await expect(approve).toBeDisabled();

  const reRead = () => {
    const i = calls.findIndex((c) => c.method === "POST");
    return calls.slice(i + 1).find((c) => c.method === "GET" && c.path === P);
  };
  await expect.poll(() => reRead() !== undefined).toBe(true);
  expect(reRead()!.answered).toBe(false);
  await expect(approve).toBeEnabled();
  expect(reRead()!.answered).toBe(true);
});

test("Episodes: a refusal value is said in the operator's words", async ({ page }) => {
  await stub(page, { [P]: detail(ID, view(), card()) }, () => ({ status: 200, body: { reply: "REFUSED_STALE" } }));
  await open(page);

  await page.getByTestId("decide-approve").click();
  await expect(page.getByTestId("decision-result")).toContainText("낡은 카드");
});

test("Episodes: 409 EPISODE_MOVED offers the current episode", async ({ page }) => {
  const MOVED = "ep:k-1/r2";
  const MOVED_P = "/api/episodes/ep%3Ak-1/r2";
  const calls = await stub(
    page,
    { [P]: detail(ID, view(), card()), [MOVED_P]: detail(MOVED, view({ instanceId: MOVED }), card()) },
    () => ({ status: 409, body: { error: "EPISODE_MOVED", currentInstanceId: MOVED } }),
  );
  await open(page);

  await page.getByTestId("decide-approve").click();
  await expect(page.getByTestId("decision-result")).toContainText("다른 에피소드");
  await expect(page.getByTestId("open-moved")).toBeVisible();

  await page.getByTestId("open-moved").click();
  await expect.poll(() => gets(calls, MOVED_P).length).toBeGreaterThan(0);
  await expect(page.getByTestId("detail-instance")).toHaveText(MOVED);
});

test("Episodes: a timeout says the outcome is unknown and re-reads the detail", async ({ page }) => {
  await page.clock.install();
  const calls = await stub(
    page,
    { [P]: detail(ID, view(), card()) },
    () => ({ status: 504, body: { error: "EPISODE_TIMEOUT", outcome: "UNKNOWN", instanceId: ID } }),
  );
  await open(page);

  // Stop the polling clock so only the re-read after the POST can add a GET.
  await page.clock.pauseAt(await page.evaluate(() => Date.now() + 50));
  await page.waitForTimeout(500);
  const before = gets(calls, P).length;

  await page.getByTestId("decide-approve").click();
  await expect(page.getByTestId("decision-result")).toContainText("결과 모름");
  await expect.poll(() => gets(calls, P).length).toBeGreaterThan(before);
});

test("Episodes: no name, too long, or not ASCII blocks the decisions", async ({ page }) => {
  await stub(page, { [P]: detail(ID, view(), card()) }, noPost);
  await open(page);

  const name = page.getByTestId("operator-name");
  const approve = page.getByTestId("decide-approve");
  await expect(approve).toBeEnabled();
  await expect(page.getByTestId("operator-problem")).toHaveCount(0);

  for (const bad of ["", "a".repeat(129), "김철수"]) {
    await name.fill(bad);
    await expect(approve).toBeDisabled();
    await expect(page.getByTestId("operator-problem")).toBeVisible();
  }
});

test("Episodes: confirming a precondition sends no proposition", async ({ page }) => {
  const PRE = "(r, j)에 searchId s-1 뒤로 더 새 탐색 줄이 없다";
  const calls = await stub(
    page,
    {
      [P]: detail(ID, view({ phase: "UNKNOWN_PRECONDITION" }), card({
        decision: { update: "confirm", proposalId: ID + "#1", candidatesVersion: VERSION, deadlineMillis: null },
        precondition: PRE,
      })),
    },
    accepted,
  );
  await open(page);

  await expect(page.getByTestId("card-precondition")).toContainText(PRE);
  // A confirm decision is not a decide decision.
  await expect(page.getByTestId("decide-approve")).toHaveCount(0);
  await page.getByTestId("confirm-precondition-holds").click();

  const post = await onePost(page, calls);
  expect(post.path).toBe(P + "/confirm");
  expect(post.body).toEqual({ kind: "PRECONDITION", candidateId: "APPROVE_REMEDY:r:j:pick_place", proposalId: ID + "#1", holds: true });
  expect(post.body).not.toHaveProperty("proposition");
});

test("Episodes: an unknown outcome is confirmed as OUTCOME, never as the candidate's own UNKNOWN item", async ({ page }) => {
  const calls = await stub(
    page,
    {
      [P]: detail(ID, view({ phase: "UNKNOWN_OUTCOME" }), card({
        decision: null,
        unknowns: [{ subject: { candidateId: "APPROVE_REMEDY:r:j:pick_place" }, what: "OUTCOME", since: null, source: "s" }],
      })),
    },
    accepted,
  );
  await open(page);

  await expect(page.getByTestId("confirm-outcome-done")).toBeVisible();
  await expect(page.getByTestId("confirm-unknown-0")).toHaveCount(0);

  await page.getByTestId("confirm-outcome-done").click();
  const post = await onePost(page, calls);
  expect(post.path).toBe(P + "/confirm");
  expect(post.body).toEqual({ kind: "OUTCOME", candidateId: "APPROVE_REMEDY:r:j:pick_place", proposalId: ID + "#1", holds: true });
});

test("Episodes: an escalated episode offers close, not takeover", async ({ page }) => {
  const calls = await stub(
    page,
    {
      // A robot unknown the core could take — but not once a person has the episode.
      [P]: detail(ID, view({ phase: "ESCALATED", escalationReason: "TAKEN_OVER" }), card({
        decision: null,
        unknowns: [{ subject: { robotId: "r" }, what: "OBSERVATION_ABSENT", since: null, source: "s" }],
      })),
    },
    accepted,
  );
  await open(page);

  await expect(page.getByTestId("close-outcome")).toBeVisible();
  await expect(page.getByTestId("takeover")).toHaveCount(0);
  await expect(page.getByTestId("confirm-unknown-0")).toHaveCount(0);
  await expect(page.getByTestId("close-outcome")).toHaveValue("");
  await expect(page.getByTestId("close")).toBeDisabled();

  await page.getByTestId("close-outcome").fill("현장 처리");
  await page.getByTestId("close").click();
  const post = await onePost(page, calls);
  expect(post.path).toBe(P + "/close");
  expect(post.body).toEqual({ outcome: "현장 처리" });
});

test("Episodes: takeover of a running episode asks first and sends no body", async ({ page }) => {
  const calls = await stub(page, { [P]: detail(ID, view({ phase: "DIAGNOSING" }), card({ decision: null })) }, accepted);
  await open(page);

  let asked = "";
  page.once("dialog", (d) => { asked = d.message(); void d.accept(); });
  await page.getByTestId("takeover").click();
  const post = await onePost(page, calls);
  expect(asked).toContain("Take this episode over?");
  expect(post.path).toBe(P + "/takeover");
  expect(post.body ?? null).toBeNull();
});

test("Episodes: a takeover the operator cancels sends nothing", async ({ page }) => {
  const calls = await stub(page, { [P]: detail(ID, view({ phase: "DIAGNOSING" }), card({ decision: null })) }, accepted);
  await open(page);

  let asked = false;
  page.once("dialog", (d) => { asked = true; void d.dismiss(); });
  await page.getByTestId("takeover").click();
  await expect.poll(() => asked).toBe(true);
  await page.waitForTimeout(500);
  expect(posts(calls)).toHaveLength(0);
  await expect(page.getByTestId("decision-result")).toHaveCount(0);
});

test("Episodes: a refused answer is named and its rationale is not shown; a missing rationale is said", async ({ page }) => {
  const details: Record<string, unknown> = {
    [P]: detail(ID, view(), card({
      diagnosisRefused: "DIAGNOSIS_FAILED", diagnosisRefusedDetail: "no verified citation",
      rationale: null, guidance: [], citations: [],
    })),
  };
  await stub(page, details, noPost);
  await open(page);

  await expect(page.getByTestId("card-refused")).toContainText("판정이 거절한 답");
  await expect(page.getByTestId("card-refused")).toContainText("no verified citation");
  await expect(page.getByTestId("card-rationale")).toHaveCount(0);

  // The next poll reads a card whose rationale is missing.
  details[P] = detail(ID, view(), card({ rationale: null, rationaleMissing: true }));
  await expect(page.getByTestId("card-rationale")).toContainText("이유 없음");
});

test("Episodes: records never show the narrator's cause", async ({ page }) => {
  const events = [{
    seq: 5, kind: "DIAGNOSIS_RESULT",
    payload: { attempt: 1, verdict: "PROPOSED", response: { cause: "SECRET_CAUSE", rationale: "r" } },
    at: "2026-10-02T00:00:00Z",
  }];
  await stub(page, { [P]: detail(ID, view(), card(), events) }, noPost);
  await open(page);

  await page.getByTestId("records").locator("summary").click();
  await expect(page.getByTestId("records")).toContainText("DIAGNOSIS_RESULT");
  await expect(page.getByTestId("records")).toContainText("PROPOSED");
  await expect(page.getByText("SECRET_CAUSE")).toHaveCount(0);
});

test("Episodes: records never show a late narrator answer", async ({ page }) => {
  const events = [{
    seq: 9, kind: "IGNORED",
    payload: {
      event: "DiagnosisReturned", phaseAtReceipt: "ESCALATED", phase: "ESCALATED", why: "late",
      json: JSON.stringify({ outcome: "RECOMMENDED", cause: { text: "LATE_SECRET_CAUSE" }, rationale: "r" }),
    },
    at: "2026-10-02T00:00:00Z",
  }];
  await stub(page, { [P]: detail(ID, view(), card(), events) }, noPost);
  await open(page);

  await page.getByTestId("records").locator("summary").click();
  await expect(page.getByTestId("records")).toContainText("IGNORED");
  await expect(page.getByTestId("records")).toContainText("DiagnosisReturned");
  await expect(page.getByText("LATE_SECRET_CAUSE")).toHaveCount(0);
});

test("Episodes: a person-task notice opens its episode", async ({ page }) => {
  const OTHER = "ep:k-2/r1";
  const OTHER_P = "/api/episodes/ep%3Ak-2/r1";
  const notices = [{
    id: 7, instanceId: OTHER, at: "2026-10-02T00:00:00Z",
    notice: { kind: "PERSON_TASK", idempotencyKey: "k", intent: { candidate: { kind: "CHOOSE_SOURCE" } } },
  }];
  const calls = await stub(page, { [P]: detail(ID, view(), card()), [OTHER_P]: detail(OTHER, view({ instanceId: OTHER }), card()) }, noPost, notices);

  await page.goto("/");
  await page.getByRole("button", { name: /episodes/i }).click();
  await page.getByTestId("operator-name").fill("op-1");

  await expect(page.getByTestId("notice-7")).toContainText("사람 과업");
  await expect(page.getByTestId("notice-7")).toContainText("CHOOSE_SOURCE");

  await page.getByTestId("notice-open-7").click();
  await expect.poll(() => gets(calls, OTHER_P).length).toBeGreaterThan(0);
  await expect(page.getByTestId("detail-instance")).toHaveText(OTHER);
});
