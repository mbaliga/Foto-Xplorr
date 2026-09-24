package com.fotoxplorr.app.media

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempFile
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P0-03 decision D (see Decisions in `docs/handoff/PHASE-0-PROGRESS.md`): empirically confirms,
 * against this exact Robolectric/AGP/SDK setup, that [ImageDecoder] already applies a JPEG's EXIF
 * orientation before [decodeUpright] ever sees the pixels -- rather than trusting a general claim
 * about the platform. NATIVE graphics mode, the same reason `MetadataWriterTest` uses it: the
 * legacy Robolectric graphics shadow draws and decodes nothing real.
 *
 * A real 4x6 JPEG tagged `Orientation = 6` (rotate 90 CW) is unambiguous either way: if
 * [ImageDecoder] ignores the tag, both the header callback's reported size and the final bitmap
 * stay 4x6 (the raw encoded grid); if it applies the tag, both become 6x4 (upright).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BitmapDecodingImageDecoderExifTest {

    private fun realJpegWithOrientation(orientation: Int): File {
        val file = createTempFile("fx-decode", ".jpg").toFile()
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file
    }

    @Test
    fun `ImageDecoder reports and decodes a rotate-90 JPEG already upright`() {
        val file = realJpegWithOrientation(ExifInterface.ORIENTATION_ROTATE_90)
        val source = ImageDecoder.createSource(file)

        var headerWidth = 0
        var headerHeight = 0
        val bitmap = ImageDecoder.decodeBitmap(source) { _, info, _ ->
            headerWidth = info.size.width
            headerHeight = info.size.height
        }

        // Raw encoded grid is 4 wide x 6 tall; Orientation=6 rotates it 90 CW to upright 6x4.
        assertEquals(6, headerWidth)
        assertEquals(4, headerHeight)
        assertEquals(6, bitmap.width)
        assertEquals(4, bitmap.height)
    }
}
