// NoticeFeed = the right pane of the Episodes tab: every episode's notices, newest first. Notice ids are not
// commit-ordered, so each poll re-reads an overlap below the last id seen and drops ids already seen (design
// §8.5 구현(B3c)); a full page means more are waiting, so it keeps paging until a short one.
import { useEffect, useRef, useState } from "react";
import type { FeedNotice } from "./episodeTypes";
import { describeFailure, noticesAfter } from "./episodeApi";
import { mergeNotices, noticeText, overlapAfter } from "./episodes";

export function NoticeFeed({ onSelect }: { onSelect: (instanceId: string) => void }) {
  const seen = useRef<FeedNotice[]>([]);
  const [items, setItems] = useState<FeedNotice[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Both flags belong to this effect run (a ref would outlive StrictMode's re-run and skip its first tick).
    let alive = true;
    let inFlight = false;
    const tick = async () => {
      // One read (with its paging) at a time: a slow one must not interleave with the next.
      if (inFlight) return;
      inFlight = true;
      try {
        let after = overlapAfter(seen.current);
        for (let i = 0; i < 10; i++) {
          const rows = await noticesAfter(after, 500);
          if (!alive) return;
          seen.current = mergeNotices(seen.current, rows);
          if (rows.length < 500) break;
          after = rows[rows.length - 1].id;
        }
        if (alive) { setItems([...seen.current].reverse()); setError(null); }
      } catch (e) {
        if (alive) setError(describeFailure(e).text);
      } finally {
        inFlight = false;
      }
    };
    void tick();
    const id = setInterval(() => void tick(), 3000);
    return () => { alive = false; clearInterval(id); };
  }, []);

  return (
    <div className="episodes-feed">
      <div className="muted pad-sm">알림 · notices</div>
      {error && <div className="banner err">{error}</div>}
      {items.length === 0 && !error && <div className="muted pad-sm">알림 없음 · no notices yet</div>}
      {items.map((n) => (
        <div key={n.id} className="console-run" data-testid={"notice-" + n.id}>
          <div className="row1">
            <span>{noticeText(n.notice)}</span>
            {n.notice.kind === "PERSON_TASK" && <span className="chip await">사람 과업 · PERSON_TASK</span>}
          </div>
          <div className="muted mono">{n.at}</div>
          <button type="button" className="btn ghost mono" data-testid={"notice-open-" + n.id} onClick={() => onSelect(n.instanceId)}>
            {n.instanceId}
          </button>
        </div>
      ))}
    </div>
  );
}
