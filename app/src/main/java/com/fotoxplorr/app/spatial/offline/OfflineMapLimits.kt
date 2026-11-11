package com.fotoxplorr.app.spatial.offline

/**
 * Every number that bounds an offline-map download, in one place.
 *
 * The caps come from the only tile source whose written terms allow an app to download for offline
 * use (OpenFreeMap, terms as read by the October 2026 design investigation): at most about 3,000
 * tiles or 50 MB per download, at most 4 requests in flight at once, and a User-Agent that names
 * the app. The app stays below the published numbers on purpose ([MAX_TILES], [MAX_BYTES]) because
 * the byte side of the budget is an ESTIMATE (see [RegionBudget]) and the tile side was counted on
 * paper, not by the downloader.
 * Terms can change and the service has no SLA, so re-read them before release; a larger allowance
 * needs the provider's written permission and is not something to code around.
 *
 * Nothing here is measured on a device. Every constant marked "estimate" is to be replaced after
 * the device spike (create one small region, compare the downloader's required-resource count and
 * completed bytes against [RegionBudget.estimate]).
 */
object OfflineMapLimits {

    /** What the provider's terms say, kept only so a test can prove our caps sit below them. */
    const val TERMS_MAX_TILES: Long = 3_000L

    /** What the provider's terms say: 50 MB, counted in decimal megabytes. */
    const val TERMS_MAX_BYTES: Long = 50_000_000L

    /** What the provider's terms say about requests in flight. The engine must enforce this. */
    const val TERMS_MAX_PARALLEL_REQUESTS: Int = 4

    /** Our tile cap for one region: conservative against [TERMS_MAX_TILES]. */
    const val MAX_TILES: Long = 2_500L

    /** Our byte cap for one region: conservative against [TERMS_MAX_BYTES]. */
    const val MAX_BYTES: Long = 45_000_000L

    /** Regions the app keeps. A seventh is refused, never made room for by deleting another. */
    const val MAX_REGIONS: Int = 6

    /** Total stored across all regions. Past it a new download is refused, never evicted for. */
    const val MAX_TOTAL_BYTES: Long = 300_000_000L

    /** Lowest zoom a region starts from: the whole-world overview costs a handful of tiles. */
    const val MIN_ZOOM: Int = 0

    /** The tile source stops at zoom 14, so asking for more downloads nothing extra. */
    const val MAX_ZOOM: Int = 14

    /**
     * The lowest top zoom worth offering. Below it the saved map is a blur, so an area that only
     * fits at, say, zoom 9 is called too large instead of being offered as a download.
     * Product choice, not a measured figure.
     */
    const val MIN_USEFUL_MAX_ZOOM: Int = 12

    /** Web Mercator stops here; beyond it the projection runs away to infinity. */
    const val MAX_MERCATOR_LATITUDE: Double = 85.0511287798

    /** Consecutive server or connection errors before the guard stops a download. */
    const val MAX_CONSECUTIVE_ERRORS: Int = 5
}
