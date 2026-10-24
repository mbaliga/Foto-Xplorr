package com.fotoxplorr.feature.airemote

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * Limits how many requests the map library keeps in flight, and adds a name to its User-Agent,
 * for as long as an offline map region is being downloaded.
 *
 * This is the only code that hands the map library an OkHttp client. It lives in this module
 * because this module is the sole owner of OkHttp (the app cannot name an OkHttp type at compile
 * time: the map library declares it with runtime scope only), and the map library is a
 * compile-only dependency here, supplied at run time by the connect flavour. Nothing outside the
 * connect flavour ever reaches this class.
 *
 * The map library routes its own requests, offline downloads included, through one replaceable
 * client (read from its bytecode, not run on a device). [install] swaps in one whose dispatcher
 * allows at most `maxParallel` calls at once, in total and per host, so a download stays inside
 * the tile provider's limit; [restore] puts the library's own client back, so live panning is not
 * throttled afterwards. Whether the native downloader really goes through this client is the first
 * thing the device check must confirm: with a logging proxy, count the requests in flight.
 */
object MapRequestCap {

    @Volatile
    private var installed = false

    /** True between a successful [install] and the next [restore]. */
    val isInstalled: Boolean get() = installed

    /**
     * Applies the cap. Returns false, and changes nothing, when [maxParallel] is below 1 or the map
     * library's client hook is missing or refuses the client (a different library version, or a
     * class that fails to link): the caller must then not download.
     */
    @Synchronized
    fun install(maxParallel: Int, userAgentSuffix: String): Boolean {
        if (maxParallel < 1) return false
        return try {
            HttpRequestUtil.setOkHttpClient(cappedClient(maxParallel, userAgentSuffix))
            installed = true
            true
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    /** Puts the map library's own client back. Safe to call when nothing is installed. */
    @Synchronized
    fun restore() {
        try {
            HttpRequestUtil.setOkHttpClient(null)
        } catch (_: Exception) {
            // Nothing to put back if the hook is gone.
        } catch (_: LinkageError) {
        }
        installed = false
    }

    /** [existing] with [suffix] appended after a space; just [suffix] when there is no User-Agent yet. */
    fun userAgentWith(existing: String?, suffix: String): String {
        val clean = suffix.filter { it.code in PRINTABLE_ASCII }.trim()
        return when {
            clean.isEmpty() -> existing.orEmpty()
            existing.isNullOrBlank() -> clean
            existing.endsWith(" $clean") -> existing
            else -> "$existing $clean"
        }
    }

    private fun cappedClient(maxParallel: Int, userAgentSuffix: String): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = maxParallel
            maxRequestsPerHost = maxParallel
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(
                    request.newBuilder()
                        .header("User-Agent", userAgentWith(request.header("User-Agent"), userAgentSuffix))
                        .build(),
                )
            }
            .build()
    }

    private val PRINTABLE_ASCII = 0x20..0x7E
}
