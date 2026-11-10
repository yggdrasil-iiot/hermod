package yggdrasil.hermod.registry

import java.sql.Connection

/**
 * Control-plane drift observation log: the last observed state code per unit + an append-only drift audit.
 * Pure JDBC (mirrors FsmDeploymentStore); `connect` injected. Detect-only — this store records observations,
 * it does not drive anything. See design 2026-07-03.
 */
class DriftStore(private val connect: () -> Connection) {
    data class DriftAuditRow(val unit: String, val fromCode: Int?, val toCode: Int, val verdict: String, val detail: String)

    fun lastState(unit: String): Int? = connect().use { c ->
        c.prepareStatement("SELECT last_state_code FROM drift_observation WHERE unit=?").use { ps ->
            ps.setString(1, unit); ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null } } }

    fun observe(unit: String, fromCode: Int?, toCode: Int, verdict: String, detail: String): Unit = connect().use { c ->
        c.autoCommit = false
        try {
            c.prepareStatement("INSERT INTO drift_audit(unit,from_code,to_code,verdict,detail) VALUES(?,?,?,?,?)").use { ps ->
                ps.setString(1, unit)
                if (fromCode != null) ps.setInt(2, fromCode) else ps.setNull(2, java.sql.Types.INTEGER)
                ps.setInt(3, toCode); ps.setString(4, verdict); ps.setString(5, detail); ps.executeUpdate() }
            c.prepareStatement(
                "INSERT INTO drift_observation(unit,last_state_code) VALUES(?,?) " +
                "ON CONFLICT (unit) DO UPDATE SET last_state_code=EXCLUDED.last_state_code, observed_at=now()").use { ps ->
                ps.setString(1, unit); ps.setInt(2, toCode); ps.executeUpdate() }
            c.commit()
        } catch (e: Exception) { c.rollback(); throw e } finally { c.autoCommit = true }
    }

    /** Append a corrective (HOLD) audit row WITHOUT touching drift_observation. The observation pointer is
     *  drift-check's; a corrective dispatch is a distinct event, so it must NOT advance last_state_code.
     *  Reuses drift_audit (verdict/detail are free text — no schema change). See design 2026-07-03 §2.4. */
    fun recordCorrection(unit: String, fromCode: Int, toCode: Int, workflow: String): Unit = connect().use { c ->
        c.prepareStatement("INSERT INTO drift_audit(unit,from_code,to_code,verdict,detail) VALUES(?,?,?,?,?)").use { ps ->
            ps.setString(1, unit); ps.setInt(2, fromCode); ps.setInt(3, toCode)
            ps.setString(4, "HOLD"); ps.setString(5, "SafeHold -> code $toCode via $workflow"); ps.executeUpdate()
        }
    }

    /** Append a DENY-alarm audit row (a corrective SafeHold was governed-DENIED). Pointer untouched (drift-check
     *  owns drift_observation). Reuses drift_audit (verdict/detail free text — no schema change). */
    fun recordDenyAlarm(unit: String, code: Int, reason: String): Unit = connect().use { c ->
        c.prepareStatement("INSERT INTO drift_audit(unit,from_code,to_code,verdict,detail) VALUES(?,?,?,?,?)").use { ps ->
            ps.setString(1, unit); ps.setInt(2, code); ps.setInt(3, code)
            ps.setString(4, "DENY"); ps.setString(5, "SafeHold DENY: $reason"); ps.executeUpdate()
        }
    }

    fun audit(unit: String): List<DriftAuditRow> = connect().use { c ->
        c.prepareStatement("SELECT unit,from_code,to_code,verdict,detail FROM drift_audit WHERE unit=? ORDER BY id").use { ps ->
            ps.setString(1, unit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(DriftAuditRow(
                rs.getString(1), (rs.getObject(2) as? Int), rs.getInt(3), rs.getString(4), rs.getString(5))) } } } }
}
