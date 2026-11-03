package com.fotoxplorr.app.sync

import com.fotoxplorr.app.sync.MiniJson.long
import com.fotoxplorr.app.sync.MiniJson.obj
import com.fotoxplorr.app.sync.MiniJson.str
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/** The Dropbox endpoints. Production is fixed; tests point the same paths at a local server. */
class DropboxApi(
    val rpc: String = "https://api.dropboxapi.com/2",
    val content: String = "https://content.dropboxapi.com/2",
) {
    companion object {
        val PRODUCTION = DropboxApi()
    }
}

/**
 * Dropbox's content hash (https://www.dropbox.com/developers/reference/content-hash): SHA-256 of each 4 MiB
 * block, then SHA-256 over the concatenated block digests, as lower-case hex. Fed as the bytes go by.
 */
class DropboxContentHash {
    private val blocks = MessageDigest.getInstance("SHA-256")
    private var block = MessageDigest.getInstance("SHA-256")
    private var inBlock = 0

    fun update(buffer: ByteArray, offset: Int, length: Int) {
        var off = offset
        var left = length
        while (left > 0) {
            val take = minOf(left, BLOCK - inBlock)
            block.update(buffer, off, take)
            inBlock += take
            off += take
            left -= take
            if (inBlock == BLOCK) closeBlock()
        }
    }

    fun hex(): String {
        if (inBlock > 0) closeBlock()
        return blocks.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun closeBlock() {
        blocks.update(block.digest())
        block = MessageDigest.getInstance("SHA-256")
        inBlock = 0
    }

    private companion object {
        const val BLOCK = 4 * 1024 * 1024
    }
}

/**
 * Dropbox (API v2) as a [SyncProvider], for an app with "App folder" access: every path is inside the app's own
 * folder in the person's Dropbox, which is all the app can ever see. Same semantics as the other providers:
 * one-way, no delete (the delete and move calls are never made), exists/stat/list for the manifest.
 *
 * Files up to [SINGLE_MAX] go in one `files/upload` (streamed, never held in memory); larger ones through an upload
 * session (`start`, `append_v2`, `finish`) in [CHUNK]-sized pieces. The session id and the offset Dropbox has
 * acknowledged are kept for the run: when the engine retries a half-sent file the provider reads past the bytes
 * Dropbox already holds and carries on; on `incorrect_offset` it adopts the offset Dropbox names. Not kept across
 * runs. Folders: `create_folder_v2` with autorename OFF; "already exists as a folder" counts as done, as a file it is
 * refused. Existence: `get_metadata` (a `not_found` is "no"). After an upload the content hash Dropbox reports must
 * equal the one computed over the bytes handed over, else the copy is not believed.
 *
 * Errors: 401 renews the token once; 409 is read by its summary (`not_found`, `conflict`, `insufficient_space`);
 * 429 carries Retry-After (the engine waits at least that long, up to two minutes); 5xx is transient. Dropbox
 * paths are case-insensitive; the engine's unique-name step already compares that way. Never run against a real Dropbox.
 */
class DropboxProvider(
    private val appKey: String,
    refreshToken: CharArray,
    transport: HttpTransport,
    private val api: DropboxApi = DropboxApi.PRODUCTION,
    private val clock: () -> Long = System::currentTimeMillis,
    private val specFor: (SyncDestinationKind) -> OAuthSpec? = OAuthSpecs::forKind,
    allowPlainHttpOnLocal: Boolean = false,
) : SyncProvider {
    private val http: AuthedHttp
    private val knownFolders = HashSet<String>()
    private val sessions = HashMap<String, Session>()

    init {
        val spec = specFor(SyncDestinationKind.DROPBOX) ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.NO_DESTINATION)
        val caller = HttpCaller(transport, allowPlainHttpOnLocal, followSameHostRedirects = true)
        val tokenCaller = HttpCaller(transport, allowPlainHttpOnLocal, followSameHostRedirects = false)
        http = AuthedHttp(caller, OAuthSession(OAuthClient(spec, appKey, tokenCaller, clock), refreshToken, clock))
    }

    override val label: String get() = "Dropbox"
    override val needsNetwork: Boolean = true

    private class Session(val id: String, var offset: Long, val length: Long)

    private class Meta(val isFolder: Boolean, val name: String, val size: Long, val hash: String?, val modifiedMillis: Long?)

    override fun testConnection(cancel: Cancellation): String {
        rpc("users/get_current_account", null, cancel).use { response ->
            if (response.code != 200) throw failure(response, SyncCopy.DROPBOX_FAILED)
            return SyncCopy.connectedTo(MiniJson.parseObject(response.bodyText()).str("email") ?: label)
        }
    }

    override fun stat(path: String): RemoteEntry? {
        rpc("files/get_metadata", """{"path":${MiniJson.quote(dbx(path), true)}}""").use { response ->
            if (response.code != 200) {
                val text = textOf(response)
                if (response.code == 409 && isNotFound(text)) return null
                throw failure(response, SyncCopy.DROPBOX_FAILED, text)
            }
            return entryOf(path, metaOf(MiniJson.parseObject(response.bodyText())))
        }
    }

    override fun list(folder: String): List<RemoteEntry> {
        val clean = folder.trim('/')
        val out = ArrayList<RemoteEntry>()
        var endpoint = "files/list_folder"
        var body = """{"path":${MiniJson.quote(if (clean.isEmpty()) "" else "/$clean", true)},"recursive":false,"limit":2000}"""
        while (true) {
            var more = false
            rpc(endpoint, body).use { response ->
                if (response.code != 200) {
                    val text = textOf(response)
                    if (response.code == 409 && isNotFound(text)) return emptyList()
                    throw failure(response, SyncCopy.DROPBOX_FAILED, text)
                }
                val json = MiniJson.parseObject(response.bodyText())
                (json?.get("entries") as? List<*>).orEmpty().forEach { raw ->
                    val meta = metaOf(raw as? Map<String, Any?>) ?: return@forEach
                    out += entryOf(if (clean.isEmpty()) meta.name else "$clean/${meta.name}", meta)
                }
                if (json?.get("has_more") == true) {
                    endpoint = "files/list_folder/continue"
                    body = """{"cursor":${MiniJson.quote(json.str("cursor").orEmpty(), true)}}"""
                    more = true
                }
            }
            if (!more) return out
        }
    }

    override fun ensureFolder(path: String) {
        var current = ""
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            if (current in knownFolders) continue
            rpc("files/create_folder_v2", """{"path":${MiniJson.quote(dbx(current), true)},"autorename":false}""").use { response ->
                if (response.code != 200) {
                    val text = textOf(response)
                    if (!(response.code == 409 && summaryOf(text).startsWith("path/conflict/folder"))) throw failure(response, SyncCopy.DROPBOX_FAILED, text)
                }
            }
            knownFolders += current
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
        val hash = DropboxContentHash()
        val watched = Watching(input, cancel, hash)
        val meta = if (length <= SINGLE_MAX) singleUpload(path, watched, length, overwrite, cancel, onBytes)
        else sessionUpload(path, watched, length, overwrite, cancel, onBytes)
        if (meta.hash != null && !meta.hash.equals(hash.hex(), ignoreCase = true)) {
            throw SyncException(SyncFailure.TRANSIENT, "Dropbox kept different bytes than were sent")
        }
        return entryOf(path, meta)
    }

    private fun singleUpload(path: String, input: Watching, length: Long, overwrite: Boolean, cancel: Cancellation, onBytes: (Long) -> Unit): Meta {
        val arg = """{"path":${MiniJson.quote(dbx(path), true)},"mode":"${if (overwrite) "overwrite" else "add"}","autorename":false,"mute":true}"""
        input.onRead = onBytes
        content("files/upload", arg, null, input, length, cancel).use { response ->
            if (response.code != 200) {
                val text = textOf(response)
                if (response.code == 409 && summaryOf(text).startsWith("path/conflict")) {
                    throw SyncException(SyncFailure.REJECTED, "A file with this name is already at the destination")
                }
                throw failure(response, SyncCopy.DROPBOX_FAILED, text)
            }
            if (input.count != length) throw SyncException(SyncFailure.TRANSIENT, "The file changed while it was being sent")
            return metaOf(MiniJson.parseObject(response.bodyText())) ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.DROPBOX_FAILED)
        }
    }

    private fun sessionUpload(path: String, input: Watching, length: Long, overwrite: Boolean, cancel: Cancellation, onBytes: (Long) -> Unit): Meta {
        var session = sessions[path]?.takeIf { it.length == length }
        if (session != null && session.offset > 0) {
            skip(input, session.offset, cancel)
            onBytes(session.offset)
        }
        while (true) {
            cancel.throwIfCancelled()
            val offset = session?.offset ?: 0L
            val take = minOf(CHUNK.toLong(), length - offset).toInt()
            val chunk = readExactly(input, take, cancel)
            val last = offset + take >= length
            if (session == null) {
                // The first piece opens the session; a file larger than one piece cannot end here.
                content("files/upload_session/start", """{"close":false}""", chunk, null, 0, cancel).use { response ->
                    if (response.code != 200) throw failure(response, SyncCopy.DROPBOX_FAILED)
                    val id = MiniJson.parseObject(response.bodyText()).str("session_id") ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.DROPBOX_FAILED)
                    session = Session(id, take.toLong(), length).also { sessions[path] = it }
                }
                onBytes(take.toLong())
                continue
            }
            val live = session!!
            val cursor = """{"session_id":${MiniJson.quote(live.id, true)},"offset":$offset}"""
            if (!last) {
                content("files/upload_session/append_v2", """{"cursor":$cursor,"close":false}""", chunk, null, 0, cancel).use { response ->
                    if (response.code != 200) {
                        val text = textOf(response)
                        if (response.code == 409) adoptOffset(path, live, text)
                        throw failure(response, SyncCopy.DROPBOX_FAILED, text)
                    }
                }
                live.offset = offset + take
                onBytes(live.offset)
            } else {
                val commit = """{"path":${MiniJson.quote(dbx(path), true)},"mode":"${if (overwrite) "overwrite" else "add"}","autorename":false,"mute":true}"""
                content("files/upload_session/finish", """{"cursor":$cursor,"commit":$commit}""", chunk, null, 0, cancel).use { response ->
                    if (response.code != 200) {
                        val text = textOf(response)
                        if (response.code == 409) {
                            adoptOffset(path, live, text)
                            if (summaryOf(text).contains("conflict")) throw SyncException(SyncFailure.REJECTED, "A file with this name is already at the destination")
                        }
                        throw failure(response, SyncCopy.DROPBOX_FAILED, text)
                    }
                    sessions.remove(path)
                    onBytes(length)
                    return metaOf(MiniJson.parseObject(response.bodyText())) ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.DROPBOX_FAILED)
                }
            }
        }
    }

    /** `incorrect_offset` names the right offset; keep it and make the engine try again. `not_found` drops the session. */
    private fun adoptOffset(path: String, session: Session, text: String) {
        val json = MiniJson.parseObject(text)
        val summary = json.str("error_summary").orEmpty()
        val correct = json.obj("error")?.long("correct_offset")
        if (summary.startsWith("incorrect_offset") && correct != null) {
            session.offset = correct
            throw SyncException(SyncFailure.TRANSIENT, "Dropbox is at a different place in the file. Trying again.")
        }
        if (summary.contains("not_found") || summary.contains("closed")) {
            sessions.remove(path)
            throw SyncException(SyncFailure.TRANSIENT, "The upload session ended. Trying again.")
        }
    }

    // ---- requests -------------------------------------------------------------------------------------

    private fun rpc(endpoint: String, jsonBody: String?, cancel: Cancellation = Cancellation.NEVER): HttpResponse =
        http.send(cancel) { token ->
            val headers = listOf("Authorization" to "Bearer $token", "User-Agent" to USER_AGENT)
            if (jsonBody == null) HttpRequest("POST", "${api.rpc}/$endpoint", headers, bodyBytes = ByteArray(0))
            else HttpRequest("POST", "${api.rpc}/$endpoint", headers + ("Content-Type" to "application/json"), bodyBytes = jsonBody.toByteArray(Charsets.UTF_8))
        }

    /** A content-endpoint call: the argument travels in a header (ASCII only, so non-ASCII is \u-escaped), the file in the body. */
    private fun content(endpoint: String, arg: String, bytes: ByteArray?, stream: InputStream?, streamLength: Long, cancel: Cancellation): HttpResponse =
        http.send(cancel) { token ->
            val headers = listOf(
                "Authorization" to "Bearer $token", "Dropbox-API-Arg" to arg,
                "Content-Type" to "application/octet-stream", "User-Agent" to USER_AGENT,
            )
            if (stream != null) HttpRequest("POST", "${api.content}/$endpoint", headers, bodyStream = stream, bodyLength = streamLength)
            else HttpRequest("POST", "${api.content}/$endpoint", headers, bodyBytes = bytes ?: ByteArray(0))
        }

    // ---- answers --------------------------------------------------------------------------------------

    private fun dbx(path: String) = "/" + path.trim('/')

    private fun metaOf(raw: Map<String, Any?>?): Meta? {
        val tag = raw.str(".tag") ?: return null
        if (tag != "file" && tag != "folder") return null
        return Meta(
            isFolder = tag == "folder",
            name = raw.str("name").orEmpty(),
            size = if (tag == "folder") -1L else raw.long("size") ?: -1L,
            hash = raw.str("content_hash"),
            modifiedMillis = raw.str("server_modified")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        )
    }

    private fun entryOf(path: String, meta: Meta?): RemoteEntry =
        if (meta == null) throw SyncException(SyncFailure.REJECTED, SyncCopy.DROPBOX_FAILED) else RemoteEntry(path, meta.size, meta.isFolder, meta.modifiedMillis)

    /** The body of a non-200 answer, read ONCE (a stream cannot be read twice); the helpers below take this text. */
    private fun textOf(response: HttpResponse): String = runCatching { response.bodyText() }.getOrDefault("")

    private fun summaryOf(text: String): String = MiniJson.parseObject(text).str("error_summary").orEmpty()

    private fun isNotFound(text: String): Boolean = summaryOf(text).let { it.startsWith("path/not_found") || it.startsWith("path/not_folder") }

    private fun failure(response: HttpResponse, what: String, text: String = textOf(response)): SyncException {
        val code = response.code
        val json = MiniJson.parseObject(text)
        val summary = json.str("error_summary").orEmpty()
        return when {
            code == 429 -> {
                val seconds = response.header("retry-after")?.trim()?.toLongOrNull() ?: json.obj("error")?.long("retry_after")
                if (seconds != null) SyncRateLimited(SyncCopy.DROPBOX_BUSY, seconds * 1000L) else SyncException(SyncFailure.TRANSIENT, SyncCopy.DROPBOX_BUSY)
            }
            summary.contains("insufficient_space") -> SyncException(SyncFailure.NO_SPACE, SyncCopy.DROPBOX_FULL)
            summary.startsWith("missing_scope") -> SyncException(SyncFailure.AUTH, SyncCopy.DROPBOX_SCOPES)
            code == 403 -> SyncException(SyncFailure.AUTH, SyncCopy.DROPBOX_ACCESS_REFUSED)
            code == 409 -> SyncException(SyncFailure.REJECTED, SyncCopy.DROPBOX_FAILED + " (HTTP 409)")
            else -> HttpStatus.failureFor(code, what)
        }
    }

    // ---- streams --------------------------------------------------------------------------------------

    /** Counts and hashes what is read, checks for cancel, and reports progress. */
    private class Watching(source: InputStream, private val cancel: Cancellation, private val hash: DropboxContentHash) : FilterInputStream(source) {
        var count = 0L
            private set
        var onRead: (Long) -> Unit = {}

        override fun read(): Int {
            cancel.throwIfCancelled()
            val b = super.read()
            if (b >= 0) {
                hash.update(byteArrayOf(b.toByte()), 0, 1)
                count++
                onRead(count)
            }
            return b
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            cancel.throwIfCancelled()
            val n = super.read(buffer, off, len)
            if (n > 0) {
                hash.update(buffer, off, n)
                count += n
                onRead(count)
            }
            return n
        }
    }

    private fun readExactly(input: InputStream, count: Int, cancel: Cancellation): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            cancel.throwIfCancelled()
            val n = input.read(buffer, read, count - read)
            if (n < 0) throw SyncException(SyncFailure.TRANSIENT, "The file changed while it was being sent")
            read += n
        }
        return buffer
    }

    private fun skip(input: InputStream, count: Long, cancel: Cancellation) {
        var left = count
        val scratch = ByteArray(64 * 1024)
        while (left > 0) {
            cancel.throwIfCancelled()
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n < 0) throw SyncException(SyncFailure.TRANSIENT, "The file changed while it was being sent")
            left -= n
        }
    }

    private companion object {
        const val USER_AGENT = "Fotoz-sync"

        /** Dropbox allows 150 MB in one `files/upload`; stay under it. */
        const val SINGLE_MAX = 140L * 1000 * 1000
        const val CHUNK = 8 * 1024 * 1024
    }
}
