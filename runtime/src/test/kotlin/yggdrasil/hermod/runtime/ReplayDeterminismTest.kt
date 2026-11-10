package yggdrasil.hermod.runtime

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowExtension
import io.temporal.testing.WorkflowReplayer
import yggdrasil.hermod.blocks.ActuateBlock
import yggdrasil.hermod.blocks.NotifyEmailBlock
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.RegisterExtension
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReplayDeterminismTest {
    private val pg = PostgreSQLContainer("postgres:16")

    @JvmField @RegisterExtension
    val testEnv: TestWorkflowExtension = TestWorkflowExtension.newBuilder()
        .setWorkflowTypes(SagaWorkflowImpl::class.java)
        .setActivityImplementations(BlockActivitiesImpl())
        .setWorkflowClientOptions(DataConverterSupport.clientOptions())
        .build()

    @BeforeAll fun up() {
        pg.start(); DbTestSupport.override(pg.jdbcUrl, pg.username, pg.password)
        DbTestSupport.exec("CREATE TABLE source_rows (id TEXT PRIMARY KEY, val TEXT NOT NULL)")
        DbTestSupport.exec("CREATE TABLE target_rows (id TEXT PRIMARY KEY, val TEXT NOT NULL)")
        DbTestSupport.exec("INSERT INTO source_rows VALUES ('A1','x')")
        NotifyEmailBlock.SENT.clear(); ActuateBlock.FIRED.clear()
        BoundWorkflow.bind("demo", RuntimeAssembly.planFor(DemoWorkflow.DEF))
    }
    @AfterAll fun down() { pg.stop() }

    @Test fun `R2 - recorded history replays with no non-determinism`(
        client: WorkflowClient, options: WorkflowOptions,
    ) {
        val wf = client.newWorkflowStub(SagaWorkflow::class.java,
            options.toBuilder().setWorkflowId("replay-it").build())
        wf.run(WorkflowInput(autoApprove = true))

        // SDK 1.25.1: fetchHistory lives on WorkflowClient and returns
        // io.temporal.common.WorkflowExecutionHistory, accepted by WorkflowReplayer.
        val history = client.fetchHistory("replay-it")
        // Throws on non-determinism; absence of exception == pass.
        WorkflowReplayer.replayWorkflowExecution(history, SagaWorkflowImpl::class.java)
        assertTrue(true)
    }
}
