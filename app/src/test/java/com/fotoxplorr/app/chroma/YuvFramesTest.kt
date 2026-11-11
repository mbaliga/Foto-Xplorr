package com.fotoxplorr.app.chroma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The YUV to RGB conversion, the preview's clipping look, and the choice of sizes. */
class YuvFramesTest {

    /** A planar picture of one colour: Y, U, V constant. */
    private fun solid(y: Int, u: Int, v: Int, w: Int = 8, h: Int = 8, interleaved: Boolean = false, pad: Int = 0): YuvPlanes {
        val yRow = w + pad
        val yp = ByteArray(yRow * h) { y.toByte() }
        val cw = w / 2
        val ch = h / 2
        return if (interleaved) {
            // NV21-like: V first, then U, one pair per chroma sample; the U plane starts one byte in.
            val uv = ByteArray(cw * 2 * ch)
            for (i in 0 until cw * ch) {
                uv[i * 2] = v.toByte()
                uv[i * 2 + 1] = u.toByte()
            }
            YuvPlanes(w, h, yp, yRow, uv.copyOfRange(1, uv.size), uv, cw * 2, 2)
        } else {
            YuvPlanes(w, h, yp, yRow, ByteArray(cw * ch) { u.toByte() }, ByteArray(cw * ch) { v.toByte() }, cw, 1)
        }
    }

    private fun first(p: YuvPlanes): Int = YuvFrames.toFrame(p).argb[0]

    @Test
    fun greyStaysGrey() {
        val px = first(solid(128, 128, 128))
        assertEquals(0xFF808080.toInt(), px)
        assertEquals(0xFF000000.toInt(), first(solid(0, 128, 128)))
        assertEquals(0xFFFFFFFF.toInt(), first(solid(255, 128, 128)))
    }

    @Test
    fun theKnownFullRangeColoursComeOutRight() {
        // JFIF red (255,0,0): Y=76, Cb=85, Cr=255 (rounded). Green: Y=150, Cb=44, Cr=21. Blue: Y=29, Cb=255, Cr=107.
        val red = first(solid(76, 85, 255))
        assertTrue(((red shr 16) and 0xFF) >= 250 && ((red shr 8) and 0xFF) <= 4 && (red and 0xFF) <= 4)
        val green = first(solid(150, 44, 21))
        assertTrue(((green shr 8) and 0xFF) >= 250 && ((green shr 16) and 0xFF) <= 4 && (green and 0xFF) <= 4)
        val blue = first(solid(29, 255, 107))
        assertTrue((blue and 0xFF) >= 250 && ((blue shr 16) and 0xFF) <= 4 && ((blue shr 8) and 0xFF) <= 4)
    }

    @Test
    fun clearingThePlanesOverwritesTheCopiesOfTheCamerasBytes() {
        val p = solid(90, 100, 160)
        assertTrue(p.y.any { it.toInt() != 0 } && p.u.any { it.toInt() != 0 } && p.v.any { it.toInt() != 0 })
        p.clear()
        assertTrue(p.y.all { it.toInt() == 0 } && p.u.all { it.toInt() == 0 } && p.v.all { it.toInt() == 0 })
    }

    @Test
    fun rowPaddingAndInterleavedChromaGiveTheSamePicture() {
        val plain = YuvFrames.toFrame(solid(90, 100, 160))
        val padded = YuvFrames.toFrame(solid(90, 100, 160, pad = 8))
        val interleaved = YuvFrames.toFrame(solid(90, 100, 160, interleaved = true))
        assertTrue(plain.argb.contentEquals(padded.argb))
        assertTrue(plain.argb.contentEquals(interleaved.argb))
    }

    @Test
    fun theFrameHasThePicturesSize() {
        val f = YuvFrames.toFrame(solid(10, 128, 128, w = 16, h = 8))
        assertEquals(16, f.width)
        assertEquals(8, f.height)
    }

    @Test
    fun aWhitePictureIsAllClippedAndAMidGreyNone() {
        assertEquals(1.0, YuvFrames.clippedShare(solid(255, 128, 128, 64, 64)), 1e-9)
        assertEquals(0.0, YuvFrames.clippedShare(solid(120, 128, 128, 64, 64)), 1e-9)
    }

    @Test
    fun onlyTheMiddleIsLookedAt() {
        val w = 64
        val h = 64
        val y = ByteArray(w * h) { 120 }
        // White in the outer ring only.
        for (row in 0 until h) for (col in 0 until w) {
            if (col < 10 || col >= 54 || row < 10 || row >= 54) y[row * w + col] = 255.toByte()
        }
        val p = YuvPlanes(w, h, y, w, ByteArray(w * h / 4) { 128.toByte() }, ByteArray(w * h / 4) { 128.toByte() }, w / 2, 1)
        assertEquals(0.0, YuvFrames.clippedShare(p), 1e-9)
    }

    @Test
    fun aSaturatedChannelCountsAsClippedEvenWhenBrightnessIsModest() {
        // Pure-ish red: Y=76 only, but R is at 255.
        assertTrue(YuvFrames.clippedShare(solid(76, 85, 255, 64, 64)) > 0.9)
    }

    // ---- sizes ------------------------------------------------------------------------------------

    private fun sizes(vararg wh: Pair<Int, Int>) = wh.map { CaptureSize.Size(it.first, it.second) }

    @Test
    fun thePreviewIsTheLargestWithinTheBudget() {
        val p = CaptureSize.pickPreview(sizes(1920 to 1080, 1280 to 720, 640 to 480, 320 to 240))!!
        assertEquals(1280, p.width)
        assertEquals(720, p.height)
    }

    @Test
    fun thePreviewFallsBackToTheSmallestWhenAllAreBig() {
        val p = CaptureSize.pickPreview(sizes(4000 to 3000, 3000 to 2000))!!
        assertEquals(3000, p.width)
        assertNull(CaptureSize.pickPreview(emptyList()))
    }

    @Test
    fun theCaptureMatchesThePreviewsShapeBeforeItsSize() {
        val preview = CaptureSize.Size(1280, 720)
        val offered = sizes(640 to 480, 1920 to 1080, 800 to 450, 4000 to 3000)
        val c = CaptureSize.pickCapture(offered, preview)!!
        // 16:9 candidates win over the 4:3 one nearest in pixel count; of those 800x450 (360k) is nearest to 300k.
        assertEquals(800, c.width)
        assertEquals(450, c.height)
    }

    @Test
    fun theCaptureIsAboutTheTargetPixelCount() {
        val preview = CaptureSize.Size(640, 480)
        val c = CaptureSize.pickCapture(sizes(640 to 480, 320 to 240, 1600 to 1200), preview)!!
        assertEquals(640, c.width)
        assertNotNull(c)
        assertTrue(c.pixels in 200_000..450_000)
    }

    // ---- the preview's shape ----------------------------------------------------------------------

    @Test
    fun theDisplayedShapeSwapsWhenSensorAndDisplayDifferByAQuarterTurn() {
        // A typical phone: sensor at 90 degrees, display upright.
        assertEquals(0.75, PreviewShape.displayAspect(640, 480, 90, 0), 1e-9)
        // The same phone on its side.
        assertEquals(640.0 / 480, PreviewShape.displayAspect(640, 480, 90, 90), 1e-9)
        assertEquals(640.0 / 480, PreviewShape.displayAspect(640, 480, 0, 0), 1e-9)
        assertEquals(0.75, PreviewShape.displayAspect(640, 480, 270, 180), 1e-9)
    }

    // ---- no flash ---------------------------------------------------------------------------------

    @Test
    fun aRoomOnlyReadingIsTheMiddleColourAsSeen() {
        val frame = ChromaTestKit.flat(200, 100, 50)
        val r = (AmbientOnly.read(frame) as Answer.Value).value
        assertEquals(200, r.red)
        assertEquals(100, r.green)
        assertEquals(50, r.blue)
        assertTrue(r.description.isNotBlank())
    }

    @Test
    fun aRoomOnlyReadingStillRefusesAWashedOutPicture() {
        val a = AmbientOnly.read(ChromaTestKit.flat(255, 255, 255))
        assertEquals(Refusal.CLIPPED, (a as Answer.Refused).reason)
    }
}
