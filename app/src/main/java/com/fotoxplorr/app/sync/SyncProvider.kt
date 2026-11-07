package com.fotoxplorr.app.sync

import java.io.Closeable
import java.io.InputStream
import java.net.URI
import java.util.Locale

/**
 * A place to send copies to. Blocking, like `BackupDestination`: callers run it off the main
 * thread, and a failure is a [SyncException], never an empty answer (an empty listing and a
 * listing that failed lead to opposite decisions).
 *
 * There is NO delete here, on purpose: slice 1 never removes anything from a destination, and an
 * interface that cannot do it cannot be made to by a later bug. Paths are `/`-separated and
 * relative to the destination's root (the folder the person chose, or the bucket / base URL);
 * callers pass only paths [RemoteLayout.isSafePath] accepts.
 */
interface SyncProvider : Closeable {
    /** What the person sees as the destination's name. */
    val label: String

    /**
     * False when the size this provider reports right after a write cannot be trusted (a cloud app's
     * folder may show a stale size or 0 for a moment). The engine then records a file whose bytes were
     * all handed over, instead of treating a size mismatch as a failure.
     */
    val sizeReportReliable: Boolean get() = true

    /** True for providers that talk over a network (the engine then applies the Wi-Fi-only guard). */
    val needsNetwork: Boolean

    /**
     * Proves the settings work: reaches the server, authenticates, and can see (or create) the root.
     * Returns one short sentence for the person; throws [SyncException] otherwise.
     */
    fun testConnection(cancel: Cancellation = Cancellation.NEVER): String

    /** The file or folder at [path], or null when there is none. */
    fun stat(path: String): RemoteEntry?

    /** The direct children of [folder] (which may not exist: then empty). */
    fun list(folder: String): List<RemoteEntry>

    /** Makes [path] and every missing parent. Idempotent. */
    fun ensureFolder(path: String)

    /**
     * Writes [length] bytes of [input] to [path]. With [overwrite] false an existing file is a
     * [SyncFailure.REJECTED] failure and is left alone; true is used only for a half-sent file of
     * our own ([SyncBegun]). Returns the entry the server now reports, or null when it cannot say.
     * [onBytes] gets the number of bytes handed over so far, for progress. An implementation checks
     * [cancel] between chunks and must not leave a recorded success behind a cancelled transfer
     * (recording is the engine's job, after this returns).
     */
    fun put(
        path: String,
        input: InputStream,
        length: Long,
        overwrite: Boolean,
        cancel: Cancellation,
        onBytes: (Long) -> Unit,
    ): RemoteEntry?

    override fun close() {}
}

/** A request without a network: bytes or a one-shot stream for the body, and no engine detail. */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val bodyBytes: ByteArray? = null,
    val bodyStream: InputStream? = null,
    /** Required with [bodyStream]; the transport sends exactly this many bytes. */
    val bodyLength: Long = bodyBytes?.size?.toLong() ?: -1L,
) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

/** [headers] keys are lower-case; a repeated header's values are joined with a comma. */
class HttpResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: InputStream,
    private val closer: Closeable = Closeable { },
) : Closeable {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
    // InputStream.readNBytes needs API 33; this app's minSdk is 26, so a plain loop reads up to [limit] bytes.
    fun bodyText(limit: Int = 1 shl 20): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = body.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
    override fun close() {
        runCatching { body.close() }
        closer.close()
    }
}

/**
 * The one door to the network, so every provider is testable without one. The Connect build's
 * implementation is an OkHttp client in `:feature:ai-remote`; the Offline build's reports that it
 * needs the Connect build. Implementations MUST NOT follow redirects: the callers decide (a
 * redirect that carried our credentials to another host is the thing being refused).
 */
fun interface HttpTransport {
    /** Blocking. Throws [SyncException] ([SyncFailure.TRANSIENT]) for I/O trouble. Every 1xx-5xx is a response. */
    fun execute(request: HttpRequest, cancel: Cancellation): HttpResponse
}

/**
 * What counts as a local address, for the "plain http only on my own network" option.
 *
 * Literal addresses are judged by their numbers (private ranges, loopback, link-local; an octet written
 * with a leading zero is refused outright, because some resolvers read it as octal, so `010.0.0.1` is not
 * `10.0.0.1`). Names are a TRUST decision, not a proof: `nas`, `box.local` and `*.lan` could resolve to a
 * public address on a hostile network. They are accepted only because plain http is off by default and the
 * person turned it on for this destination.
 */
object HostRules {
    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        if (!h.contains('.') && !h.contains(':')) return true // a bare NAS name such as "nas"
        if (h == "::1") return true
        if (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:")) return h.contains(':')
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { p -> p.all { it in '0'..'9' } && p.isNotEmpty() }) {
            if (parts.any { it.length > 1 && it.startsWith("0") }) return false
        }
        if (parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
        }
        return false
    }
}

/** Status codes, as the engine acts on them. */
object HttpStatus {
    fun failureFor(code: Int, what: String): SyncException {
        val failure = when {
            code == 401 || code == 403 -> SyncFailure.AUTH
            code == 507 || code == 413 && what.contains("quota", ignoreCase = true) -> SyncFailure.NO_SPACE
            code == 408 || code == 425 || code == 429 || code in 500..599 -> SyncFailure.TRANSIENT
            else -> SyncFailure.REJECTED
        }
        return SyncException(failure, "$what (HTTP $code)")
    }
}

/**
 * Sends requests through an [HttpTransport] under the rules every provider shares: TLS only
 * unless [allowPlainHttpOnLocal] and the host is local; redirects are followed ONLY when
 * [followSameHostRedirects] is set, ONLY to the same host and port (and never from https to http), and
 * never for a request whose body is a one-shot stream. A redirect anywhere else is
 * [SyncFailure.UNSAFE]: the credentials never go to a host the person did not enter. With
 * [followSameHostRedirects] false (S3: a signature is bound to its host) a 3xx is handed back to the
 * provider as the answer it is, and nothing is requested a second time.
 */
class HttpCaller(
    private val transport: HttpTransport,
    private val allowPlainHttpOnLocal: Boolean = false,
    private val followSameHostRedirects: Boolean = true,
) {
    fun call(request: HttpRequest, cancel: Cancellation = Cancellation.NEVER): HttpResponse {
        var current = request
        var hops = 0
        while (true) {
            checkUrl(current.url)
            cancel.throwIfCancelled()
            val response = transport.execute(current, cancel)
            if (response.code !in REDIRECTS || !followSameHostRedirects) return response // the caller sees a 3xx as the answer it is
            val target = response.header("location")
            response.close()
            if (target.isNullOrBlank()) throw SyncException(SyncFailure.REJECTED, "The server redirected without saying where")
            if (current.bodyStream != null) {
                throw SyncException(SyncFailure.UNSAFE, "The server redirected an upload; use the address it redirects to")
            }
            val from = URI.create(current.url)
            val to: URI
            val next: String
            try {
                next = resolve(current.url, target)
                to = URI.create(next)
            } catch (e: IllegalArgumentException) {
                throw SyncException(SyncFailure.REJECTED, "The server redirected to an address that is not valid", e)
            }
            if (to.host == null) throw SyncException(SyncFailure.REJECTED, "The server redirected to an address that is not valid")
            if (!from.host.equals(to.host, ignoreCase = true) || portOf(from) != portOf(to) ||
                (from.scheme == "https" && to.scheme != "https")
            ) {
                throw SyncException(SyncFailure.UNSAFE, "The server redirected to another address (${to.host}), so nothing was sent there")
            }
            if (++hops > MAX_HOPS) throw SyncException(SyncFailure.REJECTED, "Too many redirects")
            // 303 turns into a GET; 301/302/307/308 keep the method (a PROPFIND/MKCOL must stay one).
            current = HttpRequest(
                if (response.code == 303) "GET" else current.method,
                next,
                current.headers,
                current.bodyBytes.takeIf { response.code != 303 },
                null,
            )
        }
    }

    private fun checkUrl(url: String) {
        val uri = try {
            URI.create(url)
        } catch (e: IllegalArgumentException) {
            throw SyncException(SyncFailure.REJECTED, "The address is not valid", e)
        }
        val host = uri.host ?: throw SyncException(SyncFailure.REJECTED, "The address has no host")
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "https" -> Unit
            "http" -> if (!(allowPlainHttpOnLocal && HostRules.isLocalHost(host))) {
                throw SyncException(SyncFailure.UNSAFE, "Plain http is only allowed on your own network, and only if you turn it on")
            }
            else -> throw SyncException(SyncFailure.REJECTED, "The address must start with https://")
        }
    }

    private fun resolve(base: String, location: String): String = URI.create(base).resolve(location).toString()

    private fun portOf(uri: URI): Int = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val MAX_HOPS = 5
    }
}
