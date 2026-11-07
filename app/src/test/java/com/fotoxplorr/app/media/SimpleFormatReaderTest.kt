package com.fotoxplorr.app.media

import com.fotoxplorr.core.formats.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimpleFormatReaderTest {

    private fun header(vararg b: Int) = ByteArray(64).also { b.forEachIndexed { i, v -> it[i] = v.toByte() } }

    @Test
    fun `a magic-byte format of ours wins over the name`() {
        val qoi = header('q'.code, 'o'.code, 'i'.code, 'f'.code)
        assertEquals(MediaFormat.Qoi, SimpleFormatReader.resolveFormat(qoi, "", "whatever.bin"))
    }

    @Test
    fun `tga has no magic so the name decides`() {
        assertEquals(MediaFormat.Tga, SimpleFormatReader.resolveFormat(ByteArray(64), "", "scan.TGA"))
        assertNull(SimpleFormatReader.resolveFormat(ByteArray(64), "", "scan.bin"))
    }

    @Test
    fun `a sniffed format that is not ours is left alone even with a simple name`() {
        val png = header(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertNull(SimpleFormatReader.resolveFormat(png, "", "looks-like.tga"))
    }
}
