// EpisodeList = the left pane of the Episodes tab. Polls GET /api/episodes (~2s) so new episodes and phase
// changes appear; clicking a row selects that episode by its instance id. A tick is skipped while one is in flight,
// so a slow answer cannot land after a newer one.
import { useEffect, useState } from "react";
import type { EpisodeSummary } from "./episodeTypes";
import { describeFailure, listEpisodes } from "./episodeApi";
import { phaseChip } from "./episodes";

export function EpisodeList({ selectedId, onSelect }: { selectedId: string | null; onSelect: (instanceId: string) => void }) {
  const [rows, setRows] = useState<EpisodeSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    // Both flags belong to this effect run: a ref would outlive StrictMode's mount → cleanup → re-run and skip
    // the re-run's first tick while the discarded run's answer is thrown away.
    let alive = true;
    let inFlight = false;
    const tick = async () => {
      if (inFlight) return;
      inFlight = true;
      try {
        const list = await listEpisodes();
        if (alive) { setRows(list); setError(null); }
      } catch (e) {
        if (alive) setError(describeFailure(e).text);
      } finally {
        inFlight = false;
      }
    };
    void tick();
    const id = setInterval(() => void tick(), 2000);
    return () => { alive = false; clearInterval(id); };
  }, []);

  return (
    <div className="console-list">
      {error && <div className="banner err">{error}</div>}
      {rows.length === 0 && <div className="muted pad-sm">에피소드 없음 · no episodes yet</div>}
      {rows.map((r) => {
        const chip = phaseChip(r.lastPhase);
        const active = r.instanceId === selectedId ? "active" : "";
        return (
          <button
            key={r.instanceId}
            type="button"
            className={`episodes-row console-run ${active}`}
            data-testid={"episode-row-" + r.instanceId}
            onClick={() => onSelect(r.instanceId)}
          >
            <span className="row1">
              <span className="mono run-name" title={r.instanceId}>{r.workflowId}</span>
              <span className={`chip ${chip.cls}`}>{chip.label}</span>
            </span>
            <span className="muted">
              알림 {r.notices} · {r.notices} notices
              {r.recordLag && <> <span className="chip err">기록 지연 · record lag</span></>}
            </span>
            <span className="muted mono">{r.lastAt}</span>
          </button>
        );
      })}
    </div>
  );
}
