package com.fotoxplorr.app.wall

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * [JfifDensity] on real JPEG bytes: the Android encoder (Skia, native graphics) writes them and
 * decodes them again, so "patching never touches the picture" is checked by decoding the result and
 * by comparing bytes, on the very header the app's own files carry. [JfifDensity] itself is pure.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class JfifDensityTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun fileOf(bytes: ByteArray) = folder.newFile().also { it.writeBytes(bytes) }

    private fun jpeg(w: Int = 24, h: Int = 16): ByteArray {
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) image.setPixel(x, y, (0xFF shl 24) or ((x * 10) shl 16) or ((y * 15) shl 8) or 0x40)
        return ByteArrayOutputStream().also { check(image.compress(Bitmap.CompressFormat.JPEG, 90, it)) }.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))

    private fun u8(b: Byte) = b.toInt() and 0xFF

    /** Where the entropy-coded data starts: the byte after the SOS header. */
    private fun scanStart(b: ByteArray): Int {
        var pos = 2
        while (u8(b[pos + 1]) != 0xDA) pos += 2 + ((u8(b[pos + 2]) shl 8) or u8(b[pos + 3]))
        return pos + 2 + ((u8(b[pos + 2]) shl 8) or u8(b[pos + 3]))
    }

    /** [original] without its JFIF segment, so a file with no density header at all. */
    private fun withoutJfif(original: ByteArray): ByteArray {
        assertEquals(0xE0, u8(original[3]))
        val length = (u8(original[4]) shl 8) or u8(original[5])
        return original.copyOfRange(0, 2) + original.copyOfRange(4 + length, original.size)
    }

    /** SOI, then an Exif-like APP1 of [fill] bytes, then everything after the original JFIF segment. */
    private fun exifFirst(original: ByteArray, fill: Int = 40): ByteArray {
        val length = (u8(original[4]) shl 8) or u8(original[5])
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((fill + 2) shr 8).toByte(), (fill + 2).toByte()) + ByteArray(fill) { 7 }
        return original.copyOfRange(0, 2) + app1 + original.copyOfRange(4 + length, original.size)
    }

    @Test
    fun `a JFIF header gets the density in dots per inch on both axes`() {
        val original = jpeg()
        assertEquals(0xE0, u8(original[3])) // the encoder writes a JFIF header: the case under test
        val patched = JfifDensity.withDpi(original, 300)

        assertEquals(original.size, patched.size)
        assertEquals(300 to 300, JfifDensity.readDpi(patched))
        assertEquals(1, u8(patched[13])) // units: dots per inch
    }

    @Test
    fun `only the five density bytes of the header change, the picture bytes are identical`() {
        val original = jpeg()
        val patched = JfifDensity.withDpi(original, 254)
        val changed = original.indices.filter { original[it] != patched[it] }

        assertEquals(true, changed.all { it in 13..17 })
        // Everything from the start of the scan on is the original, byte for byte.
        val scan = scanStart(original)
        assertArrayEquals(original.copyOfRange(scan, original.size), patched.copyOfRange(scan, patched.size))
    }

    @Test
    fun `a patched file still decodes to the same picture`() {
        val original = jpeg(40, 30)
        val before = decode(original)
        val after = decode(JfifDensity.withDpi(original, 300))

        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        for (y in 0 until before.height) for (x in 0 until before.width) assertEquals(before.getPixel(x, y), after.getPixel(x, y))
    }

    @Test
    fun `a file with no JFIF header gets one right after the start marker`() {
        val bare = withoutJfif(jpeg())
        assertNull(JfifDensity.readDpi(bare))
        val patched = JfifDensity.withDpi(bare, 300)

        assertEquals(bare.size + 18, patched.size)
        assertEquals(300 to 300, JfifDensity.readDpi(patched))
        assertEquals(0xFF, u8(patched[2]))
        assertEquals(0xE0, u8(patched[3]))
        // The rest of the file follows the new header unchanged.
        assertArrayEquals(bare.copyOfRange(2, bare.size), patched.copyOfRange(2 + 18, patched.size))
        val picture = decode(patched)
        assertEquals(24, picture.width)
        assertEquals(16, picture.height)
    }

    @Test
    fun `an Exif-first file keeps its Exif segment and gains the header before it`() {
        val exif = exifFirst(jpeg())
        val patched = JfifDensity.withDpi(exif, 150)

        assertEquals(150 to 150, JfifDensity.readDpi(patched))
        assertArrayEquals(exif.copyOfRange(2, exif.size), patched.copyOfRange(2 + 18, patched.size))
        assertNotNull(decode(patched))
    }

    @Test
    fun `a header already after another segment is patched where it is`() {
        val original = jpeg()
        val length = (u8(original[4]) shl 8) or u8(original[5])
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 12) + ByteArray(10) { 1 }
        // SOI, APP1, then the original JFIF segment and the rest.
        val reordered = original.copyOfRange(0, 2) + app1 + original.copyOfRange(2, original.size)
        assertEquals(length, (u8(reordered[2 + app1.size + 2]) shl 8) or u8(reordered[2 + app1.size + 3]))

        val patched = JfifDensity.withDpi(reordered, 200)
        assertEquals(reordered.size, patched.size)
        assertEquals(200 to 200, JfifDensity.readDpi(patched))
    }

    @Test
    fun `a header that said aspect ratio only is made a real density`() {
        val original = jpeg()
        assertNull(JfifDensity.readDpi(original.copyOf().also { it[13] = 0 }))
        assertEquals(72 to 72, JfifDensity.readDpi(JfifDensity.withDpi(original.copyOf().also { it[13] = 0 }, 72)))
    }

    @Test
    fun `the density is clamped to what two bytes hold and to at least 1`() {
        assertEquals(65535 to 65535, JfifDensity.readDpi(JfifDensity.withDpi(jpeg(), 1_000_000)))
        assertEquals(1 to 1, JfifDensity.readDpi(JfifDensity.withDpi(jpeg(), 0)))
        assertEquals(1 to 1, JfifDensity.readDpi(JfifDensity.withDpi(jpeg(), -5)))
    }

    @Test
    fun `patching twice gives the same bytes as patching once`() {
        val once = JfifDensity.withDpi(jpeg(), 300)
        assertArrayEquals(once, JfifDensity.withDpi(once, 300))
        assertEquals(240 to 240, JfifDensity.readDpi(JfifDensity.withDpi(once, 240)))
    }

    @Test
    fun `bytes that are not a JPEG come back as they are`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        assertSame(png, JfifDensity.withDpi(png, 300))
        val empty = ByteArray(0)
        assertSame(empty, JfifDensity.withDpi(empty, 300))
        val one = byteArrayOf(0xFF.toByte())
        assertSame(one, JfifDensity.withDpi(one, 300))
        assertNull(JfifDensity.readDpi(png))
        assertNull(JfifDensity.readDpi(empty))
    }

    @Test
    fun `a JPEG cut off inside its headers, or with a segment that overruns the file, is not touched`() {
        val original = jpeg()
        val cut = original.copyOf(10) // inside the JFIF segment
        assertSame(cut, JfifDensity.withDpi(cut, 300))

        val overrun = original.copyOf().also {
            it[4] = 0xFF.toByte() // the JFIF segment now claims 0xFF10 bytes
            it[5] = 0x10
        }
        assertSame(overrun, JfifDensity.withDpi(overrun, 300))
    }

    @Test
    fun `a JPEG with no scan at all is not touched`() {
        val onlyHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        assertSame(onlyHeader, JfifDensity.withDpi(onlyHeader, 300))
    }

    @Test
    fun `the input array is never changed`() {
        val original = jpeg()
        val copy = original.copyOf()
        JfifDensity.withDpi(original, 300)
        assertArrayEquals(copy, original)
    }

    @Test
    fun `the extension segment is not mistaken for the header`() {
        val original = jpeg()
        // Rename the identifier JFIF to JFXX: that is a different APP0 and carries no density.
        val ext = original.copyOf().also { it[8] = 'X'.code.toByte(); it[9] = 'X'.code.toByte() }
        assertNull(JfifDensity.readDpi(ext))
        val patched = JfifDensity.withDpi(ext, 300)
        assertEquals(300 to 300, JfifDensity.readDpi(patched))
        assertEquals(ext.size + 18, patched.size)
    }

    @Test
    fun `stamping a file gives the very bytes stamping an array gives, for a header, none, and Exif first`() {
        val original = jpeg()
        for (input in listOf(original, withoutJfif(original), exifFirst(original))) {
            val file = fileOf(input)
            assertEquals(true, JfifDensity.stampFile(file, 300))
            assertArrayEquals(JfifDensity.withDpi(input, 300), file.readBytes())
            assertEquals(300 to 300, JfifDensity.readDpi(file))
            assertNotNull(decode(file.readBytes()))
        }
    }

    @Test
    fun `stamping a file that is not a JPEG, or is cut off, leaves it as it is and says no`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        val cut = jpeg().copyOf(10)
        for (input in listOf(png, cut, ByteArray(0))) {
            val file = fileOf(input)
            assertEquals(false, JfifDensity.stampFile(file, 300))
            assertArrayEquals(input, file.readBytes())
            assertNull(JfifDensity.readDpi(file))
        }
    }

    @Test
    fun `no staging file is left beside a file that gained a header`() {
        val file = fileOf(withoutJfif(jpeg()))
        JfifDensity.stampFile(file, 300)
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
    }
}
