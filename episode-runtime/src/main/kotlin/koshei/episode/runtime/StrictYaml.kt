package koshei.episode.runtime

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLParser

/**
 * YAML to JSON text for the policy file (design §8.2). The tree is built from the parser's tokens rather than by
 * Jackson's tree reader, so the refusals do not depend on a parser feature: a repeated key (a later `agentLayerEnabled`
 * must not silently override an earlier one), an anchor or alias (a value pulled in from elsewhere), a second
 * document, and an empty file are all refused with [IllegalArgumentException].
 */
internal object StrictYaml {
    private val factory = YAMLFactory()
    private val nodes = JsonNodeFactory.instance

    fun toJson(text: String): String = try {
        (factory.createParser(text) as YAMLParser).use { p ->
            if (p.nextToken() == null) throw IllegalArgumentException("empty document")
            val root = value(p)
            if (p.nextToken() != null) throw IllegalArgumentException("more than one document")
            root.toString()
        }
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("not readable YAML: ${e.message}", e)
    }

    private fun value(p: YAMLParser): JsonNode {
        if (p.isCurrentAlias) throw IllegalArgumentException("alias *${p.text} is not allowed (no anchors in the policy)")
        p.objectId?.let { throw IllegalArgumentException("anchor &$it is not allowed") }
        return when (p.currentToken()) {
            JsonToken.START_OBJECT -> nodes.objectNode().also { o ->
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    if (p.isCurrentAlias || p.objectId != null) throw IllegalArgumentException("anchor or alias on a key is not allowed")
                    val name = p.currentName()
                    if (o.has(name)) throw IllegalArgumentException("duplicate key '$name'")
                    p.nextToken()
                    o.set<JsonNode>(name, value(p))
                }
            }
            JsonToken.START_ARRAY -> nodes.arrayNode().also { a ->
                while (p.nextToken() != JsonToken.END_ARRAY) a.add(value(p))
            }
            JsonToken.VALUE_STRING -> nodes.textNode(p.text)
            JsonToken.VALUE_NUMBER_INT -> when (p.numberType) {
                JsonParser.NumberType.INT, JsonParser.NumberType.LONG -> nodes.numberNode(p.longValue)
                else -> nodes.numberNode(p.bigIntegerValue)
            }
            JsonToken.VALUE_NUMBER_FLOAT -> nodes.numberNode(p.decimalValue)
            JsonToken.VALUE_TRUE -> nodes.booleanNode(true)
            JsonToken.VALUE_FALSE -> nodes.booleanNode(false)
            JsonToken.VALUE_NULL -> nodes.nullNode()
            else -> throw IllegalArgumentException("unexpected ${p.currentToken()}")
        }
    }
}
