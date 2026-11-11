package com.fotoxplorr.app.spatial.offline

import android.content.Context
import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.core.model.MediaId
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/** A place a photo was taken. Nothing else about the photo is carried. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * The located photos among [browsableIds], as plain points.
 *
 * [browsableIds] must come from the app's one visibility filter (`browsableAssets`, TRAPS #33): a
 * suggested region is a place on a map, and a locked or hidden photo must not be able to put one
 * there. Ids with no stored location add nothing.
 */
fun GeoIndexState.browsablePoints(browsableIds: Collection<MediaId>): List<GeoPoint> =
    browsableIds.mapNotNull { id -> metadataById[id]?.let { GeoPoint(it.latitude, it.longitude) } }

/**
 * Like [browsablePoints], for the places offered to save: without any photo in a protected folder,
 * whether that folder is unlocked right now or not.
 *
 * [browsable] includes an unlocked folder's photos. A saved map area outlives the unlock (it stays
 * listed in Settings after the folder locks again, named by its coordinates, and the tile requests
 * tell the provider where it is), so those photos must not suggest one. [lockedFolders] is the
 * app's whole set of protected folder keys, unlocked ones included.
 */
fun GeoIndexState.everydayPoints(browsable: Collection<MediaAsset>, lockedFolders: Set<String>): List<GeoPoint> =
    browsablePoints(browsable.filter { folderIdentity(it).key.value !in lockedFolders }.map { it.id })

/** [everydayPoints] with the protected-folder set read from the store at this moment, for a caller that has none. */
fun GeoIndexState.everydayPoints(context: Context, browsable: Collection<MediaAsset>): List<GeoPoint> =
    everydayPoints(browsable, PrivateFolderStore(context.applicationContext).observeLockedFolders().value)

/** A suggested area to save, with how many photos fall inside it and how it would be downloaded. */
data class SuggestedRegion(
    val box: GeoBox,
    val photoCount: Int,
    /** Coordinates, not a place name: the UI shows it until the person names the area. */
    val namePlaceholder: String,
    val verdict: BudgetVerdict,
)

/**
 * City-scale areas worth saving, found from where the person's own photos were taken.
 *
 * Photos are dropped into a grid of [CELL_DEGREES] cells (about 22 km). The busiest cell seeds a
 * region, which then takes in neighbouring cells for as long as the padded box still fits the
 * download caps at full detail ([BudgetVerdict.Fits]); then the next busiest unused cell seeds the
 * next region. A sprawling or chained set of photos therefore becomes several regions that each
 * fit, never one that is too large to offer. There is no hard-coded list of places and no network:
 * the input is the in-memory geo index.
 *
 * Boxes are worked out in longitude relative to the seed, so a cluster either side of the
 * antimeridian is one region. Points that cannot be drawn on a Web Mercator map (beyond about 85
 * degrees of latitude) or that are not valid coordinates are ignored. The result depends only on
 * the set of points, not their order.
 */
object RegionSuggestions {

    /** Grid cell size in degrees. Estimate of "city scale", a product choice. */
    const val CELL_DEGREES: Double = 0.2

    /** Each side of a region is grown by this fraction so photos are not on the edge. */
    const val PAD_FRACTION: Double = 0.15

    /** The smallest a region may be on either side, so one photo still gets a usable area. */
    const val MIN_SPAN_DEGREES: Double = 0.05

    private const val CELLS_PER_DEGREE = 5 // 1 / CELL_DEGREES
    private const val LON_CELLS = 360 * CELLS_PER_DEGREE

    /** Slack so two cells that share an edge are seen as touching despite rounding. */
    private const val EDGE_TOLERANCE = 1e-6

    private fun latStart(cell: Cell): Double = (cell.key / LON_CELLS) / CELLS_PER_DEGREE.toDouble() - 90.0

    private fun lonStart(cell: Cell): Double = (cell.key % LON_CELLS) / CELLS_PER_DEGREE.toDouble() - 180.0

    private class Cell(
        val key: Long,
        var count: Int,
        var minLat: Double,
        var maxLat: Double,
        var minLon: Double,
        var maxLon: Double,
    )

    /**
     * The extent of a cluster, longitude relative to [anchor]. The first four fields are the photos'
     * own extent and size the box; the nominal ones are the grid cells' extent and decide which
     * cells touch the cluster.
     */
    private class Extent(
        val anchor: Double,
        var minLat: Double, var maxLat: Double, var minRel: Double, var maxRel: Double,
        var nomMinLat: Double, var nomMaxLat: Double, var nomMinRel: Double, var nomMaxRel: Double,
    )

    fun suggest(
        points: Collection<GeoPoint>,
        maxRegions: Int = OfflineMapLimits.MAX_REGIONS,
        minPhotos: Int = 1,
    ): List<SuggestedRegion> {
        if (maxRegions <= 0) return emptyList()
        val usable = points.mapNotNull(::normalised)
        if (usable.isEmpty()) return emptyList()

        val remaining = cellsOf(usable)
            .sortedWith(compareByDescending<Cell> { it.count }.thenBy { it.key })
            .toMutableList()

        val found = ArrayList<SuggestedRegion>()
        while (remaining.isNotEmpty() && found.size < maxRegions) {
            val seed = remaining.removeAt(0)
            val nomMinLat = latStart(seed)
            val nomMinRel = GeoBox.wrapLongitude(lonStart(seed) - seed.minLon)
            val extent = Extent(
                seed.minLon, seed.minLat, seed.maxLat, 0.0, seed.maxLon - seed.minLon,
                nomMinLat, nomMinLat + CELL_DEGREES, nomMinRel, nomMinRel + CELL_DEGREES,
            )
            if (boxOf(extent)?.let { RegionBudget.plan(it) } !is BudgetVerdict.Fits) continue
            grow(extent, remaining)

            val box = boxOf(extent) ?: continue
            val photos = usable.count { box.contains(it.latitude, it.longitude) }
            if (photos < minPhotos) continue
            found += SuggestedRegion(box, photos, placeholderName(box), RegionBudget.plan(box))
        }
        return found
    }

    /** A short stand-in for a name: the box centre to one decimal, such as `48.9 N, 2.3 E`. */
    fun placeholderName(box: GeoBox): String =
        "${tenths(box.centreLatitude, "N", "S")}, ${tenths(box.centreLongitude, "E", "W")}"

    private fun tenths(value: Double, positive: String, negative: String): String {
        val scaled = round(value * 10.0).toLong()
        val magnitude = abs(scaled)
        return "${magnitude / 10}.${magnitude % 10} ${if (scaled < 0) negative else positive}"
    }

    /** Takes in every remaining cell that touches [extent], busiest first, while the result fits. */
    private fun grow(extent: Extent, remaining: MutableList<Cell>) {
        var absorbedAny = true
        while (absorbedAny) {
            absorbedAny = false
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val cell = iterator.next()
                val nomMinLat = latStart(cell)
                val nomMinRel = GeoBox.wrapLongitude(lonStart(cell) - extent.anchor)
                val touches = nomMinLat + CELL_DEGREES >= extent.nomMinLat - EDGE_TOLERANCE &&
                    nomMinLat <= extent.nomMaxLat + EDGE_TOLERANCE &&
                    nomMinRel + CELL_DEGREES >= extent.nomMinRel - EDGE_TOLERANCE &&
                    nomMinRel <= extent.nomMaxRel + EDGE_TOLERANCE
                if (!touches) continue

                val relMin = GeoBox.wrapLongitude(cell.minLon - extent.anchor)
                val relMax = relMin + (cell.maxLon - cell.minLon)
                val trial = Extent(
                    extent.anchor,
                    minOf(extent.minLat, cell.minLat), max(extent.maxLat, cell.maxLat),
                    minOf(extent.minRel, relMin), max(extent.maxRel, relMax),
                    minOf(extent.nomMinLat, nomMinLat), max(extent.nomMaxLat, nomMinLat + CELL_DEGREES),
                    minOf(extent.nomMinRel, nomMinRel), max(extent.nomMaxRel, nomMinRel + CELL_DEGREES),
                )
                val fits = boxOf(trial)?.let { RegionBudget.plan(it) } is BudgetVerdict.Fits
                if (!fits) continue

                extent.minLat = trial.minLat
                extent.maxLat = trial.maxLat
                extent.minRel = trial.minRel
                extent.maxRel = trial.maxRel
                extent.nomMinLat = trial.nomMinLat
                extent.nomMaxLat = trial.nomMaxLat
                extent.nomMinRel = trial.nomMinRel
                extent.nomMaxRel = trial.nomMaxRel
                iterator.remove()
                absorbedAny = true
            }
        }
    }

    /** The padded box for [extent], at least [MIN_SPAN_DEGREES] on each side; null if unusable. */
    private fun boxOf(extent: Extent): GeoBox? {
        val latSpan = extent.maxLat - extent.minLat
        val lonSpan = extent.maxRel - extent.minRel
        val latPad = max(latSpan * PAD_FRACTION, (MIN_SPAN_DEGREES - latSpan) / 2.0)
        val lonPad = max(lonSpan * PAD_FRACTION, (MIN_SPAN_DEGREES - lonSpan) / 2.0)
        return GeoBox.clamped(
            west = extent.anchor + extent.minRel - lonPad,
            south = extent.minLat - latPad,
            east = extent.anchor + extent.maxRel + lonPad,
            north = extent.maxLat + latPad,
        )
    }

    /** The point with its longitude wrapped, or null when it is not a drawable coordinate. */
    private fun normalised(point: GeoPoint): GeoPoint? {
        if (!point.latitude.isFinite() || !point.longitude.isFinite()) return null
        if (abs(point.latitude) > OfflineMapLimits.MAX_MERCATOR_LATITUDE) return null
        if (abs(point.longitude) > 180.0) return null
        return GeoPoint(point.latitude, GeoBox.wrapLongitude(point.longitude))
    }

    private fun cellsOf(points: List<GeoPoint>): List<Cell> {
        val cells = HashMap<Long, Cell>()
        for (p in points) {
            val latIndex = floor((p.latitude + 90.0) * CELLS_PER_DEGREE).toInt()
            val lonIndex = floor((p.longitude + 180.0) * CELLS_PER_DEGREE).toInt().mod(LON_CELLS)
            val key = latIndex.toLong() * LON_CELLS + lonIndex
            val cell = cells[key]
            if (cell == null) {
                cells[key] = Cell(key, 1, p.latitude, p.latitude, p.longitude, p.longitude)
            } else {
                cell.count += 1
                cell.minLat = minOf(cell.minLat, p.latitude)
                cell.maxLat = max(cell.maxLat, p.latitude)
                cell.minLon = minOf(cell.minLon, p.longitude)
                cell.maxLon = max(cell.maxLon, p.longitude)
            }
        }
        return cells.values.toList()
    }
}
