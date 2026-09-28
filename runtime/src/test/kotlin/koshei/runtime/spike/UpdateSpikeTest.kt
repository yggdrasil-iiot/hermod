package koshei.runtime.spike

import io.temporal.api.enums.v1.EventType
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.client.WorkflowUpdateException
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.worker.WorkerFactory
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.UpdateValidatorMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * SPIKE (episode outer loop, design 2026-09-27): does Workflow Update work on the compose stack
 * (temporalio/auto-setup:1.25.2 + Java SDK 1.25.1), and which rejections reach the event history?
 * Expectation from the Temporal docs: a validator rejection leaves NO history trace; an accepted update whose
 * handler returns a refusal VALUE is recorded (Accepted + Completed). Skips when no server listens on 7233.
 */
@WorkflowInterface
interface UpdateSpikeWorkflow {
    @WorkflowMethod fun run(proposalId: String): String
    @UpdateMethod fun decide(proposalId: String): String
    @UpdateValidatorMethod(updateName = "decide") fun validateDecide(proposalId: String)
}

class UpdateSpikeWorkflowImpl : UpdateSpikeWorkflow {
    private var current: String? = null
    private var approved = false

    override fun run(proposalId: String): String {
        current = proposalId
        val decided = Workflow.await(Duration.ofSeconds(30)) { approved }
        return if (decided) "APPROVED" else "EXPIRED"
    }

    // Validator: only undecodable / malformed input is an error.
    override fun validateDecide(proposalId: String) {
        require(proposalId.matches(Regex("p-[0-9]+"))) { "undecodable proposal id: $proposalId" }
    }

    // Handler: a stale proposal is a refusal VALUE, not an exception.
    override fun decide(proposalId: String): String {
        Workflow.await { current != null }
        if (proposalId != current) return "REFUSED_STALE"
        approved = true
        return "APPROVED"
    }
}

class UpdateSpikeTest {
    private fun reachable(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 500); true }
    } catch (_: Exception) { false }

    @Test
    fun `validator rejection leaves no history but a refused value does`() {
        assumeTrue(reachable("localhost", 7233), "Temporal not listening on localhost:7233")
        val service = WorkflowServiceStubs.newLocalServiceStubs()
        val client = WorkflowClient.newInstance(service)
        val tq = "update-spike-${UUID.randomUUID()}"
        val factory = WorkerFactory.newInstance(client)
        factory.newWorker(tq).registerWorkflowImplementationTypes(UpdateSpikeWorkflowImpl::class.java)
        factory.start()
        try {
            val wfId = "update-spike-${UUID.randomUUID()}"
            val stub = client.newWorkflowStub(
                UpdateSpikeWorkflow::class.java,
                WorkflowOptions.newBuilder().setTaskQueue(tq).setWorkflowId(wfId).build(),
            )
            WorkflowStub.fromTyped(stub).start("p-1")

            val rejected = assertThrows<WorkflowUpdateException> { stub.decide("garbage-proposal") }
            println("[spike] validator rejection surfaced to caller as: ${rejected.cause}")
            assertEquals("REFUSED_STALE", stub.decide("p-2"))
            assertEquals("APPROVED", stub.decide("p-1"))
            assertEquals("APPROVED", WorkflowStub.fromTyped(stub).getResult(String::class.java))

            val events = client.fetchHistory(wfId).events
            fun count(t: EventType) = events.count { it.eventType == t }
            val accepted = count(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED)
            val completed = count(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED)
            val rejectedEv = count(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_REJECTED)
            println("[spike] history: ${events.size} events, updateAccepted=$accepted updateCompleted=$completed updateRejected=$rejectedEv")
            println("[spike] event types: ${events.map { it.eventType.name.removePrefix("EVENT_TYPE_") }}")

            assertEquals(2, accepted, "stale + valid updates are accepted into history")
            assertEquals(2, completed, "both accepted updates record their returned value")
            assertEquals(0, rejectedEv, "validator rejection is not written to history")
            assertFalse(events.any { it.toString().contains("garbage-proposal") }, "rejected payload leaves no trace")
        } finally {
            factory.shutdownNow()
            service.shutdown()
        }
    }
}
