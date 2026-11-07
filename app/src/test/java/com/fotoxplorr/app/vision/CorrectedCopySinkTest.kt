package com.fotoxplorr.app.vision

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * What [EditorCopySink] actually writes, through the editor's real [com.fotoxplorr.app.editor.EditedCopyWriter]
 * and a stand-in for MediaStore ([FakeMediaStoreProvider]): the format follows the source, a
 * transparent picture keeps its transparency, and the place a photo was taken is not carried to the
 * copy. What the real MediaStore accepts is not exercised.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CorrectedCopySinkTest {

    private val context = RuntimeEnvironment.getApplication()
    private val made = mutableListOf<File>()

    init {
        FakeMediaStoreProvider.install()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    @After
    fun cleanUp() = made.forEach { it.delete() }

    /** A small JPEG with a place, a capture time and a make. */
    private fun fileWithPlace(): File {
        val file = File.createTempFile("fx-sink-source", ".jpg").also { made += it }
        FileOutputStream(file).use { Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val exif = ExifInterface(file.absolutePath)
        exif.setLatLong(51.5074, -0.1278)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:06:01 10:30:00")
        exif.setAttribute(ExifInterface.TAG_MAKE, "Fotoxplorr Cam Co")
        exif.saveAttributes()
        return file
    }

    private fun sourceWithPlace(id: String, mime: String = "image/jpeg", name: String = "IMG_0007.jpg"): MediaAsset {
        val uri = FakeMediaStoreProvider.serve(id, fileWithPlace())
        return ColourVisionKit.asset(id = 7, name = name, mime = mime, uri = uri.toString())
    }

    private fun save(source: MediaAsset, bitmap: Bitmap): FakeMediaStoreProvider.Inserted {
        val before = FakeMediaStoreProvider.inserted.size
        val result = runBlocking { EditorCopySink(context).save(source, bitmap) {} }
        assertTrue("the save should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(before + 1, FakeMediaStoreProvider.inserted.size)
        return FakeMediaStoreProvider.inserted.last().also { made += it.file }
    }

    private fun opaque() = ColourVisionKit.bitmap(6, 6) { _, _ -> VisionTestKit.argb(200, 80, 40) }

    @Test
    fun `the source fixture really carries a place, so the checks below prove something`() {
        assertNotNull(ExifInterface(fileWithPlace().absolutePath).latLong)
    }

    @Test
    fun `a corrected copy carries no place but keeps the rest of the metadata`() {
        val inserted = save(sourceWithPlace("s1"), opaque())
        val exif = ExifInterface(inserted.file.absolutePath)
        assertNull("no coordinate may reach the copy", exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertEquals("2024:06:01 10:30:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("Fotoxplorr Cam Co", exif.getAttribute(ExifInterface.TAG_MAKE))
    }

    @Test
    fun `a JPEG source gives a JPEG copy named for the original`() {
        val inserted = save(sourceWithPlace("s2"), opaque())
        assertEquals("image/jpeg", inserted.values.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals("IMG_0007-edited.jpg", inserted.values.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
    }

    @Test
    fun `a PNG source gives a PNG copy, and a clear pixel stays clear`() {
        val source = sourceWithPlace("s3", mime = "image/png", name = "icon.png")
        val bitmap = ColourVisionKit.bitmap(6, 6) { x, _ -> if (x < 3) Color.TRANSPARENT else VisionTestKit.argb(200, 80, 40) }
        val inserted = save(source, bitmap)
        assertEquals("image/png", inserted.values.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals("icon-edited.png", inserted.values.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
        val decoded = BitmapFactory.decodeFile(inserted.file.absolutePath)
        assertNotNull(decoded)
        assertEquals("a clear pixel must not turn black", 0, Color.alpha(decoded.getPixel(1, 3)))
        assertEquals(255, Color.alpha(decoded.getPixel(4, 3)))
    }

    @Test
    fun `a WebP source gives a WebP copy`() {
        val inserted = save(sourceWithPlace("s4", mime = "image/webp", name = "shot.webp"), opaque())
        assertEquals("image/webp", inserted.values.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals("shot-edited.webp", inserted.values.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
    }

    @Test
    fun `a source that is not JPEG, PNG or WebP gives a JPEG copy`() {
        val inserted = save(sourceWithPlace("s5", mime = "image/heic", name = "IMG_1.heic"), opaque())
        assertEquals("image/jpeg", inserted.values.getAsString(MediaStore.Images.Media.MIME_TYPE))
    }
}
