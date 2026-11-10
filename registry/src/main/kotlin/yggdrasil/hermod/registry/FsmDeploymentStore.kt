package yggdrasil.hermod.registry

import java.sql.Connection

/**
 * Control-plane pointer: which FSM spec VERSION is active for each unit (the "deployed" fact).
 * Pure JDBC, engine-neutral (mirrors RunStore); `connect` injected for Hikari pooling. Spec content +
 * approval stay Git-canonical (model/fsm/ yaml specs); this store holds ONLY the active-version pointer + an
 * append-only deploy/rollback audit. deploy/rollback are each ONE transaction so the pointer is never
 * observed half-swapped (atomicity = the instant-rollback guarantee). See spec 2026-07-02 §5.
 */
class FsmDeploymentStore(private val connect: () -> Connection) {
    data class AuditRow(val unit: String, val fromVersion: String?, val toVersion: String, val action: String)

    data class SoakRow(val unit: String, val activeVersion: String, val previousVersion: String?,
                       val failCount: Int, val failThreshold: Int, val soakUntil: java.time.Instant)

    /** The live version for [unit], or null if never deployed. */
    fun activeVersion(unit: String): String? = connect().use { c -> activeVersionOn(c, unit) }

    /**
     * Make [version] active for [unit]: previous_version := old active (null on first deploy);
     * active_version := version; append a DEPLOY audit row. One tx. Deploying the ALREADY-active
     * version is a guarded no-op for the POINTER (previous_version is preserved — it must not clobber a
     * real rollback target); the redeploy is still recorded as a DEPLOY audit row (an honest attempt log).
     */
    fun deploy(unit: String, version: String, actor: String = "-",
               soakSeconds: Long? = null, failThreshold: Int? = null): Unit = connect().use { c ->
        c.autoCommit = false
        try {
            val old = activeVersionOn(c, unit)
            if (soakSeconds != null) {
                val target = if (old == version) previousVersionOn(c, unit) else old
                if (target == null) throw IllegalStateException("cannot soak '$unit' — no prior version to roll back to")
            }
            val status = if (soakSeconds != null) "soaking" else "promoted"
            val thr = if (soakSeconds != null) (failThreshold ?: 1) else null
            val soakExpr = if (soakSeconds != null) "now() + make_interval(secs => ?)" else "NULL"
            if (old == version) {
                c.prepareStatement(
                    "UPDATE fsm_deployment SET deployed_at=now(), deployed_by=?, status=?, " +
                    "soak_until=$soakExpr, fail_count=0, fail_threshold=? WHERE unit=?").use { ps ->
                    var i = 1; ps.setString(i++, actor); ps.setString(i++, status)
                    if (soakSeconds != null) ps.setDouble(i++, soakSeconds.toDouble())
                    if (thr != null) ps.setInt(i++, thr) else ps.setNull(i++, java.sql.Types.INTEGER)
                    ps.setString(i++, unit); ps.executeUpdate() }
            } else {
                c.prepareStatement(
                    "INSERT INTO fsm_deployment(unit,active_version,previous_version,deployed_by,status,soak_until,fail_count,fail_threshold) " +
                    "VALUES(?,?,?,?,?,$soakExpr,0,?) " +
                    "ON CONFLICT (unit) DO UPDATE SET previous_version=fsm_deployment.active_version, " +
                    "active_version=EXCLUDED.active_version, deployed_at=now(), deployed_by=EXCLUDED.deployed_by, " +
                    "status=EXCLUDED.status, soak_until=EXCLUDED.soak_until, fail_count=0, fail_threshold=EXCLUDED.fail_threshold").use { ps ->
                    var i = 1; ps.setString(i++, unit); ps.setString(i++, version); ps.setString(i++, old)
                    ps.setString(i++, actor); ps.setString(i++, status)
                    if (soakSeconds != null) ps.setDouble(i++, soakSeconds.toDouble())
                    if (thr != null) ps.setInt(i++, thr) else ps.setNull(i++, java.sql.Types.INTEGER)
                    ps.executeUpdate() }
            }
            appendAudit(c, unit, old, version, "DEPLOY", actor)
            c.commit()
        } catch (e: Exception) { c.rollback(); throw e } finally { c.autoCommit = true }
    }

    /** Increment the failure counter for [unit] only while it is soaking. Returns true if a soaking row was counted. */
    fun recordFailure(unit: String): Boolean = connect().use { c ->
        c.prepareStatement("UPDATE fsm_deployment SET fail_count=fail_count+1 WHERE unit=? AND status='soaking'").use { ps ->
            ps.setString(1, unit); ps.executeUpdate() > 0 } }

    /** All currently-soaking deployments. */
    fun soaking(): List<SoakRow> = connect().use { c ->
        c.prepareStatement("SELECT unit,active_version,previous_version,fail_count,fail_threshold,soak_until " +
            "FROM fsm_deployment WHERE status='soaking'").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(SoakRow(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                (rs.getObject(5) as? Int) ?: 1, rs.getTimestamp(6).toInstant())) } } } }

    /** Every unit that currently has an active deployment (the auto-correct sweep set), ordered by unit. */
    fun activeUnits(): List<String> = connect().use { c ->
        c.prepareStatement("SELECT unit FROM fsm_deployment ORDER BY unit").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } } }

    /** Mark [unit]'s soaking deployment as promoted (survived the window). One tx + a PROMOTE audit row. */
    fun promote(unit: String): Unit = connect().use { c ->
        c.autoCommit = false
        try {
            val active = activeVersionOn(c, unit) ?: throw IllegalStateException("no deployment for unit '$unit'")
            c.prepareStatement("UPDATE fsm_deployment SET status='promoted' WHERE unit=?").use { it.setString(1, unit); it.executeUpdate() }
            appendAudit(c, unit, active, active, "PROMOTE", "supervisor")
            c.commit()
        } catch (e: Exception) { c.rollback(); throw e } finally { c.autoCommit = true }
    }

    /** Swap active<->previous (instant). Returns the now-active version. Fail-closed if no previous / no deployment. */
    fun rollback(unit: String, actor: String = "-", action: String = "ROLLBACK"): String = connect().use { c ->
        c.autoCommit = false
        try {
            val (active, previous) = rowOn(c, unit) ?: throw IllegalStateException("no deployment for unit '$unit'")
            val prev = previous ?: throw IllegalStateException("no previous version to roll back to for unit '$unit'")
            c.prepareStatement(
                "UPDATE fsm_deployment SET active_version=?, previous_version=?, status='promoted', deployed_at=now(), deployed_by=? WHERE unit=?"
            ).use { ps -> ps.setString(1, prev); ps.setString(2, active); ps.setString(3, actor); ps.setString(4, unit); ps.executeUpdate() }
            appendAudit(c, unit, active, prev, action, actor)
            c.commit()
            prev
        } catch (e: Exception) { c.rollback(); throw e } finally { c.autoCommit = true }
    }

    /** Append-only audit for a unit, oldest-first. */
    fun audit(unit: String): List<AuditRow> = connect().use { c ->
        c.prepareStatement("SELECT unit,from_version,to_version,action FROM fsm_deployment_audit WHERE unit=? ORDER BY id").use { ps ->
            ps.setString(1, unit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(
                AuditRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))) } }
        }
    }

    // --- in-tx / in-connection helpers -----------------------------------------------------------
    private fun activeVersionOn(c: Connection, unit: String): String? =
        c.prepareStatement("SELECT active_version FROM fsm_deployment WHERE unit=?").use { ps ->
            ps.setString(1, unit); ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }

    private fun previousVersionOn(c: Connection, unit: String): String? =
        c.prepareStatement("SELECT previous_version FROM fsm_deployment WHERE unit=?").use { ps ->
            ps.setString(1, unit); ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }

    private fun rowOn(c: Connection, unit: String): Pair<String, String?>? =
        c.prepareStatement("SELECT active_version, previous_version FROM fsm_deployment WHERE unit=?").use { ps ->
            ps.setString(1, unit)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null } }

    private fun appendAudit(c: Connection, unit: String, from: String?, to: String, action: String, actor: String) =
        c.prepareStatement("INSERT INTO fsm_deployment_audit(unit,from_version,to_version,action,actor) VALUES(?,?,?,?,?)").use { ps ->
            ps.setString(1, unit); ps.setString(2, from); ps.setString(3, to); ps.setString(4, action); ps.setString(5, actor); ps.executeUpdate() }
}
