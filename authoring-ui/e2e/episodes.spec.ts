// Episodes tab end to end against a stubbed /api/episodes (plan B3c-2). Needs only the Vite dev server.
// Uses the installed Google Chrome: this machine's Playwright cache lacks the chromium revision 1.61 wants.
import { test, expect, type Page } from "@playwright/test";

test.use({ channel: "chrome" });

type Call = { method: string; path: string; headers: Record<string, string>; body?: unknown };
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
    diagnosisJson: null, joinedAfterSnapshot: 0, ...over,
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

/** Stubs /api/episodes**: GETs from [details], POSTs from [onPost]; every call is remembered in [calls]. */
async function stub(page: Page, details: Record<string, unknown>, onPost: (path: string) => Answer, notices: unknown[] = []) {
  const calls: Call[] = [];
  await page.route("**/api/episodes**", async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname;   // keeps %3A
    calls.push({ method: req.method(), path, headers: req.headers(), body: req.method() === "POST" ? req.postDataJSON() : undefined });
    const json = (status: number, body: unknown) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (req.method() === "GET" && path === "/api/episodes") return json(200, LIST);
    if (req.method() === "GET" && path === "/api/episodes/notices") return json(200, notices);
    if (req.method() === "GET" && details[path]) return json(200, details[path]);
    if (req.method() === "POST") { const a = onPost(path); return json(a.status, a.body); }
    return json(404, { error: `no stub ${req.method()} ${path}` });
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

/** The one POST this test sent, once it has gone out. */
async function onePost(calls: Call[]): Promise<Call> {
  await expect.poll(() => posts(calls).length).toBe(1);
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

  // A candidate with ref: null still renders.
  await page.getByTestId("candidates").locator("summary").click();
  await expect(page.getByTestId("candidates")).toContainText("ESCALATE");
});

test("Episodes: approve sends one POST with the operator in the header and no operatorId in the body", async ({ page }) => {
  const calls = await stub(page, { [P]: detail(ID, view(), card()) }, accepted);
  await open(page);

  await page.getByTestId("decide-approve").click();
  await expect(page.getByTestId("decision-result")).toContainText("받아들여짐");

  const post = await onePost(calls);
  expect(post.path).toBe(P + "/decide");
  expect(post.headers["x-koshei-operator"]).toBe("op-1");
  expect(post.body).toEqual({ proposalId: ID + "#1", sawCandidatesVersion: VERSION, approve: true });
  expect(post.body).not.toHaveProperty("operatorId");
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
  await page.getByTestId("confirm-precondition-holds").click();

  const post = await onePost(calls);
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
  const post = await onePost(calls);
  expect(post.path).toBe(P + "/confirm");
  expect(post.body).toEqual({ kind: "OUTCOME", candidateId: "APPROVE_REMEDY:r:j:pick_place", proposalId: ID + "#1", holds: true });
});

test("Episodes: an escalated episode offers close, not takeover", async ({ page }) => {
  const calls = await stub(
    page,
    { [P]: detail(ID, view({ phase: "ESCALATED", escalationReason: "TAKEN_OVER" }), card({ decision: null, unknowns: [] })) },
    accepted,
  );
  await open(page);

  await expect(page.getByTestId("close-outcome")).toBeVisible();
  await expect(page.getByTestId("takeover")).toHaveCount(0);
  await expect(page.getByTestId("close-outcome")).toHaveValue("");
  await expect(page.getByTestId("close")).toBeDisabled();

  await page.getByTestId("close-outcome").fill("현장 처리");
  await page.getByTestId("close").click();
  const post = await onePost(calls);
  expect(post.path).toBe(P + "/close");
  expect(post.body).toEqual({ outcome: "현장 처리" });
});

test("Episodes: takeover of a running episode asks first and sends no body", async ({ page }) => {
  const calls = await stub(page, { [P]: detail(ID, view({ phase: "DIAGNOSING" }), card({ decision: null })) }, accepted);
  await open(page);

  page.once("dialog", (d) => void d.accept());
  await page.getByTestId("takeover").click();
  const post = await onePost(calls);
  expect(post.path).toBe(P + "/takeover");
  expect(post.body ?? null).toBeNull();
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
