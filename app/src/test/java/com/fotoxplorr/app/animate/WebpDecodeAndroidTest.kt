package com.fotoxplorr.app.animate

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.spatial.GeoIndexState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * The two Android codecs the new sources lean on, against what the host's Robolectric offers: the
 * platform WebP decoder (real, native graphics) behind [WebpReader], and Robolectric's stand-in for
 * `MediaMetadataRetriever` behind [RetrieverVideoReader]. What this shows: the stand-alone files
 * [WebpReader] builds are accepted by a real WebP decoder, lossless and lossy, with and without alpha.
 * What it cannot: a WebP from a real animated encoder (the frames here are wrapped by [HandWebp]),
 * the device's own decoder, or a real video file.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WebpDecodeAndroidTest {
    private val context = RuntimeEnvironment.getApplication()

    private val host = ExperimentAnimateHost(
        ExperimentEnv(
            assets = emptyList(), indexInput = emptyList(), geoState = GeoIndexState(), onIndexLocations = {},
            onOpenAsset = { _, _ -> }, context = context, currentBrowsableIds = { emptySet() },
        ),
    )

    private fun bitmapOf(w: Int, h: Int, pixel: (x: Int, y: Int) -> Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(IntArray(w * h) { pixel(it % w, it / w) }, 0, w, 0, 0, w, h)
        return b
    }

    private fun encode(b: Bitmap, lossless: Boolean): ByteArray = ByteArrayOutputStream().also {
        b.compress(if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY, if (lossless) 100 else 92, it)
    }.toByteArray()

    /** The chunks that make a frame in an ANMF: everything in a still WebP file after the RIFF header except its VP8X. */
    private fun imageChunks(webp: ByteArray): ByteArray {
        assertEquals("RIFF", String(webp, 0, 4))
        assertEquals("WEBP", String(webp, 8, 4))
        val out = ByteArrayOutputStream()
        var pos = 12
        while (pos + 8 <= webp.size) {
            val type = String(webp, pos, 4)
            val len = (0..3).sumOf { (webp[pos + 4 + it].toInt() and 255) shl (8 * it) }
            val end = minOf(webp.size, pos + 8 + len + (len and 1))
            if (type == "ALPH" || type == "VP8 " || type == "VP8L") out.write(webp, pos, end - pos)
            pos = end
        }
        return out.toByteArray()
    }

    private fun frameOf(x: Int, y: Int, b: Bitmap, lossless: Boolean, ms: Int = 100, blend: Boolean = true, dispose: Boolean = false) =
        HandWebp.Frame(x, y, b.width, b.height, ms, dispose, blend, imageChunks(encode(b, lossless)))

    private fun close(a: Int, b: Int, tolerance: Int): Boolean =
        listOf(24, 16, 8, 0).all { abs(((a ushr it) and 255) - ((b ushr it) and 255)) <= tolerance }

    private fun readAll(bytes: ByteArray): Pair<List<AnimFrame>, WebpReader> {
        val r = WebpReader(bytes) { host.decodeImage(it) }
        val frames = ArrayList<AnimFrame>()
        while (true) frames.add(r.next() ?: break)
        return frames to r
    }

    @Test
    fun `lossless opaque frames come back exactly through the platform decoder`() {
        val a = bitmapOf(32, 24) { x, y -> 0xFF000000.toInt() or ((x * 8) shl 16) or ((y * 10) shl 8) or 0x55 }
        val b = bitmapOf(16, 8) { x, y -> 0xFF000000.toInt() or ((y * 30) shl 16) or ((x * 15) shl 8) or 0xAA }
        val bytes = HandWebp(32, 24, loop = 2).frame(frameOf(0, 0, a, true)).frame(frameOf(8, 4, b, true, ms = 50)).build()
        val (frames, r) = readAll(bytes)
        assertEquals(DecodeProblem.NONE, r.problem)
        assertEquals(2, frames.size)
        val expectA = IntArray(32 * 24).also { a.getPixels(it, 0, 32, 0, 0, 32, 24) }
        assertEquals(expectA.toList(), frames[0].pixels.toList())
        val second = frames[1].pixels
        assertEquals("inside the second frame's box", b.getPixel(3, 2), second[(4 + 2) * 32 + 8 + 3])
        assertEquals("outside it the first frame stays", expectA[0], second[0])
        assertEquals(50, frames[1].delayMs)
    }

    @Test
    fun `lossless frames with alpha are blended over what is under them`() {
        val base = bitmapOf(16, 16) { _, _ -> 0xFF2040C0.toInt() }
        val half = bitmapOf(8, 8) { _, _ -> 0x80FF8000.toInt() }
        val clear = bitmapOf(4, 4) { _, _ -> 0 }
        val bytes = HandWebp(16, 16)
            .frame(frameOf(0, 0, base, true))
            .frame(frameOf(2, 2, half, true, blend = true))
            .frame(frameOf(0, 0, clear, true, blend = false))
            .build()
        val (frames, r) = readAll(bytes)
        assertEquals(DecodeProblem.NONE, r.problem)
        assertEquals(3, frames.size)
        val mixed = frames[1].pixels[4 * 16 + 4]
        assertEquals(255, mixed ushr 24)
        assertTrue("half orange over blue: ${Integer.toHexString(mixed)}", close(mixed, FrameScaler.over(0x80FF8000.toInt(), 0xFF2040C0.toInt()), 3))
        assertEquals("a frame that does not blend replaces the pixels with its clear ones", 0, frames[2].pixels[0])
        assertEquals("beside it the canvas stays", 0xFF2040C0.toInt(), frames[2].pixels[12 * 16 + 12])
    }

    @Test
    fun `lossy frames, with and without an alpha chunk, are accepted too`() {
        val opaque = bitmapOf(48, 32) { x, y -> 0xFF000000.toInt() or ((x * 5) shl 16) or ((y * 7) shl 8) or 0x80 }
        val soft = bitmapOf(48, 32) { x, y -> ((40 + x * 3) shl 24) or ((x * 5) shl 16) or ((y * 7) shl 8) or 0x80 }
        val bytes = HandWebp(48, 32).frame(frameOf(0, 0, opaque, false)).frame(frameOf(0, 0, soft, false, blend = false)).build()
        val (frames, r) = readAll(bytes)
        assertEquals(DecodeProblem.NONE, r.problem)
        assertEquals(2, frames.size)
        val want = opaque.getPixel(20, 10)
        assertTrue("lossy, close: ${Integer.toHexString(frames[0].pixels[10 * 48 + 20])} vs ${Integer.toHexString(want)}", close(frames[0].pixels[10 * 48 + 20], want, 14))
        val alpha = frames[1].pixels[10 * 48 + 20] ushr 24
        assertTrue("the alpha plane came through: $alpha", alpha in 80..120)
    }

    @Test
    fun `the platform decoder says no to garbage and the reader turns that into damage`() {
        val bytes = HandWebp(8, 8).solid(0, 0, 8, 8, 0xFFFF0000.toInt(), lossy = true).solid(0, 0, 8, 8, 0xFF00FF00.toInt(), lossy = true).build()
        val (frames, r) = readAll(bytes)
        assertEquals("the four bytes of 'colour' are not a VP8 stream", 0, frames.size)
        assertEquals(DecodeProblem.CORRUPT, r.problem)
        assertNull(host.decodeImage(ByteArray(40) { 3 }))
    }

    // ---- the retriever's reader -----------------------------------------------------------------------------

    private val uri = Uri.parse("content://fake/video/1")

    private fun addVideo(width: String, height: String, rotation: String?, duration: String = "5000") {
        val source = DataSource.toDataSource(context, uri)
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, width)
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, height)
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_DURATION, duration)
        if (rotation != null) ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, rotation)
    }

    private fun addFrame(timeUs: Long, bitmap: Bitmap, scaledTo: Pair<Int, Int>? = null) {
        val source = DataSource.toDataSource(context, uri)
        if (scaledTo == null) ShadowMediaMetadataRetriever.addFrame(source, timeUs, bitmap)
        else ShadowMediaMetadataRetriever.addScaledFrame(source, timeUs, scaledTo.first, scaledTo.second, bitmap)
    }

    @Test
    fun `a landscape video is described by its coded size and length`() {
        ShadowMediaMetadataRetriever.reset()
        addVideo("640", "480", "0")
        val r = RetrieverVideoReader.open(context, uri)!!
        val info = r.info()!!
        assertEquals(640, info.width)
        assertEquals(480, info.height)
        assertEquals(5000L, info.durationMs)
        r.close()
    }

    @Test
    fun `a video recorded with a quarter turn is described upright`() {
        ShadowMediaMetadataRetriever.reset()
        addVideo("640", "480", "90")
        val info = RetrieverVideoReader.open(context, uri)!!.info()!!
        assertEquals(480, info.width)
        assertEquals(640, info.height)
    }

    @Test
    fun `a video with no size is unreadable`() {
        ShadowMediaMetadataRetriever.reset()
        addVideo("0", "0", null)
        assertNull(RetrieverVideoReader.open(context, uri)!!.info())
    }

    @Test
    fun `a source the platform refuses is no reader`() {
        ShadowMediaMetadataRetriever.reset()
        ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(context, uri), RuntimeException("no codec"))
        assertNull(RetrieverVideoReader.open(context, uri))
    }

    @Test
    fun `frames of a video that comes back upright are used as they are, and the scaled call follows`() {
        ShadowMediaMetadataRetriever.reset()
        addVideo("640", "480", "0")
        val first = bitmapOf(640, 480) { _, _ -> 0xFF112233.toInt() }
        addFrame(1_000_000, first)
        val scaled = bitmapOf(320, 240) { _, _ -> 0xFF445566.toInt() }
        addFrame(2_000_000, scaled, 320 to 240)
        val r = RetrieverVideoReader.open(context, uri, sdk = 34)!!
        val a = r.frameAt(1000, 320, 240)!!
        assertEquals("the first read is unscaled and fitted down here", 320, a.width)
        assertEquals(0xFF112233.toInt(), a.argb[0])
        val b = r.frameAt(2000, 320, 240)!!
        assertEquals(320, b.width)
        assertEquals(240, b.height)
        assertEquals(0xFF445566.toInt(), b.argb[0])
    }

    @Test
    fun `frames that come back unturned from a portrait video are turned here`() {
        ShadowMediaMetadataRetriever.reset()
        addVideo("640", "480", "90")
        // The retriever returns the coded (landscape) frame; the upright video is portrait.
        val coded = bitmapOf(640, 480) { x, _ -> if (x < 320) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        // The platform hands out a new bitmap for every read, and the reader gives each one back.
        addFrame(0, coded)
        addFrame(500_000, bitmapOf(640, 480) { x, _ -> if (x < 320) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() })
        val r = RetrieverVideoReader.open(context, uri, sdk = 34)!!
        val f = r.frameAt(0, 1000, 1000)!!
        assertEquals(480, f.width)
        assertEquals(640, f.height)
        // Turned clockwise, the coded left half (red) is the top half.
        assertEquals(0xFFFF0000.toInt(), f.argb[10 * 480 + 10])
        assertEquals(0xFF0000FF.toInt(), f.argb[630 * 480 + 10])
        val again = r.frameAt(500, 1000, 1000)!!
        assertEquals("later frames are turned the same way", 480, again.width)
    }

    @Test
    fun `a time with no frame is no frame`() {
        ShadowMediaMetadataRetriever.reset()
        addVideo("640", "480", "0")
        addFrame(0, bitmapOf(640, 480) { _, _ -> 0xFF000000.toInt() })
        val r = RetrieverVideoReader.open(context, uri, sdk = 26)!!
        assertNotNull(r.frameAt(0, 100, 100))
        assertNull(r.frameAt(9000, 100, 100))
    }
}
