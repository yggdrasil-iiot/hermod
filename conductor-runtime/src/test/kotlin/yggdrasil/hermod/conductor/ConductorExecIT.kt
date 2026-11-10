package yggdrasil.hermod.conductor

import com.netflix.conductor.client.automator.TaskRunnerConfigurer
import com.netflix.conductor.client.http.ConductorClient
import com.netflix.conductor.client.http.TaskClient
import com.netflix.conductor.client.worker.Worker
import com.netflix.conductor.common.run.Workflow
import yggdrasil.hermod.compiler.WorkflowCompiler
import yggdrasil.hermod.compiler.conductor.ConductorBackend
import yggdrasil.hermod.core.WorkflowDef
import yggdrasil.hermod.core.WorkflowStep
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end happy path: the SAME engine-neutral IR is deployed to a real Conductor server and EXECUTED by
 * in-process workers that reuse the real block handlers. Linear `db.read -> transform.map -> db.upsert`:
 * reads source_rows, uppercases `val`, upserts into target_rows. Asserts the workflow COMPLETES and the DB
 * side effect is the expected transform.
 *
 * Container fixture lives in [ConductorItSupport] (JVM singleton) so the next segment's approve/reject/
 * compensation tests reuse the same conductor + Postgres without restarting them.
 */
class ConductorExecIT {

    companion object {
        private lateinit var client: ConductorClient
        private lateinit var runner: TaskRunnerConfigurer

        /** Linear, no-WAIT workflow: db.read (source) -> transform.map -> db.upsert (target_rows). */
        private val happyDef = WorkflowDef(
            name = "it-happy",
            steps = listOf(
                WorkflowStep("db.read", "1.0.0"),
                WorkflowStep("transform.map", "1.0.0"),
                WorkflowStep("db.upsert", "1.2.0", params = mapOf("table" to "target_rows")),
            ),
        )

        @BeforeAll
        @JvmStatic
        fun setup() {
            ConductorItSupport.ensureUp()
            client = ConductorItSupport.newClient()

            // Deploy the bundle (taskdefs + main + compensation) from the SAME emit the CLI/gate use.
            val ir = WorkflowCompiler.compile(happyDef, ConductorItSupport.registry())
            ConductorDeployer(client).deploy(ConductorBackend.emitBundle(ir))

            // Boot in-process workers for every builtin block + compensate, against the container.
            val registry = ConductorItSupport.registry()
            val ledger = ConductorItSupport.ledger()
            val builtinIds = listOf("db.read", "transform.map", "db.upsert", "notify.email", "actuate")
            val workers: List<Worker> = builtinIds.map { ForwardWorker(it, registry, ledger) } +
                CompensateWorker(registry, ledger)
            runner = TaskRunnerConfigurer.Builder(TaskClient(client), workers)
                .withThreadCount(builtinIds.size + 1)
                .build()
            runner.init()
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            if (::runner.isInitialized) runner.shutdown()
        }
    }

    @Test
    fun `happy path runs forward to COMPLETED and applies the expected transform`() {
        // Seed source data; clear target so the assert is unambiguous.
        ConductorItSupport.exec("DELETE FROM source_rows")
        ConductorItSupport.exec("DELETE FROM target_rows")
        ConductorItSupport.exec("INSERT INTO source_rows(id,val) VALUES ('r1','alpha'),('r2','beta')")

        val starter = ConductorStarter(client)
        val workflowId = starter.start("it-happy", mapOf("rows" to emptyList<Any?>(), "workflowId" to ""))
        assertTrue(workflowId.isNotBlank(), "start must return a workflowId")

        val wf: Workflow = starter.awaitTerminal(workflowId, timeoutMs = 90_000)

        assertEquals(
            Workflow.WorkflowStatus.COMPLETED, wf.status,
            "workflow must COMPLETE (status was ${wf.status}; reason=${wf.reasonForIncompletion})"
        )

        // The transform: db.read -> transform.map uppercases val -> db.upsert writes to target_rows.
        assertEquals(2, ConductorItSupport.count("target_rows"), "both rows upserted to target_rows")
        assertEquals("ALPHA", selectVal("r1"))
        assertEquals("BETA", selectVal("r2"))
    }

    private fun selectVal(id: String): String =
        java.sql.DriverManager.getConnection(
            ConductorItSupport.postgres.jdbcUrl,
            ConductorItSupport.postgres.username,
            ConductorItSupport.postgres.password,
        ).use { c ->
            c.prepareStatement("SELECT val FROM target_rows WHERE id=?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            }
        }
}
