package koshei.episode.runtime

import java.sql.Connection
import java.sql.SQLException
import java.time.Instant

/** Where the watcher is in one (source, run): [position] lines carried, the last one named [lastId]. */
data class WatchCursor(val position: Int, val lastId: String)

/** One row of the watch log. */
data class WatchLogRow(val id: Long, val source: String, val kind: String, val detailJson: String, val at: Instant)

/**
 * The watcher's tables (design §12): its cursor, its log, and the one read it makes of the episode records.
 * `open` for tests: they subclass it (to make [log] fail, for one).
 */
open class WatchStore(private val connect: () -> Connection) {

    /** The cursor for ([source], [runId]); null means nothing has been carried yet from that source and run. */
    fun cursor(source: String, runId: String): WatchCursor? = connect().use { c ->
        c.prepareStatement(
            "SELECT position, last_id FROM episode_watch_cursor WHERE source = ? AND run_id = ? ORDER BY position DESC LIMIT 1",
        ).use { s ->
            s.setString(1, source); s.setString(2, runId)
            s.executeQuery().use { rs -> if (rs.next()) WatchCursor(rs.getInt(1), rs.getString(2)) else null }
        }
    }

    /**
     * Moves the cursor to [position] / [lastId]. The same place again is a no-op; a different id there is refused; a
     * position more than one past the current one is refused (the cursor never skips lines).
     *
     * The gap check reads the current position before the insert, without a lock: one watcher moves a given (source, run)
     * at a time. Two writers racing could both pass the check, but the primary key still refuses two ids at one position.
     */
    open fun advance(source: String, runId: String, position: Int, lastId: String): Unit = connect().use { c ->
        val current = c.prepareStatement(
            "SELECT max(position) FROM episode_watch_cursor WHERE source = ? AND run_id = ?",
        ).use { s ->
            s.setString(1, source); s.setString(2, runId)
            s.executeQuery().use { rs -> rs.next(); rs.getInt(1) }   // 0 when there is no row (max is NULL)
        }
        check(position <= current + 1) { "$source $runId #$position would skip lines (the cursor is at #$current)" }
        val written = c.prepareStatement(
            "INSERT INTO episode_watch_cursor (source, run_id, position, last_id) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        ).use { s ->
            s.setString(1, source); s.setString(2, runId); s.setInt(3, position); s.setString(4, lastId)
            s.executeUpdate() == 1
        }
        if (!written) c.prepareStatement(
            "SELECT last_id FROM episode_watch_cursor WHERE source = ? AND run_id = ? AND position = ?",
        ).use { s ->
            s.setString(1, source); s.setString(2, runId); s.setInt(3, position)
            s.executeQuery().use { rs ->
                check(rs.next()) { "$source $runId #$position vanished" }
                check(rs.getString(1) == lastId) { "$source $runId #$position already names a different line" }
            }
        }
    }

    /**
     * Writes one watch-log row. [detailJson] must be one JSON object. The log must never be blocked by its own content
     * (a BROKEN detail quotes untrusted line text): when jsonb cannot hold [detailJson] (a NUL, a lone surrogate — SQLState
     * class 22), the row is written with `{"unstorable":true}` as its detail, with the same [source] and [kind].
     */
    open fun log(source: String, kind: String, detailJson: String): Unit = connect().use { c ->
        try {
            insertLog(c, source, kind, detailJson)
        } catch (e: SQLException) {
            if (e.sqlState?.startsWith("22") != true) throw e
            insertLog(c, source, kind, """{"unstorable":true}""")
        }
    }

    private fun insertLog(c: Connection, source: String, kind: String, detailJson: String) {
        c.prepareStatement("INSERT INTO episode_watch_log (source, kind, detail) VALUES (?, ?, ?::jsonb)").use { s ->
            s.setString(1, source); s.setString(2, kind); s.setString(3, detailJson)
            s.executeUpdate()
        }
    }

    /** Newest first. */
    fun logs(limit: Int): List<WatchLogRow> = connect().use { c ->
        c.prepareStatement("SELECT id, source, kind, detail::text, at FROM episode_watch_log ORDER BY id DESC LIMIT ?").use { s ->
            s.setInt(1, limit)
            s.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) WatchLogRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant())
                    else null
                }.toList()
            }
        }
    }

    /**
     * Every episode instance with a remedy-approval intent for [jobOrderId], in the order their first such intent was
     * written. Only APPROVE_REMEDY: only a remedy approval gets an `executionId` that a JobResponse can link to (§12);
     * a person task (CHOOSE_SOURCE also names the order) would only record the report and hide that it went nowhere.
     *
     * By the order alone, not by (robot, order) as design §12 first put it: picasso's JobResponse carries no robot id,
     * only `jobOrderId`. An instance that gets a report for an attempt it did not make only records it — the core links a
     * report to an attempt by (picasso `instanceId`, executionId).
     */
    fun instancesForOrder(jobOrderId: String): List<String> = connect().use { c ->
        c.prepareStatement(
            """
            SELECT episode_instance_id FROM episode_event
             WHERE kind = 'DISPATCH_INTENT' AND payload -> 'candidate' -> 'ref' ->> 'jobOrderId' = ?
               AND payload -> 'candidate' ->> 'kind' = 'APPROVE_REMEDY'
             GROUP BY episode_instance_id ORDER BY min(at), episode_instance_id
            """.trimIndent(),
        ).use { s ->
            s.setString(1, jobOrderId)
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }
    }
}
