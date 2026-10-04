package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** What is unknown (contract 0.6 §3.3). `OUTCOME` is episode-level (e.g. picasso NO_PROPOSAL) and is passed in by the caller. */
enum class UnknownWhat { OBSERVATION_ABSENT, LINK_BROKEN, LATE_EVENTS, PROGRESS_UNOBSERVED, PROGRESS_STALLED, OUTCOME }

data class Unknown(val subject: Map<String, String?>, val what: UnknownWhat, val since: String?, val source: String) {
    fun toJson(): ObjectNode = mapper.createObjectNode().apply {
        putObject("subject").apply { subject.forEach { (k, v) -> put(k, v) } }
        put("what", what.name)
        put("since", since)
        put("source", source)
    }
}

/** True for the item [subject] / [what] names — a person's confirmation addresses an unknown by exactly these two. */
internal fun Unknown.matches(subject: Map<String, String?>, what: UnknownWhat): Boolean = this.subject == subject && this.what == what

/**
 * v1 rule (design §9.1, contract 0.6 §3.3): anything other than an explicitly SAFE observation value is an unknown.
 * picasso's three-valued fields are never folded — `progressObservable: null` ("not yet observed") is as unknown as
 * `false`. Fail-safe on purpose until picasso settles the list (design §19): an unknown only removes execution-class
 * candidates, while a missed unknown could double an irreversible action.
 */
fun deriveUnknowns(snapshot: Snapshot): List<Unknown> = snapshot.incidents.flatMap(::deriveUnknownsOf)

/** The unknowns one incident line derives (the rule of [deriveUnknowns], per line). */
internal fun deriveUnknownsOf(inc: ObjectNode): List<Unknown> {
    val subject = linkedMapOf("robotId" to inc.text("robotId"), "executionId" to inc.text("executionId"), "unitId" to inc.text("unitId"))
    fun unknown(what: UnknownWhat) = Unknown(subject, what, inc.text("at"), "picasso")

    val obs = inc.get("observation")
    if (obs == null || obs.isNull) return listOf(unknown(UnknownWhat.OBSERVATION_ABSENT))
    return buildList {
        if (!obs.isExactly("linkBroken", false)) add(unknown(UnknownWhat.LINK_BROKEN))
        val late = obs.get("lateEvents")
        if (late == null || !late.isArray || late.size() > 0) add(unknown(UnknownWhat.LATE_EVENTS))
        if (!obs.isExactly("progressObservable", true)) add(unknown(UnknownWhat.PROGRESS_UNOBSERVED))
        if (!obs.isExactly("progressStalled", false)) add(unknown(UnknownWhat.PROGRESS_STALLED))
    }
}

private fun JsonNode.isExactly(key: String, value: Boolean): Boolean = get(key)?.let { it.isBoolean && it.booleanValue() == value } ?: false
