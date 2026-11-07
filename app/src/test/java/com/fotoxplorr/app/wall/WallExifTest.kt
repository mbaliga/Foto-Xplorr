package com.fotoxplorr.app.wall

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempFile

/** NATIVE graphics: the legacy shadow would write an empty JPEG (see ExifCopierTest). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WallExifTest {

    private fun jpeg(focal35: Int?): File {
        val file = createTempFile("fx-wall-exif", ".jpg").toFile()
        file.deleteOnExit()
        FileOutputStream(file).use { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (focal35 != null) {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, focal35.toString())
                saveAttributes()
            }
        }
        return file
    }

    @Test
    fun `the 35 mm focal length is read from the file`() {
        assertEquals(26f, WallExif.focal35mm(jpeg(26).inputStream()))
        assertEquals(77f, WallExif.focal35mm(jpeg(77).inputStream()))
    }

    @Test
    fun `a photo with no focal length, or a value that cannot be real, gives null`() {
        assertNull(WallExif.focal35mm(jpeg(null).inputStream()))
        assertNull(WallExif.focal35mm(jpeg(0).inputStream()))
        assertNull(WallExif.focal35mm(jpeg(90000).inputStream()))
    }

    @Test
    fun `bytes that are not an image give null, not a crash`() {
        assertNull(WallExif.focal35mm(ByteArrayInputStream(ByteArray(40) { 7 })))
    }
}
