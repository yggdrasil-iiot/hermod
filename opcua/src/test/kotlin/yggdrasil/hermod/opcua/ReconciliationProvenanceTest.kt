package yggdrasil.hermod.opcua

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals

/** Guarded: runs only when HERMOD_DB_URL is reachable (mirrors CommandAuditTest). */
class ReconciliationProvenanceTest {

    private fun reachable(): Boolean = try {
        DriverManager.getConnection(OpcuaDb.url, OpcuaDb.user, OpcuaDb.pass).use { c ->
            c.prepareStatement("DROP TABLE IF EXISTS reconciliation_provenance").execute()
            c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS reconciliation_provenance (" +
                "run_id text NOT NULL, def_ref text NOT NULL, content_sha256 text NOT NULL, at_millis bigint NOT NULL)"
            ).execute()
        }
        true
    } catch (_: Exception) { false }

    @BeforeEach fun guard() = assumeTrue(reachable(), "Postgres unreachable — skipping")

    @Test fun `def_ref and content_sha256 round-trip by run_id`() {
        val runId = "test-prov-${System.currentTimeMillis()}"
        ReconciliationProvenance.record(runId, "d".repeat(40), "csha-xyz", atMillis = 42L)
        assertEquals("d".repeat(40), ReconciliationProvenance.defRefFor(runId))
        assertEquals("csha-xyz", ReconciliationProvenance.contentShaFor(runId))
        assertEquals(null, ReconciliationProvenance.defRefFor("no-such-run"))
    }
}
