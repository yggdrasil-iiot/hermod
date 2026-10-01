-- Episode audit and channel tables (design §8.5, §13). Separate from app/src/main/resources/schema.sql, which DROPs.
-- Safe to run at every worker start. Append-only by use: production revokes UPDATE and DELETE on these tables from
-- the runtime account (plan D); nothing in koshei updates or deletes a row.

-- The audit record of an episode instance (workflow id + run id). Written at least once per (instance, seq).
CREATE TABLE IF NOT EXISTS episode_event (
    episode_instance_id text        NOT NULL,
    seq                 bigint      NOT NULL,
    kind                text        NOT NULL,
    payload             jsonb       NOT NULL,
    at                  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (episode_instance_id, seq)
);

-- The operator channel of plan B (design §8.5): one row per notice, read by the control plane (plan B3c).
CREATE TABLE IF NOT EXISTS episode_notice (
    id                  bigserial   PRIMARY KEY,
    episode_instance_id text        NOT NULL,
    notice              jsonb       NOT NULL,
    at                  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS episode_notice_instance ON episode_notice (episode_instance_id, id);

-- What a dispatch came back with, by idempotency key (design §8.4, §11). The answer is picasso's text, verbatim.
CREATE TABLE IF NOT EXISTS episode_dispatch (
    idempotency_key     text        PRIMARY KEY,
    episode_instance_id text        NOT NULL,
    outcome_kind        text        NOT NULL,
    answer              text,
    at                  timestamptz NOT NULL DEFAULT now()
);
