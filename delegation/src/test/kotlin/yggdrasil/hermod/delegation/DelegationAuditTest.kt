package yggdrasil.hermod.delegation

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guarded: runs only when HERMOD_DB_URL is reachable (mirrors CommandAuditTest / the `:blocks`
 * DB-touching pattern). Round-trips all 4 DelegationDecisionOutcome tokens through delegation_audit,
 * and exercises the setNull(_, Types.DOUBLE) branch with a null score/threshold row.
 */
class DelegationAuditTest {

    private fun reachable(): Boolean = try {
        DriverManager.getConnection(DelegationDb.url, DelegationDb.user, DelegationDb.pass).use { c ->
            c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS delegation_audit (" +
                "run_id text NOT NULL, node text NOT NULL, endpoint_id text NOT NULL, " +
                "score double precision, threshold double precision, " +
                "decision text NOT NULL, detail text, at_millis bigint NOT NULL)"
            ).execute()
        }
        true
    } catch (_: Exception) { false }

    @BeforeEach fun guard() = assumeTrue(reachable(), "Postgres unreachable — skipping DelegationAuditTest")

    @Test fun `all 4 decision tokens round-trip through delegation_audit`() {
        val outcomes = DelegationDecisionOutcome.entries
        assertEquals(
            listOf("DENIED", "FAILED", "REJECTED", "PASSED"),
            outcomes.map { it.name },
            "DelegationDecisionOutcome enum order / names are a gate contract — do not rename"
        )

        val runId = "test-delegation-audit-${System.currentTimeMillis()}"
        // One row per token. DENIED carries a null score/threshold (exercises setNull DOUBLE);
        // the other three carry concrete score/threshold values.
        for (o in outcomes) {
            val hasScore = o != DelegationDecisionOutcome.DENIED
            DelegationAudit.record(
                runId = runId, node = "delegate.score", endpointId = "quality-scorer",
                score = if (hasScore) 0.9 else null, threshold = if (hasScore) 0.8 else null,
                decision = o, detail = "detail-${o.name}", atMillis = System.currentTimeMillis(),
            )
        }

        DriverManager.getConnection(DelegationDb.url, DelegationDb.user, DelegationDb.pass).use { c ->
            c.prepareStatement(
                "SELECT decision, score, threshold FROM delegation_audit WHERE run_id=? ORDER BY at_millis"
            ).use { ps ->
                ps.setString(1, runId)
                val found = mutableListOf<String>()
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val decision = rs.getString("decision")
                        found.add(decision)
                        if (decision == "DENIED") {
                            rs.getDouble("score"); assertTrue(rs.wasNull(), "DENIED score must read back as SQL NULL")
                            rs.getDouble("threshold"); assertTrue(rs.wasNull(), "DENIED threshold must read back as SQL NULL")
                        } else {
                            assertEquals(0.9, rs.getDouble("score"), 1e-9, "score for $decision")
                            assertEquals(0.8, rs.getDouble("threshold"), 1e-9, "threshold for $decision")
                        }
                    }
                }
                assertEquals(outcomes.map { it.name }, found, "all 4 decision rows must round-trip")
            }
        }
    }
}
