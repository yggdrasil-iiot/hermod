package koshei.episode

import com.fasterxml.jackson.databind.JsonNode
import java.security.MessageDigest

/**
 * RFC 8785 (JCS) canonical JSON for the subset koshei hashes: objects (keys sorted by UTF-16 code units, which is
 * Kotlin's String order), arrays, strings, null and booleans. Numbers are rejected rather than approximated —
 * candidate lists carry none, and a hash over a guessed number format would silently disagree with any other
 * implementation (design §9.3).
 */
object Jcs {
    fun canonical(node: JsonNode): String = StringBuilder().also { write(node, it) }.toString()

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun write(n: JsonNode, sb: StringBuilder) {
        when {
            n.isNull -> sb.append("null")
            n.isBoolean -> sb.append(if (n.booleanValue()) "true" else "false")
            n.isTextual -> writeString(n.textValue(), sb)
            n.isArray -> {
                sb.append('[')
                n.forEachIndexed { i, e -> if (i > 0) sb.append(','); write(e, sb) }
                sb.append(']')
            }
            n.isObject -> {
                sb.append('{')
                n.fieldNames().asSequence().sorted().forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    writeString(k, sb); sb.append(':'); write(n.get(k), sb)
                }
                sb.append('}')
            }
            else -> throw IllegalArgumentException("JCS subset: ${n.nodeType} is not supported (numbers are out of scope)")
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }
}
