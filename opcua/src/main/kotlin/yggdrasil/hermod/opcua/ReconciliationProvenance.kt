package yggdrasil.hermod.opcua

import java.sql.Connection

/**
 * Pure-JDBC store for the version-reference SHA of a governed reconciliation run. Mirrors [CommandAudit]:
 * each call opens+closes a single connection over the same Postgres (`OpcuaDb`) where command_audit lives.
 */
object ReconciliationProvenance {
    fun record(runId: String, defRef: String, contentSha256: String, atMillis: Long = System.currentTimeMillis()) {
        OpcuaDb.connect().use { c -> insert(c, runId, defRef, contentSha256, atMillis) }
    }

    fun defRefFor(runId: String): String? = OpcuaDb.connect().use { c ->
        c.prepareStatement("SELECT def_ref FROM reconciliation_provenance WHERE run_id=? ORDER BY at_millis DESC LIMIT 1").use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun contentShaFor(runId: String): String? = OpcuaDb.connect().use { c ->
        c.prepareStatement("SELECT content_sha256 FROM reconciliation_provenance WHERE run_id=? ORDER BY at_millis DESC LIMIT 1").use { ps ->
            ps.setString(1, runId); ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    private fun insert(c: Connection, runId: String, defRef: String, contentSha256: String, atMillis: Long) {
        c.prepareStatement("INSERT INTO reconciliation_provenance(run_id,def_ref,content_sha256,at_millis) VALUES (?,?,?,?)").use { ps ->
            ps.setString(1, runId); ps.setString(2, defRef); ps.setString(3, contentSha256); ps.setLong(4, atMillis); ps.executeUpdate()
        }
    }
}
