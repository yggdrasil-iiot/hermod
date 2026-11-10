package yggdrasil.hermod.conductor

import com.netflix.conductor.common.metadata.tasks.Task
import com.netflix.conductor.common.metadata.workflow.WorkflowDef
import com.netflix.conductor.common.run.Workflow
import kotlin.test.Test
import kotlin.test.assertEquals

class ConductorNodeStatesTest {

    private fun task(nodeId: String?, status: Task.Status, type: String = "SIMPLE", ref: String = nodeId ?: "r"): Task =
        Task().apply {
            taskType = type
            referenceTaskName = ref
            this.status = status
            inputData = mutableMapOf<String, Any?>().apply { if (nodeId != null) put("_nodeId", nodeId) }
        }

    private fun wf(tasks: List<Task>, name: String = "ot-recipe-apply"): Workflow =
        Workflow().apply { workflowDefinition = WorkflowDef().apply { setName(name) }; this.tasks = tasks }

    @Test fun `happy main lights forward DONE and WAIT gate AWAITING_APPROVAL`() {
        val main = wf(listOf(
            task("sensorRead", Task.Status.COMPLETED),
            task("recordPlan", Task.Status.COMPLETED),
            task("applyPLC", Task.Status.IN_PROGRESS, type = "WAIT"),
        ))
        assertEquals(
            mapOf("sensorRead" to "DONE", "recordPlan" to "DONE", "applyPLC" to "AWAITING_APPROVAL"),
            ConductorNodeStates.nodeStates(main, null),
        )
    }

    @Test fun `a non-WAIT IN_PROGRESS block task stays RUNNING`() {
        val main = wf(listOf(task("worker", Task.Status.IN_PROGRESS, type = "SIMPLE")))
        assertEquals(mapOf("worker" to "RUNNING"), ConductorNodeStates.nodeStates(main, null))
    }

    @Test fun `COMPLETED_WITH_ERRORS maps to DONE`() {
        val main = wf(listOf(task("n1", Task.Status.COMPLETED_WITH_ERRORS)))
        assertEquals(mapOf("n1" to "DONE"), ConductorNodeStates.nodeStates(main, null))
    }

    @Test fun `failed main plus compensation run overlays COMPENSATED`() {
        val main = wf(listOf(
            task("sensorRead", Task.Status.COMPLETED),
            task("recordPlan", Task.Status.COMPLETED),
            task("interlockAck", Task.Status.COMPLETED),
            task("preflight", Task.Status.FAILED_WITH_TERMINAL_ERROR),
            task("applyPLC", Task.Status.CANCELED, type = "WAIT"),
        ))
        val comp = wf(listOf(
            task("interlockAck", Task.Status.COMPLETED, ref = "c0"),
            task("recordPlan", Task.Status.COMPLETED, ref = "c1"),
        ), name = "ot-recipe-apply-compensation")
        assertEquals(
            mapOf(
                "sensorRead" to "DONE",
                "recordPlan" to "COMPENSATED",
                "interlockAck" to "COMPENSATED",
                "preflight" to "FAILED",
                "applyPLC" to "PENDING",
            ),
            ConductorNodeStates.nodeStates(main, comp),
        )
    }

    @Test fun `compensate FAILED overlays COMP_FAILED`() {
        val main = wf(listOf(task("recordPlan", Task.Status.COMPLETED), task("preflight", Task.Status.FAILED)))
        val comp = wf(listOf(task("recordPlan", Task.Status.FAILED)), name = "x-compensation")
        assertEquals(mapOf("recordPlan" to "COMP_FAILED", "preflight" to "FAILED"), ConductorNodeStates.nodeStates(main, comp))
    }

    @Test fun `structural tasks without nodeId are skipped`() {
        val main = wf(listOf(
            task(null, Task.Status.COMPLETED, type = "FORK_JOIN", ref = "fork_L1"),
            task(null, Task.Status.COMPLETED, type = "JOIN", ref = "join_L1"),
            task("n1", Task.Status.COMPLETED),
        ))
        assertEquals(mapOf("n1" to "DONE"), ConductorNodeStates.nodeStates(main, null))
    }

    @Test fun `null comp leaves forward map untouched`() {
        val main = wf(listOf(task("n1", Task.Status.SCHEDULED)))
        assertEquals(mapOf("n1" to "PENDING"), ConductorNodeStates.nodeStates(main, null))
    }
}
