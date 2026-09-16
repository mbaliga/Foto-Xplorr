package com.fotoxplorr.app.editor

/**
 * A minimal JSON value model, reader and writer, hand-rolled rather than pulled in.
 *
 * This app already avoids adding a dependency where the offline flavour's classpath gate or a
 * licence question would make one costly (see `docs/adr/ADR-007-photo-editing.md`); a JSON
 * library is neither of those, but presets and persisted edit recipes are a small, fully-known
 * shape (a handful of floats, a couple of lists, never anything recursive-and-arbitrary), and
 * that shape is exactly what makes hand-rolling both cheap and safe to get right. Pure Kotlin, no
 * Android imports (unlike `org.json`, which is stubbed to throw under a plain JVM unit test and
 * would force every serialization test in this file's users onto Robolectric for no reason).
 *
 * Not a general-purpose parser: no comments, no trailing commas, no `\uXXXX` beyond what
 * [unescape] handles, and a malformed document fails the whole parse rather than best-effort
 * recovering — exactly right for a file this app wrote itself moments earlier, wrong for parsing
 * arbitrary JSON off the network (which this app does not do with it).
 */
sealed class JsonValue {
    data class JsonObject(val entries: Map<String, JsonValue>) : JsonValue()
    data class JsonArray(val items: List<JsonValue>) : JsonValue()
    data class JsonString(val value: String) : JsonValue()
    data class JsonNumber(val value: Double) : JsonValue()
    data class JsonBool(val value: Boolean) : JsonValue()
    object JsonNull : JsonValue()
}

// ---- convenience accessors, so callers write recipe.json["exposure"].asFloat() rather than a
// when-block per field ----

fun JsonValue.asObject(): Map<String, JsonValue> = (this as? JsonValue.JsonObject)?.entries.orEmpty()
fun JsonValue.asArray(): List<JsonValue> = (this as? JsonValue.JsonArray)?.items.orEmpty()
fun JsonValue.asFloat(default: Float = 0f): Float = (this as? JsonValue.JsonNumber)?.value?.toFloat() ?: default
fun JsonValue.asInt(default: Int = 0): Int = (this as? JsonValue.JsonNumber)?.value?.toInt() ?: default
fun JsonValue.asLong(default: Long = 0L): Long = (this as? JsonValue.JsonNumber)?.value?.toLong() ?: default
fun JsonValue.asString(default: String = ""): String = (this as? JsonValue.JsonString)?.value ?: default
fun JsonValue.asBool(default: Boolean = false): Boolean = (this as? JsonValue.JsonBool)?.value ?: default
operator fun JsonValue.get(key: String): JsonValue = asObject()[key] ?: JsonValue.JsonNull

fun jsonObjectOf(vararg pairs: Pair<String, JsonValue>): JsonValue = JsonValue.JsonObject(linkedMapOf(*pairs))
fun jsonArrayOf(items: List<JsonValue>): JsonValue = JsonValue.JsonArray(items)
fun Float.toJson(): JsonValue = JsonValue.JsonNumber(this.toDouble())
fun Int.toJson(): JsonValue = JsonValue.JsonNumber(this.toDouble())
fun Long.toJson(): JsonValue = JsonValue.JsonNumber(this.toDouble())
fun String.toJson(): JsonValue = JsonValue.JsonString(this)
fun Boolean.toJson(): JsonValue = JsonValue.JsonBool(this)

/** Renders [this] as compact JSON text. */
fun JsonValue.stringify(): String = buildString { writeTo(this) }

private fun JsonValue.writeTo(out: StringBuilder) {
    when (this) {
        is JsonValue.JsonObject -> {
            out.append('{')
            entries.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) out.append(',')
                out.append('"').append(escape(key)).append("\":")
                value.writeTo(out)
            }
            out.append('}')
        }
        is JsonValue.JsonArray -> {
            out.append('[')
            items.forEachIndexed { index, item ->
                if (index > 0) out.append(',')
                item.writeTo(out)
            }
            out.append(']')
        }
        is JsonValue.JsonString -> out.append('"').append(escape(value)).append('"')
        is JsonValue.JsonNumber -> out.append(
            // Whole numbers print without a trailing ".0" -- MediaId and dateModifiedSeconds
            // round-trip as JSON integers, which is what every other tool reading this file
            // would expect for what are, semantically, integer fields.
            if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString(),
        )
        is JsonValue.JsonBool -> out.append(value.toString())
        JsonValue.JsonNull -> out.append("null")
    }
}

private fun escape(text: String): String = buildString {
    text.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }
}

/** Parses [text] as JSON, or null if it is not well-formed. */
fun parseJson(text: String): JsonValue? = runCatching { JsonParser(text).parseDocument() }.getOrNull()

private class JsonParser(private val text: String) {
    private var pos = 0

    fun parseDocument(): JsonValue {
        val value = parseValue()
        skipWhitespace()
        require(pos == text.length) { "Trailing content after JSON value at $pos" }
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        return when (val c = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.JsonString(parseStringLiteral())
            't' -> literal("true", JsonValue.JsonBool(true))
            'f' -> literal("false", JsonValue.JsonBool(false))
            'n' -> literal("null", JsonValue.JsonNull)
            else -> if (c == '-' || c.isDigit()) parseNumber() else error("Unexpected character '$c' at $pos")
        }
    }

    private fun parseObject(): JsonValue {
        expect('{')
        val entries = linkedMapOf<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') { pos++; return JsonValue.JsonObject(entries) }
        while (true) {
            skipWhitespace()
            val key = parseStringLiteral()
            skipWhitespace()
            expect(':')
            entries[key] = parseValue()
            skipWhitespace()
            when (val c = peek()) {
                ',' -> pos++
                '}' -> { pos++; return JsonValue.JsonObject(entries) }
                else -> error("Expected ',' or '}' at $pos, found '$c'")
            }
        }
    }

    private fun parseArray(): JsonValue {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (peek() == ']') { pos++; return JsonValue.JsonArray(items) }
        while (true) {
            items += parseValue()
            skipWhitespace()
            when (val c = peek()) {
                ',' -> pos++
                ']' -> { pos++; return JsonValue.JsonArray(items) }
                else -> error("Expected ',' or ']' at $pos, found '$c'")
            }
        }
    }

    private fun parseStringLiteral(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            val c = text[pos++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    val escaped = text[pos++]
                    when (escaped) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'u' -> {
                            val hex = text.substring(pos, pos + 4)
                            pos += 4
                            out.append(hex.toInt(16).toChar())
                        }
                        else -> error("Unknown escape '\\$escaped' at $pos")
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun parseNumber(): JsonValue {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
        return JsonValue.JsonNumber(text.substring(start, pos).toDouble())
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        require(text.regionMatches(pos, word, 0, word.length)) { "Expected '$word' at $pos" }
        pos += word.length
        return value
    }

    private fun peek(): Char {
        if (pos >= text.length) error("Unexpected end of JSON at $pos")
        return text[pos]
    }

    private fun expect(c: Char) {
        require(peek() == c) { "Expected '$c' at $pos, found '${peek()}'" }
        pos++
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }
}
