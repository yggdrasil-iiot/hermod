-- The episode runtime's database role (design §13, R11; plan D-lite). Run by the OWNER account after episode-schema.sql:
--   psql -v ON_ERROR_STOP=1 -v rt_password='…' -U koshei -d koshei -f scripts/episode-db-roles.sql
-- The runtime appends and reads; it never changes or removes a row (the tables are append-only by use, §13). The
-- password comes from the psql variable rt_password, never from this file. Safe to run again.
-- The -v value shows in shell history and process lists, and ALTER ROLE … PASSWORD is logged when log_statement is
-- ddl or all: pass a throwaway value and then set the real one with psql's \password koshei_episode_rt, which avoids both.
-- The names are unqualified: run it with the search_path the runtime uses (the schema episode-schema.sql was applied in).
-- Keep the table list in step with episode-schema.sql and EpisodeStore's SCHEMA_OBJECTS (EpisodeDbRolesTest checks).

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'koshei_episode_rt') THEN
        CREATE ROLE koshei_episode_rt LOGIN;
    END IF;
END
$$;
-- An existing role keeps whatever it was given before: take it back.
ALTER ROLE koshei_episode_rt LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- A runtime role that may create in the schema could create, and so own, the audit tables (EpisodeStore.ensureSchema
-- runs the DDL for any account with CREATE while a table is missing). Before Postgres 15, PUBLIC has CREATE on public.
DO $$
BEGIN
    IF has_schema_privilege('koshei_episode_rt', current_schema(), 'CREATE') THEN
        RAISE EXCEPTION 'koshei_episode_rt may CREATE in schema %: first REVOKE CREATE ON SCHEMA % FROM PUBLIC (and from koshei_episode_rt or any role it is in) - a runtime role that can create could own the audit tables',
            current_schema(), current_schema();
    END IF;
END
$$;
ALTER ROLE koshei_episode_rt PASSWORD :'rt_password';

REVOKE ALL ON episode_event, episode_notice, episode_dispatch, episode_watch_cursor, episode_watch_log FROM koshei_episode_rt;
GRANT SELECT, INSERT ON episode_event, episode_notice, episode_dispatch, episode_watch_cursor, episode_watch_log TO koshei_episode_rt;
REVOKE ALL ON SEQUENCE episode_notice_id_seq, episode_watch_log_id_seq FROM koshei_episode_rt;
GRANT USAGE, SELECT ON SEQUENCE episode_notice_id_seq, episode_watch_log_id_seq TO koshei_episode_rt;
