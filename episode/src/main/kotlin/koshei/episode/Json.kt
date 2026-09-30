package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** One mapper for the module. Plain ObjectMapper: the core works on JSON trees, never on data-class binding. */
internal val mapper: ObjectMapper = ObjectMapper()

/** The field as a string, or null when absent, JSON null, or not a string. */
internal fun JsonNode.text(key: String): String? = get(key)?.takeIf { it.isTextual }?.textValue()
