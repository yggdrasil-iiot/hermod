package yggdrasil.hermod.authoring

import com.zaxxer.hikari.HikariDataSource

/**
 * Stages desired setpoint values into the hermod DB `source_rows` data plane (id=logical node key,
 * val=value) — the plane R1's db.read consumes and opcua.write writes from. Raw JDBC over the existing
 * authoring DataSource (same hermod DB; see RegistryConfig). Upsert per key; the gate manages overall
 * source_rows cleanliness between scenarios.
 */
open class SourceRowSeeder(private val ds: HikariDataSource) {
    open fun seed(rows: Map<String, String>) {
        ds.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO source_rows(id, val) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET val = EXCLUDED.val"
            ).use { ps ->
                for ((id, v) in rows) { ps.setString(1, id); ps.setString(2, v); ps.addBatch() }
                ps.executeBatch()
            }
        }
    }
}
