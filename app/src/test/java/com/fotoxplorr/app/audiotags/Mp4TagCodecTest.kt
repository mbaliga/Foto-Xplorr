package com.fotoxplorr.app.audiotags

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4TagCodecTest {

    @Test
    fun `reads title, artist, album, year, track, genre and cover from an existing ilst`() {
        val cover = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        val ilst = box(
            "ilst",
            item("©nam", dataAtom(FLAG_UTF8, "Old Title".toByteArray(Charsets.UTF_8))),
            item("©ART", dataAtom(FLAG_UTF8, "Old Artist".toByteArray(Charsets.UTF_8))),
            item("©alb", dataAtom(FLAG_UTF8, "Old Album".toByteArray(Charsets.UTF_8))),
            item("©day", dataAtom(FLAG_UTF8, "2021".toByteArray(Charsets.UTF_8))),
            item("trkn", dataAtom(0, trknPayload(5))),
            item("©gen", dataAtom(FLAG_UTF8, "Ambient".toByteArray(Charsets.UTF_8))),
            item("covr", dataAtom(FLAG_PNG, cover)),
        )
        val moov = box("moov", box("udta", box("meta", fullBoxHeader() + ilst)))
        val file = box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII)) + moov + box("mdat", "AUDIO".toByteArray(Charsets.US_ASCII))

        val tags = Mp4TagCodec.read(file)
        assertEquals("Old Title", tags?.title)
        assertEquals("Old Artist", tags?.artist)
        assertEquals("Old Album", tags?.album)
        assertEquals(2021, tags?.year)
        assertEquals(5, tags?.trackNumber)
        assertEquals("Ambient", tags?.genre)
        assertArrayEquals(cover, tags?.coverArt)
    }

    @Test
    fun `reads the legacy numeric gnre atom through the ID3v1 genre table`() {
        val gnrePayload = byteArrayOf(0, 18) // 1-based index 18 -> ID3V1_GENRES[17] = "Rock"
        val ilst = box("ilst", item("gnre", dataAtom(0, gnrePayload)))
        val moov = box("moov", box("udta", box("meta", fullBoxHeader() + ilst)))
        val file = box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII)) + moov + box("mdat", byteArrayOf(0))

        assertEquals("Rock", Mp4TagCodec.read(file)?.genre)
    }

    @Test
    fun `writing creates udta_meta_ilst from scratch when the file has none`() {
        val moov = box("moov", box("mvhd", ByteArray(4)))
        val file = box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII)) + moov + box("mdat", "AUDIO".toByteArray(Charsets.US_ASCII))

        val output = ByteArrayOutputStream()
        Mp4TagCodec.write(file, output, AudioTags(title = "Fresh Title"))
        val written = output.toByteArray()

        assertEquals("Fresh Title", Mp4TagCodec.read(written)?.title)
        // The audio payload must still be present and untouched.
        assertTrue(indexOfSubsequence(written, "AUDIO".toByteArray(Charsets.US_ASCII)) >= 0)
    }

    @Test
    fun `writing preserves an ilst item this codec has no field for`() {
        val ilst = box(
            "ilst",
            item("©nam", dataAtom(FLAG_UTF8, "Title".toByteArray(Charsets.UTF_8))),
            item("aART", dataAtom(FLAG_UTF8, "Album Artist".toByteArray(Charsets.UTF_8))),
        )
        val moov = box("moov", box("udta", box("meta", fullBoxHeader() + ilst)))
        val file = box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII)) + moov + box("mdat", byteArrayOf(1))

        val output = ByteArrayOutputStream()
        Mp4TagCodec.write(file, output, AudioTags(title = "New Title"))
        val written = output.toByteArray()

        assertEquals("New Title", Mp4TagCodec.read(written)?.title)
        assertTrue(indexOfSubsequence(written, "Album Artist".toByteArray(Charsets.UTF_8)) >= 0)
    }

    @Test
    fun `stco offsets shift by the moov size delta when moov precedes mdat`() {
        val marker = "AUDIO-MARKER-DATA".toByteArray(Charsets.US_ASCII)
        val ftyp = box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII))
        val originalMdatContentOffset = ftyp.size + smallMoov(stco = true, offsets = listOf(0)).size + 8
        val moov = smallMoov(stco = true, offsets = listOf(originalMdatContentOffset))
        val file = ftyp + moov + box("mdat", marker)

        val output = ByteArrayOutputStream()
        Mp4TagCodec.write(file, output, AudioTags(title = "A Considerably Longer Replacement Title"))
        val written = output.toByteArray()

        val newMarkerOffset = indexOfSubsequence(written, marker)
        assertTrue("marker must still be present", newMarkerOffset >= 0)
        assertEquals(newMarkerOffset, readStcoFirstOffset(written))
    }

    @Test
    fun `co64 offsets shift by the moov size delta when moov precedes mdat`() {
        val marker = "AUDIO-MARKER-DATA-64".toByteArray(Charsets.US_ASCII)
        val ftyp = box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII))
        val originalMdatContentOffset = (ftyp.size + smallMoov(stco = false, offsets = listOf(0L)).size + 8).toLong()
        val moov = smallMoov(stco = false, offsets = listOf(originalMdatContentOffset))
        val file = ftyp + moov + box("mdat", marker)

        val output = ByteArrayOutputStream()
        Mp4TagCodec.write(file, output, AudioTags(title = "Another Considerably Longer Title"))
        val written = output.toByteArray()

        val newMarkerOffset = indexOfSubsequence(written, marker).toLong()
        assertTrue(newMarkerOffset >= 0)
        assertEquals(newMarkerOffset, readCo64FirstOffset(written))
    }

    @Test
    fun `a co64 offset far beyond 32 bits shifts without truncation`() {
        val hugeOffset = 5_000_000_000L // > 2^32, would silently wrap if handled as a 32-bit value
        val moov = smallMoov(stco = false, offsets = listOf(hugeOffset))
        val file = box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII)) + moov + box("mdat", byteArrayOf(1))

        val output = ByteArrayOutputStream()
        Mp4TagCodec.write(file, output, AudioTags(title = "Grows moov"))
        val written = output.toByteArray()

        val delta = (moovSizeOf(written) - moov.size).toLong()
        assertEquals(hugeOffset + delta, readCo64FirstOffset(written))
    }

    @Test
    fun `stco offsets are left untouched when moov follows mdat`() {
        val mdat = box("mdat", "AUDIO".toByteArray(Charsets.US_ASCII))
        val originalOffset = 1234 // deliberately not pointing anywhere real -- moov is after mdat,
        // so mdat's own position never moves and this must survive completely unchanged.
        val moov = smallMoov(stco = true, offsets = listOf(originalOffset))
        val file = box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII)) + mdat + moov

        val output = ByteArrayOutputStream()
        Mp4TagCodec.write(file, output, AudioTags(title = "A Much Longer Title That Grows moov"))
        val written = output.toByteArray()

        assertEquals(originalOffset, readStcoFirstOffset(written))
    }

    // ── Fixture builders ────────────────────────────────────────────────────────────────

    private fun smallMoov(stco: Boolean, offsets: List<Number>): ByteArray {
        val table = if (stco) {
            stcoBox(offsets.map { it.toInt() })
        } else {
            co64Box(offsets.map { it.toLong() })
        }
        val stbl = box("stbl", table)
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        val trak = box("trak", mdia)
        return box("moov", trak)
    }

    private fun moovSizeOf(bytes: ByteArray): Int {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val size = bytes.beInt32(pos)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            if (type == "moov") return size
            pos += size
        }
        error("no moov found")
    }

    private fun readStcoFirstOffset(bytes: ByteArray): Int {
        val at = indexOfSubsequence(bytes, "stco".toByteArray(Charsets.US_ASCII))
        assertTrue(at >= 0)
        // `at` points at the 4-char type; payload is [version/flags(4)][count(4)][entries...].
        return bytes.beInt32(at + 4 + 8)
    }

    private fun readCo64FirstOffset(bytes: ByteArray): Long {
        val at = indexOfSubsequence(bytes, "co64".toByteArray(Charsets.US_ASCII))
        assertTrue(at >= 0)
        return bytes.beLong64(at + 4 + 8)
    }

    private fun stcoBox(offsets: List<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeBeInt32(0)
        out.writeBeInt32(offsets.size)
        offsets.forEach { out.writeBeInt32(it) }
        return box("stco", out.toByteArray())
    }

    private fun co64Box(offsets: List<Long>): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeBeInt32(0)
        out.writeBeInt32(offsets.size)
        offsets.forEach { out.writeBeLong64(it) }
        return box("co64", out.toByteArray())
    }

    private fun fullBoxHeader(): ByteArray = byteArrayOf(0, 0, 0, 0)

    private fun dataAtom(flags: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeBeInt32(flags)
        out.writeBeInt32(0)
        out.write(payload)
        return box("data", out.toByteArray())
    }

    private fun trknPayload(track: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeBeInt16(0); out.writeBeInt16(track); out.writeBeInt16(0); out.writeBeInt16(0)
        return out.toByteArray()
    }

    private fun item(type: String, data: ByteArray): ByteArray = boxOf(type, data)

    private fun box(type: String, vararg children: ByteArray): ByteArray =
        boxOf(type, children.fold(ByteArray(0)) { acc, c -> acc + c })

    private fun boxOf(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeBeInt32(8 + payload.size)
        // ISO-8859-1, not US-ASCII: the `©`-prefixed atom types (`©nam`, `©day`, …) use byte
        // 0xA9, which US-ASCII cannot represent at all -- see Mp4TagCodec.ATOM_TYPE_CHARSET.
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun indexOfSubsequence(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        const val FLAG_UTF8 = 1
        const val FLAG_PNG = 14
    }
}
