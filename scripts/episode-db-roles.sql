-- The episode runtime's database role (design §13, R11; plan D-lite). Run by the OWNER account after episode-schema.sql:
--   psql -v ON_ERROR_STOP=1 -v rt_password='…' -U koshei -d koshei -f scripts/episode-db-roles.sql
-- The runtime appends and reads; it never changes or removes a row (the tables are append-only by use, §13). The
-- password comes from the psql variable rt_password, never from this file. Safe to run again.
-- Keep the table list in step with episode-schema.sql and EpisodeStore's SCHEMA_OBJECTS.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'koshei_episode_rt') THEN
        CREATE ROLE koshei_episode_rt LOGIN;
    END IF;
END
$$;
ALTER ROLE koshei_episode_rt PASSWORD :'rt_password';

REVOKE ALL ON episode_event, episode_notice, episode_dispatch, episode_watch_cursor, episode_watch_log FROM koshei_episode_rt;
GRANT SELECT, INSERT ON episode_event, episode_notice, episode_dispatch, episode_watch_cursor, episode_watch_log TO koshei_episode_rt;
GRANT USAGE, SELECT ON SEQUENCE episode_notice_id_seq, episode_watch_log_id_seq TO koshei_episode_rt;
