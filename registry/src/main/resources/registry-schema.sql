CREATE TABLE IF NOT EXISTS block_index (
  id            text        NOT NULL,
  version       text        NOT NULL,
  manifest_json text        NOT NULL,
  jar_path      text        NOT NULL,
  sha256        text        NOT NULL,
  published_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (id, version)
);

ALTER TABLE block_index ADD COLUMN IF NOT EXISTS deprecated boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS workflow_def (
  name        text        NOT NULL,
  version     text        NOT NULL,
  def_json    text        NOT NULL,
  deployed    boolean     NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (name, version)
);

CREATE TABLE IF NOT EXISTS run_index (
  run_id            text        NOT NULL,
  workflow_name     text        NOT NULL,
  workflow_version  text        NOT NULL,
  params_json       text        NOT NULL DEFAULT '{}',
  started_at        timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (run_id)
);

ALTER TABLE run_index ADD COLUMN IF NOT EXISTS engine text NOT NULL DEFAULT 'temporal';

-- v0.7 run-state durable archive --------------------------------------------------
-- run-level terminal record (write-once). final_status non-null == "archived".
-- final_status holds the RAW engine status (Temporal "WORKFLOW_EXECUTION_STATUS_COMPLETED"
-- | Conductor "COMPLETED"); normalization happens only in the terminal check (RunStatus.kt).
ALTER TABLE run_index ADD COLUMN IF NOT EXISTS final_status text;
ALTER TABLE run_index ADD COLUMN IF NOT EXISTS completed_at timestamptz;
ALTER TABLE run_index ADD COLUMN IF NOT EXISTS comp_outcome text;   -- NONE | COMPENSATED | COMP_FAILED

-- final per-node lighting snapshot (canvas re-render for aged runs)
CREATE TABLE IF NOT EXISTS run_node_state (
  run_id  text NOT NULL,
  node_id text NOT NULL,
  state   text NOT NULL,
  PRIMARY KEY (run_id, node_id)
);

-- compensation-timeline snapshot, uniform across engines (mirrors CompensationEvent)
CREATE TABLE IF NOT EXISTS run_comp_event (
  run_id    text   NOT NULL,
  idx       int    NOT NULL,
  node_id   text   NOT NULL,
  block_id  text   NOT NULL,
  version   text   NOT NULL,
  outcome   text   NOT NULL,    -- COMPENSATED | FAILED
  at_millis bigint NOT NULL,
  PRIMARY KEY (run_id, idx)
);

-- Outbound governance-event dedup (spec 2026-07-01). Write-once (run_id,event_type).
CREATE TABLE IF NOT EXISTS emitted_event (
  run_id     TEXT   NOT NULL,
  event_type TEXT   NOT NULL,   -- RECONCILING | CONFIRMED | RECON_FAILED
  emitted_at BIGINT NOT NULL,
  PRIMARY KEY (run_id, event_type)
);

-- R4 canary/rollback: per-unit active FSM-spec version pointer (spec 2026-07-02 §5).
-- Spec CONTENT + approval stay Git-canonical (model/fsm/*.yaml); this holds only "which version is
-- live now" + an append-only deploy/rollback audit. deploy/rollback are one tx each (atomic swap).
CREATE TABLE IF NOT EXISTS fsm_deployment (
  unit             text        NOT NULL,
  active_version   text        NOT NULL,
  previous_version text,                         -- immediately-prior active version (instant rollback target)
  deployed_at      timestamptz NOT NULL DEFAULT now(),
  deployed_by      text        NOT NULL DEFAULT '-',
  PRIMARY KEY (unit)
);

-- Soak window + auto-rollback (additive; a pre-existing row defaults to a stable 'promoted' deployment).
ALTER TABLE fsm_deployment ADD COLUMN IF NOT EXISTS status         text        NOT NULL DEFAULT 'promoted';
ALTER TABLE fsm_deployment ADD COLUMN IF NOT EXISTS soak_until     timestamptz;
ALTER TABLE fsm_deployment ADD COLUMN IF NOT EXISTS fail_count     int         NOT NULL DEFAULT 0;
ALTER TABLE fsm_deployment ADD COLUMN IF NOT EXISTS fail_threshold int;

CREATE TABLE IF NOT EXISTS fsm_deployment_audit (
  id           bigserial   PRIMARY KEY,
  unit         text        NOT NULL,
  from_version text,                             -- null on first deploy
  to_version   text        NOT NULL,
  action       text        NOT NULL,             -- DEPLOY | ROLLBACK | AUTO_ROLLBACK | PROMOTE
  at           timestamptz NOT NULL DEFAULT now(),
  actor        text        NOT NULL DEFAULT '-'
);

-- FSM field-transition drift-detect (design 2026-07-03). drift_observation = the last observed state code
-- per unit; drift_audit = the append-only observation/drift log. Detect-only (hermod observes, not drives).
CREATE TABLE IF NOT EXISTS drift_observation (
  unit            text        NOT NULL,
  last_state_code int         NOT NULL,
  observed_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (unit)
);
CREATE TABLE IF NOT EXISTS drift_audit (
  id         bigserial   PRIMARY KEY,
  unit       text        NOT NULL,
  from_code  int,
  to_code    int         NOT NULL,
  verdict    text        NOT NULL,
  detail     text        NOT NULL DEFAULT '-',
  at         timestamptz NOT NULL DEFAULT now()
);

-- R4 auto-correct auto-dispatch (design 2026-07-04): per-unit dedup ledger for auto-dispatched corrective
-- ot-safe-hold runs. PENDING while the run is in flight/parked; RESOLVED on run success, FAILED otherwise.
-- Distinct from drift_audit (immutable observations) — this carries a mutable correction lifecycle.
CREATE TABLE IF NOT EXISTS drift_correction (
  id            bigserial   PRIMARY KEY,
  unit          text        NOT NULL,
  run_id        text        NOT NULL,
  from_code     int         NOT NULL,
  to_code       int         NOT NULL,
  workflow      text        NOT NULL,
  status        text        NOT NULL,       -- PENDING | RESOLVED | FAILED
  dispatched_at timestamptz NOT NULL DEFAULT now(),
  resolved_at   timestamptz
);
CREATE INDEX IF NOT EXISTS drift_correction_unit_idx ON drift_correction(unit);

-- R4 loose-ends: at most one in-flight (PENDING) correction per unit — DB-enforced dedup atomicity
-- so two concurrent sweep drivers can't both dispatch. The losing insert gets SQLState 23505.
CREATE UNIQUE INDEX IF NOT EXISTS drift_correction_one_pending ON drift_correction(unit) WHERE status='PENDING';
