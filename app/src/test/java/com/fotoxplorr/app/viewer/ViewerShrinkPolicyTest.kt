package com.fotoxplorr.app.viewer

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure halves of the shrink-to-map review fixes: the slideshow's lifecycle gate, the flier's
 * start rectangle for an EXIF-rotated photo and its fade-in, the place block's loading face, and
 * Null Island. The composed behaviour is in [ViewerShrinkRoomTest].
 */
class ViewerShrinkPolicyTest {

    // ── the slideshow does not run while the host is stopped ───────────────────────────────

    private fun advances(hostRunning: Boolean) = ViewerChromePolicy.slideshowMayAdvance(
        slideshowActive = true, total = 9, anyRoomVisible = false,
        videoPlaybackActive = false, modeActive = false, hostRunning = hostRunning,
    )

    @Test
    fun `a stopped activity does not advance the slideshow, a running one does`() {
        assertTrue(advances(hostRunning = true))
        assertFalse("after Home or the lock key", advances(hostRunning = false))
    }

    @Test
    fun `without a lifecycle argument the old behaviour is unchanged`() {
        assertTrue(ViewerChromePolicy.slideshowMayAdvance(true, 9, false, false, false))
    }

    @Test
    fun `the host counts as running when started, or in picture-in-picture`() {
        assertTrue(ViewerChromePolicy.slideshowHostRunning(lifecycleStarted = true, inPictureInPicture = false))
        assertTrue(ViewerChromePolicy.slideshowHostRunning(lifecycleStarted = false, inPictureInPicture = true))
        assertTrue(ViewerChromePolicy.slideshowHostRunning(lifecycleStarted = true, inPictureInPicture = true))
        assertFalse(ViewerChromePolicy.slideshowHostRunning(lifecycleStarted = false, inPictureInPicture = false))
    }

    // ── the flier's start rectangle follows the displayed photo ────────────────────────────

    @Test
    fun `orientations 5 to 8 swap the stored sides, the rest keep them`() {
        for (o in listOf(0, 1, 2, 3, 4, 9)) {
            val size = PlaceMorph.uprightSize(4000, 3000, o)!!
            assertEquals("orientation $o", 4000f, size.width, 0f)
            assertEquals("orientation $o", 3000f, size.height, 0f)
        }
        for (o in 5..8) {
            val size = PlaceMorph.uprightSize(4000, 3000, o)!!
            assertEquals("orientation $o", 3000f, size.width, 0f)
            assertEquals("orientation $o", 4000f, size.height, 0f)
        }
    }

    @Test
    fun `a degenerate stored size has no upright size`() {
        assertNull(PlaceMorph.uprightSize(0, 3000, 6))
        assertNull(PlaceMorph.uprightSize(4000, -1, 1))
    }

    @Test
    fun `a rotated phone portrait starts the flight from a portrait rectangle, not a landscape one`() {
        val containerW = 1080f
        val containerH = 2400f
        // MediaStore says 4000 x 3000 (stored landscape); the file says orientation 6.
        val stored = PlaceMorph.fitRect(containerW, containerH, 4000f, 3000f)
        val upright = PlaceMorph.uprightSize(4000, 3000, 6)!!
        val shown = PlaceMorph.restingImageSize(4000, 3000, upright.width.toInt(), upright.height.toInt())
        val rect = PlaceMorph.fitRect(containerW, containerH, shown.width, shown.height)
        assertTrue("stored dimensions give a landscape rect", stored.width > stored.height)
        assertTrue("the displayed photo is portrait-shaped: ${rect.width} x ${rect.height}", rect.height > rect.width)
        assertEquals(3f / 4f, rect.width / rect.height, 0.001f)
    }

    @Test
    fun `the stored size is the fallback until the displayed size is known`() {
        val size = PlaceMorph.restingImageSize(4000, 3000, 0, 0)
        assertEquals(4000f, size.width, 0f)
        assertEquals(3000f, size.height, 0f)
        val oneZero = PlaceMorph.restingImageSize(4000, 3000, 3000, 0)
        assertEquals(4000f, oneZero.width, 0f)
        // Nothing known at all: fitRect already gives the whole container rather than NaN.
        val none = PlaceMorph.restingImageSize(0, 0, 0, 0)
        assertEquals(Rect(0f, 0f, 100f, 200f), PlaceMorph.fitRect(100f, 200f, none.width, none.height))
    }

    @Test
    fun `the decoder is credited with the orientation only for the formats it honours`() {
        assertTrue(PlaceMorph.decoderAppliesOrientation("image/jpeg"))
        assertTrue(PlaceMorph.decoderAppliesOrientation("IMAGE/HEIC"))
        assertTrue(PlaceMorph.decoderAppliesOrientation("image/webp"))
        assertFalse(PlaceMorph.decoderAppliesOrientation("image/png"))
        assertFalse(PlaceMorph.decoderAppliesOrientation("image/gif"))
    }

    // ── the flier fades in ──────────────────────────────────────────────────────────────────

    @Test
    fun `the flier is invisible at the first pixel and fully there a few percent later`() {
        assertEquals(0f, PlaceMorph.liftOffAlpha(0f), 0f)
        assertTrue(PlaceMorph.liftOffAlpha(0.002f) < 0.01f)
        assertEquals(1f, PlaceMorph.liftOffAlpha(0.08f), 1e-6f)
        assertEquals(1f, PlaceMorph.liftOffAlpha(0.5f), 0f)
        assertEquals(1f, PlaceMorph.liftOffAlpha(1f), 0f)
        var last = 0f
        var v = 0f
        while (v <= 0.1f) {
            val a = PlaceMorph.liftOffAlpha(v)
            assertTrue("monotonic at $v", a >= last)
            last = a
            v += 0.005f
        }
    }

    @Test
    fun `the fade-in leaves the hand-off contract alone`() {
        // flightFrame.alpha is still the pure hand-off curve; the fade-in is applied on top.
        val photo = Rect(0f, 0f, 400f, 800f)
        val pin = Rect(100f, 500f, 260f, 660f)
        val frame = PlaceMorph.flightFrame(photo, pin, 800f, 1600f, 144f, 0.01f, 2f)
        assertNotNull(frame)
        assertEquals(1f, frame!!.alpha, 1e-6f)
    }

    // ── the place block's loading face ──────────────────────────────────────────────────────

    @Test
    fun `an unread file claims nothing about its place`() {
        val mode = PlaceMorph.placeBlockMode(pending = true, editingWithSetter = false, drawable = false, readOk = true)
        assertEquals(PlaceMorph.PlaceBlockMode.LOADING, mode)
        assertFalse("no pin is reported while loading", PlaceMorph.hasPin(mode))
        // Not even a hand-placed location or an open picker overrides it.
        assertEquals(
            PlaceMorph.PlaceBlockMode.LOADING,
            PlaceMorph.placeBlockMode(pending = true, editingWithSetter = true, drawable = true, readOk = true),
        )
    }

    @Test
    fun `once read, the faces are as they were`() {
        fun mode(editing: Boolean, drawable: Boolean, readOk: Boolean) =
            PlaceMorph.placeBlockMode(false, editing, drawable, readOk)
        assertEquals(PlaceMorph.PlaceBlockMode.PICKER, mode(editing = true, drawable = true, readOk = true))
        assertEquals(PlaceMorph.PlaceBlockMode.PLATE, mode(editing = false, drawable = true, readOk = true))
        assertEquals(PlaceMorph.PlaceBlockMode.READ_FAILED, mode(editing = false, drawable = false, readOk = false))
        assertEquals(PlaceMorph.PlaceBlockMode.UNKNOWN, mode(editing = false, drawable = false, readOk = true))
        assertTrue(PlaceMorph.hasPin(PlaceMorph.PlaceBlockMode.PLATE))
        assertTrue(PlaceMorph.hasPin(PlaceMorph.PlaceBlockMode.UNKNOWN))
        assertFalse(PlaceMorph.hasPin(PlaceMorph.PlaceBlockMode.PICKER))
        assertFalse(PlaceMorph.hasPin(PlaceMorph.PlaceBlockMode.READ_FAILED))
    }

    @Test
    fun `the pending marker is only on the initial state`() {
        assertTrue(ImageExifDetails.PENDING.pending)
        assertFalse(ImageExifDetails().pending)
        assertFalse(ImageExifDetails(readOk = false).pending)
    }

    // ── Null Island ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `exactly 0, 0 is Null Island and nothing near it is`() {
        assertTrue(PlaceMorph.isNullIsland(0.0, 0.0))
        assertTrue(PlaceMorph.isNullIsland(-0.0, 0.0))
        assertFalse(PlaceMorph.isNullIsland(0.0, 0.0001))
        assertFalse(PlaceMorph.isNullIsland(0.0001, 0.0))
        assertFalse(PlaceMorph.isNullIsland(17.4, 78.4))
        // isDrawable keeps its old meaning: the point is in range.
        assertTrue(PlaceMorph.isDrawable(0.0, 0.0))
    }
}
