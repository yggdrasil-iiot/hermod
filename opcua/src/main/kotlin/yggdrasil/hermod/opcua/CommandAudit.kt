package yggdrasil.hermod.opcua

import java.sql.Connection

/**
 * Outcome tokens for the [command_audit] table. These exact names are the contract with the
 * Chunk-4 gate's SQL string queries — do NOT rename.
 */
enum class AuditOutcome { WRITTEN, CONFIRMED, DENIED, EURANGE_REJECT, FAILED, RESTORED }

/**
 * Pure-JDBC persisted command audit writer. Each call opens+closes a single connection
 * (mirrors the comp_ledger pattern in `:app`). Writes one row per governed OPC-UA operation.
 */
object CommandAudit {
    fun record(
        runId: String,
        node: String,
        logicalNode: String,
        opcuaNode: String?,
        value: String?,
        allowed: Boolean,
        ruleId: String?,
        outcome: AuditOutcome,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        OpcuaDb.connect().use { c -> insert(c, runId, node, logicalNode, opcuaNode, value, allowed, ruleId, outcome, atMillis) }
    }

    private fun insert(
        c: Connection,
        runId: String,
        node: String,
        logicalNode: String,
        opcuaNode: String?,
        value: String?,
        allowed: Boolean,
        ruleId: String?,
        outcome: AuditOutcome,
        atMillis: Long,
    ) {
        c.prepareStatement(
            "INSERT INTO command_audit(run_id,node,logical_node,opcua_node,value,allowed,rule_id,outcome,at_millis) " +
            "VALUES (?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, runId)
            ps.setString(2, node)
            ps.setString(3, logicalNode)
            ps.setString(4, opcuaNode)
            ps.setString(5, value)
            ps.setBoolean(6, allowed)
            ps.setString(7, ruleId)
            ps.setString(8, outcome.name)
            ps.setLong(9, atMillis)
            ps.executeUpdate()
        }
    }
}
