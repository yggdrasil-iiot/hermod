package yggdrasil.hermod.delegation

import java.sql.Connection
import java.sql.Types

/** Outcome tokens for the `delegation_audit` table — the exact contract with the gate's SQL. Do NOT rename. */
enum class DelegationDecisionOutcome { DENIED, FAILED, REJECTED, PASSED }

object DelegationAudit {
    fun record(
        runId: String, node: String, endpointId: String,
        score: Double?, threshold: Double?, decision: DelegationDecisionOutcome, detail: String?,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        DelegationDb.connect().use { c -> insert(c, runId, node, endpointId, score, threshold, decision, detail, atMillis) }
    }

    private fun insert(
        c: Connection, runId: String, node: String, endpointId: String,
        score: Double?, threshold: Double?, decision: DelegationDecisionOutcome, detail: String?, atMillis: Long,
    ) {
        c.prepareStatement(
            "INSERT INTO delegation_audit(run_id,node,endpoint_id,score,threshold,decision,detail,at_millis) " +
            "VALUES (?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, runId)
            ps.setString(2, node)
            ps.setString(3, endpointId)
            if (score != null) ps.setDouble(4, score) else ps.setNull(4, Types.DOUBLE)
            if (threshold != null) ps.setDouble(5, threshold) else ps.setNull(5, Types.DOUBLE)
            ps.setString(6, decision.name)
            ps.setString(7, detail)
            ps.setLong(8, atMillis)
            ps.executeUpdate()
        }
    }
}
