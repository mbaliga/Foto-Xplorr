package com.fotoxplorr.app.sync

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/*
 * Why raw sockets: the Android unit-test classpath is android.jar, which has neither the JDK's
 * com.sun.net.httpserver nor java.net.http, and HttpURLConnection there refuses PROPFIND and MKCOL. So the
 * tests carry a minimal HTTP/1.1 server and client of their own over java.net sockets (src/test is not
 * scanned by the offline verifier). Plain http, one request per connection, Content-Length bodies only.
 */

private fun readLine(input: InputStream): String? {
    val line = StringBuilder()
    while (true) {
        val b = input.read()
        if (b < 0) return if (line.isEmpty()) null else line.toString()
        if (b == '\n'.code) return line.toString().trimEnd('\r')
        line.append(b.toChar())
    }
}

/** A client over java.net.Socket: sends any verb, streams a body of a known length, never follows redirects. */
class SocketHttpTransport : HttpTransport {
    override fun execute(request: HttpRequest, cancel: Cancellation): HttpResponse {
        val uri = URI.create(request.url)
        val port = if (uri.port >= 0) uri.port else 80
        val socket = try {
            java.net.Socket(uri.host, port).also { it.soTimeout = 20_000 }
        } catch (e: java.io.IOException) {
            throw SyncException(SyncFailure.TRANSIENT, e.message ?: "connect", e)
        }
        try {
            val out = socket.getOutputStream().buffered()
            val path = (uri.rawPath.ifEmpty { "/" }) + (uri.rawQuery?.let { "?$it" } ?: "")
            val length = when {
                request.bodyStream != null -> request.bodyLength
                request.bodyBytes != null -> request.bodyBytes.size.toLong()
                else -> 0L
            }
            val head = StringBuilder("${request.method} $path HTTP/1.1\r\nHost: ${uri.host}:$port\r\nConnection: close\r\n")
            request.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            if (request.bodyStream != null || request.bodyBytes != null) head.append("Content-Length: $length\r\n")
            head.append("\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            when {
                request.bodyBytes != null -> out.write(request.bodyBytes)
                request.bodyStream != null -> {
                    val buffer = ByteArray(32 * 1024)
                    var left = length
                    while (left > 0) {
                        cancel.throwIfCancelled()
                        val n = request.bodyStream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        if (n < 0) throw java.io.IOException("body ended early")
                        out.write(buffer, 0, n)
                        left -= n
                    }
                }
            }
            out.flush()
            val input = socket.getInputStream().buffered()
            val status = readLine(input) ?: throw java.io.IOException("no answer")
            val code = status.split(' ')[1].toInt()
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                headers.merge(line.substringBefore(':').trim().lowercase(), line.substringAfter(':').trim()) { a, b -> "$a,$b" }
            }
            val declared = headers["content-length"]?.toLongOrNull()
            val body = if (request.method == "HEAD" || declared == 0L) ByteArray(0)
            else if (declared != null) input.readNBytes(declared.toInt()) else input.readAllBytes()
            socket.close()
            return HttpResponse(code, headers, body.inputStream())
        } catch (e: java.io.IOException) {
            runCatching { socket.close() }
            cancel.throwIfCancelled()
            throw SyncException(SyncFailure.TRANSIENT, e.message ?: "io", e)
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }
}

/** What a fake server saw. */
class SeenRequest(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: ByteArray)

class HeaderBag {
    val all = ArrayList<Pair<String, String>>()
    fun add(name: String, value: String) {
        all += name to value
    }
    fun has(name: String) = all.any { it.first.equals(name, ignoreCase = true) }
}

/** The server side of one request, shaped like the JDK's exchange so handlers read the same. */
class Exchange(val requestMethod: String, private val out: java.io.OutputStream) {
    val responseHeaders = HeaderBag()
    val responseBody: java.io.OutputStream get() = out
    private var sent = false

    fun sendResponseHeaders(code: Int, length: Long) {
        check(!sent)
        sent = true
        val head = StringBuilder("HTTP/1.1 $code ${if (code in 200..299) "OK" else "Status"}\r\n")
        responseHeaders.all.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        if (!responseHeaders.has("Content-Length")) head.append("Content-Length: ${if (length < 0) 0 else length}\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    fun close() {
        if (!sent) sendResponseHeaders(500, -1)
        runCatching { out.flush() }
    }
}

/** A tiny local HTTP server whose behaviour a test supplies. */
class FakeHttpServer(private val handler: (Exchange, SeenRequest) -> Unit) : AutoCloseable {
    val seen = CopyOnWriteArrayList<SeenRequest>()
    private val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    val port: Int get() = server.localPort
    val base: String get() = "http://127.0.0.1:$port"

    init {
        pool.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: java.io.IOException) { break }
                pool.execute { serve(socket) }
            }
        }
    }

    private fun serve(socket: java.net.Socket) {
        socket.use {
            socket.soTimeout = 20_000
            val input = socket.getInputStream().buffered()
            val line = readLine(input) ?: return
            val parts = line.split(' ')
            val target = parts[1]
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                headers.merge(h.substringBefore(':').trim().lowercase(), h.substringAfter(':').trim()) { a, b -> "$a,$b" }
            }
            val body = headers["content-length"]?.toIntOrNull()?.let { n -> runCatching { input.readNBytes(n) }.getOrDefault(ByteArray(0)) } ?: ByteArray(0)
            val request = SeenRequest(parts[0], target.substringBefore('?'), target.substringAfter('?', "").ifEmpty { null }, headers, body)
            seen += request
            val exchange = Exchange(parts[0], socket.getOutputStream().buffered())
            try {
                handler(exchange, request)
            } finally {
                runCatching { exchange.close() }
            }
        }
    }

    fun reply(exchange: Exchange, code: Int, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
        if (exchange.requestMethod == "HEAD") {
            exchange.responseHeaders.add("Content-Length", body.size.toString())
            exchange.sendResponseHeaders(code, -1)
        } else {
            exchange.sendResponseHeaders(code, body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.write(body)
        }
    }

    override fun close() {
        runCatching { server.close() }
        pool.shutdownNow()
    }
}

/** An in-memory manifest store. [appended] shows every line written, in order. */
class MemoryManifestStore(initial: String = "") : SyncManifestStore {
    var text = initial
    val appended = ArrayList<String>()
    override fun load(): SyncManifest = SyncManifest.decode(text)
    override fun append(line: String) {
        if (text.isEmpty()) text = SyncManifestCodec.HEADER + "\n"
        text += line
        appended += line
    }
    override fun compact(manifest: SyncManifest) {
        text = manifest.encode()
    }
}

/** A SafFileSystem over a plain directory. */
class DirectoryFileSystem(private val root: File) : SafFileSystem {
    var usable = true
    var failWritesAfterBytes: Long? = null
    override val rootLabel: String get() = root.name
    override fun isRootUsable(): Boolean = usable && root.isDirectory
    override fun stat(path: String): RemoteEntry? = File(root, path).takeIf { it.exists() }?.let {
        RemoteEntry(path, if (it.isFile) it.length() else -1L, it.isDirectory, it.lastModified())
    }
    override fun list(folder: String): List<RemoteEntry> =
        File(root, folder).listFiles().orEmpty().map { RemoteEntry(if (folder.isEmpty()) it.name else "$folder/${it.name}", if (it.isFile) it.length() else -1L, it.isDirectory) }
    override fun mkdirs(path: String) {
        File(root, path).mkdirs()
    }
    override fun create(path: String, truncateExisting: Boolean): OutputStream {
        val file = File(root, path)
        if (file.exists() && !truncateExisting) throw java.io.IOException("exists")
        val out = file.outputStream()
        val limit = failWritesAfterBytes ?: return out
        return object : OutputStream() {
            var count = 0L
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (count + len > limit) {
                    out.write(b, off, (limit - count).toInt().coerceAtLeast(0))
                    out.close()
                    throw java.io.IOException("card pulled out")
                }
                out.write(b, off, len)
                count += len
            }
            override fun close() = out.close()
        }
    }
}

/** Candidates and their bytes, in memory. */
class FakeLibrary {
    private val bytes = ConcurrentHashMap<Long, ByteArray>()
    val candidates = ArrayList<SyncCandidate>()
    var opens = 0

    fun add(
        id: Long,
        name: String,
        content: ByteArray,
        takenMillis: Long = 1_700_000_000_000L + id * 1000,
        modifiedSeconds: Long = 1_700_000_000L + id,
        folder: String = "Pictures/Camera",
    ): SyncCandidate {
        bytes[id] = content
        val c = SyncCandidate(id, name, "$folder/", "image/jpeg", content.size.toLong(), takenMillis, modifiedSeconds, folder, false)
        candidates += c
        return c
    }

    fun change(id: Long, content: ByteArray) {
        bytes[id] = content
        val i = candidates.indexOfFirst { it.mediaId == id }
        candidates[i] = candidates[i].copy(sizeBytes = content.size.toLong(), dateModifiedSeconds = candidates[i].dateModifiedSeconds + 1)
    }

    val source = SyncFileSource { c ->
        opens++
        bytes[c.mediaId]?.let { OpenedLocalFile(it.inputStream(), it.size.toLong()) }
    }

    fun contentOf(id: Long): ByteArray = bytes.getValue(id)
}

fun ByteArray.sha256Hex(): String = SigV4.sha256Hex(this)

fun randomBytes(size: Int, seed: Long = 7): ByteArray = ByteArray(size).also { java.util.Random(seed).nextBytes(it) }

fun InputStream.drain(): ByteArray = readAllBytes()
