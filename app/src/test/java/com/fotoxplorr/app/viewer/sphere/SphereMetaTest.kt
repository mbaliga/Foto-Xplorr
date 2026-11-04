package com.fotoxplorr.app.viewer.sphere

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereMetaTest {
    private fun props(vararg kv: Pair<String, String>): (String) -> String? = kv.toMap()::get

    @Test
    fun `a full sphere covers 360 by 180`() {
        val m = SphereMeta.parse(
            props(
                "ProjectionType" to "equirectangular", "FullPanoWidthPixels" to "8000", "FullPanoHeightPixels" to "4000",
                "CroppedAreaImageWidthPixels" to "8000", "CroppedAreaImageHeightPixels" to "4000",
                "CroppedAreaLeftPixels" to "0", "CroppedAreaTopPixels" to "0",
            ),
            2000, 1000,
        )!!
        assertEquals(-180f, m.lonMin, 1e-4f); assertEquals(180f, m.lonMax, 1e-4f)
        assertEquals(90f, m.latMax, 1e-4f); assertEquals(-90f, m.latMin, 1e-4f)
        assertTrue(m.wrapsHorizontally); assertTrue(m.isWholeSphere())
    }

    @Test
    fun `a partial panorama covers only its window`() {
        // 120 degrees wide, centred, 60 high, offset 1000 px from the top of a 4000 px tall sphere.
        val m = SphereMeta.parse(
            props(
                "ProjectionType" to "equirectangular", "FullPanoWidthPixels" to "6000", "FullPanoHeightPixels" to "3000",
                "CroppedAreaImageWidthPixels" to "2000", "CroppedAreaImageHeightPixels" to "1000",
                "CroppedAreaLeftPixels" to "2000", "CroppedAreaTopPixels" to "1000",
            ),
            1000, 500,
        )!!
        assertEquals(-60f, m.lonMin, 1e-3f); assertEquals(60f, m.lonMax, 1e-3f)
        assertEquals(30f, m.latMax, 1e-3f); assertEquals(-30f, m.latMin, 1e-3f)
        assertFalse(m.wrapsHorizontally)
    }

    @Test
    fun `only the projection type makes a 2 to 1 picture a full sphere`() {
        val m = SphereMeta.parse(props("ProjectionType" to "EQUIRECTANGULAR"), 4096, 2048)!!
        assertTrue(m.isWholeSphere())
        assertNull(SphereMeta.parse(props("ProjectionType" to "cylindrical"), 4096, 2048))
        assertNull(SphereMeta.parse(props(), 4096, 2048))
    }

    @Test
    fun `nonsense sizes are refused and offsets clamped, initial view is read`() {
        assertNull(SphereMeta.parse(props("ProjectionType" to "equirectangular", "FullPanoWidthPixels" to "100", "CroppedAreaImageWidthPixels" to "200"), 10, 10))
        assertNull(SphereMeta.parse(props("ProjectionType" to "equirectangular"), 0, 0))
        val m = SphereMeta.parse(
            props(
                "ProjectionType" to "equirectangular", "FullPanoWidthPixels" to "1000", "FullPanoHeightPixels" to "500",
                "CroppedAreaImageWidthPixels" to "400", "CroppedAreaImageHeightPixels" to "500", "CroppedAreaLeftPixels" to "9999",
                "InitialViewHeadingDegrees" to "45", "InitialViewPitchDegrees" to "-10", "InitialVerticalFOVDegrees" to "999",
            ),
            400, 500,
        )!!
        assertNotNull(m)
        assertEquals(600, m.croppedLeft)
        assertEquals(45f, m.initialHeading, 0f); assertEquals(-10f, m.initialPitch, 0f)
        assertNull(m.initialFov) // 999 degrees is not a field of view
    }
}
