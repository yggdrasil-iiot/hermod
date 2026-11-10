import type { Page } from "@playwright/test";

/**
 * Synthetic HTML5 drag-and-drop helper — shared across specs.
 *
 * Playwright's native mouse-based drag does NOT populate the `dataTransfer` object, so
 * Canvas.tsx's `onDrop` (which reads `e.dataTransfer.getData("application/hermod-block")`)
 * receives nothing and silently returns without adding a node.
 *
 * Fix: use `page.evaluate` to construct a real `DataTransfer` in the browser, call setData()
 * on it, then dispatch synthetic dragenter + dragover + drop events on the ReactFlow root
 * element (`[data-testid="compose-canvas"]`).  The `clientX/clientY` passed to the drop event
 * is used by Canvas's `screenToFlowPosition()` to place the node inside the flow viewport.
 *
 * @param page     Playwright Page object
 * @param blockId  PaletteCard id, e.g. "db.read"
 * @param version  Pinned version string, e.g. "1.0.0"
 * @param x        Absolute viewport clientX for the drop
 * @param y        Absolute viewport clientY for the drop
 */
export async function dragCardToCanvas(
  page: Page,
  blockId: string,
  version: string,
  x: number,
  y: number,
): Promise<void> {
  const DRAG_MIME = "application/hermod-block";
  const payload = JSON.stringify({ id: blockId, version });

  await page.evaluate(
    ({ mime, data, cx, cy }) => {
      const canvas = document.querySelector('[data-testid="compose-canvas"]') as HTMLElement | null;
      if (!canvas) throw new Error("compose-canvas element not found in DOM");

      const dt = new DataTransfer();
      dt.setData(mime, data);

      // Prime React's synthetic event handling with dragenter + dragover first
      canvas.dispatchEvent(
        new DragEvent("dragenter", { bubbles: true, cancelable: true, dataTransfer: dt, clientX: cx, clientY: cy }),
      );
      canvas.dispatchEvent(
        new DragEvent("dragover", { bubbles: true, cancelable: true, dataTransfer: dt, clientX: cx, clientY: cy }),
      );
      canvas.dispatchEvent(
        new DragEvent("drop", { bubbles: true, cancelable: true, dataTransfer: dt, clientX: cx, clientY: cy }),
      );
    },
    { mime: DRAG_MIME, data: payload, cx: x, cy: y },
  );
}

const STYLE = `#e2e-caption{position:fixed;top:16px;left:50%;transform:translateX(-50%);z-index:2147483647;`
  + `background:rgba(10,14,11,.93);border:1px solid #7CFF6B;color:#eafff0;`
  + `font:600 18px/1.4 system-ui,'Malgun Gothic',Apple SD Gothic Neo,sans-serif;`
  + `padding:9px 20px;border-radius:9px;box-shadow:0 6px 24px rgba(0,0,0,.55);pointer-events:none;max-width:84vw;text-align:center}`;

// Inject/update a fixed top-center caption banner. Recorded natively in the video; Korean renders
// via the page font. Call only AFTER the spec's single page.goto (a later goto wipes the banner).
export async function caption(page: Page, text: string, dwellMs = 850): Promise<void> {
  await page.evaluate(({ text, style }) => {
    if (!document.getElementById("e2e-caption-style")) {
      const s = document.createElement("style"); s.id = "e2e-caption-style"; s.textContent = style;
      document.head.appendChild(s);
    }
    let el = document.getElementById("e2e-caption");
    if (!el) { el = document.createElement("div"); el.id = "e2e-caption"; document.body.appendChild(el); }
    el.textContent = text;
  }, { text, style: STYLE });
  await page.waitForTimeout(dwellMs); // dwell so the label is readable in the GIF
}

// Inject a cursor dot that follows real mouse events. Call BEFORE page.goto (addInitScript applies
// to subsequent navigations). Best-effort: the dot jumps to click points (no glide). Drop if janky.
export async function installCursor(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const mk = () => {
      if (document.getElementById("e2e-cursor")) return;
      const s = document.createElement("style");
      s.textContent = "#e2e-cursor{position:fixed;width:18px;height:18px;margin:-9px 0 0 -9px;border-radius:50%;"
        + "background:rgba(124,255,107,.5);border:2px solid #7CFF6B;z-index:2147483646;pointer-events:none;"
        + "transition:transform .09s ease}#e2e-cursor.dn{transform:scale(.55)}";
      document.head.appendChild(s);
      const d = document.createElement("div"); d.id = "e2e-cursor"; document.body.appendChild(d);
      addEventListener("mousemove", (e) => { d.style.left = (e as MouseEvent).clientX + "px"; d.style.top = (e as MouseEvent).clientY + "px"; }, true);
      addEventListener("mousedown", () => { d.classList.add("dn"); setTimeout(() => d.classList.remove("dn"), 160); }, true);
    };
    if (document.body) mk(); else addEventListener("DOMContentLoaded", mk);
  });
}

// Full-screen intro/outro card. lines are HTML strings (use <b> for emphasis). Shown for ms then removed.
// MUST be awaited; for the OUTRO card it must be the final awaited statement in the test body (the video
// is finalized on context close — anything not dwelled during the body isn't recorded).
export async function card(page: Page, lines: string[], ms = 2400, persist = false): Promise<void> {
  await page.evaluate(({ lines }) => {
    if (!document.getElementById("e2e-card-style")) {
      const s = document.createElement("style"); s.id = "e2e-card-style";
      s.textContent =
        "#e2e-card{position:fixed;inset:0;z-index:2147483647;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px;"
        + "background:#0a0e0b;color:#eafff0;font:600 32px/1.5 system-ui,'Malgun Gothic',Apple SD Gothic Neo,sans-serif;text-align:center;padding:6vw;"
        + "opacity:0;transition:opacity .35s ease}#e2e-card.show{opacity:1}#e2e-card b{color:#7CFF6B}"
        + "#e2e-card .l0{font-size:34px}#e2e-card .ln{font-size:22px;color:#bfe9cc;font-weight:500}";
      document.head.appendChild(s);
    }
    let el = document.getElementById("e2e-card");
    if (!el) { el = document.createElement("div"); el.id = "e2e-card"; document.body.appendChild(el); }
    el.innerHTML = lines.map((l, i) => `<div class="${i === 0 ? "l0" : "ln"}">${l}</div>`).join("");
    requestAnimationFrame(() => el!.classList.add("show"));
  }, { lines });
  await page.waitForTimeout(ms);
  // persist=true → leave the card on screen. Use this for the OUTRO so it is the LAST recorded frame
  // (the body returns with the card up; context-close captures it). The INTRO uses persist=false so the
  // app shows after it.
  if (persist) return;
  await page.evaluate(() => document.getElementById("e2e-card")?.classList.remove("show"));
  await page.waitForTimeout(350); // fade out
  await page.evaluate(() => document.getElementById("e2e-card")?.remove());
}

// Pulsing red ring + label over a React-Flow node (by step id). Call only AFTER the node is laid out
// (assert it visible first) so the bounding box is final. Remove with clearHighlight on the next beat.
export async function highlightNode(page: Page, nodeId: string, label: string): Promise<void> {
  await page.evaluate(({ nodeId, label }) => {
    const node = document.querySelector(`.react-flow__node[data-id="${nodeId}"]`);
    if (!node) return;
    const r = node.getBoundingClientRect();
    if (!document.getElementById("e2e-hl-style")) {
      const s = document.createElement("style"); s.id = "e2e-hl-style";
      s.textContent =
        "#e2e-hl{position:fixed;z-index:2147483646;pointer-events:none;border:3px solid #ff5a5a;border-radius:12px;"
        + "animation:e2ehlp 1s ease-in-out infinite}"
        + "@keyframes e2ehlp{0%,100%{box-shadow:0 0 0 4px rgba(255,90,90,.18)}50%{box-shadow:0 0 0 11px rgba(255,90,90,.34)}}"
        + "#e2e-hl-label{position:fixed;z-index:2147483647;pointer-events:none;background:#ff5a5a;color:#220000;"
        + "font:700 13px system-ui,'Malgun Gothic';padding:2px 9px;border-radius:6px}";
      document.head.appendChild(s);
    }
    let hl = document.getElementById("e2e-hl");
    if (!hl) { hl = document.createElement("div"); hl.id = "e2e-hl"; document.body.appendChild(hl); }
    let lab = document.getElementById("e2e-hl-label");
    if (!lab) { lab = document.createElement("div"); lab.id = "e2e-hl-label"; document.body.appendChild(lab); }
    hl.style.left = r.left - 4 + "px"; hl.style.top = r.top - 4 + "px";
    hl.style.width = r.width + 8 + "px"; hl.style.height = r.height + 8 + "px";
    lab.textContent = label; lab.style.left = r.left + "px"; lab.style.top = (r.top - 24) + "px";
  }, { nodeId, label });
}

export async function clearHighlight(page: Page): Promise<void> {
  await page.evaluate(() => { document.getElementById("e2e-hl")?.remove(); document.getElementById("e2e-hl-label")?.remove(); });
}
