package koshei.episode.runtime

import com.google.protobuf.ByteString
import io.temporal.api.filter.v1.WorkflowTypeFilter
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException

/**
 * The agent-layer switch's broadcast (design §10.4, R5): `agentOff` to every open episode at once, so a waiting episode
 * learns now rather than at its next decision. Each goes to the workflow id's current run — every open episode is meant,
 * so no run is pinned. Sending twice is harmless: the core only records an `agentOff` for an episode already switched off.
 *
 * One attempt per listed episode per call. [Report.ended] is what the server answered NOT_FOUND for: the run ended
 * between the listing and the signal, or the id never existed. Any other failure goes to [Report.failed] with its root
 * cause, and the rest are still sent — run it again to retry them. Visibility is eventually
 * consistent: an episode that opens during the broadcast may be missed — switching the policy file off first (§10.4)
 * covers it, since every new episode reads the policy at its start.
 */
class AgentOffBroadcast(
    private val client: WorkflowClient,
    private val openEpisodes: () -> List<String> = { openEpisodeIds(client) },
) {
    data class Report(val sent: List<String>, val ended: List<String>, val failed: List<Pair<String, String>>)

    fun send(): Report {
        val sent = ArrayList<String>()
        val ended = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        for (id in openEpisodes()) {
            try {
                client.newWorkflowStub(EpisodeWorkflow::class.java, id).agentOff()
                sent += id
            } catch (e: WorkflowNotFoundException) {
                ended += id
            } catch (e: RuntimeException) {
                // WorkflowServiceException's own message names only the workflow (id, run, type); the why is its cause.
                val root = generateSequence(e as Throwable) { it.cause }.last()
                failed += id to (root.message ?: root.javaClass.simpleName)
            }
        }
        return Report(sent, ended, failed)
    }

    companion object {
        /** The workflow type of [EpisodeWorkflow] (its interface name; no explicit name is given). */
        const val TYPE = "EpisodeWorkflow"

        /**
         * Workflow ids of open episode workflows, page by page. The legacy `ListOpenWorkflowExecutions`: Temporal's test
         * server implements it and not `ListWorkflowExecutions`; the shared dev server (SQL visibility) serves both. The
         * type is checked again here — the test server ignores the type filter, and the broadcast must not widen.
         */
        fun openEpisodeIds(client: WorkflowClient): List<String> {
            val stub = client.workflowServiceStubs.blockingStub()
            val ids = LinkedHashSet<String>()
            var token = ByteString.EMPTY
            do {
                val page = stub.listOpenWorkflowExecutions(
                    ListOpenWorkflowExecutionsRequest.newBuilder()
                        .setNamespace(client.options.namespace)
                        .setTypeFilter(WorkflowTypeFilter.newBuilder().setName(TYPE))
                        .setMaximumPageSize(PAGE)
                        .setNextPageToken(token)
                        .build(),
                )
                page.executionsList.filter { it.type.name == TYPE }.forEach { ids += it.execution.workflowId }
                token = page.nextPageToken
            } while (!token.isEmpty)
            return ids.toList()
        }

        private const val PAGE = 1000
    }
}
