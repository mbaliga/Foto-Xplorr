package com.fotoxplorr.app.spatial.offline

import android.content.Context
import com.fotoxplorr.feature.airemote.MapRequestCap
import org.maplibre.android.MapLibre

/**
 * The real [RequestCap]: the provider's limit of four requests in flight and a User-Agent that
 * names the app, applied to the map library through `MapRequestCap` (the one place that may hand
 * the library an HTTP client, see ADR-015). The limit is the provider's own number
 * ([OfflineMapLimits.TERMS_MAX_PARALLEL_REQUESTS]), not a lower one.
 *
 * The library's HTTP class reads the application context when it first loads, and fails for the rest
 * of the process if the library has not been started by then (read from its bytecode, not run on a
 * device). That would also take the live map's requests down. So [libraryReady] must have started
 * the library before the first call into the hook, and when it cannot, [install] returns false and
 * the hook is not touched. [restore] likewise does nothing until an install got that far.
 *
 * [libraryReady], [apply] and [undo] are parameters only so a JVM test can check that order.
 */
internal class MapLibreRequestCap(
    private val libraryReady: () -> Boolean,
    private val apply: (maxParallel: Int, userAgentSuffix: String) -> Boolean = MapRequestCap::install,
    private val undo: () -> Unit = MapRequestCap::restore,
) : RequestCap {

    constructor(context: Context) : this(libraryReady = { startLibrary(context.applicationContext) })

    @Volatile
    private var started = false

    override fun install(): Boolean {
        if (!started) {
            if (!libraryReady()) return false
            started = true
        }
        return apply(OfflineMapLimits.TERMS_MAX_PARALLEL_REQUESTS, USER_AGENT_SUFFIX)
    }

    override fun restore() {
        if (started) undo()
    }

    companion object {
        /** Appended to the library's own User-Agent, which already carries the app's package and version. */
        const val USER_AGENT_SUFFIX = "Fotoz"

        /**
         * Starts the map library if nothing has yet (the call is idempotent; the live map and the
         * offline backend make the same one). Throwable on purpose: a native library that cannot
         * load is an Error, and it must read as "cannot download", not crash Settings.
         */
        private fun startLibrary(appContext: Context): Boolean = try {
            MapLibre.getInstance(appContext)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
