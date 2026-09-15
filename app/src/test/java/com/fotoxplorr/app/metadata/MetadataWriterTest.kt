package com.fotoxplorr.app.metadata

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempFile

/**
 * [applyMetadataEdit] against a REAL JPEG on disk -- the exact function [MetadataWriter.write]
 * itself calls, not a re-assembly of its pieces, and not a mock of [ExifInterface], which would
 * prove nothing about whether this app's bytes are readable by any tool other than itself.
 *
 * NATIVE graphics mode, the same reason `ScreenRenderTest` uses it: the legacy Robolectric
 * graphics shadow draws nothing, so `Bitmap.compress` under it would produce an empty or invalid
 * file, which is worse than not testing this at all -- it would look green while proving nothing
 * about whether a real JPEG round-trips.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MetadataWriterTest {

    private fun realJpeg(): File {
        val file = createTempFile("fx-metadata", ".jpg").toFile()
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    private fun exif(file: File) = ExifInterface(file.absolutePath)

    @Test
    fun `a fresh photo with no existing metadata gets every field written and reads back correctly`() {
        val file = realJpeg()
        applyMetadataEdit(
            exif(file),
            MetadataEdit(
                caption = "A morning at the market",
                creator = "Jane Doe",
                copyright = "© 2026 Jane Doe",
                rating = 4,
                keywordsToAdd = listOf("market", "weekend"),
                setLocation = GpsCoordinate(51.5074, -0.1278),
            ),
        )

        val reread = exif(file)
        assertEquals("A morning at the market", reread.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
        assertEquals("Jane Doe", reread.getAttribute(ExifInterface.TAG_ARTIST))
        // "(c)", not "©": see setAsciiAttributeOrLeave -- EXIF's classic string tags are 7-bit
        // ASCII and cannot carry the real symbol. XMP, asserted below, gets the exact one.
        assertEquals("(c) 2026 Jane Doe", reread.getAttribute(ExifInterface.TAG_COPYRIGHT))
        val latLong = FloatArray(2)
        assertTrue(reread.getLatLong(latLong))
        assertEquals(51.5074, latLong[0].toDouble(), 0.001)
        assertEquals(-0.1278, latLong[1].toDouble(), 0.001)

        val xmp = XmpPacket.parse(reread.getAttribute(ExifInterface.TAG_XMP)!!)!!
        assertEquals(4, xmp.intValue(XmpPacket.XMP_NS, "Rating"))
        assertEquals(listOf("market", "weekend"), xmp.bag(XmpPacket.DC_NS, "subject"))
        assertEquals("A morning at the market", xmp.langAlt(XmpPacket.DC_NS, "description"))
        // The exact symbol, unlike the EXIF side above -- XMP is full Unicode, no 7-bit ceiling.
        assertEquals("© 2026 Jane Doe", xmp.langAlt(XmpPacket.DC_NS, "rights"))

        val current = currentMetadataFrom(
            xmp = xmp,
            exifImageDescription = reread.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION),
            exifArtist = reread.getAttribute(ExifInterface.TAG_ARTIST),
            exifCopyright = reread.getAttribute(ExifInterface.TAG_COPYRIGHT),
        )
        assertEquals("A morning at the market", current.caption)
        assertEquals("Jane Doe", current.creator)
        assertEquals(4, current.rating)
        assertEquals(listOf("market", "weekend"), current.keywords)
    }

    @Test
    fun `keywords already on the file are kept when new ones are added, never replaced`() {
        val file = realJpeg()
        applyMetadataEdit(exif(file), MetadataEdit(keywordsToAdd = listOf("beach")))
        applyMetadataEdit(exif(file), MetadataEdit(keywordsToAdd = listOf("sunset")))

        val xmp = XmpPacket.parse(exif(file).getAttribute(ExifInterface.TAG_XMP)!!)!!
        assertEquals(listOf("beach", "sunset"), xmp.bag(XmpPacket.DC_NS, "subject"))
    }

    @Test
    fun `clearing location removes the GPS tags this app also strips for share copies`() {
        val file = realJpeg()
        applyMetadataEdit(exif(file), MetadataEdit(setLocation = GpsCoordinate(1.0, 2.0)))
        applyMetadataEdit(exif(file), MetadataEdit(clearLocation = true))

        val latLong = FloatArray(2)
        assertTrue("location should be gone", !exif(file).getLatLong(latLong))
    }

    @Test
    fun `a blank field clears it rather than leaving the old value or writing an empty string`() {
        val file = realJpeg()
        applyMetadataEdit(exif(file), MetadataEdit(caption = "first caption"))
        applyMetadataEdit(exif(file), MetadataEdit(caption = ""))

        assertNull(exif(file).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
    }

    /**
     * The property [MetadataWriter]'s own class doc calls out by name: a photo already carrying
     * real XMP must not lose it because this app touched one unrelated field. What this test
     * adds on top of [XmpPacketTest]'s equivalent case is proof the round trip survives an actual
     * [ExifInterface] file write, not just an in-memory serialize/parse.
     */
    @Test
    fun `editing one field through a real file preserves an existing keyword list`() {
        val file = realJpeg()
        applyMetadataEdit(exif(file), MetadataEdit(keywordsToAdd = listOf("original-keyword")))

        applyMetadataEdit(exif(file), MetadataEdit(caption = "added later"))

        val xmp = XmpPacket.parse(exif(file).getAttribute(ExifInterface.TAG_XMP)!!)!!
        assertEquals("added later", xmp.langAlt(XmpPacket.DC_NS, "description"))
        assertEquals(listOf("original-keyword"), xmp.bag(XmpPacket.DC_NS, "subject"))
    }

    /**
     * The case [setAsciiAttributeOrLeave] exists for, beyond the copyright symbol: a name EXIF's
     * 7-bit ASCII tags cannot represent AND that has no sensible ASCII transliteration (unlike
     * "©" -> "(c)", there is no honest one-character substitute for "é"). Writing "Jos? Garc?a"
     * into a professional's own file would be a worse outcome than the field being absent from
     * EXIF while still correct in XMP.
     */
    @Test
    fun `a name EXIF genuinely cannot represent is left out of EXIF but exact in XMP`() {
        val file = realJpeg()
        applyMetadataEdit(exif(file), MetadataEdit(creator = "José García"))

        assertNull(exif(file).getAttribute(ExifInterface.TAG_ARTIST))
        val xmp = XmpPacket.parse(exif(file).getAttribute(ExifInterface.TAG_XMP)!!)!!
        assertEquals(listOf("José García"), xmp.seq(XmpPacket.DC_NS, "creator"))
    }

    @Test
    fun `an edit with nothing set touches neither EXIF nor XMP`() {
        val file = realJpeg()
        applyMetadataEdit(exif(file), MetadataEdit(caption = "keep me"))
        val before = file.readBytes()

        applyMetadataEdit(exif(file), MetadataEdit())

        assertTrue("a no-op edit rewrote the file", before.contentEquals(file.readBytes()))
    }
}
