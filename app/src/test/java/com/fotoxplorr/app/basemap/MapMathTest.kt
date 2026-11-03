package com.fotoxplorr.app.basemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMathTest {
    private val square = MapViewport(400.0, 400.0)
    private val tall = MapViewport(400.0, 900.0)

    @Test fun `projection and inverse round-trip`() {
        for ((lon, lat) in listOf(78.3772 to 17.4435, -151.2093 to -33.8688, 0.0 to 0.0, 179.9 to 84.0)) {
            assertEquals(lon, MapMath.xToLon(MapMath.lonToX(lon)), 1e-9)
            assertEquals(lat, MapMath.yToLat(MapMath.latToY(lat)), 1e-7)
        }
    }

    @Test fun `the world edges and equator land where Web Mercator puts them`() {
        assertEquals(0.0, MapMath.latToY(MapMath.MAX_LAT), 1e-9)
        assertEquals(1.0, MapMath.latToY(-MapMath.MAX_LAT), 1e-9)
        assertEquals(0.5, MapMath.latToY(0.0), 1e-12)
        // Poles are clamped, never infinite.
        assertEquals(0.0, MapMath.latToY(90.0), 1e-9)
    }

    @Test fun `screen to geography round-trips through the camera`() {
        val cam = MapCamera(3.0, 10.0, 48.0)
        val p = MapMath.toScreen(cam, tall, 12.0, 47.0)
        val back = MapMath.fromScreen(cam, tall, p.x, p.y)
        assertEquals(12.0, back.lon, 1e-6)
        assertEquals(47.0, back.lat, 1e-6)
        val c = MapMath.toScreen(cam, tall, 10.0, 48.0)
        assertEquals(200.0, c.x, 1e-6)
        assertEquals(450.0, c.y, 1e-6)
    }

    @Test fun `level zero fits the world across the longer side`() {
        assertEquals(900.0, MapMath.worldPx(0.0, tall), 1e-9)
        assertEquals(1800.0, MapMath.worldPx(1.0, tall), 1e-9)
    }

    @Test fun `panning never shows past the edge of the world`() {
        var cam = MapCamera(2.0, 0.0, 0.0)
        cam = MapMath.panned(cam, square, 1e6, 1e6)
        val tl = MapMath.toScreen(cam, square, -180.0, MapMath.MAX_LAT)
        assertEquals(0.0, tl.x, 1e-6)
        assertEquals(0.0, tl.y, 1e-6)
        cam = MapMath.panned(cam, square, -1e7, -1e7)
        val br = MapMath.toScreen(cam, square, 180.0, -MapMath.MAX_LAT)
        assertEquals(400.0, br.x, 1e-6)
        assertEquals(400.0, br.y, 1e-6)
    }

    @Test fun `a world smaller than the view is centred, not dragged around`() {
        val cam = MapMath.panned(MapCamera(0.0, 0.0, 0.0), square, 100.0, 50.0)
        assertEquals(0.0, cam.centerLon, 1e-9)
        assertEquals(0.0, cam.centerLat, 1e-9)
    }

    @Test fun `zoom is clamped and keeps the point under the finger`() {
        val cam = MapCamera(2.0, 20.0, 30.0)
        val before = MapMath.fromScreen(cam, square, 300.0, 120.0)
        val zoomed = MapMath.zoomedAt(cam, square, 4.0, 300.0, 120.0)
        val after = MapMath.fromScreen(zoomed, square, 300.0, 120.0)
        assertEquals(before.lon, after.lon, 1e-6)
        assertEquals(before.lat, after.lat, 1e-6)
        assertEquals(MapMath.MAX_LEVEL, MapMath.zoomedAt(cam, square, 99.0, 0.0, 0.0).level, 0.0)
        assertEquals(0.0, MapMath.zoomedAt(cam, square, -5.0, 0.0, 0.0).level, 0.0)
        assertEquals(10.0, MapMath.zoomedAt(cam, square, 99.0, 0.0, 0.0, maxLevel = 10.0).level, 0.0)
    }

    @Test fun `fitting a box centres it and shows all of it`() {
        val cam = MapMath.fitted(8.0, 47.0, 12.0, 49.0, tall)
        for ((lon, lat) in listOf(8.0 to 47.0, 12.0 to 49.0, 8.0 to 49.0, 12.0 to 47.0)) {
            val p = MapMath.toScreen(cam, tall, lon, lat)
            assertTrue("$lon,$lat at $p", p.x in 0.0..tall.widthPx && p.y in 0.0..tall.heightPx)
        }
        // One photo: a tiny box asks for a huge zoom, which is capped.
        assertEquals(MapMath.MAX_LEVEL, MapMath.fitted(5.0, 5.0, 5.0001, 5.0001, tall).level, 0.0)
    }

    @Test fun `a tap or pin drag becomes a coordinate clamped at the poles and antimeridian`() {
        val cam = MapCamera(0.0, 0.0, 0.0)
        val mid = PinMath.pinAt(cam, square, 200.0, 200.0)
        assertEquals(0.0, mid.lon, 1e-9)
        assertEquals(0.0, mid.lat, 1e-9)
        val corner = PinMath.pinAt(cam, square, -50.0, -50.0)
        assertEquals(-180.0, corner.lon, 1e-9)
        assertEquals(MapMath.MAX_LAT, corner.lat, 1e-9)
        val far = PinMath.pinAt(cam, square, 900.0, 900.0)
        assertEquals(180.0, far.lon, 1e-9)
        assertEquals(-MapMath.MAX_LAT, far.lat, 1e-9)
    }

    @Test fun `a quarter down the level zero world is Mercator, not linear`() {
        val p = PinMath.pinAt(MapCamera(0.0, 0.0, 0.0), square, 300.0, 100.0)
        assertEquals(90.0, p.lon, 1e-9)
        assertEquals(MapMath.yToLat(0.25), p.lat, 1e-9)
    }

    @Test fun `grabbing the pin needs a touch within the radius`() {
        assertTrue(PinMath.grabs(PxPoint(100.0, 100.0), 110.0, 110.0, 20.0))
        assertTrue(!PinMath.grabs(PxPoint(100.0, 100.0), 130.0, 100.0, 20.0))
    }
}
