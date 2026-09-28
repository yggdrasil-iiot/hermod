import { useRef, useState } from "react";
import { publish } from "../api";
import type { AuthoredContract } from "../types";
import { RiskBadge } from "../ui";

interface Props {
  contract: AuthoredContract;
  onPublished: () => void;
  onEdit: () => void;
}

export function Publish({ contract, onPublished, onEdit }: Props) {
  const [jar, setJar] = useState<File | null>(null);
  const [over, setOver] = useState(false);
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<string[] | null>(null);
  const [ok, setOk] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  const ready = contract.id.trim() !== "" && contract.forward.handler.trim() !== "" && jar !== null;

  async function doPublish() {
    if (!jar) return;
    setBusy(true);
    setErrors(null);
    setOk(false);
    try {
      const res = await publish(contract, jar);
      if (res.ok) {
        setOk(true);
        // Give the success banner a beat, then jump to the palette/browse to see the new card.
        window.setTimeout(onPublished, 900);
      } else {
        setErrors(res.errors.length ? res.errors : ["publish rejected (no detail)"]);
      }
    } catch (e) {
      setErrors([e instanceof Error ? e.message : String(e)]);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="publish">
      {/* Contract summary */}
      <div className="panel publish-summary">
        <div className="panel-head">
          <span>Authored contract</span>
          <button className="btn ghost" onClick={onEdit}>
            ← Back to editor
          </button>
        </div>
        <div className="panel-body">
          {contract.id ? (
            <>
              <div className="summary-line">
                <span className="summary-name">
                  {contract.displayName || contract.id}
                </span>
                <span className="chip cat">{contract.category}</span>
                <span className="chip">
                  {contract.params.length} params · {contract.inputs.length} in ·{" "}
                  {contract.outputs.length} out
                </span>
              </div>
              <div className="summary-fqcn">
                {contract.id} @{contract.version} → {contract.forward.handler || "(no handler!)"}
              </div>
            </>
          ) : (
            <div className="banner info empty-contract">
              <span>No contract authored yet. Author one in the editor first.</span>
              <button className="btn ghost" onClick={onEdit}>To editor</button>
            </div>
          )}
        </div>
      </div>

      {/* Jar picker */}
      <div
        className={`dropzone ${over ? "over" : ""}`}
        onClick={() => fileRef.current?.click()}
        onDragOver={(e) => {
          e.preventDefault();
          setOver(true);
        }}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setOver(false);
          const f = e.dataTransfer.files?.[0];
          if (f) setJar(f);
        }}
      >
        <input
          ref={fileRef}
          type="file"
          accept=".jar"
          className="file-hidden"
          onChange={(e) => setJar(e.target.files?.[0] ?? null)}
        />
        <div className="big">{jar ? "JAR selected" : "Drop the built JAR here, or click to browse"}</div>
        <div className="hint">
          The plugin jar containing the handler class <code>{contract.forward.handler || "<FQCN>"}</code>
        </div>
        {jar && (
          <div className="picked">
            ▸ {jar.name} · {(jar.size / 1024).toFixed(1)} KB
          </div>
        )}
      </div>

      {/* Publish action */}
      <div className="publish-action">
        <button className="btn primary" disabled={!ready || busy} onClick={() => void doPublish()}>
          {busy ? "Publishing…" : "Publish to registry"}
        </button>
        <span className="muted action-note">
          POST /api/publish — multipart (contract + jar). Publishes are immutable per version · reserved ids blocked.
        </span>
      </div>

      {ok && (
        <div className="banner ok result-banner">
          <span>
            ✓ Published — <code className="mono-inline">{contract.id}@{contract.version}</code>{" "}
            is now in the registry. Heading to the palette…
          </span>
          <RiskBadge risk="green" />
        </div>
      )}

      {errors && (
        <div className="banner err result-banner stack">
          <strong>Publish rejected</strong>
          {errors.map((e, i) => (
            <span key={i} className="err-detail">
              ✕ {e}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
