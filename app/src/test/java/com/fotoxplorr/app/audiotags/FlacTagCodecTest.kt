package com.fotoxplorr.app.audiotags

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlacTagCodecTest {

    @Test
    fun `reads TITLE, ARTIST, ALBUM, DATE, TRACKNUMBER, GENRE and an embedded PICTURE`() {
        val cover = byteArrayOf(1, 2, 3, 4, 5)
        val file = buildFlac(
            listOf(
                BLOCK_STREAMINFO to ByteArray(34),
                BLOCK_VORBIS_COMMENT to vorbisComment(
                    "TITLE" to "A Track",
                    "ARTIST" to "A Band",
                    "ALBUM" to "An Album",
                    "DATE" to "2022",
                    "TRACKNUMBER" to "9",
                    "GENRE" to "Folk",
                ),
                BLOCK_PICTURE to picture(cover),
            ),
            audio = "FLACFRAMES".toByteArray(Charsets.US_ASCII),
        )

        val tags = FlacTagCodec.read(file)
        assertEquals("A Track", tags?.title)
        assertEquals("A Band", tags?.artist)
        assertEquals("An Album", tags?.album)
        assertEquals(2022, tags?.year)
        assertEquals(9, tags?.trackNumber)
        assertEquals("Folk", tags?.genre)
        assertArrayEquals(cover, tags?.coverArt)
    }

    @Test
    fun `a file with no VORBIS_COMMENT block reads as an all-null AudioTags, not null itself`() {
        val file = buildFlac(listOf(BLOCK_STREAMINFO to ByteArray(34)), audio = ByteArray(0))
        assertEquals(AudioTags(), FlacTagCodec.read(file))
    }

    @Test
    fun `bytes with no fLaC magic are not a FLAC file at all`() {
        assertNull(FlacTagCodec.read(ByteArray(40)))
    }

    @Test
    fun `write then read round-trips every field and a cover through a fresh VORBIS_COMMENT`() {
        val file = buildFlac(listOf(BLOCK_STREAMINFO to ByteArray(34)), audio = "AUDIO".toByteArray(Charsets.US_ASCII))
        val tags = AudioTags(
            title = "New Title", artist = "New Artist", album = "New Album",
            year = 2020, trackNumber = 2, genre = "Jazz", coverArt = byteArrayOf(9, 8, 7),
        )

        val output = ByteArrayOutputStream()
        FlacTagCodec.write(file, output, tags)
        val written = output.toByteArray()

        assertEquals(tags, FlacTagCodec.read(written))
        assertTrue(indexOfSubsequence(written, "AUDIO".toByteArray(Charsets.US_ASCII)) >= 0)
    }

    @Test
    fun `writing preserves an unrelated APPLICATION block untouched`() {
        val appData = "app-specific-blob".toByteArray(Charsets.US_ASCII)
        val file = buildFlac(
            listOf(BLOCK_STREAMINFO to ByteArray(34), BLOCK_APPLICATION to appData),
            audio = ByteArray(0),
        )

        val output = ByteArrayOutputStream()
        FlacTagCodec.write(file, output, AudioTags(title = "T"))
        assertTrue(indexOfSubsequence(output.toByteArray(), appData) >= 0)
    }

    @Test
    fun `writing reuses the existing PADDING block's size rather than a fixed guess`() {
        val file = buildFlac(
            listOf(BLOCK_STREAMINFO to ByteArray(34), BLOCK_PADDING to ByteArray(4096)),
            audio = ByteArray(0),
        )

        val output = ByteArrayOutputStream()
        FlacTagCodec.write(file, output, AudioTags(title = "T"))
        val written = output.toByteArray()

        val paddingSize = findBlockSize(written, BLOCK_PADDING)
        assertEquals(4096, paddingSize)
    }

    @Test
    fun `DATE and YEAR keys are both understood, DATE preferred when both are present`() {
        val file = buildFlac(
            listOf(
                BLOCK_STREAMINFO to ByteArray(34),
                BLOCK_VORBIS_COMMENT to vorbisComment("DATE" to "2018", "YEAR" to "1999"),
            ),
            audio = ByteArray(0),
        )
        assertEquals(2018, FlacTagCodec.read(file)?.year)
    }

    // ── Fixture builders ────────────────────────────────────────────────────────────────

    private fun buildFlac(blocks: List<Pair<Int, ByteArray>>, audio: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        blocks.forEachIndexed { index, (type, data) ->
            val isLast = index == blocks.lastIndex
            out.write((if (isLast) 0x80 else 0) or type)
            out.writeBeInt24(data.size)
            out.write(data)
        }
        out.write(audio)
        return out.toByteArray()
    }

    private fun vorbisComment(vararg fields: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vendor = "test-vendor".toByteArray(Charsets.UTF_8)
        out.writeLeInt32(vendor.size)
        out.write(vendor)
        out.writeLeInt32(fields.size)
        fields.forEach { (key, value) ->
            val entry = "$key=$value".toByteArray(Charsets.UTF_8)
            out.writeLeInt32(entry.size)
            out.write(entry)
        }
        return out.toByteArray()
    }

    private fun picture(imageBytes: ByteArray): ByteArray {
        val mime = "image/jpeg".toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        out.writeBeInt32(3) // picture type
        out.writeBeInt32(mime.size)
        out.write(mime)
        out.writeBeInt32(0) // description
        out.writeBeInt32(0) // width
        out.writeBeInt32(0) // height
        out.writeBeInt32(0) // depth
        out.writeBeInt32(0) // colors used
        out.writeBeInt32(imageBytes.size)
        out.write(imageBytes)
        return out.toByteArray()
    }

    private fun findBlockSize(bytes: ByteArray, type: Int): Int {
        var pos = 4
        while (pos + 4 <= bytes.size) {
            val header = bytes[pos].toInt() and 0xFF
            val blockType = header and 0x7F
            val length = bytes.beUInt24(pos + 1)
            if (blockType == type) return length
            pos += 4 + length
            if (header and 0x80 != 0) break
        }
        error("block type $type not found")
    }

    private fun indexOfSubsequence(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        const val BLOCK_STREAMINFO = 0
        const val BLOCK_PADDING = 1
        const val BLOCK_APPLICATION = 2
        const val BLOCK_VORBIS_COMMENT = 4
        const val BLOCK_PICTURE = 6
    }
}
