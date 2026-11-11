package com.fotoxplorr.app.sync

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Drive provider against a local fake that mimics the parts of REST v3 it uses. Not a real Drive. */
class DriveProviderTest {
    private class F(val id: String, val name: String, val parent: String, val folder: Boolean, var bytes: ByteArray = ByteArray(0))

    private val files = ConcurrentHashMap<String, F>()
    private var counter = 0
    private var validToken = "at-1"
    private var tokenCount = 0
    private val sessionData = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val sessionTarget = ConcurrentHashMap<String, Triple<String, String, String?>>() // name, parent, update-id
    private var failNextChunkWith: Int? = null
    private var forceAll: Pair<Int, String>? = null
    private var corruptMd5 = false
    private val servers = ArrayList<FakeHttpServer>()

    @After
    fun stop() = servers.forEach { it.close() }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    private fun json(f: F): String = buildString {
        append("""{"id":"${f.id}","name":${MiniJson.quote(f.name)},"mimeType":"${if (f.folder) "application/vnd.google-apps.folder" else "application/octet-stream"}"""")
        if (!f.folder) append(""","size":"${f.bytes.size}","md5Checksum":"${if (corruptMd5) "0".repeat(32) else md5(f.bytes)}"""")
        append(""","modifiedTime":"2026-11-03T10:00:00.000Z"}""")
    }

    private fun serve(): FakeHttpServer {
        lateinit var s: FakeHttpServer
        s = FakeHttpServer { ex, req ->
            if (req.path == "/token") {
                tokenCount++
                validToken = "at-${tokenCount + 1}"
                s.reply(ex, 200, """{"access_token":"$validToken","expires_in":3600}""".toByteArray())
                return@FakeHttpServer
            }
            forceAll?.let { (code, body) -> s.reply(ex, code, body.toByteArray()); return@FakeHttpServer }
            if (!req.path.startsWith("/session/") && req.headers["authorization"] != "Bearer $validToken") {
                s.reply(ex, 401); return@FakeHttpServer
            }
            val query = req.query.orEmpty().split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            when {
                req.path == "/drive/v3/about" -> s.reply(ex, 200, """{"user":{"displayName":"Me","emailAddress":"me@example.org"}}""".toByteArray())
                req.path == "/drive/v3/files" && req.method == "GET" -> {
                    val q = query.getValue("q")
                    val named = Regex("""name = '((?:\\.|[^'\\])*)' and '([^']*)' in parents""").find(q)
                    val listing = Regex("""^'([^']*)' in parents""").find(q)
                    val found = when {
                        named != null -> {
                            val name = named.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
                            files.values.filter { it.name == name && it.parent == named.groupValues[2] && (!q.contains("mimeType") || it.folder) }
                        }
                        listing != null -> files.values.filter { it.parent == listing.groupValues[1] }
                        else -> emptyList()
                    }
                    s.reply(ex, 200, """{"files":[${found.joinToString(",") { json(it) }}]}""".toByteArray())
                }
                req.path == "/drive/v3/files" && req.method == "POST" -> {
                    val body = MiniJson.parseObject(String(req.body))!!
                    val id = "id${++counter}"
                    val parent = (body["parents"] as List<*>).first() as String
                    files[id] = F(id, body["name"] as String, parent, folder = true)
                    s.reply(ex, 200, """{"id":"$id"}""".toByteArray())
                }
                req.path.startsWith("/upload/drive/v3/files") && query["uploadType"] == "multipart" -> {
                    val boundary = req.headers.getValue("content-type").substringAfter("boundary=")
                    val text = String(req.body, Charsets.ISO_8859_1)
                    val parts = text.split("--$boundary").filter { it.isNotBlank() && !it.startsWith("--") }
                    val meta = MiniJson.parseObject(parts[0].substringAfter("\r\n\r\n").trim())!!
                    val content = parts[1].substringAfter("\r\n\r\n").removeSuffix("\r\n").toByteArray(Charsets.ISO_8859_1)
                    val update = req.path.removePrefix("/upload/drive/v3/files/").takeIf { it.isNotEmpty() && req.method == "PATCH" }
                    val f = if (update != null) files.getValue(update).also { it.bytes = content } else {
                        val id = "id${++counter}"
                        F(id, meta["name"] as String, (meta["parents"] as List<*>).first() as String, false, content).also { files[id] = it }
                    }
                    s.reply(ex, 200, json(f).toByteArray())
                }
                req.path.startsWith("/upload/drive/v3/files") && query["uploadType"] == "resumable" -> {
                    val meta = MiniJson.parseObject(String(req.body))!!
                    val update = req.path.removePrefix("/upload/drive/v3/files/").takeIf { it.isNotEmpty() && req.method == "PATCH" }
                    val sid = "s${++counter}"
                    sessionData[sid] = ByteArrayOutputStream()
                    sessionTarget[sid] = Triple(meta["name"] as? String ?: "", (meta["parents"] as? List<*>)?.first() as? String ?: "", update)
                    s.reply(ex, 200, headers = mapOf("Location" to "${s.base}/session/$sid"))
                }
                req.path.startsWith("/session/") -> session(s, ex, req)
                else -> s.reply(ex, 404)
            }
        }
        servers += s
        return s
    }

    private fun session(s: FakeHttpServer, ex: Exchange, req: SeenRequest) {
        val sid = req.path.removePrefix("/session/")
        val data = sessionData[sid] ?: return s.reply(ex, 404)
        val range = req.headers.getValue("content-range")
        val total = range.substringAfter('/').toLong()
        if (range.startsWith("bytes */")) {
            return if (data.size().toLong() == total) finish(s, ex, sid, data) else rangeReply(s, ex, data)
        }
        failNextChunkWith?.let { failNextChunkWith = null; return s.reply(ex, it) }
        val start = range.removePrefix("bytes ").substringBefore('-').toLong()
        if (start != data.size().toLong()) return s.reply(ex, 400)
        data.write(req.body)
        if (data.size().toLong() == total) finish(s, ex, sid, data) else rangeReply(s, ex, data)
    }

    private fun rangeReply(s: FakeHttpServer, ex: Exchange, data: ByteArrayOutputStream) =
        s.reply(ex, 308, headers = if (data.size() > 0) mapOf("Range" to "bytes=0-${data.size() - 1}") else emptyMap())

    private fun finish(s: FakeHttpServer, ex: Exchange, sid: String, data: ByteArrayOutputStream) {
        val (name, parent, update) = sessionTarget.getValue(sid)
        val f = if (update != null) files.getValue(update).also { it.bytes = data.toByteArray() } else {
            val id = "id${++counter}"
            F(id, name, parent, false, data.toByteArray()).also { files[id] = it }
        }
        s.reply(ex, 200, json(f).toByteArray())
    }

    private fun provider(server: FakeHttpServer, retryToken: String = "rt"): DriveProvider {
        val spec = OAuthSpec(SyncDestinationKind.GDRIVE, "${server.base}/auth", "${server.base}/token", "${server.base}/revoke", OAuthRedirect.GOOGLE_URI, emptyList(), "")
        return DriveProvider(
            "cid", retryToken.toCharArray(), SocketHttpTransport(),
            DriveApi("${server.base}/drive/v3/files", "${server.base}/upload/drive/v3/files", "${server.base}/drive/v3/about"),
            specFor = { spec }, allowPlainHttpOnLocal = true,
        )
    }

    private fun put(p: DriveProvider, path: String, data: ByteArray, overwrite: Boolean = false, progress: (Long) -> Unit = {}) =
        p.put(path, data.inputStream(), data.size.toLong(), overwrite, Cancellation.NEVER, progress)

    @Test
    fun `test connection names the account`() {
        assertEquals(SyncCopy.connectedTo("me@example.org"), provider(serve()).testConnection(Cancellation.NEVER))
    }

    @Test
    fun `folders are found or made once under the root and a small file goes in one multipart request`() {
        val server = serve()
        val p = provider(server)
        p.ensureFolder("Fotoz/2023/2023-05")
        p.ensureFolder("Fotoz/2023/2023-05") // idempotent and cached
        assertEquals(3, files.values.count { it.folder })
        val data = randomBytes(10_000)
        val entry = put(p, "Fotoz/2023/2023-05/a.jpg", data)
        assertEquals(10_000L, entry!!.sizeBytes)
        val stored = files.values.single { !it.folder }
        assertArrayEquals(data, stored.bytes)
        assertEquals(files.values.single { it.name == "2023-05" }.id, stored.parent)
        assertEquals("root", files.values.single { it.name == "Fotoz" }.parent)
        assertEquals(1, server.seen.count { it.method == "POST" && it.path == "/upload/drive/v3/files" })
    }

    @Test
    fun `a name with quotes and backslashes is found again`() {
        val p = provider(serve())
        p.ensureFolder("Fotoz")
        put(p, "Fotoz/it's a \\ file.jpg", randomBytes(100))
        assertNotNull(p.stat("Fotoz/it's a \\ file.jpg"))
        assertNull(p.stat("Fotoz/other.jpg"))
    }

    @Test
    fun `stat reports size, folder or nothing and list returns the children`() {
        val p = provider(serve())
        p.ensureFolder("Fotoz/2023")
        put(p, "Fotoz/2023/a.jpg", randomBytes(321))
        assertEquals(321L, p.stat("Fotoz/2023/a.jpg")!!.sizeBytes)
        assertTrue(p.stat("Fotoz/2023")!!.isDirectory)
        assertNull(p.stat("Fotoz/2024/a.jpg"))
        assertEquals(listOf("Fotoz/2023/a.jpg"), p.list("Fotoz/2023").map { it.path })
        assertEquals(emptyList<RemoteEntry>(), p.list("Nope"))
        assertEquals(1, p.list("Fotoz").size)
    }

    @Test
    fun `an existing file is refused without overwrite and updated in place with it`() {
        val p = provider(serve())
        p.ensureFolder("Fotoz")
        put(p, "Fotoz/a.jpg", randomBytes(50, 1))
        val e = runCatching { put(p, "Fotoz/a.jpg", randomBytes(60, 2)) }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.REJECTED, e.failure)
        assertEquals(50, files.values.single { !it.folder }.bytes.size)
        val second = randomBytes(60, 2)
        put(p, "Fotoz/a.jpg", second, overwrite = true)
        assertEquals(1, files.values.count { !it.folder })
        assertArrayEquals(second, files.values.single { !it.folder }.bytes)
    }

    @Test
    fun `a large file uses a resumable session in chunks and reports progress`() {
        val server = serve()
        val p = provider(server)
        p.ensureFolder("Fotoz")
        val data = randomBytes(9 * 1024 * 1024 + 123)
        val progress = ArrayList<Long>()
        val entry = put(p, "Fotoz/big.mov", data) { progress += it }
        assertEquals(data.size.toLong(), entry!!.sizeBytes)
        assertArrayEquals(data, files.values.single { !it.folder }.bytes)
        assertEquals(2, server.seen.count { it.method == "PUT" && it.path.startsWith("/session/") })
        assertEquals(data.size.toLong(), progress.last())
        assertEquals("application/octet-stream", server.seen.first { it.query?.contains("resumable") == true }.headers["x-upload-content-type"])
    }

    @Test
    fun `a failed chunk is resumed from what Drive holds and not sent twice`() {
        val server = serve()
        val p = provider(server)
        p.ensureFolder("Fotoz")
        val data = randomBytes(9 * 1024 * 1024 + 5, 9)
        failNextChunkWith = null
        // First chunk goes; the second fails once with a 503.
        var failed = false
        val flaky = object : java.io.InputStream() {
            val inner = data.inputStream()
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!failed && data.size - inner.available() >= 8 * 1024 * 1024) { failed = true; failNextChunkWith = 503 }
                return inner.read(b, off, len)
            }
        }
        val first = runCatching { p.put("Fotoz/big.mov", flaky, data.size.toLong(), false, Cancellation.NEVER) {} }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.TRANSIENT, first.failure)
        val entry = put(p, "Fotoz/big.mov", data) // the engine's retry: a fresh stream from byte zero
        assertEquals(data.size.toLong(), entry!!.sizeBytes)
        assertArrayEquals(data, files.values.single { !it.folder }.bytes)
        assertEquals("one session for both attempts", 1, server.seen.count { it.query?.contains("resumable") == true })
        val chunkRanges = server.seen.filter { it.path.startsWith("/session/") }.mapNotNull { it.headers["content-range"] }
        assertEquals(1, chunkRanges.count { it.startsWith("bytes 0-") })
    }

    @Test
    fun `a 401 renews the token once and the call is repeated`() {
        val server = serve()
        val p = provider(server)
        p.ensureFolder("Fotoz")
        assertEquals(1, tokenCount)
        validToken = "rotated-by-server"
        assertNotNull(put(p, "Fotoz/a.jpg", randomBytes(10)))
        assertEquals(2, tokenCount)
    }

    @Test
    fun `a revoked sign in is auth with the sign in again sentence`() {
        val server = serve()
        val refusing = FakeHttpServer { ex, _ ->
            ex.responseHeaders.add("Content-Length", "26")
            ex.sendResponseHeaders(400, 26)
            ex.responseBody.write("""{"error":"invalid_grant"}""".toByteArray() + "  ".toByteArray())
        }
        servers += refusing
        val spec = OAuthSpec(SyncDestinationKind.GDRIVE, "", "${refusing.base}/token", "", OAuthRedirect.GOOGLE_URI, emptyList(), "")
        val p = DriveProvider("cid", "rt".toCharArray(), SocketHttpTransport(), DriveApi("${server.base}/drive/v3/files", "${server.base}/u", "${server.base}/drive/v3/about"), specFor = { spec }, allowPlainHttpOnLocal = true)
        val e = runCatching { p.testConnection(Cancellation.NEVER) }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.AUTH, e.failure)
        assertEquals(SyncCopy.SIGN_IN_AGAIN, e.message)
    }

    @Test
    fun `403 reasons map to full, slow down, api off or access refused`() {
        fun reason(r: String) = """{"error":{"code":403,"errors":[{"reason":"$r"}]}}"""
        val server = serve()
        val p = provider(server)
        fun failure(code: Int, body: String): SyncException {
            forceAll = code to body
            return runCatching { p.stat("Fotoz/a.jpg") }.exceptionOrNull() as SyncException
        }
        p.testConnection(Cancellation.NEVER)
        failure(403, reason("storageQuotaExceeded")).let { assertEquals(SyncFailure.NO_SPACE, it.failure); assertEquals(SyncCopy.DRIVE_FULL, it.message) }
        failure(403, reason("userRateLimitExceeded")).let { assertEquals(SyncFailure.TRANSIENT, it.failure) }
        failure(403, reason("accessNotConfigured")).let { assertEquals(SyncFailure.AUTH, it.failure); assertEquals(SyncCopy.DRIVE_API_OFF, it.message) }
        failure(403, reason("forbidden")).let { assertEquals(SyncFailure.AUTH, it.failure) }
        failure(429, "{}").let { assertEquals(SyncFailure.TRANSIENT, it.failure) }
        failure(503, "").let { assertEquals(SyncFailure.TRANSIENT, it.failure) }
        failure(500, "").let { assertTrue(it.failure.retryable) }
    }

    @Test
    fun `a different md5 from Drive means the copy is not believed`() {
        val p = provider(serve())
        p.ensureFolder("Fotoz")
        corruptMd5 = true
        val e = runCatching { put(p, "Fotoz/a.jpg", randomBytes(100)) }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.TRANSIENT, e.failure)
    }

    @Test
    fun `the secret never appears in a request line or the label`() {
        val server = serve()
        val p = provider(server, retryToken = "super-secret-refresh")
        p.ensureFolder("Fotoz")
        assertFalse(p.label.contains("super-secret"))
        assertTrue(server.seen.none { it.path.contains("super-secret") || (it.query ?: "").contains("super-secret") })
        // The refresh token goes only in the token call's body.
        assertTrue(server.seen.filter { it.path != "/token" }.none { String(it.body).contains("super-secret") })
    }
}
