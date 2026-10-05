import { useState } from "react";
import type { AuthoredContract } from "./types";
import { emptyContract } from "./types";
import { BlockBrowse } from "./views/BlockBrowse";
import { ContractEditor } from "./views/ContractEditor";
import { Publish } from "./views/Publish";
import { ComposeView } from "./views/compose/ComposeView";
import { FsmView } from "./views/fsm/FsmView";
import { ConsoleView } from "./views/console/ConsoleView";

type Tab = "browse" | "editor" | "publish" | "compose" | "fsm" | "console";

const TABS: { id: Tab; idx: string; label: string }[] = [
  { id: "browse", idx: "01", label: "Block Browse" },
  { id: "editor", idx: "02", label: "Contract Editor" },
  { id: "publish", idx: "03", label: "Publish" },
  { id: "compose", idx: "04", label: "Compose" },
  { id: "fsm", idx: "05", label: "FSM Editor" },
  { id: "console", idx: "06", label: "Console" },
];

const HEADERS: Record<Tab, { crumb: string; title: string; blurb: string }> = {
  browse: {
    crumb: "Registry · Palette projection",
    title: "Block Browse",
    blurb:
      "Inspect every registered block from the engineer's view, or flip to the operator palette projection.",
  },
  editor: {
    crumb: "Authoring · §5 contract",
    title: "Contract Editor",
    blurb:
      "Author a §5 contract and watch validation, CanvasReadiness (C1–C5), and derived risk update live.",
  },
  publish: {
    crumb: "Authoring · Registry mutation",
    title: "Publish",
    blurb:
      "Publish the built jar alongside the contract JSON — the only path that changes the operator palette.",
  },
  compose: {
    crumb: "Operator · compose & run",
    title: "Compose",
    blurb:
      "Drag blocks and wire ports to compose a workflow; save (= deploy) and run, and nodes light up live.",
  },
  fsm: {
    crumb: "Governance · R4 FSM spec",
    title: "FSM Editor",
    blurb:
      "Draw an FSM (states + governed transitions); the YAML panel emits model/fsm/*.yaml to commit as a PR. Cross-artifact checks run in the conformance gate.",
  },
  console: {
    crumb: "Operator · console",
    title: "Console",
    blurb:
      "Browse run history; select a run to light the DAG with its node states. Parked runs can be approved or rejected.",
  },
};

export default function App() {
  const [tab, setTab] = useState<Tab>("browse");
  // The authored contract is owned at the app level so the Editor can hand it to Publish.
  const [draft, setDraft] = useState<AuthoredContract>(emptyContract);

  const head = HEADERS[tab];

  return (
    <div className="shell">
      <aside className="rail">
        <div className="brand">
          <div className="mark">
            <span className="led" />
            KOSHEI
          </div>
          <div className="sub">Block Authoring</div>
        </div>

        <nav className="nav">
          {TABS.map((t) => (
            <button
              key={t.id}
              className={`nav-item ${tab === t.id ? "active" : ""}`}
              onClick={() => setTab(t.id)}
            >
              <span className="idx">{t.idx}</span>
              <span className="label">{t.label}</span>
            </button>
          ))}
        </nav>

        <div className="rail-foot">
          <span className="dot">●</span> API :18090
          <br />
          v0.4a · operator console
          <br />
          run history · detail · intervene
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <div>
            <div className="crumb">{head.crumb}</div>
            <h1>{head.title}</h1>
            <p>{head.blurb}</p>
          </div>
        </header>

        <section className="content">
          {tab === "browse" && (
            <BlockBrowse
              onEdit={(c) => {
                setDraft(c);
                setTab("editor");
              }}
            />
          )}
          {tab === "editor" && (
            <ContractEditor
              contract={draft}
              onChange={setDraft}
              onProceedToPublish={() => setTab("publish")}
            />
          )}
          {tab === "publish" && (
            <Publish
              contract={draft}
              onPublished={() => setTab("browse")}
              onEdit={() => setTab("editor")}
            />
          )}
          {tab === "compose" && <ComposeView />}
          {tab === "fsm" && <FsmView />}
          {tab === "console" && <ConsoleView />}
        </section>
      </main>
    </div>
  );
}
