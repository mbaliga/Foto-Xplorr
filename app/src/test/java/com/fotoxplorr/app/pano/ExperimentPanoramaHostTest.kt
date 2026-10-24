package com.fotoxplorr.app.pano

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * [ExperimentPanoramaHost] over a real [ExperimentEnv] and real JPEG files: the host's own
 * `mayExport` is what decides, a photo that is not in the browsable list is never read (not even its
 * EXIF), the pixels come back upright (EXIF orientation applied, TRAPS #34), and the bitmap the
 * decoder made is given back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExperimentPanoramaHostTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun jpeg(name: String, w: Int, h: Int, focal35: Int? = null, orientation: Int? = null, red: Int = 200): File {
        val file = File(context.cacheDir, name).apply { deleteOnExit() }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(red, 40, 90)) }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (focal35 != null || orientation != null) {
            val exif = ExifInterface(file.path)
            focal35?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, it.toString()) }
            orientation?.let { exif.setAttribute(ExifInterface.TAG_ORIENTATION, it.toString()) }
            exif.saveAttributes()
        }
        return file
    }

    private fun asset(id: Long, file: File, w: Int, h: Int) = MediaAsset(
        id = MediaId(id), contentUriString = Uri.fromFile(file).toString(), displayName = file.name, mimeType = "image/jpeg",
        bucketName = null, dateTakenMillis = 0, dateModifiedSeconds = 0, width = w, height = h, sizeBytes = file.length(),
        relativePath = null, isFavorite = false, isTrashed = false,
    )

    private fun host(browsable: Set<MediaId>, assets: List<MediaAsset>) = ExperimentPanoramaHost(
        ExperimentEnv(
            assets = assets, indexInput = assets, geoState = GeoIndexState(), onIndexLocations = {},
            onOpenAsset = { _, _ -> }, context = context, currentBrowsableIds = { browsable },
        ),
    )

    private val limits = DecodeLimits(maxLongEdge = 1200, maxPixels = 1_500_000L)

    @Test
    fun `a browsable photo is read upright and its size is the original's`() {
        val file = jpeg("pano-host-a.jpg", 320, 240, focal35 = 26)
        val a = asset(1, file, 320, 240)
        val decoded = runBlocking { host(setOf(a.id), listOf(a)).decode(a, limits) }
        assertNotNull(decoded)
        decoded!!
        assertEquals(320, decoded.sourceWidth)
        assertEquals(240, decoded.sourceHeight)
        assertEquals(320, decoded.pixels.width)
        assertEquals(240, decoded.pixels.height)
        val p = decoded.pixels.argb[100 * 320 + 100]
        assertTrue("the colour came through (red ${(p shr 16) and 0xFF})", ((p shr 16) and 0xFF) in 185..215)
    }

    @Test
    fun `a photo is shrunk to the limits and the original size is still reported`() {
        val file = jpeg("pano-host-b.jpg", 800, 600)
        val a = asset(2, file, 800, 600)
        val decoded = runBlocking { host(setOf(a.id), listOf(a)).decode(a, DecodeLimits(400, 400L * 300)) }!!
        assertEquals(800, decoded.sourceWidth)
        assertEquals(600, decoded.sourceHeight)
        assertEquals(400, decoded.pixels.width)
        assertEquals(300, decoded.pixels.height)
    }

    @Test
    fun `a rotated photo comes out upright with its upright source size`() {
        // Stored 320 x 240 with orientation 6 (rotate 90): upright it is 240 x 320.
        val file = jpeg("pano-host-c.jpg", 320, 240, orientation = ExifInterface.ORIENTATION_ROTATE_90)
        val a = asset(3, file, 320, 240)
        val decoded = runBlocking { host(setOf(a.id), listOf(a)).decode(a, limits) }!!
        assertEquals(240, decoded.sourceWidth)
        assertEquals(320, decoded.sourceHeight)
        assertEquals(240, decoded.pixels.width)
        assertEquals(320, decoded.pixels.height)
    }

    @Test
    fun `a photo that is not browsable is never read, neither pixels nor EXIF`() {
        val file = jpeg("pano-host-d.jpg", 320, 240, focal35 = 26)
        val a = asset(4, file, 320, 240)
        val h = host(emptySet(), listOf(a))
        assertFalse(h.mayExport(a))
        assertNull(runBlocking { h.decode(a, limits) })
        assertNull("the tag is there, but the photo may not be read", runBlocking { h.focal35mm(a) })
    }

    @Test
    fun `a video is never exported`() {
        val file = jpeg("pano-host-e.jpg", 32, 24)
        val video = asset(5, file, 32, 24).copy(mimeType = "video/mp4")
        val h = host(setOf(video.id), listOf(video))
        assertFalse(h.mayExport(video))
        assertNull(runBlocking { h.decode(video, limits) })
    }

    @Test
    fun `the 35 mm focal length is read from EXIF, and absent means null`() {
        val withTag = asset(6, jpeg("pano-host-f.jpg", 64, 48, focal35 = 26), 64, 48)
        val without = asset(7, jpeg("pano-host-g.jpg", 64, 48), 64, 48)
        val h = host(setOf(withTag.id, without.id), listOf(withTag, without))
        assertEquals(26, runBlocking { h.focal35mm(withTag) })
        assertNull(runBlocking { h.focal35mm(without) })
    }

    @Test
    fun `a file that is gone gives null, never a throw`() {
        val missing = asset(8, File(context.cacheDir, "pano-host-missing.jpg"), 64, 48)
        val h = host(setOf(missing.id), listOf(missing))
        assertNull(runBlocking { h.focal35mm(missing) })
        assertNull(runBlocking { h.decode(missing, limits) })
    }

    @Test
    fun `the job refuses a protected photo through the real host before reading anything`() {
        val files = List(3) { jpeg("pano-host-job-$it.jpg", 64, 48, focal35 = 26, red = 100 + it * 30) }
        val assets = files.mapIndexed { i, f -> asset(20L + i, f, 64, 48) }
        val h = host(setOf(assets[0].id, assets[1].id), assets)
        val sink = FakeSink()
        val outcome = runBlocking { PanoramaJob.run(PanoramaRequest(assets), h, sink) }
        assertEquals(PanoCopy.PROTECTED, (outcome as PanoramaOutcome.Failed).message)
        assertTrue(sink.canvases.isEmpty() && sink.published.isEmpty())
    }
}
