package com.fotoxplorr.feature.airemote

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source

/** A request in JDK types only: the app adapts its own request type to this one (this module never sees the app). */
class SyncHttpRequest(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>>,
    val bodyBytes: ByteArray?,
    val bodyStream: InputStream?,
    /** Exact number of bytes [bodyStream] will deliver; required with a stream. */
    val bodyLength: Long,
)

/** Header names are lower-case; repeated headers are joined with a comma. Closing releases the connection. */
class SyncHttpReply(
    val code: Int,
    val headers: Map<String, String>,
    val body: InputStream,
    private val response: Response,
) : Closeable {
    override fun close() = response.close()
}

class SyncHttpException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The OkHttp client behind cloud sync (WebDAV, S3-compatible). One instance serves a run.
 *
 * Deliberate choices, each load-bearing for the callers in the app's `sync` package:
 *  - Redirects are NEVER followed here (neither plain nor TLS-to-other-scheme): the caller decides, so
 *    credentials cannot be carried to another host by a library default.
 *  - A streamed body is one-shot (no silent replay of a half-read file); the caller retries from byte
 *    zero by opening the file again.
 *  - `Accept-Encoding: identity`, so a HEAD's Content-Length is the object's size and nothing is
 *    transparently decompressed under a signed request.
 *  - No logging interceptor: addresses and Authorization headers must not be loggable by construction.
 *
 * Reachable only from the Connect build. Exercised by a JVM test against a local server
 * (`SyncHttpTest` in the app's connect unit tests); never against the internet.
 */
class SyncHttp(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build(),
) {
    /** Blocking. [isCancelled] is polled between chunks of an outgoing body; true aborts the call with [SyncHttpException]. */
    fun execute(request: SyncHttpRequest, isCancelled: () -> Boolean): SyncHttpReply {
        val body: RequestBody? = when {
            request.bodyStream != null -> StreamBody(request.bodyStream, request.bodyLength, isCancelled)
            request.bodyBytes != null -> request.bodyBytes.toRequestBody(null)
            else -> null
        }
        val builder = Request.Builder().url(request.url).method(request.method.uppercase(Locale.ROOT), body)
        builder.header("Accept-Encoding", "identity")
        for ((name, value) in request.headers) builder.header(name, value)
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw SyncHttpException(e.message ?: "The connection failed", e)
        } catch (e: IllegalArgumentException) {
            throw SyncHttpException("The request was not valid", e)
        }
        val headers = LinkedHashMap<String, String>()
        for (name in response.headers.names()) {
            headers[name.lowercase(Locale.ROOT)] = response.headers.values(name).joinToString(",")
        }
        return SyncHttpReply(response.code, headers, response.body.byteStream(), response)
    }

    private class StreamBody(
        private val stream: InputStream,
        private val length: Long,
        private val isCancelled: () -> Boolean,
    ) : RequestBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = length
        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            val source = stream.source()
            var remaining = length
            val chunk = 64L * 1024
            while (remaining > 0) {
                if (isCancelled()) throw IOException("Cancelled")
                val read = source.read(sink.buffer, minOf(chunk, remaining))
                if (read < 0) throw IOException("The file ended before its announced length")
                remaining -= read
                sink.emitCompleteSegments()
            }
        }
    }
}
