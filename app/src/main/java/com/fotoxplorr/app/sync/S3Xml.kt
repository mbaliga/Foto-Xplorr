package com.fotoxplorr.app.sync

import java.util.Locale

/** One object of a ListObjectsV2 answer. */
data class S3Object(val key: String, val size: Long)

data class S3Listing(
    val objects: List<S3Object>,
    val commonPrefixes: List<String>,
    val nextToken: String?,
)

/**
 * Tolerant readers for the few S3 XML answers the provider needs: a listing, a multipart upload id,
 * and the error code. Scanners over tags by local name (a default namespace or none, `Contents` before
 * or after other fields), no DTD or entity machinery. Bad input yields empty results, not exceptions.
 */
object S3Xml {
    fun parseListing(xml: String): S3Listing {
        val objects = ArrayList<S3Object>()
        val prefixes = ArrayList<String>()
        for (block in blocks(xml, "Contents")) {
            val key = tag(block, "Key") ?: continue
            objects += S3Object(key, tag(block, "Size")?.trim()?.toLongOrNull() ?: -1L)
        }
        for (block in blocks(xml, "CommonPrefixes")) tag(block, "Prefix")?.let { prefixes += it }
        val truncated = tag(xml, "IsTruncated")?.trim()?.lowercase(Locale.ROOT) == "true"
        val token = if (truncated) tag(xml, "NextContinuationToken") else null
        return S3Listing(objects, prefixes, token)
    }

    fun uploadId(xml: String): String? = tag(xml, "UploadId")?.trim()?.takeIf { it.isNotEmpty() }

    fun errorCode(xml: String): String? = if (xml.contains("<Error", ignoreCase = false)) tag(xml, "Code")?.trim() else null

    fun errorMessage(xml: String): String? = tag(xml, "Message")?.trim()

    fun completeBody(parts: List<Pair<Int, String>>): String = buildString {
        append("<CompleteMultipartUpload>")
        parts.forEach { (number, etag) -> append("<Part><PartNumber>").append(number).append("</PartNumber><ETag>").append(escape(etag)).append("</ETag></Part>") }
        append("</CompleteMultipartUpload>")
    }

    private fun blocks(xml: String, name: String): List<String> {
        val out = ArrayList<String>()
        var from = 0
        val open = "<$name>"
        val close = "</$name>"
        while (true) {
            val start = xml.indexOf(open, from)
            if (start < 0) break
            val end = xml.indexOf(close, start)
            if (end < 0) break
            out += xml.substring(start + open.length, end)
            from = end + close.length
        }
        return out
    }

    private fun tag(xml: String, name: String): String? {
        val start = xml.indexOf("<$name>")
        if (start < 0) return null
        val end = xml.indexOf("</$name>", start)
        if (end < 0) return null
        return WebDavXml.decodeEntities(xml.substring(start + name.length + 2, end))
    }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
