package com.fotoxplorr.app.audiotags

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Id3v2CodecTest {

    @Test
    fun `write then read round-trips every field, ASCII text and a JPEG cover`() {
        val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        val tags = AudioTags(
            title = "Song Title",
            artist = "The Artist",
            album = "An Album",
            year = 2024,
            trackNumber = 7,
            genre = "Rock",
            coverArt = cover,
        )
        val input = byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + ByteArray(32) { it.toByte() } // fake audio frames
        val output = ByteArrayOutputStream()

        Id3v2Codec.write(input, output, tags)
        val written = output.toByteArray()

        assertEquals("ID3", String(written, 0, 3, Charsets.US_ASCII))
        val readBack = Id3v2Codec.read(written)
        assertEquals(tags, readBack)

        // The original audio bytes must survive completely untouched after the new tag.
        val audioStart = written.size - input.size
        assertArrayEquals(input, written.copyOfRange(audioStart, written.size))
    }

    @Test
    fun `write then read round-trips non-Latin1 text through the UTF-16 fallback`() {
        val tags = AudioTags(title = "Café Müller", artist = "日本語")
        val output = ByteArrayOutputStream()
        Id3v2Codec.write(ByteArray(0), output, tags)

        val readBack = Id3v2Codec.read(output.toByteArray())
        assertEquals(tags.title, readBack?.title)
        assertEquals(tags.artist, readBack?.artist)
    }

    @Test
    fun `writing replaces an existing tag rather than appending a second one`() {
        val first = ByteArrayOutputStream().also { Id3v2Codec.write(ByteArray(0), it, AudioTags(title = "First")) }.toByteArray()
        val second = ByteArrayOutputStream().also { Id3v2Codec.write(first, it, AudioTags(title = "Second")) }.toByteArray()

        assertEquals(1, countOccurrences(second, "ID3"))
        assertEquals("Second", Id3v2Codec.read(second)?.title)
    }

    @Test
    fun `reads a hand-built ID3v2_2 tag using 3-character frame ids and 3-byte sizes`() {
        val cover = byteArrayOf(9, 9, 9, 9)
        val tag = buildV22Tag(
            listOf(
                "TT2" to latin1TextFrame("Old Title"),
                "TP1" to latin1TextFrame("Old Artist"),
                "TAL" to latin1TextFrame("Old Album"),
                "TYE" to latin1TextFrame("1999"),
                "TRK" to latin1TextFrame("3"),
                "TCO" to latin1TextFrame("(17)"),
                "PIC" to picFrame(cover),
            ),
        )
        val tags = Id3v2Codec.read(tag)
        assertEquals("Old Title", tags?.title)
        assertEquals("Old Artist", tags?.artist)
        assertEquals("Old Album", tags?.album)
        assertEquals(1999, tags?.year)
        assertEquals(3, tags?.trackNumber)
        assertEquals("Rock", tags?.genre) // (17) resolves through the ID3v1 genre table
        assertArrayEquals(cover, tags?.coverArt)
    }

    @Test
    fun `reads a hand-built ID3v2_4 tag with synchsafe frame sizes and a TDRC date`() {
        val tag = buildV24Tag(
            listOf(
                "TIT2" to latin1TextFrame("Modern Title"),
                "TDRC" to latin1TextFrame("2023-05-01"),
                "TRCK" to latin1TextFrame("4/12"),
            ),
        )
        val tags = Id3v2Codec.read(tag)
        assertEquals("Modern Title", tags?.title)
        assertEquals(2023, tags?.year)
        assertEquals(4, tags?.trackNumber)
    }

    @Test
    fun `a header-level unsynchronised ID3v2_4 tag decodes back to the original bytes`() {
        val originalCover = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 1, 2, 0xFF.toByte(), 0)
        val tag = buildV24Tag(
            listOf("APIC" to apicFrame(originalCover)),
            unsynchronised = true,
        )
        val tags = Id3v2Codec.read(tag)
        assertArrayEquals(originalCover, tags?.coverArt)
    }

    @Test
    fun `an untagged file (bare MPEG frame sync) has no ID3v2 tag to read`() {
        val bareMp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 1, 2, 3)
        assertNull(Id3v2Codec.read(bareMp3))
    }

    @Test
    fun `genre round-trips a plain freetext value unchanged`() {
        val output = ByteArrayOutputStream()
        Id3v2Codec.write(ByteArray(0), output, AudioTags(genre = "Chiptune"))
        assertEquals("Chiptune", Id3v2Codec.read(output.toByteArray())?.genre)
    }

    @Test
    fun `removeUnsynchronization reverses every inserted zero exactly once`() {
        val original = byteArrayOf(1, 0xFF.toByte(), 0xE0.toByte(), 2, 0xFF.toByte(), 0)
        val encoded = byteArrayOf(1, 0xFF.toByte(), 0, 0xE0.toByte(), 2, 0xFF.toByte(), 0, 0)
        assertArrayEquals(original, Id3v2Codec.removeUnsynchronization(encoded))
    }

    // ── Fixture builders ────────────────────────────────────────────────────────────────

    private fun latin1TextFrame(text: String): ByteArray = byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)

    private fun picFrame(imageBytes: ByteArray): ByteArray =
        byteArrayOf(0) + "JPG".toByteArray(Charsets.US_ASCII) + byteArrayOf(3, 0) + imageBytes

    private fun apicFrame(imageBytes: ByteArray): ByteArray =
        byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + imageBytes

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(),
        ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun buildV22Tag(frames: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { (id, data) ->
            body.write(id.toByteArray(Charsets.US_ASCII))
            body.write((data.size ushr 16) and 0xFF)
            body.write((data.size ushr 8) and 0xFF)
            body.write(data.size and 0xFF)
            body.write(data)
        }
        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.US_ASCII))
        out.write(2); out.write(0); out.write(0)
        out.write(synchsafe(bodyBytes.size))
        out.write(bodyBytes)
        out.write("AUDIODATA".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    private fun buildV24Tag(frames: List<Pair<String, ByteArray>>, unsynchronised: Boolean = false): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { (id, data) ->
            body.write(id.toByteArray(Charsets.US_ASCII))
            body.write(synchsafe(data.size))
            body.write(0); body.write(0)
            body.write(data)
        }
        var bodyBytes = body.toByteArray()
        if (unsynchronised) bodyBytes = applyUnsyncForTest(bodyBytes)

        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.US_ASCII))
        out.write(4); out.write(0)
        out.write(if (unsynchronised) 0x80 else 0)
        out.write(synchsafe(bodyBytes.size))
        out.write(bodyBytes)
        out.write("AUDIODATA".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    /** Conservative but valid: inserting a zero after EVERY 0xFF (not only before a false-sync
     *  byte) is still correctly reversed by [Id3v2Codec.removeUnsynchronization], which undoes
     *  exactly that pattern regardless of what follows. */
    private fun applyUnsyncForTest(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        bytes.forEach { b ->
            out.write(b.toInt())
            if (b == 0xFF.toByte()) out.write(0)
        }
        return out.toByteArray()
    }

    private fun countOccurrences(bytes: ByteArray, needle: String): Int {
        val pattern = needle.toByteArray(Charsets.US_ASCII)
        var count = 0
        var i = 0
        outer@ while (i + pattern.size <= bytes.size) {
            for (j in pattern.indices) {
                if (bytes[i + j] != pattern[j]) {
                    i += 1
                    continue@outer
                }
            }
            count += 1
            i += pattern.size
        }
        return count
    }
}
