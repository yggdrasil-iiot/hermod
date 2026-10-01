package koshei.episode.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Collections

/** picasso run-1 lines (byte copies of the `:episode` test resources; the picasso repo is read-only). */
internal object Picasso {
    private val mapper = ObjectMapper()
    private fun res(name: String): String =
        requireNotNull(javaClass.getResource("/picasso/run-1/$name")) { "missing test resource run-1/$name" }.readText(Charsets.UTF_8)

    val manifest: String get() = res("manifest.json")

    private fun line(file: String, key: String, id: String): String =
        res(file).lines().filter { it.isNotBlank() }.single { mapper.readTree(it).get(key)?.asText() == id }

    fun search(id: String): String = line("remedy-searches.jsonl", "searchId", id)
    fun incident(id: String): String = line("incidents.jsonl", "incidentId", id)
}

/** Policy tables as `readPolicy` would return them (design §10.1; the same values as the `:episode` test table). */
internal object TestPolicies {
    fun table(autoApprove: Boolean): String = """
        {
          "version": "2026-10-02.1",
          "expiresAt": null,
          "agentLayerEnabled": true,
          "autoApprove": { "APPROVE_REMEDY": { "allowed": $autoApprove, "requireClean": true, "skills": ["pick_place"] } },
          "actionCatalog": [],
          "deadlines": {
            "correlatingMs": 5000, "approvalMs": 300000, "approvalValidityMs": 600000, "revalidateMs": 30000,
            "recordMs": 10000, "dispatchMs": 30000, "childMaxMs": 600000, "evidenceMs": 600000,
            "unknownRecheckMs": 30000, "unknownMs": 900000, "escalatedRetentionMs": 86400000, "episodeMs": 3600000
          },
          "diagnosis": { "scheduleToCloseMs": 1200000, "startToCloseMs": 540000, "heartbeatMs": 30000, "maxAttempts": 2 },
          "maxAttemptsPerEpisode": 3,
          "repeatedRemedyThreshold": 2,
          "snapshotMaxBytes": 262144,
          "correlation": []
        }
    """.trimIndent()
}

/** picasso's approval-window answer for an approved remedy (picasso `ApprovalWire.encode`, schema 2). */
internal const val PICASSO_APPROVED =
    """{"schemaVersion":"2","contractSemver":"1.0.0","outcome":"APPROVED","executionId":"exec-42","steps":[{"skillType":"pick_place","parameters":{"slot":"B-03"}}]}"""

/** koshei-side activities for tests: programmable answers, every call remembered in order. */
internal class FakeEpisodeActivities : EpisodeActivities {
    @Volatile var policyJson: String? = TestPolicies.table(autoApprove = true)
    @Volatile var revalidation: String = "TRUE"
    @Volatile var dispatchOutcome: DispatchOutcome = DispatchOutcome("ANSWER", PICASSO_APPROVED)
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun readPolicy(): PolicyReadResult = policyJson?.let { PolicyReadResult("FOUND", it) } ?: PolicyReadResult("MISSING")

    override fun revalidate(candidateJson: String): String {
        calls += "revalidate"
        return revalidation
    }

    override fun record(instanceId: String, seq: Long, kind: String, payloadJson: String) {
        calls += "record:$kind"
    }

    override fun recordIntent(instanceId: String, seq: Long, intentJson: String) {
        calls += "intent:$seq"
    }

    override fun dispatch(intentJson: String, approverKind: String?): DispatchOutcome {
        calls += "dispatch:$approverKind"
        return dispatchOutcome
    }

    override fun sendNotice(instanceId: String, noticeJson: String) {
        calls += "notice"
    }
}
