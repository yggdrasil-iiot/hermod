// Palette = the drag source for the canvas. Loads GET /palette (canvas-ready blocks only) and renders
// each PaletteCard as a draggable `.palette-card`, grouped by category. The drag payload carries just
// {id, version}; Canvas resolves it back to the full card from the `palette` prop (exposed via onLoaded).
import { useEffect, useState } from "react";
import { getPalette } from "../../api";
import type { PaletteCard } from "../../types";
import { RiskBadge, Spinner } from "../../ui";

interface Props {
  // Expose the loaded cards up so Canvas/ComposeView can resolve dropped {id,version} → PaletteCard.
  onLoaded?: (cards: PaletteCard[]) => void;
}

// Stable group order mirrors the authored-contract BlockCategory union (types.ts CATEGORIES).
const CATEGORY_ORDER = ["source", "transform", "sink", "control", "external"];

export const DRAG_MIME = "application/hermod-block";

export function Palette({ onLoaded }: Props) {
  const [cards, setCards] = useState<PaletteCard[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const p = await getPalette();
        if (!alive) return;
        setCards(p);
        onLoaded?.(p);
      } catch (e) {
        if (alive) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => {
      alive = false;
    };
    // onLoaded intentionally excluded — palette is fetched once on mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (error) return <div className="banner err">Failed to load palette: {error}</div>;
  if (!cards) return <Spinner label="LOADING PALETTE" />;
  if (cards.length === 0)
    return <div className="empty">Palette is empty — no canvas-ready blocks.</div>;

  // Group by category, preserving the canonical order; unknown categories trail at the end.
  const byCat = new Map<string, PaletteCard[]>();
  for (const c of cards) {
    const list = byCat.get(c.category) ?? [];
    list.push(c);
    byCat.set(c.category, list);
  }
  const groups = [...byCat.keys()].sort((a, b) => {
    const ia = CATEGORY_ORDER.indexOf(a);
    const ib = CATEGORY_ORDER.indexOf(b);
    return (ia < 0 ? CATEGORY_ORDER.length : ia) - (ib < 0 ? CATEGORY_ORDER.length : ib);
  });

  return (
    <>
      {groups.map((cat) => (
        <div className="palette-group" key={cat}>
          <div className="palette-group-label">{cat}</div>
          {byCat.get(cat)!.map((card) => (
            <div
              className="palette-card"
              key={card.id}
              data-testid={"palette-" + card.id}
              draggable
              onDragStart={(e) => {
                e.dataTransfer.setData(
                  DRAG_MIME,
                  JSON.stringify({ id: card.id, version: card.latestVersion }),
                );
                e.dataTransfer.effectAllowed = "move";
              }}
            >
              <div className="pc-name">{card.displayName || card.id}</div>
              <div className="pc-id">
                {card.id} <span>@{card.latestVersion}</span>
              </div>
              <RiskBadge risk={card.risk} />
              {card.description && <div className="pc-desc">{card.description}</div>}
            </div>
          ))}
        </div>
      ))}
    </>
  );
}
