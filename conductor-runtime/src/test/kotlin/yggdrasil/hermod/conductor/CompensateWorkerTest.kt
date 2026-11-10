package yggdrasil.hermod.conductor

import com.netflix.conductor.common.metadata.tasks.Task
import com.netflix.conductor.common.metadata.tasks.TaskResult
import yggdrasil.hermod.blocks.Db
import yggdrasil.hermod.core.*
import yggdrasil.hermod.registry.BlockIndex
import yggdrasil.hermod.registry.BlockStore
import yggdrasil.hermod.registry.Registry
import yggdrasil.hermod.sdk.*
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Testcontainers
class CompensateWorkerTest {

    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer<Nothing>("postgres:16").apply {
            withDatabaseName("hermod_test")
            withUsername("hermod")
            withPassword("hermod")
        }

        lateinit var registry: Registry
        lateinit var ledger: CompLedger

        // Spy Block: tracks compensate calls
        var compensateCalled = false
        var compensateCalledWithBoundState: Map<String, String>? = null

        val spyBlock = object : Block {
            override val id = "db.upsert"
            override fun forward(input: BlockInput): BlockOutput =
                BlockOutput(rows = emptyList(), boundState = emptyMap())

            override fun compensate(boundState: Map<String, String>, ctx: CompensationContext): CompensationAction {
                compensateCalled = true
                compensateCalledWithBoundState = boundState
                return CompensationAction("RESTORE", "compensated")
            }
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

            val builtinContracts = listOf("db.read", "transform.map", "db.upsert", "notify.email", "actuate")
                .associate { id ->
                    val yaml = CompensateWorkerTest::class.java.getResourceAsStream("/manifests/$id.yaml")
                        ?: error("manifest not found: /manifests/$id.yaml")
                    val c = yggdrasil.hermod.registry.ManifestLoader.load(yaml.bufferedReader().readText())
                    "${c.id}#${c.version}" to c
                }

            registry = Registry(
                index = BlockIndex { Db.connect() },
                store = BlockStore(File.createTempFile("hermod-cw-store", "").let { it.delete(); it.mkdirs(); it }),
                builtins = builtinContracts,
                handlerLoadCheck = { _, _ -> },
            )

            ledger = CompLedger { Db.connect() }
        }

        private fun makeCompensateTask(
            failedWorkflowId: String,
            blockId: String = "db.upsert",
            version: String = "1.2.0",
            nodeId: String = "n-comp",
        ): Task {
            val t = Task()
            t.taskDefName = "compensate"
            t.workflowInstanceId = "comp-wf-1"
            t.status = Task.Status.IN_PROGRESS
            t.inputData = mutableMapOf(
                "_failedWorkflowId" to failedWorkflowId,
                "_blockId" to blockId,
                "_pinnedVersion" to version,
                "_nodeId" to nodeId,
            )
            return t
        }
    }

    @Test
    fun `compensate reads ledger, calls handler compensate, marks row compensated, always COMPLETED`() {
        // Seed ledger with a compensable row
        val wfId = "wf-comp-test-1"
        val boundState = mapOf("insertedIds" to "[\"row-a\"]")
        ledger.append(wfId, "n-comp", "db.upsert", "1.2.0", boundState)
        compensateCalled = false; compensateCalledWithBoundState = null
        val worker = CompensateWorker(registry = registry, ledger = ledger, resolveHandler = { _, _ -> spyBlock })
        val result = worker.execute(makeCompensateTask(failedWorkflowId = wfId, nodeId = "n-comp"))
        assertEquals(TaskResult.Status.COMPLETED, result.status)
        assertEquals(true, compensateCalled)
        assertEquals(boundState, compensateCalledWithBoundState)
        assertNull(ledger.readForCompensation(wfId, "n-comp"), "row should be marked compensated")
    }

    @Test
    fun `compensate with no ledger row is idempotent and COMPLETED (nothing to compensate)`() {
        compensateCalled = false

        val worker = CompensateWorker(registry = registry, ledger = ledger, resolveHandler = { _, _ -> spyBlock })
        val task = makeCompensateTask(failedWorkflowId = "wf-no-ledger-row")

        val result = worker.execute(task)

        assertEquals(TaskResult.Status.COMPLETED, result.status)
        assertEquals(false, compensateCalled, "no compensate call when no ledger row")
    }

    @Test
    fun `compensate for node c leaves node b (same blockId) untouched`() {
        val wfId = "wf-comp-same-block"
        ledger.append(wfId, "b", "db.upsert", "1.2.0", mapOf("insertedIds" to "[\"r1\"]"))
        ledger.append(wfId, "c", "db.upsert", "1.2.0", mapOf("insertedIds" to "[\"r2\"]"))
        compensateCalled = false; compensateCalledWithBoundState = null
        val worker = CompensateWorker(registry = registry, ledger = ledger, resolveHandler = { _, _ -> spyBlock })
        val result = worker.execute(makeCompensateTask(failedWorkflowId = wfId, nodeId = "c"))
        assertEquals(TaskResult.Status.COMPLETED, result.status)
        assertEquals(mapOf("insertedIds" to "[\"r2\"]"), compensateCalledWithBoundState)
        assertNull(ledger.readForCompensation(wfId, "c"), "c marked compensated")
        assertNotNull(ledger.readForCompensation(wfId, "b"), "b must remain uncompensated")
    }

    @Test
    fun `compensate failure records FAILED outcome and still returns COMPLETED (best-effort continue)`() {
        val wfId = "wf-comp-failed-1"
        ledger.append(wfId, "n-fail", "db.upsert", "1.2.0", mapOf("insertedIds" to "[\"x\"]"))
        val throwingBlock = object : Block {
            override val id = "db.upsert"
            override fun forward(input: BlockInput) = BlockOutput(rows = emptyList(), boundState = emptyMap())
            override fun compensate(boundState: Map<String, String>, ctx: CompensationContext): CompensationAction =
                throw RuntimeException("compensation blew up")
        }
        val worker = CompensateWorker(registry = registry, ledger = ledger, resolveHandler = { _, _ -> throwingBlock })
        val result = worker.execute(makeCompensateTask(failedWorkflowId = wfId, nodeId = "n-fail"))
        assertEquals(TaskResult.Status.COMPLETED, result.status)      // best-effort: chain continues
        val t = ledger.readTimeline(wfId)
        assertEquals(1, t.size); assertEquals("FAILED", t[0].outcome); assertEquals("n-fail", t[0].nodeId)
    }
}
