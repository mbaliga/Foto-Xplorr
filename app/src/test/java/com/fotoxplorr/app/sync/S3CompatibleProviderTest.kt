package com.fotoxplorr.app.sync

import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The S3 provider against a local fake that VERIFIES the signature, payload hash and Content-MD5 of every request. Not a real service. */
class S3CompatibleProviderTest {
    private val accessKey = "AKIDEXAMPLE"
    private val secretKey = "s3cr3t/Key+value"
    private val bucket = "photos"
    private val objects = ConcurrentHashMap<String, ByteArray>()
    private val uploads = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()
    private val aborted = ArrayList<String>()
    private val badRequests = ArrayList<String>()
    private val partAttempts = ConcurrentHashMap<Int, AtomicInteger>()
    private var failPartOnce: Int? = null
    private var completeWithError = false
    private var override: ((SeenRequest) -> Pair<Int, String>?)? = null
    private val servers = ArrayList<FakeHttpServer>()
    private var pageSize = 1000

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun serve(): FakeHttpServer {
        lateinit var server: FakeHttpServer
        server = FakeHttpServer { ex, req ->
            override?.invoke(req)?.let { (code, body) -> server.reply(ex, code, body.toByteArray()); return@FakeHttpServer }
            if (!signatureOk(req)) {
                badRequests += "signature ${req.method} ${req.path}"
                server.reply(ex, 403, "<Error><Code>SignatureDoesNotMatch</Code><Message>no</Message></Error>".toByteArray()); return@FakeHttpServer
            }
            val declared = req.headers["x-amz-content-sha256"]
            if (declared != SigV4.sha256Hex(req.body)) {
                badRequests += "payload hash ${req.method} ${req.path}"
                server.reply(ex, 400, "<Error><Code>XAmzContentSHA256Mismatch</Code></Error>".toByteArray()); return@FakeHttpServer
            }
            req.headers["content-md5"]?.let {
                if (it != Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(req.body))) {
                    badRequests += "md5"
                    server.reply(ex, 400, "<Error><Code>BadDigest</Code></Error>".toByteArray()); return@FakeHttpServer
                }
            }
            val decoded = WebDavXml.decodePath(req.path)
            val key = decoded.removePrefix("/$bucket").trimStart('/')
            val q = queryOf(req)
            when {
                req.method == "GET" && key.isEmpty() -> list(server, ex, q)
                req.method == "HEAD" -> objects[key]?.let { server.reply(ex, 200, it) } ?: server.reply(ex, 404)
                req.method == "PUT" && q.containsKey("partNumber") -> {
                    val n = q.getValue("partNumber").toInt()
                    if (failPartOnce == n && partAttempts.getOrPut(n) { AtomicInteger() }.incrementAndGet() == 1) {
                        server.reply(ex, 503, "<Error><Code>SlowDown</Code></Error>".toByteArray()); return@FakeHttpServer
                    }
                    uploads[q.getValue("uploadId")]!![n] = req.body
                    server.reply(ex, 200, headers = mapOf("ETag" to "\"etag-$n-${hex(MessageDigest.getInstance("MD5").digest(req.body)).take(6)}\""))
                }
                req.method == "PUT" -> { objects[key] = req.body; server.reply(ex, 200, headers = mapOf("ETag" to "\"x\"")) }
                req.method == "POST" && q.containsKey("uploads") -> {
                    val id = "upload-${uploads.size + 1}"
                    uploads[id] = ConcurrentHashMap()
                    server.reply(ex, 200, "<InitiateMultipartUploadResult><Bucket>$bucket</Bucket><Key>x</Key><UploadId>$id</UploadId></InitiateMultipartUploadResult>".toByteArray())
                }
                req.method == "POST" && q.containsKey("uploadId") -> {
                    if (completeWithError) {
                        server.reply(ex, 200, "<Error><Code>InternalError</Code><Message>try again</Message></Error>".toByteArray()); return@FakeHttpServer
                    }
                    val parts = uploads.getValue(q.getValue("uploadId"))
                    val numbers = Regex("<PartNumber>(\\d+)</PartNumber>").findAll(String(req.body)).map { it.groupValues[1].toInt() }.toList()
                    objects[key] = numbers.fold(ByteArray(0)) { acc, n -> acc + parts.getValue(n) }
                    server.reply(ex, 200, "<CompleteMultipartUploadResult><Key>x</Key></CompleteMultipartUploadResult>".toByteArray())
                }
                req.method == "DELETE" && q.containsKey("uploadId") -> {
                    aborted += q.getValue("uploadId"); uploads.remove(q.getValue("uploadId")); server.reply(ex, 204)
                }
                else -> server.reply(ex, 405)
            }
        }
        servers += server
        return server
    }

    private fun queryOf(req: SeenRequest): Map<String, String> =
        (req.query ?: "").split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('='); val v = it.substringAfter('=', "")
            WebDavXml.decodePath(k) to WebDavXml.decodePath(v)
        }

    private fun signatureOk(req: SeenRequest): Boolean {
        val auth = req.headers["authorization"] ?: return false
        val signedNames = Regex("SignedHeaders=([^,]+)").find(auth)?.groupValues?.get(1)?.split(';') ?: return false
        val date = req.headers["x-amz-date"] ?: return false
        val headers = signedNames.associateWith { req.headers.getValue(it) }
        val query = (req.query ?: "").split('&').filter { it.isNotEmpty() }.map {
            WebDavXml.decodePath(it.substringBefore('=')) to WebDavXml.decodePath(it.substringAfter('=', ""))
        }
        val expected = SigV4.sign(req.method, req.path, query, headers, req.headers.getValue("x-amz-content-sha256"), accessKey, secretKey, "eu-test-1", "s3", date)
        return expected.authorization == auth
    }

    private fun list(server: FakeHttpServer, ex: Exchange, q: Map<String, String>) {
        val prefix = q["prefix"].orEmpty()
        val delimiter = q["delimiter"]
        val start = q["continuation-token"]?.toInt() ?: 0
        val matching = objects.keys.filter { it.startsWith(prefix) }.sorted()
        val entries = ArrayList<Pair<Boolean, String>>() // isPrefix to name
        val seenPrefixes = HashSet<String>()
        for (k in matching) {
            val rest = k.removePrefix(prefix)
            if (delimiter != null && rest.contains(delimiter)) {
                val p = prefix + rest.substringBefore(delimiter) + delimiter
                if (seenPrefixes.add(p)) entries += true to p
            } else {
                entries += false to k
            }
        }
        val page = entries.drop(start).take(pageSize)
        val truncated = start + pageSize < entries.size
        val xml = buildString {
            append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>$bucket</Name>")
            page.filter { !it.first }.forEach { append("<Contents><Key>${esc(it.second)}</Key><Size>${objects.getValue(it.second).size}</Size></Contents>") }
            page.filter { it.first }.forEach { append("<CommonPrefixes><Prefix>${esc(it.second)}</Prefix></CommonPrefixes>") }
            append("<IsTruncated>$truncated</IsTruncated>")
            if (truncated) append("<NextContinuationToken>${start + pageSize}</NextContinuationToken>")
            append("</ListBucketResult>")
        }
        server.reply(ex, 200, xml.toByteArray())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")

    private fun provider(
        server: FakeHttpServer,
        tuning: S3Tuning = S3Tuning(),
        secret: String = secretKey,
        settings: S3Settings = S3Settings(server.base, "eu-test-1", bucket, accessKey, pathStyle = true, allowPlainHttpOnLocal = true),
    ) = S3CompatibleProvider(settings, secret.toCharArray(), SocketHttpTransport(), tuning)

    @After
    fun stop() = servers.forEach { it.close() }

    private fun put(p: S3CompatibleProvider, path: String, data: ByteArray, cancel: Cancellation = Cancellation.NEVER, onBytes: (Long) -> Unit = {}) =
        p.put(path, data.inputStream(), data.size.toLong(), false, cancel, onBytes)

    @Test
    fun `a small file is one signed PUT with its real payload hash and md5`() {
        val server = serve()
        val data = randomBytes(5_000)
        val entry = put(provider(server), "Fotoz/2023/2023-11/Ünï cøde 日本 (2).jpg", data)
        assertTrue(badRequests.toString(), badRequests.isEmpty())
        assertArrayEquals(data, objects["Fotoz/2023/2023-11/Ünï cøde 日本 (2).jpg"])
        assertEquals(5_000L, entry!!.sizeBytes)
        val put = server.seen.first { it.method == "PUT" }
        assertEquals(SigV4.sha256Hex(data), put.headers["x-amz-content-sha256"])
        assertTrue(put.headers.containsKey("content-md5"))
        assertTrue(put.path.startsWith("/photos/Fotoz/2023/2023-11/%C3%9Cn"))
    }

    @Test
    fun `stat is a HEAD and a missing key is null`() {
        val server = serve()
        val p = provider(server)
        put(p, "a/b.jpg", ByteArray(77))
        assertEquals(77L, p.stat("a/b.jpg")!!.sizeBytes)
        assertNull(p.stat("a/missing.jpg"))
        assertEquals("HEAD", server.seen.last().method)
    }

    @Test
    fun `listing follows continuation tokens and reports folders`() {
        val server = serve()
        pageSize = 2
        val p = provider(server)
        for (i in 1..5) put(p, "Fotoz/2023/2023-11/f$i.jpg", ByteArray(i))
        put(p, "Fotoz/2023/2023-12/g.jpg", ByteArray(9))
        put(p, "Fotoz/top.jpg", ByteArray(3))
        val inMonth = p.list("Fotoz/2023/2023-11").associateBy { it.path }
        assertEquals((1..5).map { "Fotoz/2023/2023-11/f$it.jpg" }.toSet(), inMonth.keys)
        assertEquals(4L, inMonth.getValue("Fotoz/2023/2023-11/f4.jpg").sizeBytes)
        val top = p.list("Fotoz").associateBy { it.path }
        assertEquals(setOf("Fotoz/2023", "Fotoz/top.jpg"), top.keys)
        assertTrue(top.getValue("Fotoz/2023").isDirectory)
        assertTrue(server.seen.count { it.method == "GET" } > 3)
    }

    @Test
    fun `a prefix keeps everything under one folder of the bucket`() {
        val server = serve()
        val p = provider(server, settings = S3Settings(server.base, "eu-test-1", bucket, accessKey, prefix = "/backup/phone/", allowPlainHttpOnLocal = true))
        put(p, "Fotoz/a.jpg", ByteArray(4))
        assertTrue(objects.containsKey("backup/phone/Fotoz/a.jpg"))
        assertEquals(4L, p.stat("Fotoz/a.jpg")!!.sizeBytes)
        assertEquals(listOf("Fotoz/a.jpg"), p.list("Fotoz").map { it.path })
    }

    @Test
    fun `a big file goes as a multipart upload in order and reads back identical`() {
        val server = serve()
        val data = randomBytes(2_500, 5)
        val progress = ArrayList<Long>()
        val p = provider(server, S3Tuning(multipartThresholdBytes = 1_000, partSizeBytes = 400))
        put(p, "Fotoz/big.mp4", data, onBytes = { progress += it })
        assertTrue(badRequests.toString(), badRequests.isEmpty())
        assertArrayEquals(data, objects["Fotoz/big.mp4"])
        val partPuts = server.seen.filter { it.method == "PUT" && (it.query ?: "").contains("partNumber") }
        assertEquals(7, partPuts.size)
        assertEquals(listOf(400, 400, 400, 400, 400, 400, 100), partPuts.map { it.body.size })
        assertEquals(listOf(400L, 800L, 1200L, 1600L, 2000L, 2400L, 2500L), progress)
        assertTrue(aborted.isEmpty())
    }

    @Test
    fun `a part that gets a slow-down answer is retried and the upload completes`() {
        val server = serve()
        failPartOnce = 2
        val data = randomBytes(1_300, 6)
        val p = provider(server, S3Tuning(multipartThresholdBytes = 1_000, partSizeBytes = 500))
        put(p, "v.mp4", data)
        assertArrayEquals(data, objects["v.mp4"])
        assertEquals(2, partAttempts.getValue(2).get())
        assertTrue(aborted.isEmpty())
    }

    @Test
    fun `a part that keeps failing aborts the upload`() {
        val server = serve()
        override = { req -> if ((req.query ?: "").contains("partNumber=2")) 503 to "<Error><Code>SlowDown</Code></Error>" else null }
        val p = provider(server, S3Tuning(multipartThresholdBytes = 1_000, partSizeBytes = 500, partAttempts = 3))
        val e = runCatching { put(p, "v.mp4", randomBytes(1_300)) }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.TRANSIENT, e.failure)
        assertEquals(3, server.seen.count { (it.query ?: "").contains("partNumber=2") })
        assertEquals(1, aborted.size)
        assertFalse(objects.containsKey("v.mp4"))
    }

    @Test
    fun `cancelling mid upload aborts it so no hidden parts are left`() {
        val server = serve()
        val data = randomBytes(5_000, 7)
        val p = provider(server, S3Tuning(multipartThresholdBytes = 1_000, partSizeBytes = 500))
        var cancelled = false
        val cancel = Cancellation { if (cancelled) throw kotlinx.coroutines.CancellationException("stop") }
        val error = runCatching { put(p, "v.mp4", data, cancel) { if (it >= 1_500) cancelled = true } }.exceptionOrNull()
        assertTrue(error is kotlinx.coroutines.CancellationException)
        assertEquals(listOf("upload-1"), aborted)
        assertTrue(uploads.isEmpty())
        assertFalse(objects.containsKey("v.mp4"))
    }

    @Test
    fun `a 200 answer that carries an error document is a failure and aborts`() {
        val server = serve()
        completeWithError = true
        val p = provider(server, S3Tuning(multipartThresholdBytes = 1_000, partSizeBytes = 500))
        val e = runCatching { put(p, "v.mp4", randomBytes(1_200)) }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.TRANSIENT, e.failure)
        assertEquals(1, aborted.size)
        assertFalse(objects.containsKey("v.mp4"))
    }

    @Test
    fun `wrong credentials are an AUTH failure`() {
        val server = serve()
        val e = runCatching { provider(server, secret = "not-the-secret").testConnection(Cancellation.NEVER) }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.AUTH, e.failure)
        assertTrue(e.message!!.contains("SignatureDoesNotMatch") || e.message!!.contains("403"))
        assertFalse(e.message!!.contains("not-the-secret"))
    }

    @Test
    fun `connection test lists one key and names the bucket`() {
        val server = serve()
        assertEquals(SyncCopy.connectedTo(bucket), provider(server).testConnection(Cancellation.NEVER))
        assertEquals("1", queryOf(server.seen.last())["max-keys"])
    }

    @Test
    fun `service error codes map to the failure the engine acts on`() {
        val server = serve()
        val p = provider(server)
        fun failure(code: Int, s3: String?): SyncFailure {
            override = { code to (s3?.let { "<Error><Code>$it</Code><Message>m</Message></Error>" } ?: "") }
            return (runCatching { p.testConnection(Cancellation.NEVER) }.exceptionOrNull() as SyncException).failure
        }
        assertEquals(SyncFailure.NO_SPACE, failure(403, "QuotaExceeded"))
        assertEquals(SyncFailure.NO_SPACE, failure(403, "cap_exceeded"))
        assertEquals(SyncFailure.REJECTED, failure(404, "NoSuchBucket"))
        assertEquals(SyncFailure.TRANSIENT, failure(503, "SlowDown"))
        assertEquals(SyncFailure.REJECTED, failure(400, "AuthorizationHeaderMalformed"))
        assertEquals(SyncFailure.REJECTED, failure(403, "RequestTimeTooSkewed"))
        assertEquals(SyncFailure.AUTH, failure(403, "AccessDenied"))
        assertEquals(SyncFailure.TRANSIENT, failure(500, null))
    }

    @Test
    fun `a redirect is never followed`() {
        val target = serve()
        val origin = FakeHttpServer { ex, _ ->
            ex.responseHeaders.add("Location", "http://localhost:${target.port}/photos")
            ex.sendResponseHeaders(301, -1)
        }
        servers += origin
        val e = runCatching { provider(origin).testConnection(Cancellation.NEVER) }.exceptionOrNull() as SyncException
        assertEquals(SyncCopy.S3_WRONG_ENDPOINT, e.message)
        assertEquals(1, origin.seen.size)
        assertEquals(0, target.seen.size)
    }

    @Test
    fun `virtual host style puts the bucket in the host and the key in the path`() {
        val seen = ArrayList<HttpRequest>()
        val transport = HttpTransport { request, _ ->
            seen += request
            HttpResponse(200, mapOf("content-length" to "3"), "<ListBucketResult/>".byteInputStream())
        }
        val p = S3CompatibleProvider(
            S3Settings("https://s3.eu-central-1.amazonaws.com", "eu-central-1", "my-photos", accessKey, pathStyle = false),
            secretKey.toCharArray(), transport,
        )
        p.stat("Fotoz/a b.jpg")
        val request = seen.single()
        assertEquals("https://my-photos.s3.eu-central-1.amazonaws.com/Fotoz/a%20b.jpg", request.url)
        assertNull("host is signed but left to the transport to send", request.header("host"))
        assertTrue(request.header("authorization")!!.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
        assertTrue(request.header("authorization")!!.contains("/eu-central-1/s3/aws4_request"))
    }

    @Test
    fun `path style puts the bucket first and keeps a custom port in the signed host`() {
        val seen = ArrayList<HttpRequest>()
        val transport = HttpTransport { request, _ -> seen += request; HttpResponse(404, emptyMap(), ByteArray(0).inputStream()) }
        val p = S3CompatibleProvider(
            S3Settings("https://minio.lan:9000", "us-east-1", "photos", accessKey, pathStyle = true),
            secretKey.toCharArray(), transport,
        )
        assertNull(p.stat("a.jpg"))
        assertEquals("https://minio.lan:9000/photos/a.jpg", seen.single().url)
    }

    @Test
    fun `settings that cannot be right are refused before anything is sent`() {
        fun refused(settings: S3Settings): String? =
            (runCatching { S3CompatibleProvider(settings, "k".toCharArray(), { _, _ -> throw AssertionError("sent") }) }.exceptionOrNull() as? SyncException)?.message
        val ok = S3Settings("https://s3.example.com", "r", "bucket-1", "AK")
        assertEquals(SyncCopy.S3_BAD_BUCKET, refused(ok.copy(bucket = "Bad_Bucket")))
        assertEquals(SyncCopy.S3_BAD_BUCKET, refused(ok.copy(bucket = "../x")))
        assertEquals(SyncCopy.S3_BAD_REGION, refused(ok.copy(region = " ")))
        assertEquals(SyncCopy.S3_BAD_ACCESS_KEY, refused(ok.copy(accessKeyId = "")))
        assertEquals(SyncCopy.S3_BAD_ENDPOINT, refused(ok.copy(endpoint = "https://s3.example.com/with/path")))
        assertEquals(SyncCopy.S3_BAD_ENDPOINT, refused(ok.copy(endpoint = "http://s3.example.com")))
        assertEquals(SyncCopy.S3_BAD_ENDPOINT, refused(ok.copy(endpoint = "https://user:pw@s3.example.com")))
        assertEquals(SyncCopy.S3_BAD_ENDPOINT, refused(ok.copy(endpoint = "http://s3.example.com", allowPlainHttpOnLocal = true)))
        assertNull(refused(ok.copy(endpoint = "s3.example.com")))
        assertNull(refused(ok.copy(endpoint = "http://minio.lan:9000", allowPlainHttpOnLocal = true)))
    }

    @Test
    fun `the secret buffer is wiped after construction`() {
        val buffer = "top-secret".toCharArray()
        S3CompatibleProvider(S3Settings("https://s3.example.com", "r", "bucket-1", "AK"), buffer, { _, _ -> throw AssertionError() })
        assertTrue(buffer.all { it == '\u0000' })
    }

    @Test
    fun `parts are sized so a huge file stays under ten thousand parts`() {
        val server = serve()
        val p = provider(server)
        // 200 GB at the default 16 MiB would be 12,500 parts; the size must grow.
        val method = p.javaClass.getDeclaredMethod("partSizeFor", Long::class.javaPrimitiveType).apply { isAccessible = true }
        val size = method.invoke(p, 200L * 1024 * 1024 * 1024) as Long
        assertTrue(200L * 1024 * 1024 * 1024 / size < 10_000)
        assertEquals(0L, size % (1024 * 1024))
        assertEquals(16L * 1024 * 1024, method.invoke(p, 2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `an engine run against it sends, then a second run sends nothing`() {
        val server = serve()
        val library = FakeLibrary()
        library.add(1, "a.jpg", randomBytes(3_000, 1))
        library.add(2, "b.mp4", randomBytes(4_000, 2))
        val store = MemoryManifestStore()
        val p = provider(server, S3Tuning(multipartThresholdBytes = 3_500, partSizeBytes = 1_000))
        fun run() = runBlocking { SyncEngine(p, library.source, store, { true }, sleep = {}).run(library.candidates, null) {} }
        val first = run().state as SyncRunState.Finished
        assertEquals(2, first.tally.uploaded)
        assertEquals(2, objects.size)
        val requestsAfterFirst = server.seen.size
        val second = run().state as SyncRunState.Finished
        assertEquals(0, second.tally.uploaded)
        assertEquals(2, second.tally.upToDate)
        assertEquals("no request at all for files the manifest holds", requestsAfterFirst, server.seen.size)
        assertTrue(badRequests.isEmpty())
    }
}
