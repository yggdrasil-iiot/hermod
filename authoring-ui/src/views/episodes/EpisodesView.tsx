// EpisodesView = the 07 "Episodes" tab root: the operator's name on top, then three panes — the episode list,
// the selected episode, and the notice feed. The selection is held as an instance id.
import { useId, useState } from "react";
import { EpisodeList } from "./EpisodeList";
import { NoticeFeed } from "./NoticeFeed";
import { operatorProblem } from "./episodes";

const OPERATOR_KEY = "koshei.operator";

function readOperator(): string {
  try {
    return localStorage.getItem(OPERATOR_KEY) ?? "";
  } catch {
    /* private window etc. */
    return "";
  }
}

export function EpisodesView() {
  const id = useId();
  const [name, setName] = useState<string>(readOperator);
  const [selected, setSelected] = useState<string | null>(null);
  const problem = operatorProblem(name);

  const changeName = (v: string) => {
    setName(v);
    try {
      localStorage.setItem(OPERATOR_KEY, v);
    } catch {
      /* private window etc. */
    }
  };

  return (
    <div>
      <div className="toolbar">
        <label htmlFor={id}>운영자 · operator</label>
        <input id={id} type="text" className="mono" data-testid="operator-name" value={name} onChange={(e) => changeName(e.target.value)} />
        {problem && <span className="episodes-hint" data-testid="operator-problem">{problem}</span>}
      </div>
      <div className="episodes-grid">
        <EpisodeList selectedId={selected} onSelect={setSelected} />
        {selected
          ? <div className="console-detail empty" data-testid="detail-pending">{selected}</div>
          : <div className="console-detail empty">왼쪽에서 에피소드를 고르십시오 · select an episode</div>}
        <NoticeFeed onSelect={setSelected} />
      </div>
    </div>
  );
}
