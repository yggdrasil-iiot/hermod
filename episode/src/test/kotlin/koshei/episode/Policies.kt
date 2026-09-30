package koshei.episode

import com.fasterxml.jackson.databind.node.ObjectNode

/** A policy table that satisfies every rule of design §10.2; tests edit a copy to break one rule at a time. */
internal object Policies {
    private val validJson = """
        {
          "version": "2026-09-30.1",
          "expiresAt": null,
          "agentLayerEnabled": true,
          "autoApprove": { "APPROVE_REMEDY": { "allowed": true, "requireClean": true, "skills": ["pick_place"] } },
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

    fun node(edit: ObjectNode.() -> Unit = {}): ObjectNode = (mapper.readTree(validJson) as ObjectNode).apply(edit)

    fun valid(edit: ObjectNode.() -> Unit = {}): PolicyTable =
        (parsePolicy(node(edit)) as? PolicyParse.Valid)?.policy ?: error("test policy is not valid: ${parsePolicy(node(edit))}")

    fun errors(edit: ObjectNode.() -> Unit): List<String> =
        (parsePolicy(node(edit)) as? PolicyParse.Invalid)?.errors ?: error("expected an invalid policy")

    fun ObjectNode.obj(name: String): ObjectNode = get(name) as ObjectNode
}
