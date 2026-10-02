package koshei.authoring

import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowUpdateException
import io.temporal.failure.ApplicationFailure
import koshei.episode.runtime.CloseRequest
import koshei.episode.runtime.ConfirmRequest
import koshei.episode.runtime.DecideRequest
import koshei.episode.runtime.EpisodeView
import koshei.episode.runtime.EpisodeWorkflow
import koshei.episode.runtime.TakeoverRequest
import java.time.Duration
import java.util.Optional
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class EpisodeNotFound(val workflowId: String) : RuntimeException("no episode workflow $workflowId")
/** The instance asked for is not the workflow's current run: the decision is not sent (design §7.1). */
class EpisodeMoved(val instanceId: String, val currentInstanceId: String) :
    RuntimeException("$instanceId is not the current episode ($currentInstanceId)")
/** The run has ended (or ended between the check and the Update): nothing can be decided on it. */
class EpisodeEnded(val instanceId: String) : RuntimeException("$instanceId has ended")
/** The Update's validator refused the request's form (design §7.2): nothing reached the episode or its history. */
class InvalidEpisodeRequest(message: String) : RuntimeException(message)
/**
 * The Update did not complete within the bound: the worker may be down or slow. Its outcome is UNKNOWN — the Update was
 * sent and may still be applied — so the operator reads the episode again before deciding again.
 */
class EpisodeTimeout(val instanceId: String) : RuntimeException("no answer from $instanceId in time")

/** The workflow's current run as Temporal reports it. [view] is queried only while the run is running. */
class CurrentRun(val runId: String, val running: Boolean, val view: EpisodeView?, val stub: EpisodeWorkflow)

/**
 * The control plane's way to the episode workflows (design §7.2, §7.3). Decisions go only to the workflow's current
 * run, only while it runs, and only when it holds the instance the operator saw. The run id comes from Temporal
 * (describe), never from the instance id — they differ after a reset (§7.1). A stub pinned to that run carries both
 * the check and the Update, so a run that continued as new in between refuses the Update rather than the next
 * episode taking it. An ended run is not queried: that needs a worker to replay it, and the records answer instead.
 */
class EpisodeGateway(private val client: Lazy<WorkflowClient>, private val updateTimeout: Duration = Duration.ofSeconds(30)) : AutoCloseable {
    private val updates = Executors.newCachedThreadPool { r -> Thread(r, "episode-update").apply { isDaemon = true } }

    fun current(workflowId: String): CurrentRun {
        val c = client.value
        val info = try {
            c.workflowServiceStubs.blockingStub().describeWorkflowExecution(
                DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(c.options.namespace)
                    .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
                    .build(),
            ).workflowExecutionInfo
        } catch (e: StatusRuntimeException) {
            if (e.status.code == Status.Code.NOT_FOUND) throw EpisodeNotFound(workflowId) else throw e
        }
        val runId = info.execution.runId
        val running = info.status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
        val stub = c.newWorkflowStub(EpisodeWorkflow::class.java, workflowId, Optional.of(runId))
        if (!running) return CurrentRun(runId, false, null, stub)
        // The run can close between the describe and the query: then it is ended, as the describe would have said.
        val view = try {
            stub.view()
        } catch (e: WorkflowNotFoundException) {
            return CurrentRun(runId, false, null, stub)
        }
        return CurrentRun(runId, true, view, stub)
    }

    /** The live view of [instanceId], or null when it is not the workflow's running current run (its records still answer). */
    fun view(instanceId: String): EpisodeView? = current(workflowIdOf(instanceId)).view?.takeIf { it.instanceId == instanceId }

    fun decide(instanceId: String, request: DecideRequest): String = act(instanceId) { it.decide(request) }
    fun confirm(instanceId: String, request: ConfirmRequest): String = act(instanceId) { it.confirm(request) }
    fun takeover(instanceId: String, request: TakeoverRequest): String = act(instanceId) { it.takeover(request) }
    fun close(instanceId: String, request: CloseRequest): String = act(instanceId) { it.close(request) }

    /**
     * Shuts the gateway down (the bean's destroy method): Update threads still waiting are interrupted, and the client's
     * service stubs are shut down if the client was ever made — a context that never reached Temporal makes none here.
     */
    override fun close() {
        updates.shutdownNow()
        if (client.isInitialized()) client.value.workflowServiceStubs.shutdown()
    }

    private fun act(instanceId: String, update: (EpisodeWorkflow) -> String): String = send(current(workflowIdOf(instanceId)), instanceId, update)

    /**
     * Sends [update] on [run] — the run as checked — if it still holds [instanceId]. Its stub is pinned to that run, so a
     * run that ended or continued as new since the check refuses the Update ([EpisodeEnded]); the next run never gets it.
     */
    internal fun send(run: CurrentRun, instanceId: String, update: (EpisodeWorkflow) -> String): String {
        val view = run.view ?: throw EpisodeEnded(instanceId)
        if (view.instanceId != instanceId) throw EpisodeMoved(instanceId, view.instanceId)
        val pending = updates.submit<String> { update(run.stub) }
        return try {
            pending.get(updateTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.cancel(true)
            throw EpisodeTimeout(instanceId)
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is WorkflowUpdateException -> {
                    val failure = cause.cause
                    if (failure is ApplicationFailure && failure.type == IllegalArgumentException::class.java.name) {
                        throw InvalidEpisodeRequest(failure.originalMessage)
                    }
                    throw cause                                   // anything else stays a 500: not hidden
                }
                // 1.25.1: an Update to a completed run is NOT_FOUND "workflow execution already completed"
                is WorkflowNotFoundException -> throw EpisodeEnded(instanceId)
                is RuntimeException -> throw cause
                else -> throw e
            }
        }
    }

    companion object {
        /** The workflow id is the instance id's first part (design §7.1). The run part is never used as a run id. */
        fun workflowIdOf(instanceId: String): String = instanceId.substringBeforeLast('/')
    }
}
