package com.fotoxplorr.app.lift

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The pieces of lift that decide things without touching Android: where a tap lands on the
 * decoded bitmap, which failure a thrown exception is, and which photos lift is offered for.
 */
class LiftMappingTest {

    // ---- tap -> bitmap pixel ----

    @Test
    fun `a tap in the middle of a letterboxed photo maps to the middle of the bitmap`() {
        // 800x400 bitmap in a 1000x1000 container: scale 1.25, drawn 1000x500, 250px black above and below.
        val pixel = imagePointToBitmapPixel(Offset(500f, 500f), Size(1000f, 1000f), 800, 400)
        assertEquals(PixelPoint(400, 200), pixel)
    }

    @Test
    fun `a tap on the letterbox is not a pixel`() {
        assertNull(imagePointToBitmapPixel(Offset(500f, 100f), Size(1000f, 1000f), 800, 400))
        assertNull(imagePointToBitmapPixel(Offset(500f, 900f), Size(1000f, 1000f), 800, 400))
    }

    @Test
    fun `a portrait bitmap is fitted by its own dimensions, not by swapped asset dimensions`() {
        // The regression: a portrait photo whose MediaStore row reports the sensor's landscape
        // 4000x3000 while the decoded, EXIF-upright bitmap is 3000x4000. The container is a
        // 1080x2000 viewport.
        val container = Size(1080f, 2000f)
        val bitmapW = 750
        val bitmapH = 1000

        // Upright 3:4 portrait fits to full width: 1080 x 1440, with 280px of black above and below.
        val centre = imagePointToBitmapPixel(Offset(540f, 1000f), container, bitmapW, bitmapH)
        assertEquals(PixelPoint(375, 500), centre)

        // A tap near the photo's top edge, inside the real image, must be a pixel...
        assertNotNull(imagePointToBitmapPixel(Offset(540f, 300f), container, bitmapW, bitmapH))

        // ...whereas mapping through the swapped landscape dimensions (the old behaviour) fits a
        // 1080x810 rect with 595px of black above, so the very same tap was read as "letterbox"
        // and dismissed the overlay.
        assertNull(imagePointToBitmapPixel(Offset(540f, 300f), container, 1000, 750))
    }

    @Test
    fun `the corners of the drawn image map to the corner pixels`() {
        val container = Size(1000f, 1000f)
        assertEquals(PixelPoint(0, 0), imagePointToBitmapPixel(Offset(0.5f, 250.5f), container, 800, 400))
        assertEquals(PixelPoint(799, 399), imagePointToBitmapPixel(Offset(999.5f, 749.5f), container, 800, 400))
    }

    @Test
    fun `degenerate sizes map to nothing instead of throwing`() {
        assertNull(imagePointToBitmapPixel(Offset(1f, 1f), Size(100f, 100f), 0, 10))
        assertNull(imagePointToBitmapPixel(Offset(1f, 1f), Size(100f, 100f), 10, 0))
        assertNull(imagePointToBitmapPixel(Offset(1f, 1f), Size.Zero, 10, 10))
    }

    // ---- tap -> normalised position (what strokes are stored in) ----

    @Test
    fun `normalised positions are fractions of the drawn image, not of the container`() {
        val n = imagePointToNormalized(Offset(500f, 500f), Size(1000f, 1000f), 800, 400, clamp = false)
        assertEquals(0.5f, n!!.x, 0.0001f)
        assertEquals(0.5f, n.y, 0.0001f)

        val quarter = imagePointToNormalized(Offset(250f, 375f), Size(1000f, 1000f), 800, 400, clamp = false)
        assertEquals(0.25f, quarter!!.x, 0.0001f)
        assertEquals(0.25f, quarter.y, 0.0001f)
    }

    @Test
    fun `the letterbox is null unless clamping, which pulls it onto the nearest edge`() {
        val container = Size(1000f, 1000f)
        assertNull(imagePointToNormalized(Offset(500f, 100f), container, 800, 400, clamp = false))

        val above = imagePointToNormalized(Offset(500f, 100f), container, 800, 400, clamp = true)
        assertEquals(0f, above!!.y, 0.0001f)
        val farRight = imagePointToNormalized(Offset(5000f, 500f), container, 800, 400, clamp = true)
        assertEquals(1f, farRight!!.x, 0.0001f)
    }

    @Test
    fun `degenerate input has no normalised position`() {
        assertNull(imagePointToNormalized(Offset(1f, 1f), Size(100f, 100f), 0, 10, clamp = true))
        assertNull(imagePointToNormalized(Offset(1f, 1f), Size.Zero, 10, 10, clamp = true))
    }

    // ---- failure classification ----

    @Test
    fun `an out of memory error is its own failure`() {
        assertEquals(LiftFailure.OutOfMemory, classifyLiftFailure(OutOfMemoryError("bitmap")))
    }

    @Test
    fun `an out of memory error wrapped by a decoder is still out of memory`() {
        assertEquals(LiftFailure.OutOfMemory, classifyLiftFailure(RuntimeException("decode failed", OutOfMemoryError())))
    }

    @Test
    fun `unreadable files and revoked permissions are unreadable`() {
        assertEquals(LiftFailure.Unreadable, classifyLiftFailure(FileNotFoundException("gone")))
        assertEquals(LiftFailure.Unreadable, classifyLiftFailure(IOException("read error")))
        assertEquals(LiftFailure.Unreadable, classifyLiftFailure(SecurityException("permission revoked")))
    }

    @Test
    fun `anything else keeps its message`() {
        assertEquals(LiftFailure.Failed("boom"), classifyLiftFailure(IllegalStateException("boom")))
    }

    @Test
    fun `every failure has a distinct, non-blank message and the unreadable one is the documented sentence`() {
        val messages = listOf(
            LiftFailure.NoSubject,
            LiftFailure.TooMuch,
            LiftFailure.OutOfMemory,
            LiftFailure.Unreadable,
            LiftFailure.Failed(null),
        ).map { it.userMessage() }
        assertTrue(messages.all { it.isNotBlank() })
        assertEquals(messages.size, messages.toSet().size)
        assertEquals("Couldn't open this photo for lifting", LiftFailure.Unreadable.userMessage())
    }

    // ---- what a finished solve says about itself ----

    private val tap = SelectionOp.Hint(true, BrushStroke(listOf(NormPoint(0.5f, 0.5f)), 0.03f))
    private val backgroundHint = SelectionOp.Hint(false, BrushStroke(listOf(NormPoint(0.1f, 0.1f)), 0.03f))
    private val paint = SelectionOp.Paint(false, BrushStroke(listOf(NormPoint(0.1f, 0.1f)), 0.03f))

    private fun session(vararg ops: SelectionOp): SelectionSession = ops.fold(SelectionSession()) { s, op -> s.apply(op) }

    /** A 10x10 mask with the first [covered] pixels fully selected. */
    private fun mask(covered: Int, alpha: Float = 1f) =
        SubjectSegmentation.Mask(10, 10, FloatArray(100) { if (it < covered) alpha else 0f })

    @Test
    fun `nothing to say before there is a foreground mark`() {
        assertNull(solveFailureFor(mask(100), SelectionSession()))
        assertNull(solveFailureFor(mask(100), session(backgroundHint)))
    }

    @Test
    fun `a foreground mark that selected nothing is no subject`() {
        assertEquals(LiftFailure.NoSubject, solveFailureFor(mask(0), session(tap)))
    }

    @Test
    fun `selecting nearly the whole frame with no background mark is too much`() {
        assertEquals(LiftFailure.TooMuch, solveFailureFor(mask(100), session(tap)))
        assertEquals(LiftFailure.TooMuch, solveFailureFor(mask(86), session(tap)))
    }

    @Test
    fun `the too much line is above 85 percent, not at it`() {
        assertNull(solveFailureFor(mask(85), session(tap)))
        assertNull(solveFailureFor(mask(40), session(tap)))
    }

    @Test
    fun `a background mark or a painted correction means the person is steering`() {
        assertNull(solveFailureFor(mask(100), session(tap, backgroundHint)))
        assertNull(solveFailureFor(mask(100), session(tap, paint)))
    }

    @Test
    fun `faint feathered alpha does not count as covered`() {
        assertNull(solveFailureFor(mask(100, alpha = 0.3f), session(tap)))
        assertEquals(1f, coverage(mask(100)), 0f)
        assertEquals(0.3f, coverage(mask(30)), 0.0001f)
        assertEquals(0f, coverage(SubjectSegmentation.Mask(0, 0, FloatArray(0))), 0f)
    }

    @Test
    fun `catchingResult captures failures but lets cancellation through`() {
        assertTrue(catchingResult { error("x") }.isFailure)
        assertEquals(7, catchingResult { 7 }.getOrNull())
        try {
            catchingResult { throw CancellationException("cancelled") }
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            // expected
        }
    }

    // ---- which photos get the Lift row ----

    private fun asset(mime: String, name: String) = MediaAsset(
        id = MediaId(1L),
        contentUriString = "content://test/1",
        displayName = name,
        mimeType = mime,
        bucketName = null,
        dateTakenMillis = 0L,
        dateModifiedSeconds = 0L,
        width = 100,
        height = 100,
        sizeBytes = 1L,
        relativePath = null,
        isFavorite = false,
        isTrashed = false,
    )

    @Test
    fun `ordinary raster photos can be lifted`() {
        assertTrue(canLiftSubject(asset("image/jpeg", "a.jpg")))
        assertTrue(canLiftSubject(asset("image/png", "a.png")))
        assertTrue(canLiftSubject(asset("image/webp", "a.webp")))
        assertTrue(canLiftSubject(asset("image/heic", "a.heic")))
        assertTrue(canLiftSubject(asset("image/gif", "still.gif")))
    }

    @Test
    fun `video, svg, raw and animated images cannot`() {
        assertFalse(canLiftSubject(asset("video/mp4", "a.mp4")))
        assertFalse(canLiftSubject(asset("image/svg+xml", "a.svg")))
        assertFalse(canLiftSubject(asset("image/x-adobe-dng", "a.dng")))
        assertFalse(canLiftSubject(asset("image/jpeg", "IMG.CR2")))
        assertFalse(canLiftSubject(asset("image/gif", "anim.gif"), isAnimated = true))
    }
}
