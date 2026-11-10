// registry/src/main/kotlin/hermod/registry/RunStore.kt
package yggdrasil.hermod.registry

import java.sql.Connection

/**
 * Postgres index of runs that the control plane has started, keyed by runId (== Temporal workflowId).
 * Remembers ONLY that a run existed (+ the params it was started with, for later replay); the run's
 * live state stays sourced from the engine via EnginePort. Pure JDBC, engine-neutral (no spring/temporal),
 * mirrors WorkflowStore. `connect` is injected so the authoring-api can pool via Hikari.
 */
// `open` (class + the methods RunReconciler drives) so the reconcile-hook unit test can substitute an
// in-memory store with no DB (spec 2026-07-01, outbound emit).
open class RunStore(private val connect: () -> Connection) {
    data class CompEventRow(
        val idx: Int, val nodeId: String, val blockId: String,
        val version: String, val outcome: String, val atMillis: Long,
    )

    data class Row(
        val runId: String,
        val workflowName: String,
        val workflowVersion: String,
        val paramsJson: String,
        val startedAtEpochMs: Long,
        val engine: String,
        val finalStatus: String? = null,          // RAW engine status; null == not yet archived
        val completedAtEpochMs: Long? = null,
        val compOutcome: String? = null,          // NONE | COMPENSATED | COMP_FAILED
    )

    /** Insert the run row. Idempotent: a re-used (caller-supplied) runId is a benign no-op, not an error. */
    fun record(runId: String, name: String, version: String, paramsJson: String, engine: String = "temporal"): Unit = connect().use { c ->
        c.prepareStatement(
            "INSERT INTO run_index(run_id,workflow_name,workflow_version,params_json,engine) " +
            "VALUES(?,?,?,?,?) ON CONFLICT (run_id) DO NOTHING"
        ).use { ps ->
            ps.setString(1, runId); ps.setString(2, name); ps.setString(3, version); ps.setString(4, paramsJson)
            ps.setString(5, engine)
            ps.executeUpdate()
        }
    }

    fun list(limit: Int = 200): List<Row> = connect().use { c ->
        c.prepareStatement(
            "SELECT run_id,workflow_name,workflow_version,params_json,started_at,engine," +
            "final_status,completed_at,comp_outcome " +
            "FROM run_index ORDER BY started_at DESC LIMIT ?"
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rowOf(rs)) } }
        }
    }

    open fun get(runId: String): Row? = connect().use { c ->
        c.prepareStatement(
            "SELECT run_id,workflow_name,workflow_version,params_json,started_at,engine," +
            "final_status,completed_at,comp_outcome FROM run_index WHERE run_id=?"
        ).use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs -> if (rs.next()) rowOf(rs) else null }
        }
    }

    /** Returns the engine that was used to start [runId], defaulting to "temporal" if the row is absent or unknown. */
    fun engineOf(runId: String): String = connect().use { c ->
        c.prepareStatement("SELECT engine FROM run_index WHERE run_id=?").use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("engine") else "temporal" }
        }
    }

    /** Write-once: set the terminal record only if not already archived. */
    open fun markTerminal(runId: String, finalStatus: String, compOutcome: String): Unit = connect().use { c ->
        c.prepareStatement(
            "UPDATE run_index SET final_status=?, completed_at=now(), comp_outcome=? " +
            "WHERE run_id=? AND final_status IS NULL"
        ).use { ps -> ps.setString(1, finalStatus); ps.setString(2, compOutcome); ps.setString(3, runId); ps.executeUpdate() }
    }

    /**
     * Un-archive a run so it is tracked live again: clear the terminal record + drop the node/comp snapshots,
     * all in one tx. Called when a run is RE-RUN under the same runId (v0.6d Conductor whole-run retry reuses
     * the workflowId): otherwise the write-once final_status from the prior terminal masks the re-run forever.
     */
    fun clearArchive(runId: String): Unit = connect().use { c ->
        c.autoCommit = false
        try {
            c.prepareStatement(
                "UPDATE run_index SET final_status=NULL, completed_at=NULL, comp_outcome=NULL WHERE run_id=?"
            ).use { it.setString(1, runId); it.executeUpdate() }
            c.prepareStatement("DELETE FROM run_node_state WHERE run_id=?").use { it.setString(1, runId); it.executeUpdate() }
            c.prepareStatement("DELETE FROM run_comp_event WHERE run_id=?").use { it.setString(1, runId); it.executeUpdate() }
            c.prepareStatement("DELETE FROM emitted_event WHERE run_id=?").use { it.setString(1, runId); it.executeUpdate() }
            c.commit()
        } catch (e: Exception) { c.rollback(); throw e } finally { c.autoCommit = true }
    }

    /** Refresh comp_outcome within the grace window (final_status stays frozen). */
    open fun refreshCompOutcome(runId: String, compOutcome: String): Unit = connect().use { c ->
        c.prepareStatement("UPDATE run_index SET comp_outcome=? WHERE run_id=?").use { ps ->
            ps.setString(1, compOutcome); ps.setString(2, runId); ps.executeUpdate()
        }
    }

    /** Replace this run's node-state snapshot in one tx (idempotent). */
    open fun snapshotNodeStates(runId: String, states: Map<String, String>): Unit = connect().use { c ->
        c.autoCommit = false
        try {
            c.prepareStatement("DELETE FROM run_node_state WHERE run_id=?").use { it.setString(1, runId); it.executeUpdate() }
            c.prepareStatement("INSERT INTO run_node_state(run_id,node_id,state) VALUES(?,?,?)").use { ps ->
                for ((node, state) in states) { ps.setString(1, runId); ps.setString(2, node); ps.setString(3, state); ps.addBatch() }
                ps.executeBatch()
            }
            c.commit()
        } catch (e: Exception) { c.rollback(); throw e } finally { c.autoCommit = true }
    }

    fun readNodeStates(runId: String): Map<String, String> = connect().use { c ->
        c.prepareStatement("SELECT node_id,state FROM run_node_state WHERE run_id=?").use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString("node_id"), rs.getString("state")) } }
        }
    }

    /** Replace this run's compensation snapshot in one tx (idempotent). */
    open fun snapshotCompEvents(runId: String, events: List<CompEventRow>): Unit = connect().use { c ->
        c.autoCommit = false
        try {
            c.prepareStatement("DELETE FROM run_comp_event WHERE run_id=?").use { it.setString(1, runId); it.executeUpdate() }
            c.prepareStatement(
                "INSERT INTO run_comp_event(run_id,idx,node_id,block_id,version,outcome,at_millis) VALUES(?,?,?,?,?,?,?)"
            ).use { ps ->
                for (e in events) {
                    ps.setString(1, runId); ps.setInt(2, e.idx); ps.setString(3, e.nodeId); ps.setString(4, e.blockId)
                    ps.setString(5, e.version); ps.setString(6, e.outcome); ps.setLong(7, e.atMillis); ps.addBatch()
                }
                ps.executeBatch()
            }
            c.commit()
        } catch (e: Exception) { c.rollback(); throw e } finally { c.autoCommit = true }
    }

    fun readCompEvents(runId: String): List<CompEventRow> = connect().use { c ->
        c.prepareStatement(
            "SELECT idx,node_id,block_id,version,outcome,at_millis FROM run_comp_event WHERE run_id=? ORDER BY idx"
        ).use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(CompEventRow(
                rs.getInt("idx"), rs.getString("node_id"), rs.getString("block_id"),
                rs.getString("version"), rs.getString("outcome"), rs.getLong("at_millis"))) } }
        }
    }

    /** Rows the reconciler should sweep: not yet archived, OR archived but still within the grace window. */
    fun archivedOrInFlight(graceMs: Long, limit: Int = 500): List<Row> = connect().use { c ->
        c.prepareStatement(
            "SELECT run_id,workflow_name,workflow_version,params_json,started_at,engine," +
            "final_status,completed_at,comp_outcome FROM run_index " +
            "WHERE final_status IS NULL OR completed_at > now() - (? * interval '1 millisecond') " +
            "ORDER BY started_at DESC LIMIT ?"
        ).use { ps -> ps.setLong(1, graceMs); ps.setInt(2, limit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rowOf(rs)) } } }
    }

    private fun rowOf(rs: java.sql.ResultSet) = Row(
        runId = rs.getString("run_id"),
        workflowName = rs.getString("workflow_name"),
        workflowVersion = rs.getString("workflow_version"),
        paramsJson = rs.getString("params_json"),
        startedAtEpochMs = rs.getTimestamp("started_at").time,
        engine = rs.getString("engine"),
        finalStatus = rs.getString("final_status"),
        completedAtEpochMs = rs.getTimestamp("completed_at")?.time,
        compOutcome = rs.getString("comp_outcome"),
    )
}
