package com.fotoxplorr.app.wall

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * [JfifDensity] on files too big to read into memory. The "pictures" are synthetic: a JFIF header
 * and a start-of-scan marker, then filler. That is all the walk looks at, and it is the point: the
 * stamp must read only the header, and patch it in place, whatever follows. A file over 2 GiB is
 * beyond what one array can hold, so any read of the whole file fails here.
 */
class JfifDensityFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    private val jfif = byteArrayOf(
        0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, // APP0, length 16, "JFIF"
        0x01, 0x01, // version
        0x00, 0x00, 0x01, 0x00, 0x01, // units 0 (aspect only), 1:1
        0x00, 0x00, // no thumbnail
    )

    private val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00)

    private fun u8(b: Byte) = b.toInt() and 0xFF

    @Test
    fun `a file over 2 GiB is stamped in place, and its length and its far end are not touched`() {
        val head = soi + jfif + sos
        val size = 2L * 1024 * 1024 * 1024 + 123_456
        val file = folder.newFile("huge.jpg")
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(size) // a hole: nothing is written for it
            raf.seek(0)
            raf.write(head)
            raf.seek(size - 3)
            raf.write(byteArrayOf(0x11, 0x22, 0x33))
        }

        assertEquals(true, JfifDensity.stampFile(file, 300))

        assertEquals(size, file.length())
        assertEquals(300 to 300, JfifDensity.readDpi(file))
        RandomAccessFile(file, "r").use { raf ->
            val after = ByteArray(head.size).also { raf.readFully(it) }
            val changed = head.indices.filter { head[it] != after[it] }
            assertEquals(true, changed.isNotEmpty() && changed.all { it in 13..17 })
            raf.seek(size - 3)
            assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), ByteArray(3).also { raf.readFully(it) })
            raf.seek(size / 2)
            assertEquals(0, raf.read())
        }
    }

    @Test
    fun `a big file with no JFIF header gains one by streaming, and every other byte follows unchanged`() {
        val filler = ByteArray(40 * 1024 * 1024) { (it % 251).toByte() }
        val original = soi + sos + filler
        val file = folder.newFile("bare.jpg").also { it.writeBytes(original) }

        assertEquals(true, JfifDensity.stampFile(file, 254))

        assertEquals(original.size + 18L, file.length())
        assertEquals(254 to 254, JfifDensity.readDpi(file))
        val patched = file.readBytes()
        assertArrayEquals(soi, patched.copyOfRange(0, 2))
        assertArrayEquals(original.copyOfRange(2, original.size), patched.copyOfRange(2 + 18, patched.size))
        assertEquals(listOf("bare.jpg"), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun `reading the density of a file reads only the header`() {
        val file: File = folder.newFile("one.jpg")
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(3L * 1024 * 1024 * 1024)
            raf.write(soi + jfif + sos)
        }
        assertEquals(null, JfifDensity.readDpi(file)) // units 0: a shape, not a density
        assertEquals(true, JfifDensity.stampFile(file, 72))
        assertEquals(72 to 72, JfifDensity.readDpi(file))
    }
}
