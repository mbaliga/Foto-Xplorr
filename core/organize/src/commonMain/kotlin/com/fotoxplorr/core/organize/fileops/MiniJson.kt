package com.fotoxplorr.core.organize.fileops

/** Just enough JSON for the trash manifest (no serialization dependency in this module). */
internal object MiniJson {
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> {
                    sb.append("\\u")
                    val h = c.code.toString(16)
                    repeat(4 - h.length) { sb.append('0') }
                    sb.append(h)
                }
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** Parses to Map<String, Any?>, List<Any?>, String, Long, Boolean or null. Throws IllegalArgumentException. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        require(p.pos == text.length) { "trailing data" }
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWs()
            require(pos < s.length) { "unexpected end" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("bad char")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "bad literal" }
            pos += word.length
            return v
        }

        private fun num(): Long {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            return s.substring(start, pos).toLong()
        }

        private fun str(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < s.length) { "bad escape" }
                        when (val e = s[pos++]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'u' -> {
                                require(pos + 4 <= s.length) { "bad escape" }
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return out }
            while (true) {
                out.add(value())
                skipWs()
                require(pos < s.length) { "unterminated array" }
                if (s[pos] == ',') { pos++; continue }
                require(s[pos] == ']') { "bad array" }
                pos++
                return out
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                require(pos < s.length && s[pos] == '"') { "bad key" }
                val k = str()
                skipWs()
                require(pos < s.length && s[pos] == ':') { "missing colon" }
                pos++
                out[k] = value()
                skipWs()
                require(pos < s.length) { "unterminated object" }
                if (s[pos] == ',') { pos++; continue }
                require(s[pos] == '}') { "bad object" }
                pos++
                return out
            }
        }
    }
}
