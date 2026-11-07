package com.fotoxplorr.app.spatial.offline

/** What a region would cost: the tiles it needs and a guess at the bytes. Both are estimates. */
data class RegionEstimate(
    val box: GeoBox,
    val minZoom: Int,
    val maxZoom: Int,
    val tileCount: Long,
    val bytes: Long,
) {
    /** True when the top zoom was lowered from the source's maximum to stay inside the caps. */
    val reducedDetail: Boolean get() = maxZoom < OfflineMapLimits.MAX_ZOOM
}

/** Which cap an area runs into first. */
enum class TooLargeReason { TOO_MANY_TILES, TOO_MANY_BYTES }

/** Whether an area can be downloaded, and at what detail. */
sealed interface BudgetVerdict {
    /** Fits at the source's full detail. */
    data class Fits(val estimate: RegionEstimate) : BudgetVerdict

    /** Fits only with a lower top zoom; [estimate] already carries it. */
    data class Reduced(val estimate: RegionEstimate) : BudgetVerdict

    /** Does not fit even at [OfflineMapLimits.MIN_USEFUL_MAX_ZOOM]; [floorEstimate] shows what that costs. */
    data class TooLarge(val reason: TooLargeReason, val floorEstimate: RegionEstimate) : BudgetVerdict

    /** A line or a point, not an area: there is nothing sensible to download. */
    data object NoArea : BudgetVerdict
}

/**
 * How much a [GeoBox] costs to save, and the most detail that stays inside the caps.
 *
 * **Every size here is an estimate, replace after the device spike.** Tile counts are exact
 * arithmetic ([TileMath]); bytes are not. The per-zoom tile sizes below come from a dozen sampled
 * tiles over three cities (compressed vector tiles), good to about a factor of two, and the fixed
 * overhead for style, sprites and fonts is a guess that was not measured at all. The downloader's
 * own required-resource count and completed bytes are the truth; once the spike has run, change the
 * constants and nothing else. The caps themselves live in [OfflineMapLimits].
 */
object RegionBudget {

    /**
     * Style, sprite sheet, font ranges and the low-zoom raster the downloader fetches besides vector
     * tiles. Estimate, replace after the device spike: the guess is about 10 MB, and it is counted
     * against the byte cap on purpose so the cap errs on the safe side.
     */
    const val ESTIMATED_FIXED_OVERHEAD_BYTES: Long = 10_000_000L

    /**
     * Average compressed size of one vector tile, indexed by zoom 0..14, in bytes. Estimate, replace
     * after the device spike. Rounded up from samples: central London 158 KB at zoom 10, 51 KB at 12,
     * 36 KB at 14; Singapore 83 KB at 10, 26 KB at 14; Switzerland 100 KB at 10, 27 KB at 12.
     * Zooms 0 to 9 were not sampled and are a plain guess.
     */
    private val ESTIMATED_BYTES_PER_TILE_BY_ZOOM: LongArray = longArrayOf(
        60_000, 60_000, 60_000, 60_000, 60_000, 60_000, // 0..5
        80_000, 80_000, 80_000, // 6..8
        120_000, // 9
        160_000, // 10
        110_000, // 11
        60_000, // 12
        45_000, // 13
        40_000, // 14
    )

    /** The estimated size of one tile at [zoom]; zooms past the table reuse its last entry. */
    fun estimatedBytesPerTile(zoom: Int): Long =
        ESTIMATED_BYTES_PER_TILE_BY_ZOOM[zoom.coerceIn(0, ESTIMATED_BYTES_PER_TILE_BY_ZOOM.lastIndex)]

    /** Tiles and estimated bytes for [box] over [minZoom]..[maxZoom], overhead included. */
    fun estimate(box: GeoBox, minZoom: Int, maxZoom: Int): RegionEstimate {
        var tiles = 0L
        var bytes = ESTIMATED_FIXED_OVERHEAD_BYTES
        for (zoom in minZoom..maxZoom) {
            val atZoom = TileMath.tileRanges(box, zoom).sumOf { it.count }
            tiles += atZoom
            bytes += atZoom * estimatedBytesPerTile(zoom)
        }
        return RegionEstimate(box, minZoom, maxZoom, tiles, bytes)
    }

    /**
     * The highest top zoom in [minZoom]..[ceiling] whose estimate stays within [maxTiles] and
     * [maxBytes] (equal to the cap fits), or null when even [minZoom] does not. [ceiling] is held to
     * the source's own maximum zoom.
     */
    fun fitMaxZoom(
        box: GeoBox,
        minZoom: Int = OfflineMapLimits.MIN_ZOOM,
        ceiling: Int = OfflineMapLimits.MAX_ZOOM,
        maxTiles: Long = OfflineMapLimits.MAX_TILES,
        maxBytes: Long = OfflineMapLimits.MAX_BYTES,
    ): Int? {
        var tiles = 0L
        var bytes = ESTIMATED_FIXED_OVERHEAD_BYTES
        var best: Int? = null
        for (zoom in minZoom..ceiling.coerceAtMost(OfflineMapLimits.MAX_ZOOM)) {
            val atZoom = TileMath.tileRanges(box, zoom).sumOf { it.count }
            tiles += atZoom
            bytes += atZoom * estimatedBytesPerTile(zoom)
            // The estimate only grows with zoom, so the first miss ends the search.
            if (tiles > maxTiles || bytes > maxBytes) break
            best = zoom
        }
        return best
    }

    /** The verdict for downloading [box] from [OfflineMapLimits.MIN_ZOOM] to the best top zoom that fits. */
    fun plan(
        box: GeoBox,
        maxTiles: Long = OfflineMapLimits.MAX_TILES,
        maxBytes: Long = OfflineMapLimits.MAX_BYTES,
    ): BudgetVerdict {
        if (box.isZeroArea) return BudgetVerdict.NoArea
        val top = fitMaxZoom(box, OfflineMapLimits.MIN_ZOOM, OfflineMapLimits.MAX_ZOOM, maxTiles, maxBytes)
        if (top == null || top < OfflineMapLimits.MIN_USEFUL_MAX_ZOOM) {
            val floor = estimate(box, OfflineMapLimits.MIN_ZOOM, OfflineMapLimits.MIN_USEFUL_MAX_ZOOM)
            val reason = if (floor.tileCount > maxTiles) TooLargeReason.TOO_MANY_TILES else TooLargeReason.TOO_MANY_BYTES
            return BudgetVerdict.TooLarge(reason, floor)
        }
        val estimate = estimate(box, OfflineMapLimits.MIN_ZOOM, top)
        return if (top == OfflineMapLimits.MAX_ZOOM) BudgetVerdict.Fits(estimate) else BudgetVerdict.Reduced(estimate)
    }
}
