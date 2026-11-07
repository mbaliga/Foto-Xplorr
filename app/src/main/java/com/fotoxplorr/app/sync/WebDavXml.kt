package com.fotoxplorr.app.sync

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One `<response>` of a multistatus answer, raw. */
data class WebDavResponse(
    val href: String,
    val isCollection: Boolean,
    val contentLength: Long,
    val lastModifiedMillis: Long?,
)

/**
 * A tolerant reader for PROPFIND answers. Servers disagree about prefixes (`d:`, `D:`, none, a default
 * namespace), about absolute URLs versus paths in `<href>`, about the case of the element names'
 * prefix, about whitespace and about stray bytes; the reader ignores prefixes entirely (the local
 * name decides), skips what it does not understand and never throws on bad input (a document with no
 * `<response>` is an empty list). It is a scanner, not an XML parser, so it has no entity expansion
 * beyond the five predefined ones and numeric references, and cannot be driven into reading a DTD or
 * an external entity.
 */
object WebDavXml {
    fun parse(xml: String): List<WebDavResponse> {
        val out = ArrayList<WebDavResponse>()
        var depthInResponse = false
        var href: String? = null
        var collection = false
        var length = -1L
        var modified: Long? = null
        val text = StringBuilder()
        var index = 0
        while (index < xml.length) {
            val open = xml.indexOf('<', index)
            if (open < 0) break
            text.append(xml, index, open)
            if (xml.startsWith("<!--", open)) {
                index = xml.indexOf("-->", open).let { if (it < 0) xml.length else it + 3 }
                continue
            }
            if (xml.startsWith("<![CDATA[", open)) {
                val end = xml.indexOf("]]>", open).let { if (it < 0) xml.length else it }
                text.append(xml, open + 9, end)
                index = (end + 3).coerceAtMost(xml.length)
                continue
            }
            val close = xml.indexOf('>', open)
            if (close < 0) break
            val tag = xml.substring(open + 1, close)
            index = close + 1
            if (tag.startsWith("?") || tag.startsWith("!")) {
                text.setLength(0)
                continue
            }
            val closing = tag.startsWith("/")
            val selfClosing = tag.endsWith("/")
            val name = localName(tag.trim('/', ' ', '\n', '\r', '\t'))
            when {
                !closing && name == "response" && !selfClosing -> {
                    depthInResponse = true
                    href = null
                    collection = false
                    length = -1L
                    modified = null
                }
                closing && name == "response" -> {
                    val h = href
                    if (depthInResponse && h != null) out += WebDavResponse(h, collection, length, modified)
                    depthInResponse = false
                }
                !closing && name == "collection" && depthInResponse -> collection = true
                closing && depthInResponse -> {
                    val value = decodeEntities(text.toString()).trim()
                    when (name) {
                        "href" -> if (href == null && value.isNotEmpty()) href = value
                        "getcontentlength" -> length = value.toLongOrNull() ?: -1L
                        "getlastmodified" -> modified = parseHttpDate(value)
                    }
                }
            }
            text.setLength(0)
        }
        return out
    }

    private fun localName(tag: String): String {
        val first = tag.split(' ', '\n', '\r', '\t', limit = 2)[0].removePrefix("/")
        return first.substringAfter(':').lowercase(Locale.ROOT)
    }

    internal fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '&') {
                val semi = text.indexOf(';', i)
                if (semi in i + 2..i + 10) {
                    val entity = text.substring(i + 1, semi)
                    val decoded = when {
                        entity == "amp" -> "&"
                        entity == "lt" -> "<"
                        entity == "gt" -> ">"
                        entity == "quot" -> "\""
                        entity == "apos" -> "'"
                        entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
                        entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
                        else -> null
                    }
                    if (decoded != null) {
                        out.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun parseHttpDate(value: String): Long? = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(value)?.time
    } catch (e: java.text.ParseException) {
        null
    }

    /**
     * [href] (a path or an absolute URL, percent-encoded) as a path relative to [baseUrl]'s path,
     * decoded, without leading or trailing slash; null when it lies outside the base or tries to
     * climb out of it.
     */
    fun relativePath(baseUrl: String, href: String): String? {
        val basePath = decodePath(URI.create(baseUrl).rawPath.orEmpty()).trim('/')
        val rawHref = if (href.contains("://")) runCatching { URI.create(href).rawPath }.getOrNull() ?: return null else href.substringBefore('?')
        val decoded = decodePath(rawHref).trim('/')
        val relative = when {
            basePath.isEmpty() -> decoded
            decoded == basePath -> ""
            decoded.startsWith("$basePath/") -> decoded.substring(basePath.length + 1)
            else -> return null
        }
        if (relative.isEmpty()) return ""
        val parts = relative.split('/')
        if (parts.any { it == ".." || it == "." || it.isEmpty() }) return null
        return relative
    }

    internal fun decodePath(raw: String): String {
        if (!raw.contains('%')) return raw
        val bytes = java.io.ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '%' && i + 2 < raw.length) {
                val v = raw.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    bytes.write(v)
                    i += 3
                    continue
                }
            }
            bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }
}
