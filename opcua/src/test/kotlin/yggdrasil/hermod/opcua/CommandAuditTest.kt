package yggdrasil.hermod.opcua

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals

/**
 * Guarded: runs only when HERMOD_DB_URL is reachable (mirrors DB-touching test pattern in `:blocks`).
 * Tests that all 6 AuditOutcome tokens round-trip through the command_audit table.
 */
class CommandAuditTest {

    private fun reachable(): Boolean = try {
        DriverManager.getConnection(OpcuaDb.url, OpcuaDb.user, OpcuaDb.pass).use { c ->
            c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS command_audit (" +
                "run_id text NOT NULL, node text NOT NULL, logical_node text NOT NULL, " +
                "opcua_node text, value text, allowed boolean NOT NULL, rule_id text, " +
                "outcome text NOT NULL, at_millis bigint NOT NULL)"
            ).execute()
        }
        true
    } catch (_: Exception) { false }

    @BeforeEach fun guard() = assumeTrue(reachable(), "Postgres unreachable — skipping CommandAuditTest")

    @Test fun `all 6 outcome tokens round-trip through command_audit`() {
        val outcomes = AuditOutcome.entries
        assertEquals(
            listOf("WRITTEN", "CONFIRMED", "DENIED", "EURANGE_REJECT", "FAILED", "RESTORED"),
            outcomes.map { it.name },
            "AuditOutcome enum order / names are a gate contract — do not rename"
        )

        // Write one row per outcome then read them back grouped by outcome name.
        val runId = "test-audit-${System.currentTimeMillis()}"
        for (o in outcomes) {
            CommandAudit.record(
                runId = runId, node = "testNode", logicalNode = "recipe.rpmSetpoint",
                opcuaNode = "ns=2;s=Recipe/Rpm", value = "1500",
                allowed = o != AuditOutcome.DENIED, ruleId = if (o == AuditOutcome.DENIED) null else "rpm-ok",
                outcome = o, atMillis = System.currentTimeMillis(),
            )
        }

        DriverManager.getConnection(OpcuaDb.url, OpcuaDb.user, OpcuaDb.pass).use { c ->
            c.prepareStatement("SELECT outcome FROM command_audit WHERE run_id=? ORDER BY at_millis").use { ps ->
                ps.setString(1, runId)
                val found = mutableListOf<String>()
                ps.executeQuery().use { rs -> while (rs.next()) found.add(rs.getString(1)) }
                assertEquals(outcomes.map { it.name }, found, "all 6 outcome rows must round-trip")
            }
        }
    }
}
