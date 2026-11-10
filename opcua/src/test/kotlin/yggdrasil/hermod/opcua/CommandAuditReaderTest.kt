package yggdrasil.hermod.opcua

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guarded like [CommandAuditTest]: runs only when the Postgres behind [OpcuaDb] is reachable
 * (the gate DB). Verifies [CommandAuditReader.summarize] folds command_audit rows into one
 * [yggdrasil.hermod.opcua.emit.GovernedNode] per logical node (last write wins).
 */
class CommandAuditReaderTest {

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

    @BeforeEach fun guard() = assumeTrue(reachable(), "Postgres unreachable — skipping CommandAuditReaderTest")

    @Test fun `summarize returns one GovernedNode per audited logical node`() {
        val runId = "wf-reader-${System.currentTimeMillis()}"
        CommandAudit.record(
            runId, "opcua.write", "recipe.rpmSetpoint", "ns=2;s=Recipe/Rpm",
            "1450.0", true, "r-rpm", AuditOutcome.CONFIRMED, 1000L,
        )
        CommandAudit.record(
            runId, "opcua.write", "recipe.tempSetpoint", "ns=2;s=Recipe/Temp",
            null, false, null, AuditOutcome.DENIED, 1001L,
        )

        val nodes = CommandAuditReader.summarize(runId)
        val rpm = nodes.first { it.logicalKey == "recipe.rpmSetpoint" }
        assertEquals(1450.0, rpm.value)
        assertEquals("CONFIRMED", rpm.outcome)
        assertEquals("ns=2;s=Recipe/Rpm", rpm.opcuaNode)

        val temp = nodes.first { it.logicalKey == "recipe.tempSetpoint" }
        assertNull(temp.value)                 // non-numeric/null audit value -> null
        assertEquals("DENIED", temp.outcome)
    }

    @Test fun `summarize keeps the latest row per logical node`() {
        val runId = "wf-reader-latest-${System.currentTimeMillis()}"
        CommandAudit.record(runId, "opcua.write", "recipe.rpmSetpoint", "ns=2;s=Recipe/Rpm",
            "1400.0", true, "r-rpm", AuditOutcome.WRITTEN, 2000L)
        CommandAudit.record(runId, "opcua.write", "recipe.rpmSetpoint", "ns=2;s=Recipe/Rpm",
            "1450.0", true, "r-rpm", AuditOutcome.CONFIRMED, 2001L)

        val nodes = CommandAuditReader.summarize(runId)
        assertEquals(1, nodes.size)
        assertEquals(1450.0, nodes.first().value)   // last write (by at_millis) wins
        assertEquals("CONFIRMED", nodes.first().outcome)
    }
}
