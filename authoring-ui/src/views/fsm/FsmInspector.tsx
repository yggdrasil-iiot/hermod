// FsmInspector = right rail. Edits the selected state (id/code) or transition
// (command/driver/action.workflow) and lists the advisory structural issues. Owns no graph state;
// edits are delegated to FsmView which mutates the canvas node/edge data.
import type { FsmState, FsmTransition } from "./fsmTypes";
import type { FsmIssue } from "./fsmValidate";

export type Selection =
  | { kind: "state"; state: FsmState }
  | { kind: "transition"; transition: FsmTransition }
  | null;

export interface FsmInspectorProps {
  selection: Selection;
  onStateChange: (patch: Partial<FsmState>) => void;
  onTransitionChange: (patch: Partial<FsmTransition>) => void;
  onDelete: () => void;
  issues: FsmIssue[];
}

export function FsmInspector({ selection, onStateChange, onTransitionChange, onDelete, issues }: FsmInspectorProps) {
  return (
    <div className="inspector-rail">
      <div className="panel">
        <div className="panel-head"><span>SELECTION</span></div>
        <div className="inspector-section">
          {!selection && <div className="empty">Select a state or transition.</div>}

          {selection?.kind === "state" && (
            <>
              <div className="field">
                <label htmlFor="fsm-state-id">State id</label>
                <input id="fsm-state-id" data-testid="fsm-state-id" className="mono" value={selection.state.id}
                  onChange={(e) => onStateChange({ id: e.target.value })} />
              </div>
              <div className="field">
                <label htmlFor="fsm-state-code">Code</label>
                <input id="fsm-state-code" data-testid="fsm-state-code" className="mono" type="number" value={selection.state.code}
                  onChange={(e) => onStateChange({ code: Number(e.target.value) })} />
              </div>
              <button className="btn" data-testid="fsm-delete" onClick={onDelete}>Delete state</button>
            </>
          )}

          {selection?.kind === "transition" && (
            <>
              <div className="field">
                <label htmlFor="fsm-tr-command">Command (blank = reactive/null)</label>
                <input id="fsm-tr-command" data-testid="fsm-tr-command" className="mono" value={selection.transition.command ?? ""}
                  onChange={(e) => onTransitionChange({ command: e.target.value === "" ? null : e.target.value })} />
              </div>
              <div className="field">
                <label htmlFor="fsm-tr-driver">Driver</label>
                <select id="fsm-tr-driver" data-testid="fsm-tr-driver" className="mono" value={selection.transition.driver}
                  onChange={(e) => onTransitionChange({ driver: e.target.value })}>
                  <option value="field">field</option>
                  <option value="hermod">hermod</option>
                </select>
              </div>
              {selection.transition.driver === "hermod" && (
                <div className="field">
                  <label htmlFor="fsm-tr-workflow">action.workflow</label>
                  <input id="fsm-tr-workflow" data-testid="fsm-tr-workflow" className="mono" value={selection.transition.action?.workflow ?? ""}
                    onChange={(e) => onTransitionChange({ action: { workflow: e.target.value } })} />
                </div>
              )}
              <button className="btn" data-testid="fsm-delete" onClick={onDelete}>Delete transition</button>
            </>
          )}
        </div>
      </div>

      <div className="panel">
        <div className="panel-head">
          <span>VALIDATION</span>
          <span className={`risk ${issues.length === 0 ? "green" : "red"}`}><span className="led" />{issues.length === 0 ? "OK" : `${issues.length}`}</span>
        </div>
        <div className="inspector-section tight">
          {issues.length === 0 ? (
            <div className="banner ok">No structural issues. Cross-artifact checks run in the conformance gate after commit.</div>
          ) : (
            <ul className="list-plain" data-testid="fsm-issues">
              {issues.map((i, k) => <li key={k} className="banner err">{i.message}</li>)}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
