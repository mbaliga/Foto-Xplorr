package com.fotoxplorr.app.services.catalogue

/**
 * A tiny JSON reader with no Android types, so the catalogue parser runs in plain JVM tests and can
 * be lifted unchanged into a shared library. Values come back as Map<String, Any?>, List<Any?>,
 * String, Long (integers), Double (everything else numeric), Boolean or null.
 */
internal object MiniJson {
    class JsonException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) throw JsonException("Unexpected text after the JSON value")
        return value
    }

    private class Reader(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        fun skipWhitespace() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun fail(what: String): Nothing = throw JsonException("$what at $i")

        fun readValue(): Any? {
            if (atEnd()) fail("Unexpected end")
            return when (val c = s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("Unexpected '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Bad literal")
            i += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            i++
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[i] != '"') fail("Expected a key")
                val key = readString()
                skipWhitespace()
                if (atEnd() || s[i] != ':') fail("Expected ':'")
                i++
                skipWhitespace()
                map[key] = readValue()
                skipWhitespace()
                if (atEnd()) fail("Unterminated object")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return map }
                fail("Expected ',' or '}'")
            }
        }

        private fun readArray(): List<Any?> {
            i++
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                skipWhitespace()
                list += readValue()
                skipWhitespace()
                if (atEnd()) fail("Unterminated array")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return list }
                fail("Expected ',' or ']'")
            }
        }

        private fun readString(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("Bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Bad \\u escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("Bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val text = s.substring(start, i)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: fail("Bad number")
        }
    }
}
