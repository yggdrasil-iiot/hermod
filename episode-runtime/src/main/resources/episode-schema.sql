-- Episode audit and channel tables (design §8.5, §13). Separate from app/src/main/resources/schema.sql, which DROPs.
-- Safe to run at every worker start. Append-only by use: production revokes UPDATE and DELETE on these tables from
-- the runtime account (plan D); nothing in koshei updates or deletes a row.
-- CREATE TABLE IF NOT EXISTS never alters a table that is already there: a later column change must be its own
-- ALTER TABLE … ADD COLUMN IF NOT EXISTS (and likewise for constraints), or existing databases keep the old shape.

-- CREATE … IF NOT EXISTS is not safe under concurrency: two workers starting together on a fresh database both pass the
-- check and one fails on pg_type's unique index. pgjdbc sends this whole script as one implicit transaction, so this
-- transaction-scoped lock serialises the script across workers; the key is arbitrary and used nowhere else.
SELECT pg_advisory_xact_lock(7363001);

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
    outcome_kind        text        NOT NULL CHECK (outcome_kind IN ('ANSWER', 'PERSON_TASK')),
    answer              text,
    at                  timestamptz NOT NULL DEFAULT now(),
    CHECK (outcome_kind <> 'ANSWER' OR answer IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS episode_dispatch_instance ON episode_dispatch (episode_instance_id);

-- The watcher's place in each source (design §12, plan C1): per (source, run), how many lines it has carried and the id
-- of the last one. Append-only like the rest: moving forward adds a row; the cursor is the row with the highest position.
-- A row at an existing position must name the same id (a retry) — anything else means the source changed under us.
CREATE TABLE IF NOT EXISTS episode_watch_cursor (
    source              text        NOT NULL,
    run_id              text        NOT NULL,
    position            integer     NOT NULL CHECK (position > 0),
    last_id             text        NOT NULL,
    at                  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source, run_id, position)
);

-- What the watcher did not carry, or carried without merging (design §12): BROKEN, STUCK, AMBIGUOUS, UNROUTED,
-- ROUTE_ENDED, SIGNAL_FAILED, POLICY_UNUSABLE. Read by people (plan D shows it).
CREATE TABLE IF NOT EXISTS episode_watch_log (
    id                  bigserial   PRIMARY KEY,
    source              text        NOT NULL,
    kind                text        NOT NULL,
    detail              jsonb       NOT NULL,
    at                  timestamptz NOT NULL DEFAULT now()
);

-- A JobResponse finds its episode by the order its dispatch intent named (design §12). On an existing database the first
-- start after this line builds the index and holds writes to episode_event meanwhile — fine at PoC scale.
CREATE INDEX IF NOT EXISTS episode_event_intent_order
    ON episode_event ((payload -> 'candidate' -> 'ref' ->> 'jobOrderId'))
    WHERE kind = 'DISPATCH_INTENT';
