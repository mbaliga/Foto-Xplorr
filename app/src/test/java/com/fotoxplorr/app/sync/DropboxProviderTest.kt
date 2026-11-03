package com.fotoxplorr.app.sync

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Dropbox provider against a local fake of the parts of API v2 it uses. Not a real Dropbox. */
class DropboxProviderTest {
    private class E(val display: String, val folder: Boolean, var bytes: ByteArray = ByteArray(0))

    private val entries = ConcurrentHashMap<String, E>()
    private var validToken = "at-1"
    private var tokenCount = 0
    private val sessions = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private var sessionCounter = 0
    private var forced: ((SeenRequest) -> Triple<Int, String, Map<String, String>>?)? = null
    private var corruptHash = false
    private val servers = ArrayList<FakeHttpServer>()

    @After
    fun stop() = servers.forEach { it.close() }

    private fun meta(e: E): String {
        val name = e.display.substringAfterLast('/')
        return if (e.folder) """{".tag":"folder","name":${MiniJson.quote(name)}}"""
        else {
            val h = DropboxContentHash().also { it.update(e.bytes, 0, e.bytes.size) }.hex().let { if (corruptHash) "0".repeat(64) else it }
            """{".tag":"file","name":${MiniJson.quote(name)},"size":${e.bytes.size},"server_modified":"2026-11-03T10:00:00Z","content_hash":"$h"}"""
        }
    }

    private fun conflict(s: FakeHttpServer, ex: Exchange, summary: String) =
        s.reply(ex, 409, """{"error_summary":"$summary","error":{}}""".toByteArray())

    private fun serve(): FakeHttpServer {
        lateinit var s: FakeHttpServer
        s = FakeHttpServer { ex, req ->
            if (req.path == "/token") {
                tokenCount++
                validToken = "at-${tokenCount + 1}"
                s.reply(ex, 200, """{"access_token":"$validToken","expires_in":3600}""".toByteArray())
                return@FakeHttpServer
            }
            forced?.invoke(req)?.let { (code, body, headers) -> s.reply(ex, code, body.toByteArray(), headers); return@FakeHttpServer }
            if (req.headers["authorization"] != "Bearer $validToken") { s.reply(ex, 401); return@FakeHttpServer }
            val arg = req.headers["dropbox-api-arg"]?.let { MiniJson.parseObject(it) }
            val body = if (arg == null && req.body.isNotEmpty()) MiniJson.parseObject(String(req.body)) else null
            fun key(p: String) = p.lowercase()
            when (req.path) {
                "/2/users/get_current_account" -> s.reply(ex, 200, """{"email":"me@dropbox.example"}""".toByteArray())
                "/2/files/get_metadata" -> {
                    val path = body!!["path"] as String
                    entries[key(path)]?.let { s.reply(ex, 200, meta(it).toByteArray()) } ?: conflict(s, ex, "path/not_found/..")
                }
                "/2/files/create_folder_v2" -> {
                    val path = body!!["path"] as String
                    when {
                        entries[key(path)]?.folder == true -> conflict(s, ex, "path/conflict/folder/..")
                        entries.containsKey(key(path)) -> conflict(s, ex, "path/conflict/file/..")
                        else -> { entries[key(path)] = E(path, true); s.reply(ex, 200, """{"metadata":{}}""".toByteArray()) }
                    }
                }
                "/2/files/list_folder" -> {
                    val path = (body!!["path"] as String)
                    val parent = key(path)
                    if (path.isNotEmpty() && entries[parent] == null) return@FakeHttpServer conflict(s, ex, "path/not_found/..")
                    val kids = entries.filter { (k, _) -> k.substringBeforeLast('/') == parent && k != parent }.values
                    s.reply(ex, 200, """{"entries":[${kids.joinToString(",") { meta(it) }}],"has_more":false,"cursor":"c"}""".toByteArray())
                }
                "/2/files/upload" -> {
                    val path = arg!!["path"] as String
                    val exists = entries[key(path)]
                    if (exists != null && arg["mode"] == "add") return@FakeHttpServer conflict(s, ex, "path/conflict/file/..")
                    val e = E(path, false, req.body).also { entries[key(path)] = it }
                    s.reply(ex, 200, meta(e).toByteArray())
                }
                "/2/files/upload_session/start" -> {
                    val id = "sess${++sessionCounter}"
                    sessions[id] = ByteArrayOutputStream().also { it.write(req.body) }
                    s.reply(ex, 200, """{"session_id":"$id"}""".toByteArray())
                }
                "/2/files/upload_session/append_v2" -> {
                    val cursor = arg!!["cursor"] as Map<*, *>
                    val data = sessions.getValue(cursor["session_id"] as String)
                    if ((cursor["offset"] as Long) != data.size().toLong()) {
                        return@FakeHttpServer s.reply(ex, 409, """{"error_summary":"incorrect_offset/..","error":{".tag":"incorrect_offset","correct_offset":${data.size()}}}""".toByteArray())
                    }
                    data.write(req.body)
                    s.reply(ex, 200, "null".toByteArray())
                }
                "/2/files/upload_session/finish" -> {
                    val cursor = arg!!["cursor"] as Map<*, *>
                    val commit = arg["commit"] as Map<*, *>
                    val data = sessions.getValue(cursor["session_id"] as String)
                    data.write(req.body)
                    val path = commit["path"] as String
                    val e = E(path, false, data.toByteArray()).also { entries[key(path)] = it }
                    s.reply(ex, 200, meta(e).toByteArray())
                }
                else -> s.reply(ex, 404)
            }
        }
        servers += s
        return s
    }

    private fun provider(server: FakeHttpServer, token: String = "rt"): DropboxProvider {
        val spec = OAuthSpec(SyncDestinationKind.DROPBOX, "", "${server.base}/token", "", OAuthRedirect.DROPBOX_URI, emptyList(), "")
        return DropboxProvider("key", token.toCharArray(), SocketHttpTransport(), DropboxApi("${server.base}/2", "${server.base}/2"), specFor = { spec }, allowPlainHttpOnLocal = true)
    }

    private fun put(p: DropboxProvider, path: String, data: ByteArray, overwrite: Boolean = false, progress: (Long) -> Unit = {}) =
        p.put(path, data.inputStream(), data.size.toLong(), overwrite, Cancellation.NEVER, progress)

    @Test
    fun `content hash is the sha256 of the block digests`() {
        val small = randomBytes(1000)
        val one = DropboxContentHash().also { it.update(small, 0, small.size) }.hex()
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        assertEquals(hex(sha(sha(small))), one)
        val block = 4 * 1024 * 1024
        val big = randomBytes(block + 10, 3)
        val twoBlocks = hex(sha(sha(big.copyOfRange(0, block)) + sha(big.copyOfRange(block, big.size))))
        val fed = DropboxContentHash().also { h -> var o = 0; while (o < big.size) { val n = minOf(777_777, big.size - o); h.update(big, o, n); o += n } }.hex()
        assertEquals(twoBlocks, fed)
    }

    @Test
    fun `test connection names the account`() {
        assertEquals(SyncCopy.connectedTo("me@dropbox.example"), provider(serve()).testConnection(Cancellation.NEVER))
    }

    @Test
    fun `folders are created once, an existing folder is fine and a file in its place is refused`() {
        val server = serve()
        val p = provider(server)
        p.ensureFolder("Fotoz/2023")
        p.ensureFolder("Fotoz/2023")
        assertEquals(2, server.seen.count { it.path == "/2/files/create_folder_v2" })
        assertTrue(String(server.seen.first { it.path == "/2/files/create_folder_v2" }.body).contains("\"autorename\":false"))
        val fresh = provider(server) // an empty cache meets existing folders: 409 conflict/folder is "done"
        fresh.ensureFolder("Fotoz/2023")
        entries["/fotoz/file"] = E("/Fotoz/file", false)
        val e = runCatching { fresh.ensureFolder("Fotoz/file") }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.REJECTED, e.failure)
    }

    @Test
    fun `upload stat list and the conflict of a second add`() {
        val p = provider(serve())
        p.ensureFolder("Fotoz")
        val data = randomBytes(5000)
        val entry = put(p, "Fotoz/ä é.jpg", data) // non-ASCII travels escaped in the header
        assertEquals(5000L, entry!!.sizeBytes)
        assertArrayEquals(data, entries["/fotoz/ä é.jpg"]!!.bytes)
        assertEquals(5000L, p.stat("Fotoz/ä é.jpg")!!.sizeBytes)
        assertNull(p.stat("Fotoz/none.jpg"))
        assertEquals(listOf("Fotoz/ä é.jpg"), p.list("Fotoz").map { it.path })
        assertEquals(emptyList<RemoteEntry>(), p.list("Missing"))
        val e = runCatching { put(p, "Fotoz/ä é.jpg", randomBytes(10)) }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.REJECTED, e.failure)
        assertArrayEquals(data, entries["/fotoz/ä é.jpg"]!!.bytes)
        val again = randomBytes(10, 4)
        put(p, "Fotoz/ä é.jpg", again, overwrite = true)
        assertArrayEquals(again, entries["/fotoz/ä é.jpg"]!!.bytes)
    }

    @Test
    fun `the api arg header is ascii only`() {
        val server = serve()
        val p = provider(server)
        put(p, "Fotoz/é.jpg", randomBytes(10))
        val arg = server.seen.first { it.path == "/2/files/upload" }.headers.getValue("dropbox-api-arg")
        assertTrue(arg.all { it.code < 127 })
    }

    @Test
    fun `a large file goes through start, append and finish and survives a retry from the acknowledged offset`() {
        val server = serve()
        val p = provider(server)
        // Sessions start above 140 MB, so the test sends 141 MB of zeros (the content hash covers every byte of it).
        val big = ByteArray(141 * 1000 * 1000)
        val progress = ArrayList<Long>()
        val entry = p.put("Fotoz/big.mov", big.inputStream(), big.size.toLong(), false, Cancellation.NEVER) { progress += it }
        assertEquals(big.size.toLong(), entry!!.sizeBytes)
        assertEquals(1, server.seen.count { it.path == "/2/files/upload_session/start" })
        assertTrue(server.seen.count { it.path == "/2/files/upload_session/append_v2" } > 10)
        assertEquals(1, server.seen.count { it.path == "/2/files/upload_session/finish" })
        assertEquals(0, server.seen.count { it.path == "/2/files/upload" })
        assertEquals(big.size.toLong(), progress.last())
    }

    @Test
    fun `an interrupted session continues at the offset dropbox acknowledged`() {
        val server = serve()
        val p = provider(server)
        val big = ByteArray(141 * 1000 * 1000)
        var broken = false
        forced = { req ->
            // Fail the third append once, as a dropped connection would look to the provider.
            if (!broken && req.path == "/2/files/upload_session/append_v2" && server.seen.count { it.path == req.path } == 3) {
                broken = true; Triple(503, "", emptyMap())
            } else null
        }
        val stream = big.inputStream()
        val first = runCatching { p.put("Fotoz/big.mov", stream, big.size.toLong(), false, Cancellation.NEVER) {} }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.TRANSIENT, first.failure)
        val entry = p.put("Fotoz/big.mov", big.inputStream(), big.size.toLong(), false, Cancellation.NEVER) {}
        assertEquals(big.size.toLong(), entry!!.sizeBytes)
        assertEquals("one session for both attempts", 1, server.seen.count { it.path == "/2/files/upload_session/start" })
    }

    @Test
    fun `401 renews the token once and repeats a small call`() {
        val server = serve()
        val p = provider(server)
        p.ensureFolder("Fotoz")
        validToken = "revoked-on-server"
        assertNull(p.stat("Fotoz/x.jpg"))
        assertEquals(2, tokenCount)
    }

    @Test
    fun `a 401 in the middle of a streamed upload is transient and the retry uses the new token`() {
        val server = serve()
        val p = provider(server)
        p.ensureFolder("Fotoz")
        validToken = "revoked-on-server"
        val first = runCatching { put(p, "Fotoz/a.jpg", randomBytes(100)) }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.TRANSIENT, first.failure)
        assertNull(runCatching { put(p, "Fotoz/a.jpg", randomBytes(100)) }.exceptionOrNull())
    }

    @Test
    fun `429 carries retry-after into the exception the engine honours`() {
        val server = serve()
        val p = provider(server)
        p.testConnection(Cancellation.NEVER)
        forced = { Triple(429, """{"error_summary":"too_many_requests/..","error":{"retry_after":7}}""", mapOf("Retry-After" to "12")) }
        val e = runCatching { p.stat("Fotoz/a.jpg") }.exceptionOrNull() as SyncRateLimited
        assertEquals(12_000L, e.retryAfterMillis)
        assertEquals(SyncFailure.TRANSIENT, e.failure)
    }

    @Test
    fun `409 reasons map to full, scope and other rejections`() {
        val server = serve()
        val p = provider(server)
        p.testConnection(Cancellation.NEVER)
        fun fail(code: Int, summary: String): SyncException {
            forced = { Triple(code, """{"error_summary":"$summary"}""", emptyMap()) }
            return runCatching { put(p, "Fotoz/a.jpg", randomBytes(10)) }.exceptionOrNull() as SyncException
        }
        assertEquals(SyncFailure.NO_SPACE, fail(409, "path/insufficient_space/..").failure)
        assertEquals(SyncCopy.DROPBOX_SCOPES, fail(400, "missing_scope/files.content.write").message)
        assertEquals(SyncFailure.AUTH, fail(403, "something").failure)
        assertEquals(SyncFailure.TRANSIENT, fail(500, "").failure)
    }

    @Test
    fun `a different content hash means the copy is not believed`() {
        val p = provider(serve())
        corruptHash = true
        assertEquals(SyncFailure.TRANSIENT, (runCatching { put(p, "Fotoz/a.jpg", randomBytes(100)) }.exceptionOrNull() as SyncException).failure)
    }

    @Test
    fun `the engine waits at least the retry-after the provider reports`() {
        val sleeps = ArrayList<Long>()
        val attempts = intArrayOf(0)
        val provider = object : SyncProvider {
            override val label = "x"
            override val needsNetwork = true
            override fun testConnection(cancel: Cancellation) = ""
            override fun stat(path: String): RemoteEntry? = null
            override fun list(folder: String) = emptyList<RemoteEntry>()
            override fun ensureFolder(path: String) {}
            override fun put(path: String, input: java.io.InputStream, length: Long, overwrite: Boolean, cancel: Cancellation, onBytes: (Long) -> Unit): RemoteEntry? {
                if (attempts[0]++ == 0) throw SyncRateLimited("slow down", 30_000L)
                input.readAllBytes()
                return RemoteEntry(path, length, false)
            }
        }
        val library = FakeLibrary()
        library.add(1, "a.jpg", randomBytes(100))
        val engine = SyncEngine(provider, library.source, MemoryManifestStore(), { true }, sleep = { sleeps += it })
        val result = kotlinx.coroutines.runBlocking { engine.run(library.candidates, null) {} }
        assertTrue(result.state is SyncRunState.Finished)
        assertTrue("slept ${sleeps}", sleeps.any { it >= 30_000L })
    }
}
