package com.fotoxplorr.app.spatial.offline

/**
 * The few things [MapLibreRegionEngine] needs from the map library's offline manager, as plain
 * types, so the engine's rules (the request cap, pause that keeps data, one progress feed per
 * region, reconcile after a relaunch) run in a JVM test against a fake. The one real
 * implementation is [MapLibreOfflineBackend].
 *
 * Every call is made on the main thread, which is where the library delivers its callbacks; the
 * engine arranges that. A call that cannot be done throws [BackendException].
 */
internal interface OfflineBackend {

    /** Every region in the library's offline database, ours or not, with its current status. */
    suspend fun list(): List<BackendRegion>

    /** Stores a new region in the INACTIVE state. Must not fetch anything. */
    suspend fun create(definition: RegionDefinition, metadata: ByteArray): BackendRegion

    /** The current counts for region [id]. */
    suspend fun status(id: Long): BackendStatus

    /**
     * Sends status and error readings for region [id] to [listener]; null stops them. Readings
     * must also arrive once the region is inactive: the engine waits for the one that says so.
     */
    fun listen(id: Long, listener: BackendListener?)

    /** Starts ([active] true) or stops fetching for region [id]. The only call that uses the network. */
    fun setActive(id: Long, active: Boolean)

    /** Removes region [id] and what it fetched, then lets the library give the space back. */
    suspend fun delete(id: Long)
}

internal class BackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What to download: the style whose resources are saved, the area and the zoom range. */
internal data class RegionDefinition(
    val styleUrl: String,
    val box: GeoBox,
    val minZoom: Int,
    val maxZoom: Int,
)

/** A region as the library holds it. [metadata] is whatever was stored at creation, if anything. */
internal class BackendRegion(val id: Long, val metadata: ByteArray?, val status: BackendStatus)

/** The library's own counts, copied out. Sizes are bytes; "resources" are tiles plus style, sprites and glyphs. */
internal data class BackendStatus(
    val completedResourceCount: Long,
    val completedResourceSize: Long,
    val completedTileCount: Long,
    val requiredResourceCount: Long,
    val requiredCountIsPrecise: Boolean,
    val complete: Boolean,
    /** The library says the region is not downloading (after a stop request has been acted on). */
    val stopped: Boolean = false,
) {
    fun toProgress(error: DownloadErrorKind? = null): RegionProgress = RegionProgress(
        completedResources = completedResourceCount,
        requiredResources = requiredResourceCount,
        requiredIsPrecise = requiredCountIsPrecise,
        completedBytes = completedResourceSize,
        completedTiles = completedTileCount,
        complete = complete,
        error = error,
    )

    companion object {
        val EMPTY = BackendStatus(0, 0, 0, 0, requiredCountIsPrecise = false, complete = false)
    }
}

/** Readings from one region while it downloads. Called on the main thread. */
internal interface BackendListener {
    fun onStatus(status: BackendStatus)
    fun onError(kind: DownloadErrorKind)
}

/**
 * Applies and removes the provider's limit on requests in flight. The real one is
 * [MapLibreRequestCap]; the engine never downloads without a successful [install].
 */
internal interface RequestCap {
    /** True when the limit is now in force. */
    fun install(): Boolean

    /** Puts the library's unlimited client back. Safe to call when nothing is installed. */
    fun restore()
}
