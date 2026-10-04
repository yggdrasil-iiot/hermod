package koshei.episode

import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * One mapper for the module. Plain ObjectMapper: the core works on JSON trees, never on data-class binding.
 * Strict on input: a duplicate key or anything after the first value is an error, never silently "last one wins".
 */
internal val mapper: ObjectMapper =
    ObjectMapper(JsonFactoryBuilder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

/** The field as a string, or null when absent, JSON null, or not a string. */
internal fun JsonNode.text(key: String): String? = get(key)?.takeIf { it.isTextual }?.textValue()
