package koshei.episode.runtime

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLParser
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.CollectionEndEvent
import org.yaml.snakeyaml.events.CollectionStartEvent
import org.yaml.snakeyaml.events.DocumentStartEvent
import org.yaml.snakeyaml.events.NodeEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.parser.ParserImpl
import org.yaml.snakeyaml.reader.StreamReader

/**
 * YAML to JSON text for the policy file (design §8.2). Two passes, each refusing with [IllegalArgumentException]:
 * - SnakeYAML's event stream: an anchor or alias (a value pulled in from elsewhere), a tag (a value typed by the
 *   writer, including the non-specific `!`), a `%YAML` or `%TAG` directive, and nesting deeper than [MAX_DEPTH]. The
 *   depth is counted here, while walking, so a deep file stops at the limit instead of being walked to its end first
 *   (`readPolicy` is a local activity: its timeout does not stop the thread).
 * - Jackson's tokens, from which the tree is built (not by Jackson's tree reader): a repeated key (a later
 *   `agentLayerEnabled` must not silently override an earlier one), a scalar that is not in its canonical JSON form
 *   (YAML 1.1 reads `010` as 8, `0x10` as 16, `1_000` as 1000 and `yes`/`on`/`off`/`True` as booleans), a second
 *   document, an empty file, and nesting deeper than [MAX_DEPTH] (the same limit, as a stream constraint).
 * An empty value is null, as YAML says (`EMPTY_STRING_AS_NULL`, on in a plain `YAMLFactory()` but not in its builder).
 */
internal object StrictYaml {
    const val MAX_DEPTH = 64
    const val MAX_CODE_POINTS = 1024 * 1024

    private val CANONICAL_INT = Regex("-?(0|[1-9][0-9]*)")
    private val CANONICAL_FLOAT = Regex("-?(0|[1-9][0-9]*)\\.[0-9]+([eE][-+]?[0-9]+)?")

    private fun loaderOptions() = LoaderOptions().apply { codePointLimit = MAX_CODE_POINTS }

    private val factory: YAMLFactory = YAMLFactory.builder()
        .loaderOptions(loaderOptions())
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH).build())
        .enable(YAMLParser.Feature.EMPTY_STRING_AS_NULL)
        .build()
    private val nodes = JsonNodeFactory.instance

    fun toJson(text: String): String = try {
        refuseByEvents(text)
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

    /**
     * Jackson's parser does not surface anchors on scalars, tags or directives, so they are read from the events. The
     * depth is counted here too, so that the walk stops at the limit.
     */
    private fun refuseByEvents(text: String) {
        val events = ParserImpl(StreamReader(text), loaderOptions())
        var depth = 0
        while (events.peekEvent() != null) {
            val e = events.getEvent()
            if (e is CollectionStartEvent && ++depth > MAX_DEPTH) throw IllegalArgumentException("nesting depth $depth exceeds $MAX_DEPTH")
            if (e is CollectionEndEvent) depth--
            if (e is AliasEvent) throw IllegalArgumentException("alias *${e.anchor} is not allowed")
            if (e is NodeEvent && e.anchor != null) throw IllegalArgumentException("anchor &${e.anchor} is not allowed")
            // An untagged node has no tag at all; the non-specific `!` is a tag here even though SnakeYAML calls it implicit.
            val tag = when (e) {
                is ScalarEvent -> e.tag
                is CollectionStartEvent -> e.tag
                else -> null
            }
            if (tag != null) throw IllegalArgumentException("tag $tag is not allowed")
            if (e is DocumentStartEvent && (e.version != null || !e.tags.isNullOrEmpty())) {
                throw IllegalArgumentException("a %YAML or %TAG directive is not allowed")
            }
        }
    }

    private fun value(p: YAMLParser): JsonNode = when (p.currentToken()) {
        JsonToken.START_OBJECT -> nodes.objectNode().also { o ->
            while (p.nextToken() == JsonToken.FIELD_NAME) {
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
        JsonToken.VALUE_NUMBER_INT -> {
            canonical(p, CANONICAL_INT, "integer")
            when (p.numberType) {
                JsonParser.NumberType.INT, JsonParser.NumberType.LONG -> nodes.numberNode(p.longValue)
                else -> nodes.numberNode(p.bigIntegerValue)
            }
        }
        JsonToken.VALUE_NUMBER_FLOAT -> {
            canonical(p, CANONICAL_FLOAT, "number")
            nodes.numberNode(p.decimalValue)
        }
        JsonToken.VALUE_TRUE -> { canonical(p, Regex("true"), "boolean"); nodes.booleanNode(true) }
        JsonToken.VALUE_FALSE -> { canonical(p, Regex("false"), "boolean"); nodes.booleanNode(false) }
        JsonToken.VALUE_NULL -> nodes.nullNode()
        else -> throw IllegalArgumentException("unexpected ${p.currentToken()}")
    }

    /** [p]'s raw text must be the form JSON would write; quote it in the YAML to mean the string. */
    private fun canonical(p: YAMLParser, form: Regex, what: String) {
        if (!form.matches(p.text)) {
            throw IllegalArgumentException("'${p.text}' is not a canonical $what (quote it if it is meant as a string)")
        }
    }
}
