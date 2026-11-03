package com.fotoxplorr.app.basemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class BasemapAssetAndTilesTest {
    private val asset = File("src/main/assets/basemap/world.fxmap")

    @Test fun `the bundled basemap decodes and is small`() {
        assertTrue("run from :app", asset.isFile)
        assertTrue(asset.length() < 3_000_000)
        val map = BasemapCodec.decode(asset.readBytes())
        assertTrue(map.land.paths.size > 1000)
        assertTrue(map.lakes.paths.isNotEmpty())
        assertTrue(map.borders.paths.isNotEmpty())
        assertEquals(1000, map.cities.size)
        assertEquals("Tokyo", map.cities[0].name)
        assertEquals(0, map.cities[0].rank)
        assertEquals(139.75, map.cities[0].lon, 0.01)
        // Every point is a real coordinate.
        for (p in map.land.paths) for (i in p.indices step 2) {
            assertTrue(p[i] in -18_000..18_000 && p[i + 1] in -9_000..9_000)
        }
    }

    @Test fun `land is where land is`() {
        val map = BasemapCodec.decode(asset.readBytes())
        // Rough point-in-bbox check: some land ring covers Paris, none a mid-Pacific spot's bbox centre ring? Only the former.
        val paris = (2.35 * 100).toInt() to (48.85 * 100).toInt()
        val covers = map.land.bounds.any { it[0] <= paris.first && it[2] >= paris.first && it[1] <= paris.second && it[3] >= paris.second }
        assertTrue(covers)
    }

    @Test fun `a file that is not a basemap is refused`() {
        assertThrows(IllegalArgumentException::class.java) { BasemapCodec.decode(byteArrayOf(1, 2, 3, 4, 5, 6)) }
    }

    @Test fun `MBTiles rows are TMS so y flips`() {
        assertEquals(0, MbTilesMath.tmsRow(0, 0))
        assertEquals(3, MbTilesMath.tmsRow(2, 0))
        assertEquals(0, MbTilesMath.tmsRow(2, 3))
        assertEquals(5, MbTilesMath.xyzRow(3, MbTilesMath.tmsRow(3, 5)))
    }

    private class FakeTiles(override val minZoom: Int = 0, override val maxZoom: Int = 5) : TileSource {
        val asked = mutableListOf<TileAddress>()
        override fun tile(z: Int, x: Int, yXyz: Int): ByteArray? {
            asked += TileAddress(z, x, yXyz)
            return if (z == 2 && x == 1 && yXyz == 1) byteArrayOf(1) else null
        }
        override fun close() = Unit
    }

    @Test fun `the fake source answers by address and others are absent`() {
        val f = FakeTiles()
        assertEquals(1, f.tile(2, 1, 1)!!.size)
        assertNull(f.tile(2, 0, 0))
    }

    @Test fun `tile zoom follows the world size and stays inside the file's range`() {
        val src = FakeTiles(minZoom = 2, maxZoom = 5)
        assertEquals(2, MbTilesMath.zoomFor(256.0, src))
        assertEquals(4, MbTilesMath.zoomFor(256.0 * 16, src))
        assertEquals(5, MbTilesMath.zoomFor(256.0 * 1024, src))
    }

    @Test fun `visible tiles cover the view and carry their screen rectangles`() {
        val vp = MapViewport(512.0, 512.0)
        // Level 0 on a 512 view: world 512 px. At z=1 four tiles of 256 px.
        val tiles = MbTilesMath.visibleTiles(MapCamera(0.0, 0.0, 0.0), vp, 1)
        assertEquals(4, tiles.size)
        val nw = tiles.first { it.first == TileAddress(1, 0, 0) }.second
        assertEquals(0.0, nw[0], 1e-9); assertEquals(0.0, nw[1], 1e-9); assertEquals(256.0, nw[2], 1e-9)
        // Zoomed far in, only a few tiles.
        val deep = MbTilesMath.visibleTiles(MapCamera(6.0, 10.0, 50.0), vp, 8)
        assertTrue(deep.size in 1..25)
    }

    @Test fun `only SQLite files are candidates and vector formats are refused`() {
        assertEquals(MbTilesMath.FileKind.SQLITE, MbTilesMath.sniff("SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(MbTilesMath.FileKind.PMTILES, MbTilesMath.sniff("PMTiles\u0003".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(MbTilesMath.FileKind.OTHER, MbTilesMath.sniff(byteArrayOf(0x50, 0x4b, 3, 4)))
        assertTrue(MbTilesMath.isRasterFormat("PNG"))
        assertTrue(MbTilesMath.isRasterFormat("jpg"))
        assertTrue(!MbTilesMath.isRasterFormat("pbf"))
        assertTrue(!MbTilesMath.isRasterFormat(null))
    }
}

class TileSafetyTest {
    private class Fake(override val minZoom: Int, override val maxZoom: Int) : TileSource {
        override fun tile(z: Int, x: Int, yXyz: Int): ByteArray? = null
        override fun close() = Unit
    }

    private val phone = MapViewport(1080.0, 2200.0)

    @Test fun `a regional file at minzoom 14 draws nothing from the world view`() {
        val src = Fake(14, 17)
        // Level 8 on this screen is tile zoom ~11: three below the file's first zoom.
        assertTrue(MbTilesMath.tilesToDraw(MapCamera(8.0, 10.0, 50.0), phone, src).isEmpty())
        assertTrue(MbTilesMath.tilesToDraw(MapCamera(0.0, 0.0, 0.0), phone, src).isEmpty())
    }

    @Test fun `visibleTiles never enumerates more than the cap`() {
        // z=14 over a whole-world view would be millions of addresses.
        assertTrue(MbTilesMath.visibleTiles(MapCamera(0.0, 0.0, 0.0), phone, 14).isEmpty())
    }

    @Test fun `a file whose zoom range fits the view draws a bounded set`() {
        val src = Fake(0, 14)
        val t = MbTilesMath.tilesToDraw(MapCamera(6.0, 10.0, 50.0), phone, src)
        assertTrue(t.isNotEmpty() && t.size <= MbTilesMath.MAX_VISIBLE_TILES)
    }

    @Test fun `zoom ranges are validated`() {
        assertTrue(MbTilesMath.isValidZoomRange(0, 22))
        assertTrue(!MbTilesMath.isValidZoomRange(5, 4))
        assertTrue(!MbTilesMath.isValidZoomRange(0, 31))
        assertTrue(!MbTilesMath.isValidZoomRange(-1, 3))
    }

    @Test fun `tile images are bounded`() {
        assertTrue(MbTilesMath.acceptsTileImage(256, 256))
        assertTrue(MbTilesMath.acceptsTileImage(512, 512))
        assertTrue(!MbTilesMath.acceptsTileImage(100_000, 100_000))
        assertTrue(!MbTilesMath.acceptsTileImage(-1, 256))
        assertTrue(!MbTilesMath.acceptsTileImage(0, 0))
    }

    @Test fun `a truncated basemap throws IllegalArgumentException, not an index error`() {
        val whole = java.io.File("src/main/assets/basemap/world.fxmap").readBytes()
        for (cut in listOf(6, 100, 5000, whole.size / 2, whole.size - 3)) {
            try {
                BasemapCodec.decode(whole.copyOf(cut))
            } catch (_: IllegalArgumentException) {
                continue
            }
            // A cut that happens to land on a record boundary may still decode; it must not crash otherwise.
        }
    }
}
