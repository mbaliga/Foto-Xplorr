package com.fotoxplorr.app.audiotags

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioTagCodecTest {

    @Test
    fun `detects MP3 by ID3 header, MP4 by ftyp, FLAC by magic, and bare MPEG frame sync as MP3`() {
        assertEquals(AudioFormat.MP3, detectFormat("ID3".toByteArray(Charsets.US_ASCII) + ByteArray(10)))
        assertEquals(AudioFormat.MP4, detectFormat(byteArrayOf(0, 0, 0, 0) + "ftyp".toByteArray(Charsets.US_ASCII) + ByteArray(4)))
        assertEquals(AudioFormat.FLAC, detectFormat("fLaC".toByteArray(Charsets.US_ASCII)))
        assertEquals(AudioFormat.MP3, detectFormat(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0, 0)))
        assertNull(detectFormat(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `write on unrecognised bytes throws UnsupportedAudioFormatException`() {
        assertThrows(UnsupportedAudioFormatException::class.java) {
            AudioTagCodec.write(byteArrayOf(1, 2, 3), ByteArrayOutputStream(), AudioTags(title = "x"))
        }
    }

    @Test
    fun `a null field in an edit keeps the file's existing value`() {
        val original = ByteArrayOutputStream().also {
            Id3v2Codec.write(ByteArray(0), it, AudioTags(title = "Original Title", artist = "Original Artist"))
        }.toByteArray()

        val output = ByteArrayOutputStream()
        AudioTagCodec.write(original, output, AudioTags(artist = "New Artist"))

        val result = AudioTagCodec.read(output.toByteArray())
        assertEquals("Original Title", result?.title)
        assertEquals("New Artist", result?.artist)
    }

    @Test
    fun `mergeTags prefers the edit and falls back to the existing value per field`() {
        val existing = AudioTags(title = "old title", artist = "old artist", year = 1999)
        val edits = AudioTags(title = "new title", year = null)
        val merged = mergeTags(existing, edits)
        assertEquals("new title", merged.title)
        assertEquals("old artist", merged.artist)
        assertEquals(1999, merged.year)
    }

    @Test
    fun `mergeTags against no existing tags at all just keeps the edits`() {
        val edits = AudioTags(title = "only this")
        assertEquals(edits, mergeTags(null, edits))
    }
}
