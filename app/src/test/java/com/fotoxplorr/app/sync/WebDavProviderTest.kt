package com.fotoxplorr.app.sync

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
import org.junit.Test

/** The WebDAV provider against a local fake DAV server (com.sun.net.httpserver). Not a real Nextcloud. */
class WebDavProviderTest {
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val dirs = ConcurrentHashMap.newKeySet<String>().apply { add("") }
    private val user = "me"
    private val password = "app-pass word!"
    private var forceStatus: ((SeenRequest) -> Int?)? = null
    private val prefix = "/remote.php/dav/files/me/"
    private val servers = ArrayList<FakeHttpServer>()

    private fun decode(raw: String) = WebDavXml.decodePath(raw)
    private fun rel(path: String) = decode(path).removePrefix(prefix).trim('/')

    private fun serve(): FakeHttpServer {
        lateinit var server: FakeHttpServer
        server = FakeHttpServer { ex, req ->
            val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
            if (req.headers["authorization"] != expected) {
                server.reply(ex, 401); return@FakeHttpServer
            }
            forceStatus?.invoke(req)?.let { server.reply(ex, it); return@FakeHttpServer }
            val path = rel(req.path)
            when (req.method) {
                "PROPFIND" -> propfind(server, ex, req, path)
                "MKCOL" -> when {
                    path in dirs -> server.reply(ex, 405)
                    path.contains('/') && path.substringBeforeLast('/') !in dirs -> server.reply(ex, 409)
                    else -> { dirs += path; server.reply(ex, 201) }
                }
                "PUT" -> when {
                    path.contains('/') && path.substringBeforeLast('/') !in dirs -> server.reply(ex, 409)
                    req.headers["if-none-match"] == "*" && files.containsKey(path) -> server.reply(ex, 412)
                    else -> { val existed = files.containsKey(path); files[path] = req.body; server.reply(ex, if (existed) 204 else 201) }
                }
                else -> server.reply(ex, 405)
            }
        }
        servers += server
        return server
    }

    private fun propfind(server: FakeHttpServer, ex: Exchange, req: SeenRequest, path: String) {
        val depth = req.headers["depth"]
        val isDir = path in dirs
        val isFile = files.containsKey(path)
        if (!isDir && !isFile) return server.reply(ex, 404)
        fun href(p: String, dir: Boolean) = prefix + p.split('/').filter { it.isNotEmpty() }.joinToString("/") { WebDavUrl.encodeSegment(it) } + if (dir && p.isNotEmpty()) "/" else ""
        fun entry(p: String, dir: Boolean) = "<d:response><d:href>${href(p, dir)}</d:href><d:propstat><d:prop>" +
            (if (dir) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/><d:getcontentlength>${files.getValue(p).size}</d:getcontentlength>") +
            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        val sb = StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">")
        sb.append(entry(path, isDir))
        if (depth == "1" && isDir) {
            dirs.filter { it.isNotEmpty() && it.substringBeforeLast('/', "") == path && it != path }.forEach { sb.append(entry(it, true)) }
            files.keys.filter { it.substringBeforeLast('/', "") == path }.forEach { sb.append(entry(it, false)) }
        }
        sb.append("</d:multistatus>")
        server.reply(ex, 207, sb.toString().toByteArray(), mapOf("Content-Type" to "application/xml"))
    }

    private fun provider(server: FakeHttpServer, pass: String = password, base: String = server.base + prefix, plain: Boolean = true) =
        WebDavProvider(WebDavSettings(base, user, plain), pass.toCharArray(), SocketHttpTransport())

    @After
    fun stop() {
        servers.forEach { it.close() }
    }

    @Test
    fun `connection test succeeds with the right app password`() {
        dirs += ""
        val server = serve()
        assertEquals(SyncCopy.connectedTo("127.0.0.1"), provider(server).testConnection(Cancellation.NEVER))
        assertEquals("PROPFIND", server.seen.last().method)
        assertEquals("0", server.seen.last().headers["depth"])
    }

    @Test
    fun `a wrong password is an AUTH failure`() {
        val server = serve()
        val e = runCatching { provider(server, pass = "wrong").testConnection(Cancellation.NEVER) }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.AUTH, e.failure)
        assertFalse(e.message!!.contains("wrong"))
    }

    @Test
    fun `a forbidden answer is an AUTH failure and a full disk is NO_SPACE`() {
        val server = serve()
        forceStatus = { 403 }
        assertEquals(SyncFailure.AUTH, (runCatching { provider(server).stat("a.jpg") }.exceptionOrNull() as SyncException).failure)
        forceStatus = { if (it.method == "PUT") 507 else null }
        dirs += "Fotoz"
        val e = runCatching { provider(server).put("Fotoz/a.jpg", "x".byteInputStream(), 1, false, Cancellation.NEVER) {} }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.NO_SPACE, e.failure)
        forceStatus = { 503 }
        assertEquals(SyncFailure.TRANSIENT, (runCatching { provider(server).list("") }.exceptionOrNull() as SyncException).failure)
        forceStatus = { 415 }
        assertEquals(SyncFailure.REJECTED, (runCatching { provider(server).list("") }.exceptionOrNull() as SyncException).failure)
    }

    @Test
    fun `folders are made one level at a time and an existing one is fine`() {
        val server = serve()
        val p = provider(server)
        p.ensureFolder("Fotoz/2023/2023-11")
        p.ensureFolder("Fotoz/2023/2023-11")
        assertTrue(dirs.containsAll(listOf("Fotoz", "Fotoz/2023", "Fotoz/2023/2023-11")))
        val made = server.seen.filter { it.method == "MKCOL" }.map { decode(it.path).removePrefix(prefix) }
        assertEquals(listOf("Fotoz/", "Fotoz/2023/", "Fotoz/2023/2023-11/"), made.take(3))
    }

    @Test
    fun `a file goes up with PUT and is read back with a size`() {
        val server = serve()
        val p = provider(server)
        p.ensureFolder("Fotoz/2023")
        val data = randomBytes(300_000)
        val seen = ArrayList<Long>()
        val entry = p.put("Fotoz/2023/Ünï 日本 (2).jpg", data.inputStream(), data.size.toLong(), false, Cancellation.NEVER) { seen += it }
        assertArrayEquals(data, files["Fotoz/2023/Ünï 日本 (2).jpg"])
        assertEquals(data.size.toLong(), entry!!.sizeBytes)
        assertEquals(data.size.toLong(), seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> a <= b })
        val put = server.seen.first { it.method == "PUT" }
        assertEquals("*", put.headers["if-none-match"])
        assertEquals(data.size.toString(), put.headers["content-length"])
    }

    @Test
    fun `an existing file is not replaced unless it is our own partial`() {
        val server = serve()
        val p = provider(server)
        dirs += "Fotoz"
        files["Fotoz/a.jpg"] = byteArrayOf(1, 2, 3)
        val e = runCatching { p.put("Fotoz/a.jpg", "new".byteInputStream(), 3, false, Cancellation.NEVER) {} }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.REJECTED, e.failure)
        assertArrayEquals(byteArrayOf(1, 2, 3), files["Fotoz/a.jpg"])
        p.put("Fotoz/a.jpg", "new".byteInputStream(), 3, true, Cancellation.NEVER) {}
        assertArrayEquals("new".toByteArray(), files["Fotoz/a.jpg"])
        assertNull(server.seen.last { it.method == "PUT" }.headers["if-none-match"])
    }

    @Test
    fun `stat and list read the multistatus answers`() {
        val server = serve()
        val p = provider(server)
        dirs += "Fotoz"; dirs += "Fotoz/2023"
        files["Fotoz/a b.jpg"] = ByteArray(5)
        files["Fotoz/日.jpg"] = ByteArray(9)
        assertNull(p.stat("nothing/here.jpg"))
        assertEquals(5L, p.stat("Fotoz/a b.jpg")!!.sizeBytes)
        assertTrue(p.stat("Fotoz")!!.isDirectory)
        val listing = p.list("Fotoz").associateBy { it.path }
        assertEquals(setOf("Fotoz/2023", "Fotoz/a b.jpg", "Fotoz/日.jpg"), listing.keys)
        assertTrue(listing.getValue("Fotoz/2023").isDirectory)
        assertEquals(9L, listing.getValue("Fotoz/日.jpg").sizeBytes)
        assertEquals(emptyList<RemoteEntry>(), p.list("missing"))
    }

    @Test
    fun `every request carries the basic auth header and nothing else carries the password`() {
        val server = serve()
        val p = provider(server)
        p.ensureFolder("Fotoz")
        p.put("Fotoz/a.jpg", "z".byteInputStream(), 1, false, Cancellation.NEVER) {}
        val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        assertTrue(server.seen.isNotEmpty())
        assertTrue(server.seen.all { it.headers["authorization"] == expected })
        assertTrue(server.seen.none { it.path.contains("pass") || (it.query ?: "").contains("pass") })
    }

    @Test
    fun `a redirect to another host is refused and nothing is sent there`() {
        val target = serve()
        val origin = FakeHttpServer { ex, _ ->
            ex.responseHeaders.add("Location", "http://localhost:${target.port}/remote.php/dav/files/me/")
            ex.sendResponseHeaders(302, -1)
        }
        servers += origin
        val e = runCatching { provider(origin, base = origin.base + prefix).testConnection(Cancellation.NEVER) }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.UNSAFE, e.failure)
        assertEquals("the other host saw no request at all", 0, target.seen.size)
        assertFalse(e.message!!.contains(password))
    }

    @Test
    fun `a redirect on the same host is followed for a PROPFIND but never for an upload`() {
        val server = serve()
        // Server redirects /dav-old/ to the real prefix on the same host and port.
        val redirecting = FakeHttpServer { ex, req ->
            if (req.path.startsWith("/dav-old/")) {
                ex.responseHeaders.add("Location", "http://127.0.0.1:${server.port}$prefix")
                ex.sendResponseHeaders(301, -1)
            } else {
                ex.sendResponseHeaders(404, -1)
            }
        }
        servers += redirecting
        // Same host AND port required: a different port is another origin and must be refused.
        val e = runCatching { provider(redirecting, base = redirecting.base + "/dav-old/").testConnection(Cancellation.NEVER) }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.UNSAFE, e.failure)

        val sameOrigin = FakeHttpServer { ex, req ->
            when {
                req.path == "/dav/" && req.method == "PROPFIND" -> { ex.responseHeaders.add("Location", "/dav/real/"); ex.sendResponseHeaders(301, -1) }
                req.path == "/dav/real/" && req.method == "PROPFIND" -> {
                    val xml = "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/dav/real/</d:href></d:response></d:multistatus>".toByteArray()
                    ex.sendResponseHeaders(207, xml.size.toLong()); ex.responseBody.write(xml)
                }
                req.method == "PUT" -> { ex.responseHeaders.add("Location", "/elsewhere"); ex.sendResponseHeaders(307, -1) }
                else -> ex.sendResponseHeaders(404, -1)
            }
        }
        servers += sameOrigin
        val p = WebDavProvider(WebDavSettings(sameOrigin.base + "/dav", user, true), password.toCharArray(), SocketHttpTransport())
        p.testConnection(Cancellation.NEVER)
        assertEquals(listOf("/dav/", "/dav/real/"), sameOrigin.seen.map { it.path })
        assertTrue("credentials went along on the same-host hop", sameOrigin.seen.all { it.headers["authorization"] != null })
        val upload = runCatching { p.put("a.jpg", "z".byteInputStream(), 1, false, Cancellation.NEVER) {} }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.UNSAFE, upload.failure)
    }

    @Test
    fun `plain http is refused unless allowed and local`() {
        val e = runCatching { WebDavProvider(WebDavSettings("http://127.0.0.1:1/dav/", user, false), "p".toCharArray(), SocketHttpTransport()) }.exceptionOrNull() as SyncException
        assertEquals(SyncCopy.WEBDAV_BAD_ADDRESS, e.message)
        val remote = runCatching { WebDavProvider(WebDavSettings("http://cloud.example.com/dav/", user, true), "p".toCharArray(), SocketHttpTransport()) }.exceptionOrNull() as SyncException
        assertEquals(SyncCopy.WEBDAV_BAD_ADDRESS, remote.message)
    }

    @Test
    fun `the caller itself refuses plain http to a public host even if a transport would send it`() {
        var sent = 0
        val caller = HttpCaller({ _, _ -> sent++; throw AssertionError("must not send") }, allowPlainHttpOnLocal = true)
        val e = runCatching { caller.call(HttpRequest("GET", "http://example.com/")) }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.UNSAFE, e.failure)
        assertEquals(0, sent)
    }

    @Test
    fun `the password buffer is wiped after construction`() {
        val buffer = "secret-secret".toCharArray()
        WebDavProvider(WebDavSettings("https://h/dav/", user), buffer, SocketHttpTransport())
        assertTrue(buffer.all { it == '\u0000' })
    }

    @Test
    fun `the whole engine runs against it and a cancelled run keeps the manifest consistent`() {
        val server = serve()
        val library = FakeLibrary()
        val big = library.add(1, "big.jpg", randomBytes(2_000_000, 1), takenMillis = 1_700_000_000_000L)
        library.add(2, "small.jpg", randomBytes(1_000, 2), takenMillis = 1_600_000_000_000L)
        val store = MemoryManifestStore()
        val provider = provider(server)
        fun engine() = SyncEngine(provider, library.source, store, { true }, retry = SyncRetryPolicy.NONE, sleep = {})
        // Stop in the middle of the first (newest, big) file.
        kotlinx.coroutines.runBlocking {
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).let { scope ->
                lateinit var j: kotlinx.coroutines.Job
                j = scope.launch {
                    engine().run(library.candidates, null) { if (it is SyncRunEvent.Bytes && it.inCurrentFile > 100_000) j.cancel() }
                }
                j
            }
            job.join()
        }
        val afterCancel = store.load()
        assertTrue("no done line for the interrupted file", afterCancel.doneFor(big.key) == null)
        assertNotNull("a begun line says the path is ours", afterCancel.begunFor(big.key))
        val partialPath = afterCancel.begunFor(big.key)!!.remotePath
        // A server may keep whatever arrived (this one does, once the dropped connection reaches it).
        val deadline = System.currentTimeMillis() + 3_000
        while (!files.containsKey(partialPath) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        if (!files.containsKey(partialPath)) files[partialPath] = ByteArray(1234)
        assertTrue("a partial copy is at the final path", files.getValue(partialPath).size < library.contentOf(1).size)
        val result = kotlinx.coroutines.runBlocking { engine().run(library.candidates, null) {} }
        assertTrue(result.state.toString(), result.state is SyncRunState.Finished)
        val manifest = store.load()
        assertEquals(2, manifest.done.size)
        assertEquals("the partial was replaced, not duplicated", partialPath, manifest.doneFor(big.key)!!.remotePath)
        assertArrayEquals(library.contentOf(1), files[partialPath])
        assertEquals(0, manifest.begun.size)
        assertEquals(2, files.size)
    }
}
