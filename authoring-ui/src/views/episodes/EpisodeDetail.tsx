// EpisodeDetail = the middle pane of the Episodes tab. Polls GET /api/episodes/{workflowId}/{run} (~1.5s) and
// shows the head, the operator card, the decisions the card and the live phase offer, and the folded history,
// candidates and records. The server decides: a refusal or a failure is only said in the operator's words.
// It never renders view.diagnosisJson, never sends a proposition, and shows records through recordPayload
// (no narrator answer, design §9.5). Mounted with key={instanceId}, so inputs and results never leak from one
// episode to another.
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { EpisodeDetailDto, RejectReason, Unknown } from "./episodeTypes";
import { REJECT_REASONS } from "./episodeTypes";
import { describeFailure, getEpisode, sendDecision } from "./episodeApi";
import {
  availableActions, closeBody, confirmableUnknown, decideBody, noticeText, operatorProblem, outcomeBody, parseCandidates,
  parseHistory, phaseChip, preconditionBody, recordPayload, remainingText, replyText, unknownBody,
} from "./episodes";
import { OperatorCardView } from "./OperatorCardView";

type Action = "decide" | "confirm" | "takeover" | "close";
type Result = { text: string; ok: boolean; movedTo?: string };

const SENDING = "보내는 중… · sending…";

/** An unknown's note is keyed by what it is, not by its place on the card: the card may reorder between polls. */
const unknownKey = (u: Unknown) => u.what + JSON.stringify(u.subject);

/** The two countdowns, with their own 1s clock so the rest of the pane does not re-render every second. */
function Deadlines({ state, episode }: { state: number | null; episode: number | null }) {
  const [now, setNow] = useState(() => Date.now());
  const ticking = state != null || episode != null;
  useEffect(() => {
    if (!ticking) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [ticking]);
  return (
    <div className="muted" data-testid="deadlines">
      상태 · state: {remainingText(state, now) ?? "—"} · 에피소드 · episode: {remainingText(episode, now) ?? "—"}
    </div>
  );
}

export function EpisodeDetail({ instanceId, operator, onSelect }: {
  instanceId: string;
  operator: string;
  onSelect: (instanceId: string) => void;
}) {
  const [detail, setDetail] = useState<EpisodeDetailDto | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [pressed, setPressed] = useState<string | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [rejectReason, setRejectReason] = useState<RejectReason | "">("");
  const [rejectNote, setRejectNote] = useState("");
  const [closeOutcome, setCloseOutcome] = useState("");
  const [unknownNotes, setUnknownNotes] = useState<Record<string, string>>({});
  const mounted = useRef(true);
  /** The latest load issued; only its answer is applied, so a poll sent before a POST never overwrites the re-read after it. */
  const seq = useRef(0);
  /** Loads in flight; a poll tick is skipped while one is out. */
  const inFlight = useRef(0);

  const load = useCallback(async (isAlive: () => boolean) => {
    const mine = ++seq.current;
    inFlight.current += 1;
    try {
      const d = await getEpisode(instanceId);
      if (isAlive() && mine === seq.current) { setDetail(d); setLoadError(null); }
    } catch (e) {
      // Keep the last detail that was read well; only say the failure.
      if (isAlive() && mine === seq.current) setLoadError(describeFailure(e).text);
    } finally {
      inFlight.current -= 1;
    }
  }, [instanceId]);

  /** One immediate read, sequenced with the polls. */
  const refresh = useCallback(() => load(() => mounted.current), [load]);

  useEffect(() => {
    let alive = true;
    mounted.current = true;
    void load(() => alive);
    const id = setInterval(() => { if (inFlight.current === 0) void load(() => alive); }, 1500);
    return () => { alive = false; mounted.current = false; clearInterval(id); };
  }, [load]);

  async function send(key: string, action: Action, body?: object) {
    setBusy(true);
    setPressed(key);
    setResult(null);
    try {
      const reply = await sendDecision(instanceId, action, operator, body);
      if (mounted.current) setResult({ text: replyText(reply), ok: reply === "ACCEPTED" });
    } catch (e) {
      const f = describeFailure(e, true);
      if (mounted.current) setResult({ text: f.text, ok: false, movedTo: f.movedTo });
    } finally {
      // Re-read before the buttons come back, so the next decision is made on what the core now holds.
      await refresh();
      if (mounted.current) { setBusy(false); setPressed(null); }
    }
  }

  const view = detail?.view ?? null;
  const history = useMemo(() => (view ? parseHistory(view) : []), [view]);
  const candidates = useMemo(() => (view ? parseCandidates(view) : []), [view]);
  const records = useMemo(
    () => (detail?.events ?? []).map((ev) => ({ ev, shown: JSON.stringify(recordPayload(ev.kind, ev.payload), null, 2) })),
    [detail],
  );

  if (detail === null) {
    return (
      <div className="episodes-detail panel-body">
        <div className="muted">읽는 중 · loading</div>
        {loadError && <div className="banner err">{loadError}</div>}
      </div>
    );
  }

  const { card, viewError } = detail;
  const chip = phaseChip(view?.phase ?? null);
  const actions = availableActions(view, card);
  const blocked = operatorProblem(operator) != null || busy;
  const label = (key: string, text: string) => (busy && pressed === key ? SENDING : text);
  const anyAction = Object.values(actions).some(Boolean);
  const close = closeBody(closeOutcome);

  return (
    <div className="episodes-detail panel-body">
      {/* Head */}
      <div className="console-detail-head">
        <span className="mono" data-testid="detail-instance">{instanceId}</span>
        <span className={`chip ${chip.cls}`} data-testid="detail-phase">{chip.label}</span>
      </div>
      {view?.escalationReason && (
        <div className="banner err">
          <span>
            사람에게 넘어감 · escalated: {view.escalationReason}
            {view.escalationDetail ? ` — ${view.escalationDetail}` : ""}
          </span>
        </div>
      )}
      <Deadlines state={card?.decision?.deadlineMillis ?? view?.stateDeadlineMillis ?? null} episode={view?.episodeDeadlineMillis ?? null} />
      {viewError && (
        <div className="banner info">지금 상태를 읽지 못함 — 기록만 보임 · live state unavailable, records only: {viewError}</div>
      )}
      {view == null && !viewError && <div className="banner info">끝난 에피소드 — 기록만 보임 · ended, records only</div>}
      {loadError && <div className="banner err">{loadError}</div>}

      {/* Card */}
      {card && <OperatorCardView card={card} />}

      {view?.phase === "UNKNOWN_PRECONDITION" && !card?.precondition && (
        <div className="banner info" data-testid="no-proposition">
          확인할 명제가 없음 — 사람이 확인할 수 없음, 기한에 끝나거나 인수 · no proposition to confirm: it ends at its deadline, or take over
        </div>
      )}

      {/* Decisions: offered from the card's decision and the live phase only; the core still decides. */}
      {anyAction && (
        <div className="episodes-decisions" data-testid="decisions">
          {actions.decide && card?.decision && (
            <>
              <button type="button" className="btn primary" data-testid="decide-approve" disabled={blocked}
                onClick={() => void send("decide-approve", "decide", decideBody(card.decision!, true))}>
                {label("decide-approve", "✓ 승인 · Approve")}
              </button>
              <select data-testid="reject-reason" aria-label="거절 사유 · reject reason" value={rejectReason}
                onChange={(e) => setRejectReason(e.target.value as RejectReason | "")}>
                <option value="">거절 사유 · reject reason</option>
                {REJECT_REASONS.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
              <input type="text" data-testid="reject-note" aria-label="거절 메모 · reject note" placeholder="메모 · note"
                value={rejectNote} onChange={(e) => setRejectNote(e.target.value)} />
              <button type="button" className="btn danger" data-testid="decide-reject" disabled={blocked || !rejectReason}
                onClick={() => void send("decide-reject", "decide", decideBody(card.decision!, false, rejectReason || undefined, rejectNote))}>
                {label("decide-reject", "✕ 거절 · Reject")}
              </button>
            </>
          )}

          {actions.confirmPrecondition && card && (
            <>
              <button type="button" className="btn" data-testid="confirm-precondition-holds" disabled={blocked}
                onClick={() => void send("confirm-precondition-holds", "confirm", preconditionBody(card, true))}>
                {label("confirm-precondition-holds", "전제 맞음 · holds")}
              </button>
              <button type="button" className="btn" data-testid="confirm-precondition-not" disabled={blocked}
                onClick={() => void send("confirm-precondition-not", "confirm", preconditionBody(card, false))}>
                {label("confirm-precondition-not", "전제 아님 · does not hold")}
              </button>
            </>
          )}

          {actions.confirmOutcome && view && (
            <>
              <button type="button" className="btn" data-testid="confirm-outcome-done" disabled={blocked}
                onClick={() => void send("confirm-outcome-done", "confirm", outcomeBody(view, true))}>
                {label("confirm-outcome-done", "완료됨 · DONE")}
              </button>
              <button type="button" className="btn" data-testid="confirm-outcome-not" disabled={blocked}
                onClick={() => void send("confirm-outcome-not", "confirm", outcomeBody(view, false))}>
                {label("confirm-outcome-not", "안 됨 · NOT DONE")}
              </button>
            </>
          )}

          {actions.confirmUnknowns && card && card.unknowns.map((u, i) => {
            if (!confirmableUnknown(u)) return null;
            const k = unknownKey(u);
            const testKey = "confirm-unknown-" + i;
            return (
              <span key={k} className="episodes-decisions">
                <span className="mono">{u.what}</span>
                <input type="text" data-testid={"unknown-note-" + i} aria-label={`확인 메모 · note for ${u.what}`} placeholder="메모 · note"
                  value={unknownNotes[k] ?? ""} onChange={(e) => setUnknownNotes((m) => ({ ...m, [k]: e.target.value }))} />
                <button type="button" className="btn" data-testid={testKey} disabled={blocked}
                  onClick={() => void send(testKey, "confirm", unknownBody(u, unknownNotes[k]))}>
                  {label(testKey, "확인함 · confirmed")}
                </button>
              </span>
            );
          })}

          {actions.takeover && (
            <button type="button" className="btn danger" data-testid="takeover" disabled={blocked}
              onClick={() => { if (window.confirm("이 에피소드를 사람이 넘겨받습니까? · Take this episode over?")) void send("takeover", "takeover"); }}>
              {label("takeover", "■ 인수 · Take over")}
            </button>
          )}

          {actions.close && (
            <>
              <input type="text" data-testid="close-outcome" aria-label="닫는 결과 · close outcome" placeholder="결과 · outcome"
                value={closeOutcome} onChange={(e) => setCloseOutcome(e.target.value)} />
              <button type="button" className="btn" data-testid="close" disabled={blocked || close == null}
                onClick={() => { if (close) void send("close", "close", close); }}>
                {label("close", "닫기 · Close")}
              </button>
            </>
          )}
        </div>
      )}

      {result && (
        <div className={`banner ${result.ok ? "ok" : "err"}`} data-testid="decision-result">
          <span>{result.text}</span>
          {result.movedTo && (
            <button type="button" className="btn" data-testid="open-moved" onClick={() => onSelect(result.movedTo!)}>
              지금 에피소드 보기 · open the current episode
            </button>
          )}
        </div>
      )}

      {/* Folded: history, candidates, records. */}
      <details data-testid="history">
        <summary>시도 이력 · attempts ({history.length})</summary>
        {history.map((h, i) => (
          <div key={i} className="mono">
            {[
              `#${h.attempt}`,
              h.closedAs,
              h.diagnosis?.outcome,
              h.approval ? [h.approval.result, h.approval.reason].filter(Boolean).join("/") : null,
              h.evidence?.outcome,
              h.at,
            ].filter(Boolean).join(" · ")}
          </div>
        ))}
      </details>

      <details data-testid="candidates">
        <summary>후보 · candidates ({candidates.length})</summary>
        {candidates.map((c, i) => (
          <div key={i} className="mono">
            {[c.kind, ...Object.entries(c.ref ?? {}).map(([k, v]) => `${k}=${v ?? "null"}`)].join(" · ")}
          </div>
        ))}
      </details>

      <details data-testid="records">
        <summary>기록 · records ({records.length})</summary>
        {records.map(({ ev, shown }) => (
          <div key={ev.seq}>
            <div className="mono">{[String(ev.seq), ev.kind, ev.at].filter(Boolean).join(" · ")}</div>
            <pre className="mono" style={{ whiteSpace: "pre-wrap" }}>{shown}</pre>
          </div>
        ))}
      </details>

      {/* This episode's notices. */}
      <div data-testid="episode-notices">
        <div className="muted">이 에피소드의 알림 · notices ({detail.notices.length})</div>
        {detail.notices.map((n) => (
          <div key={n.id}><span className="mono">{n.at}</span> {noticeText(n.notice)}</div>
        ))}
      </div>
    </div>
  );
}
