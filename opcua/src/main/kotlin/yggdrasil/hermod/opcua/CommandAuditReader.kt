package yggdrasil.hermod.opcua

import yggdrasil.hermod.opcua.emit.GovernedNode

object CommandAuditReader {
    /** Latest audited row per logical_node for a run → GovernedNode list. */
    fun summarize(runId: String): List<GovernedNode> = OpcuaDb.connect().use { c ->
        val sql = """
            SELECT logical_node, opcua_node, value, outcome, at_millis
            FROM command_audit WHERE run_id=?
            ORDER BY at_millis
        """.trimIndent()
        c.prepareStatement(sql).use { ps ->
            ps.setString(1, runId)
            val byKey = LinkedHashMap<String, GovernedNode>()   // last write wins per logical node
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val key = rs.getString("logical_node")
                    byKey[key] = GovernedNode(
                        logicalKey = key,
                        value = rs.getString("value")?.toDoubleOrNull(),
                        outcome = rs.getString("outcome"),
                        opcuaNode = rs.getString("opcua_node"),
                    )
                }
            }
            byKey.values.toList()
        }
    }
}
