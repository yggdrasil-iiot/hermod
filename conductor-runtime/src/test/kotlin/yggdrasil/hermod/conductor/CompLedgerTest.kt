package yggdrasil.hermod.conductor

import yggdrasil.hermod.blocks.Db
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Testcontainers
class CompLedgerTest {

    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer<Nothing>("postgres:16").apply {
            withDatabaseName("hermod_test")
            withUsername("hermod")
            withPassword("hermod")
        }

        @BeforeAll
        @JvmStatic
        fun setup() {
            DbTestSupport.override(postgres.jdbcUrl, postgres.username, postgres.password)
            DbTestSupport.exec("""
                CREATE TABLE IF NOT EXISTS comp_ledger (
                  workflow_id text    NOT NULL,
                  node_id     text    NOT NULL,
                  block_id    text    NOT NULL,
                  version     text    NOT NULL,
                  bound_state jsonb   NOT NULL,
                  compensated boolean NOT NULL DEFAULT false,
                  outcome     text,
                  at_millis   bigint,
                  idx         int,
                  PRIMARY KEY (workflow_id, node_id)
                )
            """.trimIndent())
        }
    }

    @Test
    fun `append then read-for-compensation by nodeId returns the row and marks compensated`() {
        val ledger = CompLedger { Db.connect() }
        val wf = "wf-ledger-1"
        ledger.append(wf, "n0", "db.read", "1.0.0", mapOf("k" to "v1"))
        ledger.append(wf, "n1", "db.upsert", "1.2.0", mapOf("insertedIds" to "[\"a\"]"))
        val toComp = ledger.readForCompensation(wf, "n1")
        assertEquals("db.upsert", toComp!!.blockId)
        assertEquals(mapOf("insertedIds" to "[\"a\"]"), toComp.boundState)
        ledger.recordResult(wf, "n1", "COMPENSATED", 1000L)
        assertNull(ledger.readForCompensation(wf, "n1"))
    }

    @Test
    fun `two nodes with the SAME blockId compensate independently (conc-comp)`() {
        val ledger = CompLedger { Db.connect() }
        val wf = "wf-same-block"
        ledger.append(wf, "b", "db.upsert", "1.2.0", mapOf("insertedIds" to "[\"r1\"]"))
        ledger.append(wf, "c", "db.upsert", "1.2.0", mapOf("insertedIds" to "[\"r2\"]"))
        assertEquals(mapOf("insertedIds" to "[\"r1\"]"), ledger.readForCompensation(wf, "b")!!.boundState)
        assertEquals(mapOf("insertedIds" to "[\"r2\"]"), ledger.readForCompensation(wf, "c")!!.boundState)
        ledger.recordResult(wf, "b", "COMPENSATED", 1000L)
        assertNull(ledger.readForCompensation(wf, "b"))
        assertEquals(mapOf("insertedIds" to "[\"r2\"]"), ledger.readForCompensation(wf, "c")!!.boundState)
    }

    @Test
    fun `read-for-compensation returns null for unknown node`() {
        val ledger = CompLedger { Db.connect() }
        assertNull(ledger.readForCompensation("wf-none", "nX"))
    }

    @Test
    fun `append is idempotent on (workflow_id, node_id)`() {
        val ledger = CompLedger { Db.connect() }
        val wf = "wf-idem"
        ledger.append(wf, "n0", "db.upsert", "1.2.0", mapOf("insertedIds" to "[\"a\"]"))
        ledger.append(wf, "n0", "db.upsert", "1.2.0", mapOf("insertedIds" to "[\"b\"]"))
        assertEquals(mapOf("insertedIds" to "[\"a\"]"), ledger.readForCompensation(wf, "n0")!!.boundState)
    }

    @Test
    fun `recordResult assigns monotonic idx and readTimeline returns ordered events`() {
        val ledger = CompLedger { Db.connect() }
        val wf = "wf-timeline-1"
        ledger.append(wf, "interlockAck", "notify.email", "1.0.0", mapOf("k" to "v"))
        ledger.append(wf, "recordPlan", "db.upsert", "1.2.0", mapOf("insertedIds" to "[\"a\"]"))
        ledger.recordResult(wf, "interlockAck", "COMPENSATED", 1000L)   // reverse-topo: first
        ledger.recordResult(wf, "recordPlan", "COMPENSATED", 2000L)     // second
        val t = ledger.readTimeline(wf)
        assertEquals(2, t.size)
        assertEquals(listOf(0, 1), t.map { it.index })
        assertEquals(listOf("interlockAck", "recordPlan"), t.map { it.nodeId })
        assertEquals(listOf("notify.email", "db.upsert"), t.map { it.blockId })
        assertEquals(listOf("COMPENSATED", "COMPENSATED"), t.map { it.outcome })
        assertEquals(listOf(1000L, 2000L), t.map { it.atMillis })
    }

    @Test
    fun `readTimeline records a FAILED outcome and excludes uncompensated rows`() {
        val ledger = CompLedger { Db.connect() }
        val wf = "wf-timeline-2"
        ledger.append(wf, "a", "notify.email", "1.0.0", mapOf("k" to "v"))
        ledger.append(wf, "b", "db.upsert", "1.2.0", mapOf("k" to "v"))   // never compensated
        ledger.recordResult(wf, "a", "FAILED", 5000L)
        val t = ledger.readTimeline(wf)
        assertEquals(1, t.size)
        assertEquals("a", t[0].nodeId); assertEquals("FAILED", t[0].outcome); assertEquals(0, t[0].index)
    }

    @Test
    fun `readTimeline is empty for a run with no compensations`() {
        assertEquals(emptyList(), CompLedger { Db.connect() }.readTimeline("wf-none-timeline"))
    }

    @Test
    fun `clearForWorkflow drops the failed attempt's rows so a re-run reads clean (and other runs untouched)`() {
        val ledger = CompLedger { Db.connect() }
        val wf = "wf-retry"; val other = "wf-other"
        ledger.append(wf, "interlockAck", "notify.email", "1.0.0", mapOf("k" to "v"))
        ledger.append(wf, "recordPlan", "db.upsert", "1.2.0", mapOf("k" to "v"))
        ledger.recordResult(wf, "interlockAck", "COMPENSATED", 1000L)
        ledger.recordResult(wf, "recordPlan", "COMPENSATED", 2000L)
        ledger.append(other, "interlockAck", "notify.email", "1.0.0", mapOf("k" to "v"))
        ledger.recordResult(other, "interlockAck", "COMPENSATED", 3000L)

        ledger.clearForWorkflow(wf)

        assertEquals(emptyList(), ledger.readTimeline(wf))                 // the re-run starts clean
        assertEquals(1, ledger.readTimeline(other).size)                  // unrelated runs are untouched
    }
}
