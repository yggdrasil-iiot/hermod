package yggdrasil.hermod.registry

import java.sql.Connection

/**
 * Control-plane dedup ledger for auto-dispatched corrective ot-safe-hold runs (design 2026-07-04). One row per
 * dispatched correction: PENDING while its run is in flight/parked, RESOLVED on run success, FAILED otherwise.
 * Pure JDBC, `connect` injected — mirrors DriftStore/FsmDeploymentStore. Distinct from drift_audit (immutable
 * observations); this table carries the mutable correction lifecycle used to guard against duplicate dispatch.
 */
class DriftCorrectionStore(private val connect: () -> Connection) {
    data class Row(val id: Long, val unit: String, val runId: String, val status: String, val dispatchedAtEpochMs: Long)

    /** The newest still-PENDING correction for [unit], or null. A non-null row is the dedup gate. */
    fun activePending(unit: String): Row? = connect().use { c ->
        c.prepareStatement(
            "SELECT id,unit,run_id,status,dispatched_at FROM drift_correction WHERE unit=? AND status='PENDING' ORDER BY id DESC LIMIT 1").use { ps ->
            ps.setString(1, unit)
            ps.executeQuery().use { rs -> if (rs.next()) Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).time) else null } } }

    /** Record a freshly dispatched correction as PENDING. Returns false (instead of throwing) when the
     *  partial-unique index (drift_correction_one_pending) rejects a second concurrent PENDING for the same
     *  unit — SQLState 23505 — so callers can treat dedup loss as a normal outcome, not an error. */
    fun insertPending(unit: String, runId: String, fromCode: Int, toCode: Int, workflow: String): Boolean = connect().use { c ->
        try {
            c.prepareStatement(
                "INSERT INTO drift_correction(unit,run_id,from_code,to_code,workflow,status) VALUES(?,?,?,?,?,'PENDING')").use { ps ->
                ps.setString(1, unit); ps.setString(2, runId); ps.setInt(3, fromCode); ps.setInt(4, toCode)
                ps.setString(5, workflow); ps.executeUpdate() }
            true
        } catch (e: java.sql.SQLException) {
            if (e.sqlState == "23505") false else throw e
        }
    }

    /** Terminalize a row (RESOLVED on run success, FAILED otherwise); sets resolved_at. */
    fun resolve(id: Long, status: String): Unit = connect().use { c ->
        c.prepareStatement("UPDATE drift_correction SET status=?, resolved_at=now() WHERE id=?").use { ps ->
            ps.setString(1, status); ps.setLong(2, id); ps.executeUpdate() } }

    /** All PENDING rows across units, oldest-first — the reconcile set. */
    fun allPending(): List<Row> = connect().use { c ->
        c.prepareStatement("SELECT id,unit,run_id,status,dispatched_at FROM drift_correction WHERE status='PENDING' ORDER BY id").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).time)) } } } }
}
