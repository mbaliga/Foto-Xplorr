package com.fotoxplorr.app.basemap

import kotlin.math.floor

/** One tile in the XYZ scheme (y grows southward, like the screen). */
data class TileAddress(val z: Int, val x: Int, val y: Int)

/** What the reader needs from a tile file; the SQLite one is [SqliteMbTiles], tests use a fake. */
interface TileSource {
    val minZoom: Int
    val maxZoom: Int

    /** The tile's encoded image (PNG or JPEG) or null when the file has none there. */
    fun tile(z: Int, x: Int, yXyz: Int): ByteArray?
    fun close()
}

/** MBTiles stores rows in TMS (y grows northward); the screen and this app use XYZ. */
object MbTilesMath {
    fun tmsRow(z: Int, yXyz: Int): Int = (1 shl z) - 1 - yXyz
    fun xyzRow(z: Int, tmsRow: Int): Int = (1 shl z) - 1 - tmsRow

    /** The tile zoom whose tiles are drawn closest to 256 px at a world [worldPx] wide, within [source]'s range. */
    fun zoomFor(worldPx: Double, source: TileSource): Int {
        val z = Math.round(kotlin.math.log2(worldPx / MapMath.TILE_PX)).toInt()
        return z.coerceIn(source.minZoom, source.maxZoom)
    }

    /** The tiles that cover the viewport at [z], each with its on-screen rectangle (left, top, size). */
    fun visibleTiles(camera: MapCamera, viewport: MapViewport, z: Int): List<Pair<TileAddress, DoubleArray>> {
        val n = 1 shl z
        val wp = MapMath.worldPx(camera.level, viewport)
        val size = wp / n
        val originX = viewport.widthPx / 2.0 - MapMath.lonToX(camera.centerLon) * wp
        val originY = viewport.heightPx / 2.0 - MapMath.latToY(camera.centerLat) * wp
        val x0 = floor(-originX / size).toInt().coerceIn(0, n - 1)
        val x1 = floor((viewport.widthPx - originX) / size).toInt().coerceIn(0, n - 1)
        val y0 = floor(-originY / size).toInt().coerceIn(0, n - 1)
        val y1 = floor((viewport.heightPx - originY) / size).toInt().coerceIn(0, n - 1)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > MAX_VISIBLE_TILES) return emptyList()
        val out = ArrayList<Pair<TileAddress, DoubleArray>>()
        for (ty in y0..y1) for (tx in x0..x1) {
            out += TileAddress(z, tx, ty) to doubleArrayOf(originX + tx * size, originY + ty * size, size)
        }
        return out
    }

    /** Most tiles one frame may ask for; more means the zoom is wrong for this file, so none are drawn. */
    const val MAX_VISIBLE_TILES = 64

    /** Highest zoom a file may declare; beyond it `1 shl z` and the tile maths stop making sense. */
    const val MAX_TILE_ZOOM = 22

    fun isValidZoomRange(min: Int, max: Int): Boolean = min in 0..MAX_TILE_ZOOM && max in 0..MAX_TILE_ZOOM && min <= max

    /** Longest edge of a tile image the app will decode; a tile is 256 px, a retina one 512. */
    const val MAX_TILE_IMAGE_EDGE = 512

    fun acceptsTileImage(width: Int, height: Int): Boolean =
        width in 1..MAX_TILE_IMAGE_EDGE && height in 1..MAX_TILE_IMAGE_EDGE

    /**
     * The tiles worth drawing now: none when the world is shown at a zoom well below the file's
     * first (a regional extract at minzoom 14 seen from the world would be millions of addresses),
     * none when more than [MAX_VISIBLE_TILES] would be needed, and otherwise the visible set at the
     * file's nearest zoom.
     */
    fun tilesToDraw(camera: MapCamera, viewport: MapViewport, source: TileSource): List<Pair<TileAddress, DoubleArray>> {
        val natural = MapMath.tileZoom(camera.level, viewport)
        if (natural < source.minZoom - 1) return emptyList()
        val z = zoomFor(MapMath.worldPx(camera.level, viewport), source)
        val tiles = visibleTiles(camera, viewport, z)
        return if (tiles.size > MAX_VISIBLE_TILES) emptyList() else tiles
    }

    /** Whether a file's first bytes say SQLite (MBTiles), PMTiles, or neither. */
    fun sniff(header: ByteArray): FileKind = when {
        header.size >= 16 && String(header, 0, 15, Charsets.ISO_8859_1) == "SQLite format 3" -> FileKind.SQLITE
        header.size >= 7 && String(header, 0, 7, Charsets.ISO_8859_1) == "PMTiles" -> FileKind.PMTILES
        else -> FileKind.OTHER
    }

    enum class FileKind { SQLITE, PMTILES, OTHER }

    /** MBTiles `format` metadata: only raster formats are drawn; `pbf` (vector) is not supported. */
    fun isRasterFormat(format: String?): Boolean = when (format?.trim()?.lowercase()) {
        "png", "jpg", "jpeg", "webp" -> true
        else -> false
    }

}
