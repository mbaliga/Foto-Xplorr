package com.fotoxplorr.app.sync

import com.fotoxplorr.app.sync.MiniJson.long
import com.fotoxplorr.app.sync.MiniJson.obj
import com.fotoxplorr.app.sync.MiniJson.str
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/** The Drive endpoints. Production is fixed; tests point the same paths at a local server. */
class DriveApi(
    val files: String = "https://www.googleapis.com/drive/v3/files",
    val upload: String = "https://www.googleapis.com/upload/drive/v3/files",
    val about: String = "https://www.googleapis.com/drive/v3/about",
) {
    companion object {
        val PRODUCTION = DriveApi()
    }
}

/**
 * Google Drive (REST v3) as a [SyncProvider]. Same semantics as the other providers: one-way, no delete (Drive's
 * delete and trash calls are never made), exists/stat/list for the manifest.
 *
 * The scope is `drive.file` only: this app sees ONLY files and folders it created itself, never the rest of the
 * Drive. Two consequences, stated honestly. A "Fotoz" folder the person made by hand is invisible to the app, so
 * the app makes its own (a second folder of the same name; Drive allows duplicate names). And a file the app
 * uploaded keeps being visible to it across runs, which is what exists/stat need.
 *
 * Folders: found by name under their parent (`name = '..' and '<id>' in parents`), created when missing, ids kept
 * in memory for the run. Files up to [SIMPLE_MAX] go in one multipart request; larger ones in a RESUMABLE session
 * in [CHUNK]-sized pieces (a multiple of 256 KiB, as Drive requires). The session address is kept for the run:
 * when the engine retries a half-sent file the provider asks Drive how much it has and carries on from there,
 * reading past (not re-sending) the bytes Drive already holds. It is not kept across runs.
 *
 * After an upload the md5 Drive reports must equal the md5 of the bytes that were handed over, else the copy is not
 * believed ([SyncFailure.TRANSIENT]). Errors: 401 renews the token once, 403 is read by its reason (storage full ->
 * [SyncFailure.NO_SPACE], rate limits -> transient, API switched off -> a sentence naming the fix, else AUTH),
 * 429/5xx are transient and the engine's retry policy applies. Never run against a real Drive.
 */
class DriveProvider(
    private val clientId: String,
    refreshToken: CharArray,
    transport: HttpTransport,
    private val api: DriveApi = DriveApi.PRODUCTION,
    private val clock: () -> Long = System::currentTimeMillis,
    private val specFor: (SyncDestinationKind) -> OAuthSpec? = OAuthSpecs::forKind,
    allowPlainHttpOnLocal: Boolean = false,
) : SyncProvider {
    private val caller = HttpCaller(transport, allowPlainHttpOnLocal, followSameHostRedirects = true)
    /** For the upload session only: Drive answers 308 "Resume Incomplete", which must reach us as an answer, not be followed as a redirect. */
    private val sessionCaller = HttpCaller(transport, allowPlainHttpOnLocal, followSameHostRedirects = false)
    private val http: AuthedHttp
    private val folderIds = HashMap<String, String>().apply { put("", "root") }
    private val sessions = HashMap<String, UploadSession>()

    init {
        val spec = specFor(SyncDestinationKind.GDRIVE) ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.NO_DESTINATION)
        val tokenCaller = HttpCaller(transport, allowPlainHttpOnLocal, followSameHostRedirects = false)
        http = AuthedHttp(caller, OAuthSession(OAuthClient(spec, clientId, tokenCaller, clock), refreshToken, clock))
    }

    override val label: String get() = "Google Drive"
    override val needsNetwork: Boolean = true

    private class UploadSession(val uri: String, val length: Long)

    private class DriveFile(val id: String, val name: String, val isFolder: Boolean, val size: Long, val md5: String?, val modifiedMillis: Long?)

    override fun testConnection(cancel: Cancellation): String {
        val url = api.about + "?fields=" + OAuthForm.enc("user(displayName,emailAddress)")
        http.send(cancel) { token -> HttpRequest("GET", url, auth(token)) }.use { response ->
            if (response.code != 200) throw failure(response, SyncCopy.DRIVE_FAILED)
            val user = MiniJson.parseObject(response.bodyText()).obj("user")
            return SyncCopy.connectedTo(user.str("emailAddress") ?: label)
        }
    }

    override fun stat(path: String): RemoteEntry? {
        val (folder, name) = split(path)
        val parent = folderId(folder, create = false) ?: return null
        val found = find(parent, name, foldersOnly = false) ?: return null
        return entryOf(path, found)
    }

    override fun list(folder: String): List<RemoteEntry> {
        val id = folderId(folder.trim('/'), create = false) ?: return emptyList()
        val out = ArrayList<RemoteEntry>()
        var pageToken: String? = null
        do {
            val q = "'$id' in parents and trashed = false"
            val url = api.files + "?q=" + OAuthForm.enc(q) + "&pageSize=1000&spaces=drive&fields=" + OAuthForm.enc("nextPageToken,files($FIELDS)") +
                (pageToken?.let { "&pageToken=" + OAuthForm.enc(it) } ?: "")
            http.send(Cancellation.NEVER) { token -> HttpRequest("GET", url, auth(token)) }.use { response ->
                if (response.code != 200) throw failure(response, SyncCopy.DRIVE_FAILED)
                val json = MiniJson.parseObject(response.bodyText())
                json.listOfFiles().forEach { out += entryOf(join(folder.trim('/'), it.name), it) }
                pageToken = json?.str("nextPageToken")
            }
        } while (pageToken != null)
        return out
    }

    override fun ensureFolder(path: String) {
        folderId(path.trim('/'), create = true)
    }

    override fun put(
        path: String,
        input: InputStream,
        length: Long,
        overwrite: Boolean,
        cancel: Cancellation,
        onBytes: (Long) -> Unit,
    ): RemoteEntry? {
        val (folder, name) = split(path)
        val parent = folderId(folder, create = true) ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.DRIVE_FAILED)
        val existing = find(parent, name, foldersOnly = false)
        if (existing != null && (!overwrite || existing.isFolder)) {
            throw SyncException(SyncFailure.REJECTED, "A file with this name is already at the destination")
        }
        val md5 = MessageDigest.getInstance("MD5")
        val counted = DigestInputStream(input, md5)
        val result = if (length <= SIMPLE_MAX) simpleUpload(name, parent, existing, counted, length, cancel, onBytes)
        else resumableUpload(path, name, parent, existing, counted, length, cancel, onBytes)
        val sentMd5 = md5.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        if (result.md5 != null && !result.md5.equals(sentMd5, ignoreCase = true)) {
            throw SyncException(SyncFailure.TRANSIENT, "Google Drive kept different bytes than were sent")
        }
        return entryOf(path, result)
    }

    // ---- uploads ------------------------------------------------------------------------------------

    private fun simpleUpload(
        name: String,
        parent: String,
        existing: DriveFile?,
        input: InputStream,
        length: Long,
        cancel: Cancellation,
        onBytes: (Long) -> Unit,
    ): DriveFile {
        val content = readExactly(input, length.toInt(), cancel)
        onBytes(content.size.toLong())
        if (input.read() != -1) throw SyncLocalChanged("The file changed while it was being sent")
        val metadata = if (existing == null) """{"name":${MiniJson.quote(name)},"parents":[${MiniJson.quote(parent)}]}""" else "{}"
        val boundary = "fotoz-" + java.util.UUID.randomUUID().toString().replace("-", "")
        val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n"
        val body = head.toByteArray(Charsets.UTF_8) + content + "\r\n--$boundary--".toByteArray(Charsets.UTF_8)
        val url = (if (existing == null) api.upload else api.upload + "/" + existing.id) + "?uploadType=multipart&fields=" + OAuthForm.enc(FIELDS)
        val method = if (existing == null) "POST" else "PATCH"
        http.send(cancel) { token ->
            HttpRequest(method, url, auth(token) + ("Content-Type" to "multipart/related; boundary=$boundary"), bodyBytes = body)
        }.use { response ->
            if (response.code != 200 && response.code != 201) throw failure(response, SyncCopy.DRIVE_FAILED)
            return parseFile(response.bodyText())
        }
    }

    private fun resumableUpload(
        path: String,
        name: String,
        parent: String,
        existing: DriveFile?,
        input: InputStream,
        length: Long,
        cancel: Cancellation,
        onBytes: (Long) -> Unit,
    ): DriveFile {
        var session = sessions[path]?.takeIf { it.length == length }
        var committed = 0L
        if (session != null) {
            when (val state = askStatus(session, cancel)) {
                is Status.Done -> {
                    skip(input, length, cancel) // the digest must still see every byte
                    sessions.remove(path)
                    onBytes(length)
                    return state.file
                }
                is Status.At -> committed = state.offset
                Status.Gone -> {
                    sessions.remove(path)
                    session = null
                }
            }
        }
        if (session == null) {
            session = startSession(name, parent, existing, length, cancel)
            sessions[path] = session
        }
        skip(input, committed, cancel)
        onBytes(committed)
        while (true) {
            cancel.throwIfCancelled()
            val want = minOf(CHUNK.toLong(), length - committed).toInt()
            if (want <= 0) throw SyncException(SyncFailure.TRANSIENT, "The upload did not finish. Trying again.")
            val chunk = readExactly(input, want, cancel)
            var chunkStart = committed
            var slice = chunk
            while (true) {
                val end = chunkStart + slice.size - 1
                val range = "bytes $chunkStart-$end/$length"
                val reply = sessionCaller.call(
                    HttpRequest("PUT", session.uri, listOf("Content-Range" to range, "User-Agent" to USER_AGENT), bodyBytes = slice),
                    cancel,
                )
                reply.use { response ->
                    when (response.code) {
                        200, 201 -> {
                            sessions.remove(path)
                            onBytes(length)
                            return parseFile(response.bodyText())
                        }
                        308 -> {
                            val next = rangeEnd(response.header("range"))?.plus(1) ?: 0L
                            committed = next
                        }
                        404, 410 -> {
                            sessions.remove(path)
                            throw SyncException(SyncFailure.TRANSIENT, "The upload session ended. Trying again.")
                        }
                        else -> throw failure(response, SyncCopy.DRIVE_FAILED)
                    }
                }
                onBytes(committed)
                val sentTo = chunkStart + slice.size
                if (committed >= sentTo) break
                // Drive took less than the whole piece: send the rest of it from the buffer we still hold.
                val from = (committed - chunkStart).toInt().coerceAtLeast(0)
                slice = slice.copyOfRange(from, slice.size)
                chunkStart = committed
            }
        }
    }

    private sealed interface Status {
        class At(val offset: Long) : Status
        class Done(val file: DriveFile) : Status
        data object Gone : Status
    }

    private fun askStatus(session: UploadSession, cancel: Cancellation): Status {
        sessionCaller.call(
            HttpRequest("PUT", session.uri, listOf("Content-Range" to "bytes */${session.length}", "User-Agent" to USER_AGENT), bodyBytes = ByteArray(0)),
            cancel,
        ).use { response ->
            return when (response.code) {
                200, 201 -> Status.Done(parseFile(response.bodyText()))
                308 -> Status.At(rangeEnd(response.header("range"))?.plus(1) ?: 0L)
                404, 410 -> Status.Gone
                else -> throw failure(response, SyncCopy.DRIVE_FAILED)
            }
        }
    }

    private fun startSession(name: String, parent: String, existing: DriveFile?, length: Long, cancel: Cancellation): UploadSession {
        val metadata = if (existing == null) """{"name":${MiniJson.quote(name)},"parents":[${MiniJson.quote(parent)}]}""" else "{}"
        val url = (if (existing == null) api.upload else api.upload + "/" + existing.id) + "?uploadType=resumable&fields=" + OAuthForm.enc(FIELDS)
        val method = if (existing == null) "POST" else "PATCH"
        http.send(cancel) { token ->
            HttpRequest(
                method, url,
                auth(token) + listOf(
                    "Content-Type" to "application/json; charset=UTF-8",
                    "X-Upload-Content-Type" to "application/octet-stream",
                    "X-Upload-Content-Length" to length.toString(),
                ),
                bodyBytes = metadata.toByteArray(Charsets.UTF_8),
            )
        }.use { response ->
            if (response.code != 200) throw failure(response, SyncCopy.DRIVE_FAILED)
            val location = response.header("location")?.takeIf { it.isNotBlank() }
                ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.DRIVE_FAILED)
            return UploadSession(location, length)
        }
    }

    // ---- folders and lookups --------------------------------------------------------------------------

    /** The id of the folder at [path] (`""` is My Drive), made on the way when [create]; null when it is not there and not asked to be made. */
    private fun folderId(path: String, create: Boolean): String? {
        folderIds[path]?.let { return it }
        var current = ""
        var parentId = "root"
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            current = join(current, segment)
            val known = folderIds[current]
            if (known != null) {
                parentId = known
                continue
            }
            val found = find(parentId, segment, foldersOnly = true)
            parentId = when {
                found != null -> found.id
                create -> createFolder(segment, parentId)
                else -> return null
            }
            folderIds[current] = parentId
        }
        return parentId
    }

    private fun createFolder(name: String, parent: String): String {
        val body = """{"name":${MiniJson.quote(name)},"mimeType":"$FOLDER_MIME","parents":[${MiniJson.quote(parent)}]}"""
        val url = api.files + "?fields=" + OAuthForm.enc("id")
        http.send(Cancellation.NEVER) { token ->
            HttpRequest("POST", url, auth(token) + ("Content-Type" to "application/json; charset=UTF-8"), bodyBytes = body.toByteArray(Charsets.UTF_8))
        }.use { response ->
            if (response.code != 200 && response.code != 201) throw failure(response, SyncCopy.DRIVE_FAILED)
            return MiniJson.parseObject(response.bodyText()).str("id") ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.DRIVE_FAILED)
        }
    }

    private fun find(parent: String, name: String, foldersOnly: Boolean): DriveFile? {
        val q = buildString {
            append("name = '").append(escapeQuery(name)).append("' and '").append(escapeQuery(parent)).append("' in parents and trashed = false")
            if (foldersOnly) append(" and mimeType = '$FOLDER_MIME'")
        }
        val url = api.files + "?q=" + OAuthForm.enc(q) + "&pageSize=10&orderBy=createdTime&spaces=drive&fields=" + OAuthForm.enc("files($FIELDS)")
        http.send(Cancellation.NEVER) { token -> HttpRequest("GET", url, auth(token)) }.use { response ->
            if (response.code != 200) throw failure(response, SyncCopy.DRIVE_FAILED)
            return MiniJson.parseObject(response.bodyText()).listOfFiles().firstOrNull()
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private fun auth(token: String) = listOf("Authorization" to "Bearer $token", "Accept" to "application/json", "User-Agent" to USER_AGENT)

    private fun Map<String, Any?>?.listOfFiles(): List<DriveFile> =
        (this?.get("files") as? List<*>).orEmpty().mapNotNull { fileOf(it) }

    private fun parseFile(text: String): DriveFile =
        fileOf(MiniJson.parseObject(text)) ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.DRIVE_FAILED)

    private fun fileOf(raw: Any?): DriveFile? {
        val id = raw.str("id") ?: return null
        val folder = raw.str("mimeType") == FOLDER_MIME
        return DriveFile(
            id = id,
            name = raw.str("name").orEmpty(),
            isFolder = folder,
            size = if (folder) -1L else raw.long("size") ?: -1L,
            md5 = raw.str("md5Checksum"),
            modifiedMillis = raw.str("modifiedTime")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        )
    }

    private fun entryOf(path: String, f: DriveFile) = RemoteEntry(path, f.size, f.isFolder, f.modifiedMillis, f.md5?.lowercase(Locale.ROOT))

    private fun split(path: String): Pair<String, String> {
        val clean = path.trim('/')
        return clean.substringBeforeLast('/', "") to clean.substringAfterLast('/')
    }

    private fun join(folder: String, name: String) = if (folder.isEmpty()) name else "$folder/$name"

    private fun escapeQuery(text: String) = text.replace("\\", "\\\\").replace("'", "\\'")

    private fun rangeEnd(header: String?): Long? = header?.substringAfter("bytes=", "")?.substringAfter('-', "")?.trim()?.toLongOrNull()

    private fun readExactly(input: InputStream, count: Int, cancel: Cancellation): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            cancel.throwIfCancelled()
            val n = input.read(buffer, read, count - read)
            if (n < 0) throw SyncLocalChanged("The file changed while it was being sent")
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
            if (n < 0) throw SyncLocalChanged("The file changed while it was being sent")
            left -= n
        }
    }

    /** Reads a non-200 answer into the failure the engine acts on. */
    private fun failure(response: HttpResponse, what: String): SyncException {
        val code = response.code
        val body = runCatching { response.bodyText() }.getOrDefault("")
        val reasons = (MiniJson.parseObject(body).obj("error")?.get("errors") as? List<*>).orEmpty().mapNotNull { it.str("reason") }
        val retryAfter = retryAfterMillis(response)
        return when {
            code == 403 || code == 429 -> when {
                "storageQuotaExceeded" in reasons -> SyncException(SyncFailure.NO_SPACE, SyncCopy.DRIVE_FULL)
                reasons.any { it in RATE_REASONS } || code == 429 ->
                    if (retryAfter != null) SyncRateLimited(SyncCopy.DRIVE_BUSY, retryAfter) else SyncException(SyncFailure.TRANSIENT, SyncCopy.DRIVE_BUSY)
                reasons.any { it == "accessNotConfigured" || it == "SERVICE_DISABLED" } -> SyncException(SyncFailure.AUTH, SyncCopy.DRIVE_API_OFF)
                else -> SyncException(SyncFailure.AUTH, SyncCopy.DRIVE_ACCESS_REFUSED)
            }
            else -> HttpStatus.failureFor(code, what)
        }
    }

    private companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val FIELDS = "id,name,mimeType,size,md5Checksum,modifiedTime"
        const val USER_AGENT = "Fotoz-sync"
        const val SIMPLE_MAX = 5L * 1024 * 1024
        const val CHUNK = 8 * 1024 * 1024
        val RATE_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded", "dailyLimitExceeded", "backendError")
    }
}
