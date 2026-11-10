package yggdrasil.hermod.conductor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.sql.Connection

data class LedgerRow(val nodeId: String, val blockId: String, val version: String, val boundState: Map<String, String>)

data class CompTimelineRow(
    val index: Int, val nodeId: String, val blockId: String,
    val version: String, val outcome: String, val atMillis: Long,
)

/**
 * Externalizes saga state Conductor's failureWorkflow cannot carry. Keyed by (MAIN workflow id, node id) —
 * exactly one row per compensable node per run, so parallel branches (even sharing a blockId) never collide.
 * Constructor: [conn] returns a fresh Connection per call (closed via use {}).
 */
class CompLedger(private val conn: () -> Connection) {
    private val mapper = ObjectMapper().registerKotlinModule()

    fun append(workflowId: String, nodeId: String, blockId: String, version: String, boundState: Map<String, String>) =
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO comp_ledger(workflow_id, node_id, block_id, version, bound_state) " +
                    "VALUES (?, ?, ?, ?, ?::jsonb) ON CONFLICT (workflow_id, node_id) DO NOTHING"
            ).use { ps ->
                ps.setString(1, workflowId)
                ps.setString(2, nodeId)
                ps.setString(3, blockId)
                ps.setString(4, version)
                ps.setString(5, mapper.writeValueAsString(boundState))
                ps.executeUpdate()
            }
        }

    /** The uncompensated row for (workflowId, nodeId), or null if none / already compensated. */
    fun readForCompensation(workflowId: String, nodeId: String): LedgerRow? =
        conn().use { c ->
            c.prepareStatement(
                "SELECT node_id, block_id, version, bound_state FROM comp_ledger " +
                    "WHERE workflow_id = ? AND node_id = ? AND NOT compensated"
            ).use { ps ->
                ps.setString(1, workflowId)
                ps.setString(2, nodeId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null
                    else LedgerRow(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        (mapper.readValue(rs.getString(4), Map::class.java))
                            .entries.associate { (k, v) -> k.toString() to v.toString() }
                    )
                }
            }
        }

    /** Mark (workflow,node) compensated with its outcome + time, assigning the next reverse-topo idx.
     *  Conductor runs compensate tasks sequentially per failureWorkflow, so MAX(idx)+1 is race-free. */
    fun recordResult(workflowId: String, nodeId: String, outcome: String, atMillis: Long) =
        conn().use { c ->
            c.prepareStatement(
                "UPDATE comp_ledger SET compensated = true, outcome = ?, at_millis = ?, " +
                    "idx = (SELECT COALESCE(MAX(idx), -1) + 1 FROM comp_ledger WHERE workflow_id = ? AND idx IS NOT NULL) " +
                    "WHERE workflow_id = ? AND node_id = ?"
            ).use { ps ->
                ps.setString(1, outcome); ps.setLong(2, atMillis)
                ps.setString(3, workflowId); ps.setString(4, workflowId); ps.setString(5, nodeId)
                ps.executeUpdate()
            }
        }

    /** Ordered compensation results for the failed MAIN run (reverse-topo). Best-effort: [] if none. */
    fun readTimeline(workflowId: String): List<CompTimelineRow> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT idx, node_id, block_id, version, outcome, at_millis FROM comp_ledger " +
                    "WHERE workflow_id = ? AND compensated ORDER BY idx"
            ).use { ps ->
                ps.setString(1, workflowId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            CompTimelineRow(rs.getInt(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getLong(6))
                        )
                    }
                }
            }
        }

    /**
     * Discard this workflow's compensation ledger. v0.6d Conductor retry re-runs reusing the SAME workflowId,
     * so the failed attempt's rows would otherwise linger and make a clean re-run look compensated. Called on
     * retry before the re-run; the re-run repopulates the ledger as its forward steps execute.
     */
    fun clearForWorkflow(workflowId: String) =
        conn().use { c ->
            c.prepareStatement("DELETE FROM comp_ledger WHERE workflow_id = ?").use { ps ->
                ps.setString(1, workflowId); ps.executeUpdate()
            }
        }

    /** Test-only: is a compensate-phase fault armed for this block? Missing fault_inject table -> false. */
    fun isCompensateFaultArmed(blockId: String): Boolean =
        runCatching {
            conn().use { c ->
                c.prepareStatement("SELECT 1 FROM fault_inject WHERE block_id = ? AND phase = 'compensate'").use { ps ->
                    ps.setString(1, blockId)
                    ps.executeQuery().use { rs -> rs.next() }
                }
            }
        }.getOrDefault(false)
}
