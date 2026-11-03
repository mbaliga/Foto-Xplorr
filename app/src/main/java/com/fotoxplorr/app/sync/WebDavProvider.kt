package com.fotoxplorr.app.sync

import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.util.Base64
import java.util.Locale

/** What the person typed for a WebDAV / Nextcloud / ownCloud / NAS destination. The password is NOT here. */
data class WebDavSettings(
    val baseUrl: String,
    val username: String,
    /** Allow `http://` for an address on the person's own network (a NAS). Off by default. */
    val allowPlainHttpOnLocal: Boolean = false,
)

object WebDavUrl {
    /**
     * The base URL as the provider will use it: scheme defaulting to https, no credentials inside it,
     * no query or fragment, a trailing slash, the path percent-encoded once. Null when it is not a
     * usable address (empty, no host, another scheme, plain http not allowed for that host).
     */
    fun normalise(input: String, allowPlainHttpOnLocal: Boolean): String? {
        var text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() && it != ' ' }) return null
        if (!text.contains("://")) text = "https://$text"
        val uri = try {
            URI(text.replace(" ", "%20"))
        } catch (e: java.net.URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        if (uri.rawUserInfo != null) return null // credentials belong in the settings, not the address
        when (scheme) {
            "https" -> Unit
            "http" -> if (!(allowPlainHttpOnLocal && HostRules.isLocalHost(host))) return null
            else -> return null
        }
        val path = (uri.rawPath ?: "").let { if (it.endsWith("/")) it else "$it/" }
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "$scheme://${hostPart.lowercase(Locale.ROOT)}$port$path"
    }

    /** Percent-encodes each segment of [path] (UTF-8) so that it can follow a normalised base URL. */
    fun encodePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }

    internal fun encodeSegment(segment: String): String {
        val out = StringBuilder()
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val unreserved = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            if (unreserved) out.append(c.toChar()) else out.append('%').append("%02X".format(Locale.ROOT, c))
        }
        return out.toString()
    }
}

/**
 * WebDAV: Nextcloud, ownCloud, most NAS boxes, any server with a DAV share. Folders with MKCOL, files
 * with PUT (`If-None-Match: *` so an existing file is never replaced unless it is our own half-sent
 * one), listings and size checks with PROPFIND (depth 0 or 1, read by [WebDavXml]). Basic auth with
 * the app password. TLS only unless the person turned on plain http for a local address
 * ([HttpCaller]); redirects are followed only within the same host and never carry a body.
 *
 * Only the standard verbs above are used: there is no DELETE, MOVE or COPY anywhere in this class.
 * A PUT is not atomic on every server, which is why a half-sent file is tracked by the manifest's `B`
 * line instead of a temporary name. Never run against a real server.
 */
class WebDavProvider(
    private val settings: WebDavSettings,
    password: CharArray,
    transport: HttpTransport,
) : SyncProvider {
    private val base: String = WebDavUrl.normalise(settings.baseUrl, settings.allowPlainHttpOnLocal)
        ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.WEBDAV_BAD_ADDRESS)
    private val authorization: String = "Basic " + Base64.getEncoder().encodeToString(
        (settings.username + ":" + String(password)).toByteArray(Charsets.UTF_8),
    )
    private val caller = HttpCaller(transport, settings.allowPlainHttpOnLocal, followSameHostRedirects = true)

    init {
        password.fill('\u0000')
    }

    override val label: String get() = URI.create(base).host.orEmpty()
    override val needsNetwork: Boolean = true

    override fun testConnection(cancel: Cancellation): String {
        val response = propfind("", depth = 0, cancel = cancel)
        response.use {
            when {
                it.code == 207 || it.code == 200 -> return SyncCopy.connectedTo(label)
                it.code == 404 -> throw SyncException(SyncFailure.REJECTED, SyncCopy.WEBDAV_NOT_FOUND)
                else -> throw HttpStatus.failureFor(it.code, SyncCopy.WEBDAV_FAILED)
            }
        }
    }

    override fun stat(path: String): RemoteEntry? {
        propfind(path, 0, Cancellation.NEVER).use { response ->
            if (response.code == 404) return null
            if (response.code != 207 && response.code != 200) throw HttpStatus.failureFor(response.code, SyncCopy.WEBDAV_FAILED)
            val first = WebDavXml.parse(response.bodyText()).firstOrNull() ?: return null
            return RemoteEntry(path, first.contentLength, first.isCollection, first.lastModifiedMillis)
        }
    }

    override fun list(folder: String): List<RemoteEntry> {
        propfind(folder, 1, Cancellation.NEVER).use { response ->
            if (response.code == 404) return emptyList()
            if (response.code != 207 && response.code != 200) throw HttpStatus.failureFor(response.code, SyncCopy.WEBDAV_FAILED)
            val self = folder.trim('/')
            return WebDavXml.parse(response.bodyText()).mapNotNull { r ->
                val relative = WebDavXml.relativePath(base, r.href) ?: return@mapNotNull null
                if (relative == self) null else RemoteEntry(relative, r.contentLength, r.isCollection, r.lastModifiedMillis)
            }
        }
    }

    override fun ensureFolder(path: String) {
        var current = ""
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            request("MKCOL", urlOf(current, folder = true)).use { response ->
                // 405: it exists. 201: made. Some servers answer 301 for an existing collection, handled by the caller.
                if (response.code != 201 && response.code != 405 && response.code != 200 && response.code != 204) {
                    throw HttpStatus.failureFor(response.code, SyncCopy.WEBDAV_FAILED)
                }
            }
        }
    }

    override fun put(
        path: String,
        input: InputStream,
        length: Long,
        overwrite: Boolean,
        cancel: Cancellation,
        onBytes: (Long) -> Unit,
    ): RemoteEntry? {
        val counted = CountingInput(input, cancel, onBytes)
        val headers = ArrayList<Pair<String, String>>()
        headers += "Content-Type" to "application/octet-stream"
        if (!overwrite) headers += "If-None-Match" to "*"
        request("PUT", urlOf(path, folder = false), headers, bodyStream = counted, bodyLength = length, cancel = cancel).use { response ->
            when (response.code) {
                200, 201, 204 -> Unit
                412 -> throw SyncException(SyncFailure.REJECTED, "A file with this name is already at the destination")
                else -> throw HttpStatus.failureFor(response.code, SyncCopy.WEBDAV_FAILED)
            }
        }
        if (counted.count != length) throw SyncException(SyncFailure.TRANSIENT, "The file changed while it was being sent")
        return stat(path)
    }

    private fun propfind(path: String, depth: Int, cancel: Cancellation): HttpResponse =
        request(
            "PROPFIND",
            urlOf(path, folder = depth == 1 || path.isEmpty()),
            listOf("Depth" to depth.toString(), "Content-Type" to "application/xml"),
            bodyBytes = PROPFIND_BODY.toByteArray(Charsets.UTF_8),
            cancel = cancel,
        )

    private fun request(
        method: String,
        url: String,
        extra: List<Pair<String, String>> = emptyList(),
        bodyBytes: ByteArray? = null,
        bodyStream: InputStream? = null,
        bodyLength: Long = bodyBytes?.size?.toLong() ?: -1L,
        cancel: Cancellation = Cancellation.NEVER,
    ): HttpResponse {
        val headers = listOf("Authorization" to authorization, "User-Agent" to USER_AGENT) + extra
        return caller.call(HttpRequest(method, url, headers, bodyBytes, bodyStream, bodyLength), cancel)
    }

    private fun urlOf(path: String, folder: Boolean): String {
        val encoded = WebDavUrl.encodePath(path)
        return if (encoded.isEmpty()) base else base + encoded + if (folder) "/" else ""
    }

    private class CountingInput(
        source: InputStream,
        private val cancel: Cancellation,
        private val onBytes: (Long) -> Unit,
    ) : FilterInputStream(source) {
        var count = 0L
            private set

        override fun read(): Int {
            cancel.throwIfCancelled()
            val b = super.read()
            if (b >= 0) tick(1)
            return b
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            cancel.throwIfCancelled()
            val n = super.read(buffer, off, len)
            if (n > 0) tick(n)
            return n
        }

        private fun tick(n: Int) {
            count += n
            onBytes(count)
        }
    }

    private companion object {
        const val USER_AGENT = "Fotoz-sync"
        const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"
    }
}
