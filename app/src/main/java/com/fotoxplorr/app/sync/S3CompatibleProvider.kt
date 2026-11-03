package com.fotoxplorr.app.sync

import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/** Non-secret S3 settings. The secret key is NOT here. */
data class S3Settings(
    /** The service address, e.g. `https://s3.eu-central-1.amazonaws.com`, `https://s3.us-west-004.backblazeb2.com`, `https://<account>.r2.cloudflarestorage.com`, `https://minio.lan:9000`. */
    val endpoint: String,
    val region: String,
    val bucket: String,
    val accessKeyId: String,
    /** True: `endpoint/bucket/key`. False: `bucket.endpoint/key` (virtual-host style; the bucket name must be a valid host label). */
    val pathStyle: Boolean = true,
    /** A folder inside the bucket to keep everything under, or empty. */
    val prefix: String = "",
    val allowPlainHttpOnLocal: Boolean = false,
)

/** Sizes that decide single versus multipart. Tests shrink them; the app uses the defaults. */
data class S3Tuning(
    val multipartThresholdBytes: Long = 16L * 1024 * 1024,
    val partSizeBytes: Long = 16L * 1024 * 1024,
    val partAttempts: Int = 3,
)

/**
 * Any S3-compatible object store: AWS S3, Backblaze B2 (S3 API), Cloudflare R2, MinIO, Wasabi. Requests are
 * signed with [SigV4]; the payload hash is the real SHA-256 of the bytes (never UNSIGNED-PAYLOAD) and
 * each body carries Content-MD5, so a byte flipped on the way is refused by the server. Files up to
 * [S3Tuning.multipartThresholdBytes] go in one PUT held in memory; larger files go as a multipart upload
 * in parts of [S3Tuning.partSizeBytes], each part retried up to [S3Tuning.partAttempts] times from its
 * in-memory buffer, then completed; if the transfer is cancelled or fails, the upload is ABORTED
 * (the one DELETE here removes our own unfinished upload, never an object) so no hidden parts keep
 * costing money. A crash cannot abort: set a lifecycle rule on the bucket to abort incomplete
 * multipart uploads after a day or two.
 *
 * Not resumable part by part across runs: parts are not re-listed, because an encrypted file is not
 * byte-identical on a second attempt. A file that failed starts again from byte zero.
 * Folders do not exist in S3, so [ensureFolder] does nothing and [stat] is a HEAD of the key.
 *
 * Redirects are not followed at all (a wrong region or endpoint answers 301/307; the person is told).
 * Never run against a real service.
 */
class S3CompatibleProvider(
    private val settings: S3Settings,
    secretKey: CharArray,
    transport: HttpTransport,
    private val tuning: S3Tuning = S3Tuning(),
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncProvider {
    private val secret = String(secretKey)
    private val endpointUri: URI = parseEndpoint(settings)
    private val caller = HttpCaller(transport, settings.allowPlainHttpOnLocal, followSameHostRedirects = false)
    @Volatile private var conditionalWrites = true
    private val keyPrefix: String = settings.prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }

    init {
        secretKey.fill('\u0000')
        if (!S3_BUCKET.matches(settings.bucket)) throw SyncException(SyncFailure.REJECTED, SyncCopy.S3_BAD_BUCKET)
        if (settings.region.isBlank()) throw SyncException(SyncFailure.REJECTED, SyncCopy.S3_BAD_REGION)
        if (settings.accessKeyId.isBlank()) throw SyncException(SyncFailure.REJECTED, SyncCopy.S3_BAD_ACCESS_KEY)
    }

    override val label: String get() = settings.bucket
    override val needsNetwork: Boolean = true

    override fun testConnection(cancel: Cancellation): String {
        val response = send("GET", "", listOf("list-type" to "2", "max-keys" to "1"), emptyBody(), cancel)
        response.use {
            val text = it.bodyText()
            if (it.code == 200) return SyncCopy.connectedTo(settings.bucket)
            throw failureOf(it.code, text, SyncCopy.S3_FAILED)
        }
    }

    override fun stat(path: String): RemoteEntry? {
        send("HEAD", keyPrefix + path, emptyList(), emptyBody(), Cancellation.NEVER).use { response ->
            return when (response.code) {
                200 -> RemoteEntry(
                    path,
                    response.header("content-length")?.toLongOrNull() ?: -1L,
                    false,
                    md5Hex = response.header("etag")?.trim('"')?.lowercase(Locale.ROOT)?.takeIf { SINGLE_PART_ETAG.matches(it) },
                )
                404 -> null
                // Without s3:ListBucket, S3 answers 403 (not 404) for a key that does not exist, so a write-only
                // key cannot tell "missing" from "refused" here. Treat it as missing: the conditional PUT below
                // (If-None-Match) still refuses to replace anything, and wrong credentials fail on the PUT itself.
                403 -> null
                else -> throw failureOf(response.code, "", SyncCopy.S3_FAILED)
            }
        }
    }

    override fun list(folder: String): List<RemoteEntry> {
        val prefix = keyPrefix + folder.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val out = ArrayList<RemoteEntry>()
        var token: String? = null
        do {
            val query = ArrayList<Pair<String, String>>()
            query += "list-type" to "2"
            query += "delimiter" to "/"
            query += "prefix" to prefix
            token?.let { query += "continuation-token" to it }
            val page = send("GET", "", query, emptyBody(), Cancellation.NEVER).use { response ->
                val text = response.bodyText(4 shl 20)
                if (response.code != 200) throw failureOf(response.code, text, SyncCopy.S3_FAILED)
                S3Xml.parseListing(text)
            }
            page.objects.filter { it.key != prefix }.forEach { out += RemoteEntry(it.key.removePrefix(keyPrefix), it.size, false) }
            page.commonPrefixes.forEach { out += RemoteEntry(it.removePrefix(keyPrefix).trimEnd('/'), -1L, true) }
            token = page.nextToken
        } while (token != null)
        return out
    }

    override fun ensureFolder(path: String) = Unit

    override fun put(
        path: String,
        input: InputStream,
        length: Long,
        overwrite: Boolean,
        cancel: Cancellation,
        onBytes: (Long) -> Unit,
    ): RemoteEntry? {
        val key = keyPrefix + path
        if (length <= tuning.multipartThresholdBytes) {
            val data = readExactly(input, length, cancel)
            putObject(key, data, !overwrite, cancel)
            onBytes(length)
        } else {
            multipart(key, input, length, !overwrite, cancel, onBytes)
        }
        return stat(path)
    }

    /**
     * A write that must not replace an existing object sends `If-None-Match: *` (S3, R2, MinIO; 412 means it
     * exists). A service that does not know the header (501) is asked again without it, once, and the
     * provider remembers: for those the pre-upload check is the only guard, and it needs s3:ListBucket.
     */
    private fun sendWrite(method: String, key: String, query: List<Pair<String, String>>, body: Body, conditional: Boolean, cancel: Cancellation): HttpResponse {
        val first = send(method, key, query, body, cancel, conditional && conditionalWrites)
        if (!(conditional && conditionalWrites) || first.code != 501) return first
        first.close()
        conditionalWrites = false
        return send(method, key, query, body, cancel, false)
    }

    private fun putObject(key: String, data: ByteArray, conditional: Boolean, cancel: Cancellation) {
        sendWrite("PUT", key, emptyList(), Body(data, sha256(data), md5(data)), conditional, cancel).use { response ->
            val text = response.bodyText()
            if (response.code != 200) throw failureOf(response.code, text, SyncCopy.S3_FAILED)
        }
    }

    private fun multipart(key: String, input: InputStream, length: Long, conditional: Boolean, cancel: Cancellation, onBytes: (Long) -> Unit) {
        val uploadId = send("POST", key, listOf("uploads" to ""), emptyBody(), cancel).use { response ->
            val text = response.bodyText()
            if (response.code != 200) throw failureOf(response.code, text, SyncCopy.S3_FAILED)
            S3Xml.uploadId(text) ?: throw SyncException(SyncFailure.REJECTED, "The service did not start an upload")
        }
        var finished = false
        try {
            val partSize = partSizeFor(length)
            val parts = ArrayList<Pair<Int, String>>()
            var sent = 0L
            var number = 1
            while (sent < length) {
                val size = minOf(partSize, length - sent)
                val data = readExactly(input, size, cancel)
                parts += number to uploadPart(key, uploadId, number, data, cancel)
                sent += size
                onBytes(sent)
                number++
            }
            val xml = S3Xml.completeBody(parts).toByteArray(Charsets.UTF_8)
            sendWrite("POST", key, listOf("uploadId" to uploadId), Body(xml, sha256(xml), md5(xml)), conditional, cancel).use { response ->
                val text = response.bodyText()
                // S3 may answer 200 with an error document when the assembly fails after the headers went out.
                if (response.code != 200 || S3Xml.errorCode(text) != null) {
                    throw failureOf(if (response.code == 200) 500 else response.code, text, SyncCopy.S3_FAILED)
                }
            }
            finished = true
        } finally {
            if (!finished) abort(key, uploadId)
        }
    }

    private fun uploadPart(key: String, uploadId: String, number: Int, data: ByteArray, cancel: Cancellation): String {
        var attempt = 1
        while (true) {
            cancel.throwIfCancelled()
            try {
                return send(
                    "PUT",
                    key,
                    listOf("partNumber" to number.toString(), "uploadId" to uploadId),
                    Body(data, sha256(data), md5(data)),
                    cancel,
                ).use { response ->
                    val text = response.bodyText()
                    if (response.code != 200) throw failureOf(response.code, text, SyncCopy.S3_FAILED)
                    response.header("etag") ?: throw SyncException(SyncFailure.TRANSIENT, "The service did not confirm the part")
                }
            } catch (e: SyncException) {
                if (!e.failure.retryable || attempt >= tuning.partAttempts) throw e
                attempt++
            }
        }
    }

    /** Best effort: the upload is also bounded by the bucket's lifecycle rule if this fails. */
    private fun abort(key: String, uploadId: String) {
        runCatching {
            send("DELETE", key, listOf("uploadId" to uploadId), emptyBody(), Cancellation.NEVER).close()
        }
    }

    private fun partSizeFor(length: Long): Long {
        // At most 10,000 parts; the part size grows in whole MiB for very large files.
        val minimum = (length + MAX_PARTS - 2) / (MAX_PARTS - 1)
        val mib = 1024L * 1024
        return if (minimum <= tuning.partSizeBytes) tuning.partSizeBytes else (minimum + mib - 1) / mib * mib
    }

    private fun readExactly(input: InputStream, size: Long, cancel: Cancellation): ByteArray {
        val data = ByteArray(Math.toIntExact(size))
        var filled = 0
        while (filled < data.size) {
            cancel.throwIfCancelled()
            val n = input.read(data, filled, data.size - filled)
            if (n < 0) throw SyncLocalChanged("The file ended early ($filled of $size bytes)")
            filled += n
        }
        return data
    }

    // ---- requests ----

    private class Body(val bytes: ByteArray, val sha256: String, val md5Base64: String?)

    private fun emptyBody() = Body(ByteArray(0), SigV4.EMPTY_SHA256, null)

    private fun send(method: String, key: String, query: List<Pair<String, String>>, body: Body, cancel: Cancellation, conditional: Boolean = false): HttpResponse {
        val host = hostHeader()
        val canonicalPath = canonicalPath(key)
        val amzDate = SigV4.amzDate(clock())
        val signedHeaders = LinkedHashMap<String, String>()
        signedHeaders["host"] = host
        signedHeaders["x-amz-content-sha256"] = body.sha256
        signedHeaders["x-amz-date"] = amzDate
        body.md5Base64?.let { signedHeaders["content-md5"] = it }
        if (conditional) signedHeaders["if-none-match"] = "*"
        val signed = SigV4.sign(method, canonicalPath, query, signedHeaders, body.sha256, settings.accessKeyId, secret, settings.region, "s3", amzDate)
        val headers = ArrayList<Pair<String, String>>()
        signedHeaders.filterKeys { it != "host" }.forEach { (k, v) -> headers += k to v }
        headers += "Authorization" to signed.authorization
        headers += "User-Agent" to "Fotoz-sync"
        val queryString = query.joinToString("&") { SigV4.encode(it.first) + (if (it.second.isEmpty() && it.first in VALUELESS) "" else "=" + SigV4.encode(it.second)) }
        val url = "${endpointBase()}$canonicalPath" + if (queryString.isEmpty()) "" else "?$queryString"
        val hasBody = method == "PUT" || method == "POST"
        return caller.call(
            HttpRequest(method, url, headers, bodyBytes = if (hasBody) body.bytes else null),
            cancel,
        )
    }

    private fun canonicalPath(key: String): String {
        val encodedKey = SigV4.encode(key, keepSlash = true)
        return if (settings.pathStyle) "/" + SigV4.encode(settings.bucket) + if (key.isEmpty()) "" else "/$encodedKey"
        else "/$encodedKey"
    }

    private fun endpointBase(): String {
        val host = endpointUri.host.lowercase(Locale.ROOT)
        val port = if (endpointUri.port >= 0) ":${endpointUri.port}" else ""
        val hostPart = if (settings.pathStyle) host else "${settings.bucket}.$host"
        return "${endpointUri.scheme}://$hostPart$port"
    }

    private fun hostHeader(): String {
        val host = endpointUri.host.lowercase(Locale.ROOT)
        val defaultPort = if (endpointUri.scheme == "https") 443 else 80
        val port = if (endpointUri.port >= 0 && endpointUri.port != defaultPort) ":${endpointUri.port}" else ""
        return (if (settings.pathStyle) host else "${settings.bucket}.$host") + port
    }

    private fun failureOf(code: Int, body: String, what: String): SyncException {
        val s3Code = S3Xml.errorCode(body)
        return when {
            code in 300..399 -> SyncException(SyncFailure.REJECTED, SyncCopy.S3_WRONG_ENDPOINT)
            s3Code != null && s3Code in QUOTA_CODES -> SyncException(SyncFailure.NO_SPACE, "$what: $s3Code")
            s3Code == "NoSuchBucket" -> SyncException(SyncFailure.REJECTED, SyncCopy.S3_NO_BUCKET)
            s3Code == "SlowDown" || s3Code == "RequestTimeout" || s3Code == "InternalError" || s3Code == "ServiceUnavailable" ->
                SyncException(SyncFailure.TRANSIENT, "$what: $s3Code")
            s3Code == "AuthorizationHeaderMalformed" || s3Code == "PermanentRedirect" || s3Code == "InvalidRegion" ->
                SyncException(SyncFailure.REJECTED, SyncCopy.S3_WRONG_ENDPOINT)
            s3Code == "RequestTimeTooSkewed" -> SyncException(SyncFailure.REJECTED, SyncCopy.S3_CLOCK)
            else -> HttpStatus.failureFor(code, s3Code?.let { "$what: $it" } ?: what)
        }
    }

    private fun sha256(data: ByteArray): String = SigV4.hex(MessageDigest.getInstance("SHA-256").digest(data))

    private fun md5(data: ByteArray): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(data))

    private companion object {
        const val MAX_PARTS = 10_000L
        val S3_BUCKET = Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
        val SINGLE_PART_ETAG = Regex("[0-9a-f]{32}")
        val VALUELESS = setOf("uploads")
        val QUOTA_CODES = setOf("QuotaExceeded", "StorageLimitExceeded", "InsufficientStorage", "cap_exceeded", "AccountProblem", "MaxMessageLengthExceeded")

        fun parseEndpoint(settings: S3Settings): URI {
            val text = settings.endpoint.trim().trimEnd('/').let { if (it.contains("://")) it else "https://$it" }
            val uri = try {
                URI(text)
            } catch (e: java.net.URISyntaxException) {
                throw SyncException(SyncFailure.REJECTED, SyncCopy.S3_BAD_ENDPOINT, e)
            }
            val host = uri.host
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (host.isNullOrBlank() || uri.rawUserInfo != null || (uri.rawPath ?: "").isNotEmpty() ||
                (scheme != "https" && !(scheme == "http" && settings.allowPlainHttpOnLocal && HostRules.isLocalHost(host)))
            ) {
                throw SyncException(SyncFailure.REJECTED, SyncCopy.S3_BAD_ENDPOINT)
            }
            return uri
        }
    }
}
