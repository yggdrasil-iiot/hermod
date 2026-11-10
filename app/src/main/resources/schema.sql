CREATE TABLE IF NOT EXISTS source_rows (id text PRIMARY KEY, val text);
CREATE TABLE IF NOT EXISTS target_rows (id text PRIMARY KEY, val text);
DROP TABLE IF EXISTS comp_ledger;
CREATE TABLE comp_ledger (
  workflow_id text    NOT NULL,
  node_id     text    NOT NULL,
  block_id    text    NOT NULL,
  version     text    NOT NULL,
  bound_state jsonb   NOT NULL,
  compensated boolean NOT NULL DEFAULT false,   -- true once a compensate step ran, regardless of outcome (see outcome)
  outcome     text,            -- v0.6c: 'COMPENSATED' | 'FAILED' (null until compensated)
  at_millis   bigint,          -- v0.6c: epoch millis the compensation step finished
  idx         int,             -- v0.6c: 0-based reverse-topo execution order
  PRIMARY KEY (workflow_id, node_id)
);
-- v0.4b/c: test-only fault toggle. When HERMOD_FAULT_INJECT is set on the worker, BlockActivitiesImpl throws a
-- permanent failure for any (block_id, phase) present here. phase distinguishes a forward fault (v0.4b, operator
-- retry proof) from a compensate fault (v0.4c, best-effort timeline proof). DROP+CREATE because a bare CREATE IF
-- NOT EXISTS can't add the phase column to the v0.4b single-column table. Empty + env-unset in prod -> inert.
DROP TABLE IF EXISTS fault_inject;
CREATE TABLE fault_inject (
  block_id text NOT NULL,
  phase    text NOT NULL DEFAULT 'forward',   -- 'forward' | 'compensate'
  PRIMARY KEY (block_id, phase)
);
-- v0.8 / OPC-UA adapter: persisted command audit trail for governed setpoint writes and activations.
CREATE TABLE IF NOT EXISTS command_audit (
  run_id       text   NOT NULL,
  node         text   NOT NULL,   -- block id (e.g. opcua.write); workflow step name is unavailable in BlockInput
  logical_node text   NOT NULL,   -- model key (e.g. recipe.rpmSetpoint)
  opcua_node   text,              -- resolved OPC-UA NodeId
  value        text,
  allowed      boolean NOT NULL,
  rule_id      text,
  outcome      text   NOT NULL,   -- WRITTEN|CONFIRMED|DENIED|EURANGE_REJECT|FAILED|RESTORED
  at_millis    bigint NOT NULL
);
-- R2 delegation seam: persisted audit trail for governed external-scoring delegation calls.
CREATE TABLE IF NOT EXISTS delegation_audit (
  run_id      text   NOT NULL,
  node        text   NOT NULL,   -- block id (delegate.score)
  endpoint_id text   NOT NULL,   -- policy endpoint id (e.g. quality-scorer)
  score       double precision,  -- null when DENIED / FAILED-before-score
  threshold   double precision,
  decision    text   NOT NULL,   -- DENIED | FAILED | REJECTED | PASSED
  detail      text,
  at_millis   bigint NOT NULL
);
-- ③ version-reference: the Git provenance of the canonical definition that governed a reconciliation run.
-- Stamped by ReconciliationController only when the canonical working tree is CLEAN (dirty/unresolvable are
-- rejected 409 before run start), so def_ref is always a resolvable, round-trippable SHA.
DROP TABLE IF EXISTS reconciliation_provenance;
CREATE TABLE reconciliation_provenance (
  run_id        text   NOT NULL,
  def_ref       text   NOT NULL,   -- git last-commit SHA (provenance metadata, from the manifest)
  content_sha256 text  NOT NULL,   -- v3: the hash hermod COMPUTED and verified (self-attested)
  at_millis     bigint NOT NULL
);
