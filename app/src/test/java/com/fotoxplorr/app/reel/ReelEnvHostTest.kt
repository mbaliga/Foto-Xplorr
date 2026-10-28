package com.fotoxplorr.app.reel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.experimental.ExperimentEnv
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
 * [ReelEnvHost] over a real [ExperimentEnv] and real JPEG files: the host's own `mayExport` is what decides,
 * a photo that is not in the browsable list is never read, the pixels come back upright (TRAPS #34), what is
 * written for the encoder carries no metadata, and every bitmap made is given back. What this cannot show: a
 * sound file or a video through the platform's real media classes (Robolectric has none); those paths are
 * only checked for saying "cannot use" on a file that is not media.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ReelEnvHostTest {
    private val context = RuntimeEnvironment.getApplication()
    private val wide = ReelFrame(1920, 1080)

    private fun jpeg(name: String, w: Int, h: Int, orientation: Int? = null, gps: Boolean = false): File {
        val file = File(context.cacheDir, name).apply { deleteOnExit() }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(200, 40, 90))
            for (y in 0 until minOf(h, 20)) for (x in 0 until minOf(w, 20)) setPixel(x, y, Color.rgb(250, 250, 10))
        }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (orientation != null || gps) {
            val exif = ExifInterface(file.path)
            if (orientation != null) exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            if (gps) exif.setLatLong(38.7223, -9.1393)
            exif.saveAttributes()
        }
        return file
    }

    private fun asset(id: Long, file: File, w: Int, h: Int, mime: String = "image/jpeg") = MediaAsset(
        id = MediaId(id), contentUriString = Uri.fromFile(file).toString(), displayName = file.name, mimeType = mime,
        bucketName = null, dateTakenMillis = 0, dateModifiedSeconds = 7, width = w, height = h, sizeBytes = file.length(),
        relativePath = null, isFavorite = false, isTrashed = false,
    )

    private fun env(browsable: () -> Set<MediaId>, assets: List<MediaAsset>) = ExperimentEnv(
        assets = assets, indexInput = assets, geoState = GeoIndexState(), onIndexLocations = {},
        onOpenAsset = { _, _ -> }, context = context, currentBrowsableIds = browsable,
    )

    private fun host(browsable: Set<MediaId>, assets: List<MediaAsset>) = ReelEnvHost(env({ browsable }, assets))

    private fun out(name: String) = File(context.cacheDir, name).apply { delete(); deleteOnExit() }

    // ---- what the host shows ------------------------------------------------------------------------------

    @Test
    fun `an item is found by its library id in the browsable list, and only there`() {
        val a = asset(7, jpeg("reel-host-a.jpg", 64, 48), 64, 48)
        val h = host(setOf(a.id), listOf(a))
        assertEquals(a, h.asset(7L))
        assertNull(h.asset(8L))
    }

    @Test
    fun `whether a photo may be used is asked of the live list every time`() {
        val a = asset(1, jpeg("reel-host-b.jpg", 64, 48), 64, 48)
        var browsable = setOf(a.id)
        val h = ReelEnvHost(env({ browsable }, listOf(a)))
        assertTrue(h.mayUsePhoto(a))
        browsable = emptySet()
        assertFalse("locked since the editor opened", h.mayUsePhoto(a))
    }

    @Test
    fun `a video is asked the same live question, and a photo is not a video`() {
        val file = jpeg("reel-host-c.jpg", 64, 48)
        val video = asset(2, file, 64, 48, mime = "video/mp4")
        val photo = asset(3, file, 64, 48)
        var browsable = setOf(video.id, photo.id)
        val h = ReelEnvHost(env({ browsable }, listOf(video, photo)))
        assertTrue(h.mayUseVideo(video))
        assertFalse("a photo is not a video", h.mayUseVideo(photo))
        browsable = setOf(photo.id)
        assertFalse("a clip in a locked folder", h.mayUseVideo(video))
    }

    // ---- shape -----------------------------------------------------------------------------------------------

    @Test
    fun `the shape is the upright one, with the EXIF turn applied`() {
        val plain = asset(1, jpeg("reel-host-d.jpg", 320, 240), 320, 240)
        val turned = asset(2, jpeg("reel-host-e.jpg", 320, 240, orientation = ExifInterface.ORIENTATION_ROTATE_90), 320, 240)
        val h = host(setOf(plain.id, turned.id), listOf(plain, turned))
        assertEquals(320f / 240f, runBlocking { h.shapeOf(plain) }!!, 0.02f)
        assertEquals("listed as 320x240 but upright 240x320", 0.75f, runBlocking { h.shapeOf(turned) }!!, 0.02f)
    }

    @Test
    fun `the shape of a photo that is not shown or not there is null`() {
        val file = jpeg("reel-host-f.jpg", 64, 48)
        val a = asset(1, file, 64, 48)
        assertNull(runBlocking { host(emptySet(), listOf(a)).shapeOf(a) })
        val gone = asset(2, File(context.cacheDir, "reel-host-missing.jpg"), 64, 48)
        assertNull(runBlocking { host(setOf(gone.id), listOf(gone)).shapeOf(gone) })
    }

    @Test
    fun `the shape of a video that is not media is null`() {
        val notVideo = File(context.cacheDir, "reel-host-notvideo.mp4").apply { writeText("not a video"); deleteOnExit() }
        val v = asset(5, notVideo, 100, 100, mime = "video/mp4")
        assertNull(runBlocking { host(setOf(v.id), listOf(v)).shapeOf(v) })
    }

    // ---- staging a photo -------------------------------------------------------------------------------------------

    @Test
    fun `a photo is written upright and never enlarged, as a JPEG with nothing but pixels`() {
        val a = asset(1, jpeg("reel-host-g.jpg", 320, 240, orientation = ExifInterface.ORIENTATION_ROTATE_90, gps = true), 320, 240)
        val into = out("reel-staged-g.jpg")
        val staged = runBlocking { host(setOf(a.id), listOf(a)).stagePhoto(a, StageSpec(wide, ReelFit.COVER, ReelMotion.None), into) }!!
        assertEquals(240, staged.width)
        assertEquals(320, staged.height)
        assertEquals(into, staged.file)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(into.path, bounds)
        assertEquals(240, bounds.outWidth)
        assertEquals(320, bounds.outHeight)
        val exif = ExifInterface(into.path)
        assertNull("no place in what the encoder reads", exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        val head = String(into.readBytes().copyOf(4096), Charsets.ISO_8859_1)
        assertFalse("no EXIF block at all in the file the encoder reads", head.contains("Exif"))
    }

    @Test
    fun `a photo that is not shown is never read and no file is made`() {
        val a = asset(1, jpeg("reel-host-h.jpg", 320, 240), 320, 240)
        val into = out("reel-staged-h.jpg")
        assertNull(runBlocking { host(emptySet(), listOf(a)).stagePhoto(a, StageSpec(wide, ReelFit.COVER, ReelMotion.None), into) })
        assertFalse(into.exists())
    }

    @Test
    fun `a photo that has gone is null, not a crash`() {
        val gone = asset(2, File(context.cacheDir, "reel-host-gone.jpg"), 320, 240)
        assertNull(runBlocking { host(setOf(gone.id), listOf(gone)).stagePhoto(gone, StageSpec(wide, ReelFit.COVER, ReelMotion.ZoomIn()), out("reel-staged-gone.jpg")) })
    }

    @Test
    fun `a zoom asks for the size its closest window needs`() {
        val a = asset(1, jpeg("reel-host-i.jpg", 3000, 2000), 3000, 2000)
        val h = host(setOf(a.id), listOf(a))
        val zoom = runBlocking { h.stagePhoto(a, StageSpec(wide, ReelFit.COVER, ReelMotion.ZoomIn()), out("reel-staged-i1.jpg")) }!!
        val still = runBlocking { h.stagePhoto(a, StageSpec(wide, ReelFit.COVER, ReelMotion.None), out("reel-staged-i2.jpg")) }!!
        // 3:2 in a 16:9 frame: the closest window holds 1920 pixels across 5/6 of the width, so 2304 across.
        assertTrue("zoom ${zoom.width}", zoom.width in 2300..2308)
        assertTrue("a still with no move needs only the frame's long side: ${still.width}", still.width in 1916..1924)
        assertEquals(2f / 3f, zoom.height.toFloat() / zoom.width, 0.01f)
    }

    @Test
    fun `a picture that keeps its shape is placed whole on a black frame`() {
        val a = asset(1, jpeg("reel-host-j.jpg", 300, 600), 300, 600)
        val frame = ReelFrame(640, 360)
        val into = out("reel-staged-j.jpg")
        val staged = runBlocking { host(setOf(a.id), listOf(a)).stagePhoto(a, StageSpec(frame, ReelFit.CONTAIN, ReelMotion.ZoomIn()), into) }!!
        assertEquals(640, staged.width)
        assertEquals(360, staged.height)
        val bitmap = BitmapFactory.decodeFile(into.path)
        assertEquals(640, bitmap.width)
        assertEquals(360, bitmap.height)
        fun dark(x: Int, y: Int) = Color.red(bitmap.getPixel(x, y)) < 20 && Color.green(bitmap.getPixel(x, y)) < 20 && Color.blue(bitmap.getPixel(x, y)) < 20
        assertTrue("left bar is black", dark(5, 180))
        assertTrue("right bar is black", dark(634, 180))
        val middle = bitmap.getPixel(320, 180)
        assertTrue("the picture is in the middle (red ${Color.red(middle)})", Color.red(middle) in 170..230)
    }

    // ---- cards ---------------------------------------------------------------------------------------------------

    @Test
    fun `a card is written as a PNG the size of the frame`() {
        val into = out("reel-card.png")
        val staged = runBlocking { host(emptySet(), emptyList()).stageCard("June 2025\nLisbon", 0xFF223344.toInt(), ReelFrame(320, 180), into) }
        assertEquals(320, staged.width)
        assertEquals(180, staged.height)
        val bitmap = BitmapFactory.decodeFile(into.path)
        assertNotNull(bitmap)
        assertEquals(0xFF223344.toInt(), bitmap.getPixel(2, 2))
        val bytes = into.readBytes()
        assertEquals("a PNG", 0x89, bytes[0].toInt() and 0xFF)
    }

    // ---- sound ---------------------------------------------------------------------------------------------------

    @Test
    fun `a sound file that is missing, empty or not sound is not usable`() {
        val h = host(emptySet(), emptyList())
        val empty = File(context.cacheDir, "reel-empty.mp3").apply { writeBytes(ByteArray(0)); deleteOnExit() }
        val text = File(context.cacheDir, "reel-text.mp3").apply { writeText("this is not music"); deleteOnExit() }
        assertEquals(SoundtrackProbe.Unusable, runBlocking { h.probeSoundtrack(Uri.fromFile(File(context.cacheDir, "reel-none.mp3")).toString()) })
        assertEquals(SoundtrackProbe.Unusable, runBlocking { h.probeSoundtrack(Uri.fromFile(empty).toString()) })
        assertEquals(SoundtrackProbe.Unusable, runBlocking { h.probeSoundtrack(Uri.fromFile(text).toString()) })
        assertEquals(SoundtrackProbe.Unusable, runBlocking { h.probeSoundtrack("content://nobody/document/1") })
        assertEquals(SoundtrackProbe.Unusable, runBlocking { h.probeSoundtrack("") })
    }
}
