package com.fotoxplorr.app.sync

import java.util.Locale

/**
 * A small JSON reader and string writer for the two cloud APIs (Drive, Dropbox) and the OAuth token
 * endpoints. Why not a library: the answers are small and flat, nothing here may add a dependency, and
 * `org.json` is not usable in the plain JVM tests. Values read: objects -> Map, arrays -> List, strings,
 * Long for integers, Double for other numbers, Boolean, null. Malformed text is a [SyncException]
 * ([SyncFailure.REJECTED]), never a half-read value.
 */
object MiniJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipSpace()
        val value = reader.value(0)
        reader.skipSpace()
        if (!reader.atEnd()) reader.fail()
        return value
    }

    /** The object at the top of [text], or null when the text is not a JSON object. */
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?>? = try {
        parse(text) as? Map<String, Any?>
    } catch (e: SyncException) {
        null
    }

    /** [text] as a JSON string literal. With [asciiOnly] every non-ASCII character is written as \\uXXXX (Dropbox-API-Arg header). */
    fun quote(text: String, asciiOnly: Boolean = false): String = buildString {
        append('"')
        for (ch in text) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' || (asciiOnly && ch.code > 126) -> append("\\u").append("%04x".format(Locale.ROOT, ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }

    @Suppress("UNCHECKED_CAST")
    fun Any?.obj(key: String): Map<String, Any?>? = (this as? Map<String, Any?>)?.get(key) as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun Any?.list(key: String): List<Any?>? = (this as? Map<String, Any?>)?.get(key) as? List<Any?>

    fun Any?.str(key: String): String? = (this as? Map<*, *>)?.get(key) as? String

    fun Any?.long(key: String): Long? = when (val v = (this as? Map<*, *>)?.get(key)) {
        is Long -> v
        is Double -> v.toLong()
        is String -> v.toLongOrNull() // Drive reports size as a string
        else -> null
    }

    private class Reader(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        fun fail(): Nothing = throw SyncException(SyncFailure.REJECTED, "The server's answer could not be read")
        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(depth: Int): Any? {
            if (depth > 32 || atEnd()) fail()
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail()
            i += word.length
            return v
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail()
        }

        private fun str(): String {
            i++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail()
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) fail()
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                out.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail())
                                i += 4
                            }
                            else -> fail()
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skipSpace()
            if (!atEnd() && s[i] == ']') { i++; return out }
            while (true) {
                skipSpace()
                out += value(depth + 1)
                skipSpace()
                if (atEnd()) fail()
                when (s[i++]) {
                    ',' -> Unit
                    ']' -> return out
                    else -> fail()
                }
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (!atEnd() && s[i] == '}') { i++; return out }
            while (true) {
                skipSpace()
                if (atEnd() || s[i] != '"') fail()
                val key = str()
                skipSpace()
                if (atEnd() || s[i++] != ':') fail()
                skipSpace()
                out[key] = value(depth + 1)
                skipSpace()
                if (atEnd()) fail()
                when (s[i++]) {
                    ',' -> Unit
                    '}' -> return out
                    else -> fail()
                }
            }
        }
    }
}
