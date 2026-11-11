package com.fotoxplorr.core.formats.composite

import com.fotoxplorr.core.formats.raw.Reader

/**
 * Just enough XMP reading for composite media, in common code (core/metadata's `XmpPacket` is
 * JVM-only, org.w3c.dom). It reads the RDF as text: namespace prefixes are taken from the packet's
 * own `xmlns:` declarations, so `GCamera:`, `Camera:` or any other prefix a writer chose all work,
 * and a property is found in both of its forms, the attribute (`p:Name="v"`) and the element
 * (`<p:Name>v</p:Name>`).
 *
 * It is not an XML parser: entities are not expanded and CDATA is not understood. Every value it
 * is used for here is a number or a short ASCII word, which is all the composite readers need.
 */
internal class XmpLite(val text: String) {

    private val prefixesByUri: Map<String, Set<String>> = run {
        val out = HashMap<String, MutableSet<String>>()
        for (m in DECLARATION.findAll(text)) out.getOrPut(m.groupValues[2]) { HashSet() } += m.groupValues[1]
        out
    }

    /** The prefixes the packet binds to [namespaceUri] (usually one). */
    fun prefixes(namespaceUri: String): Set<String> = prefixesByUri[namespaceUri].orEmpty()

    /** The first value of [namespaceUri]:[localName] anywhere in the packet, trimmed; null when absent. */
    fun value(namespaceUri: String, localName: String): String? = valueIn(text, namespaceUri, localName)

    /** As [value], but only inside [fragment] (one element's text, see [elements]). */
    fun valueIn(fragment: String, namespaceUri: String, localName: String): String? {
        for (prefix in prefixes(namespaceUri)) {
            val name = Regex.escape("$prefix:$localName")
            Regex("""(?:^|[\s<])$name\s*=\s*(["'])(.*?)\1""").find(fragment)?.let { return it.groupValues[2].trim() }
            Regex("""<$name(?:\s[^>]*)?>([^<]*)</$name\s*>""").find(fragment)?.let { return it.groupValues[1].trim() }
        }
        return null
    }

    /**
     * Each `<p:localName ...>` element of [namespaceUri], as its whole text: up to `/>` when it is
     * self-closing, else up to its own closing tag. Nested elements of the same name are not
     * expected (Container:Item never nests) and would end the outer one early.
     */
    fun elements(namespaceUri: String, localName: String): List<String> {
        val out = ArrayList<String>()
        for (prefix in prefixes(namespaceUri)) {
            val open = "<$prefix:$localName"
            val close = "</$prefix:$localName"
            var from = 0
            while (out.size < MAX_ELEMENTS) {
                val start = text.indexOf(open, from)
                if (start < 0) break
                val after = start + open.length
                // The next character must end the name, or this is a longer name sharing a prefix.
                if (after < text.length && !(text[after].isWhitespace() || text[after] == '>' || text[after] == '/')) {
                    from = after
                    continue
                }
                val tagEnd = text.indexOf('>', after)
                if (tagEnd < 0) break
                val end = if (text[tagEnd - 1] == '/') {
                    tagEnd + 1
                } else {
                    val c = text.indexOf(close, tagEnd)
                    if (c < 0) break
                    (text.indexOf('>', c).takeIf { it >= 0 } ?: break) + 1
                }
                out += text.substring(start, end)
                from = end
            }
        }
        return out
    }

    companion object {
        const val GCAMERA_NS = "http://ns.google.com/photos/1.0/camera/"
        const val CONTAINER_NS = "http://ns.google.com/photos/1.0/container/"
        const val ITEM_NS = "http://ns.google.com/photos/1.0/container/item/"

        /** How far into a file the packet is looked for. A JPEG's XMP is in its first segments. */
        const val HEAD_BYTES = 512 * 1024

        private const val MAX_ELEMENTS = 64
        private const val MAX_PACKET_CHARS = 256 * 1024

        private val DECLARATION = Regex("""xmlns:([A-Za-z_][\w.-]*)\s*=\s*["']([^"']*)["']""")
        private val OPEN = "<x:xmpmeta".encodeToByteArray()
        private val CLOSE = "</x:xmpmeta>".encodeToByteArray()

        /**
         * The first XMP packet in the first [HEAD_BYTES] of the source, or null. Found by its
         * `<x:xmpmeta` text rather than by walking JPEG segments, so the same reader serves a HEIC
         * whose XMP item sits early in the file. A packet that does not close in that window, or is
         * absurdly long, is ignored.
         */
        fun find(r: Reader): XmpLite? {
            val n = minOf(r.length, HEAD_BYTES.toLong()).toInt()
            if (n < OPEN.size) return null
            val head = r.bytes(0, n) ?: return null
            val start = indexOf(head, OPEN, 0)
            if (start < 0) return null
            val close = indexOf(head, CLOSE, start)
            if (close < 0 || close + CLOSE.size - start > MAX_PACKET_CHARS) return null
            return XmpLite(head.decodeToString(start, close + CLOSE.size))
        }

        internal fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
            if (needle.isEmpty()) return -1
            var i = maxOf(0, from)
            val last = hay.size - needle.size
            outer@ while (i <= last) {
                for (j in needle.indices) {
                    if (hay[i + j] != needle[j]) {
                        i++
                        continue@outer
                    }
                }
                return i
            }
            return -1
        }
    }
}
