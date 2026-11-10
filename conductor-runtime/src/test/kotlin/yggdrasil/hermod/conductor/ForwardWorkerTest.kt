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

@Testcontainers
class ForwardWorkerTest {

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

        /** Stub compensable block: forward returns known output + boundState. */
        val stubBlock = object : Block {
            override val id = "db.upsert"
            override fun forward(input: BlockInput) = BlockOutput(
                rows = listOf(mapOf("id" to "inserted-1")),
                boundState = mapOf("insertedIds" to "[\"inserted-1\"]")
            )
        }

        @BeforeAll
        @JvmStatic
        fun setup() {
            DbTestSupport.override(postgres.jdbcUrl, postgres.username, postgres.password)
            // Create comp_ledger table
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

            // Build a real registry that includes the builtin contracts (loaded from classpath manifests)
            // We use a minimal in-memory registry: only needs to resolve the contracts, not publish plugins
            val builtinContracts = listOf("db.read", "transform.map", "db.upsert", "notify.email", "actuate", "merge")
                .associate { id ->
                    val yaml = ForwardWorkerTest::class.java.getResourceAsStream("/manifests/$id.yaml")
                        ?: error("manifest not found: /manifests/$id.yaml")
                    val c = yggdrasil.hermod.registry.ManifestLoader.load(yaml.bufferedReader().readText())
                    "${c.id}#${c.version}" to c
                }

            registry = Registry(
                index = BlockIndex { Db.connect() },
                store = BlockStore(File.createTempFile("hermod-fw-store", "").let { it.delete(); it.mkdirs(); it }),
                builtins = builtinContracts,
                handlerLoadCheck = { _, _ -> },  // no-op for test: we supply stub blocks
            )

            ledger = CompLedger { Db.connect() }
        }

        private fun makeTask(
            blockId: String = "db.upsert",
            version: String = "1.2.0",
            outputName: String = "written",
            workflowId: String = "wf-fw-1",
            nodeId: String = "n-fw",
            rows: Any = listOf(mapOf("id" to "row-1")),
        ): Task {
            val t = Task()
            t.taskDefName = blockId
            t.referenceTaskName = nodeId
            t.workflowInstanceId = workflowId
            t.inputData = mutableMapOf(
                "_pinnedVersion" to version,
                "_outputName" to outputName,
                "_nodeId" to nodeId,
                "rows" to rows,
            )
            return t
        }
    }

    @Test
    fun `forward dispatches to handler, publishes under outputName, appends ledger for compensable block`() {
        val worker = ForwardWorker(blockId = "db.upsert", registry = registry, ledger = ledger, resolveHandler = { _, _ -> stubBlock })
        val task = makeTask(workflowId = "wf-fw-comp-1", nodeId = "n-comp")

        val result = worker.execute(task)

        assertEquals(TaskResult.Status.COMPLETED, result.status)
        assertNotNull(result.outputData["written"], "output must be published under outputName 'written'")

        // Ledger should have one row for wf-fw-comp-1 (db.upsert is compensable: REVERSIBLE/STATIC)
        val ledgerRow = ledger.readForCompensation("wf-fw-comp-1", "n-comp")
        assertNotNull(ledgerRow, "compensable block must have ledger entry")
        assertEquals("db.upsert", ledgerRow.blockId)
    }

    @Test
    fun `forward with PermanentBlockFailure returns FAILED_WITH_TERMINAL_ERROR status`() {
        val failBlock = object : Block {
            override val id = "db.upsert"
            override fun forward(input: BlockInput): BlockOutput =
                throw PermanentBlockFailure("permanent failure for test")
        }
        val worker = ForwardWorker(blockId = "db.upsert", registry = registry, ledger = ledger, resolveHandler = { _, _ -> failBlock })
        val task = makeTask(workflowId = "wf-fw-fail-1")

        val result = worker.execute(task)

        assertEquals(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR, result.status)
        assertNotNull(result.reasonForIncompletion)
    }

    @Test
    fun `forward assembles namedInputs for a multi-input block`() {
        val captured = java.util.concurrent.atomic.AtomicReference<BlockInput>()
        val stub = object : Block {
            override val id = "merge"
            override fun forward(input: BlockInput): BlockOutput {
                captured.set(input)
                return BlockOutput(rows = (input.namedInputs["left"] ?: emptyList()) + (input.namedInputs["right"] ?: emptyList()))
            }
        }
        val worker = ForwardWorker(blockId = "merge", registry = registry, ledger = ledger, resolveHandler = { _, _ -> stub })
        val t = Task().apply {
            taskDefName = "merge"; workflowInstanceId = "wf-merge-1"
            inputData = mutableMapOf(
                "_pinnedVersion" to "1.0.0", "_outputName" to "out",
                "left" to listOf(mapOf("id" to "A")),
                "right" to listOf(mapOf("id" to "B")),
            )
        }
        val result = worker.execute(t)
        assertEquals(TaskResult.Status.COMPLETED, result.status)
        assertEquals(2, (captured.get().namedInputs["left"]!!.size + captured.get().namedInputs["right"]!!.size))
    }

    @Test
    fun `forward for non-compensable block does not append ledger`() {
        // actuate is IRREVERSIBLE/NONE => non-compensable
        val actuateBlock = object : Block {
            override val id = "actuate"
            override fun forward(input: BlockInput) = BlockOutput(rows = emptyList(), boundState = emptyMap())
        }
        val worker = ForwardWorker(blockId = "actuate", registry = registry, ledger = ledger, resolveHandler = { _, _ -> actuateBlock })

        val task = Task()
        task.taskDefName = "actuate"
        task.workflowInstanceId = "wf-fw-noncomp-1"
        task.inputData = mutableMapOf(
            "_pinnedVersion" to "1.0.0",
            "_outputName" to "",
            "_nodeId" to "n-actuate",
            "rows" to emptyList<Map<String, Any?>>(),
        )

        val result = worker.execute(task)

        assertEquals(TaskResult.Status.COMPLETED, result.status)
        // No ledger entry for actuate
        val ledgerRow = ledger.readForCompensation("wf-fw-noncomp-1", "n-actuate")
        assertEquals(null, ledgerRow, "non-compensable block must not have ledger entry")
    }

    @Test
    fun `forward passes slowMs into BlockInput when slowAtBlockId matches the block`() {
        val captured = java.util.concurrent.atomic.AtomicReference<BlockInput>()
        val stub = object : Block {
            override val id = "transform.map"
            override fun forward(input: BlockInput): BlockOutput { captured.set(input); return BlockOutput(rows = input.rows) }
        }
        val worker = ForwardWorker(blockId = "transform.map", registry = registry, ledger = ledger, resolveHandler = { _, _ -> stub })
        val t = Task().apply {
            taskDefName = "transform.map"; referenceTaskName = "p1"; workflowInstanceId = "wf-slow-1"
            inputData = mutableMapOf(
                "_pinnedVersion" to "1.0.0", "_outputName" to "rows", "_nodeId" to "p1",
                "_slowMs" to "1500", "_slowAtBlockId" to "transform.map",
                "rows" to listOf(mapOf("id" to "x")),
            )
        }
        worker.execute(t)
        assertEquals(1500L, captured.get().slowMs)
    }

    @Test
    fun `forward does NOT slow a block whose id differs from slowAtBlockId`() {
        val captured = java.util.concurrent.atomic.AtomicReference<BlockInput>()
        val stub = object : Block {
            override val id = "transform.map"
            override fun forward(input: BlockInput): BlockOutput { captured.set(input); return BlockOutput(rows = input.rows) }
        }
        val worker = ForwardWorker(blockId = "transform.map", registry = registry, ledger = ledger, resolveHandler = { _, _ -> stub })
        val t = Task().apply {
            taskDefName = "transform.map"; referenceTaskName = "p1"; workflowInstanceId = "wf-slow-2"
            inputData = mutableMapOf(
                "_pinnedVersion" to "1.0.0", "_outputName" to "rows", "_nodeId" to "p1",
                "_slowMs" to "1500", "_slowAtBlockId" to "db.upsert",
                "rows" to listOf(mapOf("id" to "x")),
            )
        }
        worker.execute(t)
        assertEquals(0L, captured.get().slowMs)
    }
}
