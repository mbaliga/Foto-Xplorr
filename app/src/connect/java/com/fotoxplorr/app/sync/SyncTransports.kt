package com.fotoxplorr.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.fotoxplorr.feature.airemote.SyncHttp
import com.fotoxplorr.feature.airemote.SyncHttpException
import com.fotoxplorr.feature.airemote.SyncHttpReply
import com.fotoxplorr.feature.airemote.SyncHttpRequest

/**
 * The Connect build's network seam: [HttpTransport] backed by the OkHttp client in `:feature:ai-remote`
 * (that module is the only place OkHttp may be named). Adapts the app's request/response types to the
 * module's wire-level ones, which are plain JDK types. Same fully-qualified names as the Offline stub.
 */
object SyncTransports {
    const val available: Boolean = true

    fun create(): HttpTransport = ConnectHttpTransport(SyncHttp())
}

internal class ConnectHttpTransport(private val http: SyncHttp) : HttpTransport {
    override fun execute(request: HttpRequest, cancel: Cancellation): HttpResponse {
        val reply: SyncHttpReply = try {
            http.execute(
                SyncHttpRequest(
                    method = request.method,
                    url = request.url,
                    headers = request.headers,
                    bodyBytes = request.bodyBytes,
                    bodyStream = request.bodyStream,
                    bodyLength = request.bodyLength,
                ),
                isCancelled = { runCatching { cancel.throwIfCancelled() }.isFailure },
            )
        } catch (e: SyncHttpException) {
            // Cancellation surfaces as the coroutine's own exception from the check above; everything else is I/O trouble.
            cancel.throwIfCancelled()
            throw SyncException(SyncFailure.TRANSIENT, e.message ?: "The connection failed", e)
        }
        return HttpResponse(reply.code, reply.headers, reply.body, reply)
    }
}

/**
 * What the platform says about the active network: not connected / mobile or otherwise metered /
 * unmetered. When the platform cannot say (no service, no permission) the answer is [SyncNetwork.METERED],
 * so Wi-Fi only holds rather than being skipped on a guess. Reads ACCESS_NETWORK_STATE, which this flavor's
 * manifest declares. Not run on a device.
 */
object SyncConnectivity {
    fun current(context: Context): SyncNetwork = try {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        when {
            manager == null -> SyncNetwork.METERED
            network == null || capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true -> SyncNetwork.NONE
            manager.isActiveNetworkMetered -> SyncNetwork.METERED
            else -> SyncNetwork.UNMETERED
        }
    } catch (_: SecurityException) {
        SyncNetwork.METERED
    }
}
