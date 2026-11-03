package com.fotoxplorr.app.sync

import com.fotoxplorr.feature.airemote.SyncHttp
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Connect build's real transport (OkHttp, in :feature:ai-remote) against a local server: it proves
 * the adapter sends the verb, headers and a streamed body as asked, does NOT follow redirects, and
 * reads headers and bodies back. It runs in this flavor's unit tests only, never against the internet.
 */
class SyncHttpTest {
    private val servers = ArrayList<FakeHttpServer>()
    private fun transport() = SyncTransports.create()

    @After
    fun stop() = servers.forEach { it.close() }

    private fun serve(handler: (Exchange, SeenRequest, FakeHttpServer) -> Unit): FakeHttpServer {
        lateinit var s: FakeHttpServer
        s = FakeHttpServer { ex, req -> handler(ex, req, s) }
        servers += s
        return s
    }

    @Test
    fun `the connect build offers a transport`() {
        assertTrue(SyncTransports.available)
    }

    @Test
    fun `a streamed PUT arrives whole with its length and headers`() {
        val server = serve { ex, _, s -> s.reply(ex, 201, headers = mapOf("X-Thing" to "a", "Etag" to "\"q\"")) }
        val data = randomBytes(300_000, 3)
        val response = transport().execute(
            HttpRequest("PUT", server.base + "/a/b%20c.jpg", listOf("Authorization" to "Basic abc", "Content-Type" to "application/octet-stream"), bodyStream = data.inputStream(), bodyLength = data.size.toLong()),
            Cancellation.NEVER,
        )
        response.use {
            assertEquals(201, it.code)
            assertEquals("a", it.header("x-thing"))
            assertEquals("\"q\"", it.header("ETag"))
        }
        val seen = server.seen.single()
        assertEquals("PUT", seen.method)
        assertEquals("/a/b%20c.jpg", seen.path)
        assertEquals("Basic abc", seen.headers["authorization"])
        assertEquals(data.size.toString(), seen.headers["content-length"])
        assertArrayEquals(data, seen.body)
    }

    @Test
    fun `webdav verbs and a byte body are sent`() {
        val server = serve { ex, _, s -> s.reply(ex, 207, "<x/>".toByteArray()) }
        for (verb in listOf("PROPFIND", "MKCOL")) {
            val response = transport().execute(HttpRequest(verb, server.base + "/dav/", listOf("Depth" to "1"), bodyBytes = if (verb == "PROPFIND") "<p/>".toByteArray() else null), Cancellation.NEVER)
            response.use { assertEquals(207, it.code); assertEquals("<x/>", it.bodyText()) }
        }
        assertEquals(listOf("PROPFIND", "MKCOL"), server.seen.map { it.method })
        assertEquals("1", server.seen[0].headers["depth"])
        assertEquals("<p/>", String(server.seen[0].body))
    }

    @Test
    fun `a redirect comes back as a response and is not followed`() {
        val target = serve { ex, _, s -> s.reply(ex, 200) }
        val origin = serve { ex, _, _ ->
            ex.responseHeaders.add("Location", "http://localhost:${target.port}/x")
            ex.sendResponseHeaders(302, -1)
        }
        val response = transport().execute(HttpRequest("GET", origin.base + "/"), Cancellation.NEVER)
        response.use {
            assertEquals(302, it.code)
            assertEquals("http://localhost:${target.port}/x", it.header("location"))
        }
        assertEquals(0, target.seen.size)
    }

    @Test
    fun `a HEAD reports the real length and no encoding trickery`() {
        val server = serve { ex, _, s -> s.reply(ex, 200, ByteArray(4321)) }
        val response = transport().execute(HttpRequest("HEAD", server.base + "/o"), Cancellation.NEVER)
        response.use { assertEquals("4321", it.header("content-length")) }
        assertEquals("identity", server.seen.single().headers["accept-encoding"])
    }

    @Test
    fun `a cancelled upload stops sending and surfaces as the cancellation`() {
        val server = serve { ex, _, s -> s.reply(ex, 201) }
        var cancelled = false
        val cancel = Cancellation { if (cancelled) throw kotlinx.coroutines.CancellationException("stop") }
        val stream = object : java.io.InputStream() {
            var sent = 0L
            override fun read(): Int = 1.also { sent++; if (sent > 200_000) cancelled = true }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = minOf(len, 64 * 1024)
                sent += n
                if (sent > 200_000) cancelled = true
                return n
            }
        }
        val error = runCatching {
            transport().execute(HttpRequest("PUT", server.base + "/big", bodyStream = stream, bodyLength = 50_000_000L), cancel)
        }.exceptionOrNull()
        assertTrue(error.toString(), error is kotlinx.coroutines.CancellationException)
        assertTrue("far less than the whole body was read", stream.sent < 5_000_000)
    }

    @Test
    fun `connection trouble is a transient failure`() {
        val server = serve { ex, _, s -> s.reply(ex, 200) }
        val dead = server.base
        server.close()
        val error = runCatching { transport().execute(HttpRequest("GET", "$dead/"), Cancellation.NEVER) }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.TRANSIENT, error.failure)
        assertNull(error.cause?.cause?.let { null })
    }

    @Test
    fun `the webdav provider works over the real transport`() {
        val files = HashMap<String, ByteArray>()
        val server = serve { ex, req, s ->
            when (req.method) {
                "PROPFIND" -> s.reply(ex, 404)
                "MKCOL" -> s.reply(ex, 201)
                "PUT" -> { files[req.path] = req.body; s.reply(ex, 201) }
                else -> s.reply(ex, 405)
            }
        }
        val provider = WebDavProvider(WebDavSettings(server.base + "/dav/", "me", allowPlainHttpOnLocal = true), "pw".toCharArray(), transport())
        provider.ensureFolder("Fotoz/2023")
        // stat after the put answers 404 from this stub, so the entry is null; the bytes are what matter here.
        provider.put("Fotoz/2023/a.jpg", "hello".byteInputStream(), 5, false, Cancellation.NEVER) {}
        assertArrayEquals("hello".toByteArray(), files["/dav/Fotoz/2023/a.jpg"])
    }

    @Test
    fun `cancelling while waiting for the server's answer ends the call promptly`() {
        val server = serve { _, _, _ -> Thread.sleep(15_000) } // never answers
        val started = System.currentTimeMillis()
        val cancel = Cancellation { if (System.currentTimeMillis() - started > 400) throw kotlinx.coroutines.CancellationException("stop") }
        val error = runCatching { transport().execute(HttpRequest("PUT", server.base + "/x", bodyBytes = ByteArray(1000)), cancel) }.exceptionOrNull()
        assertTrue(error.toString(), error is kotlinx.coroutines.CancellationException)
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 5_000)
    }
}
