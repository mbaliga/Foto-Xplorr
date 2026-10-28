package com.fotoxplorr.app.reel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Random

/** The look at a finished MP4 for a recorded place, on hand-built files. */
class ReelMp4CheckTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val copyright = byteArrayOf(0xA9.toByte())

    private fun box(type: String, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(8 + payload.size)
            write(type.toByteArray(Charsets.ISO_8859_1))
            write(payload)
        }
        return out.toByteArray()
    }

    private fun bigBox(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(1)
            write(type.toByteArray(Charsets.ISO_8859_1))
            writeLong(16L + payload.size)
            write(payload)
        }
        return out.toByteArray()
    }

    private fun file(vararg parts: ByteArray): File = folder.newFile().apply { writeBytes(parts.reduce { a, b -> a + b }) }

    private fun randomData(size: Int, seed: Int = 1) = ByteArray(size).also { Random(seed.toLong()).nextBytes(it) }

    private val ftyp = box("ftyp", "isom".toByteArray())
    private val cleanMoov = box("moov", box("mvhd", ByteArray(100)) + box("trak", ByteArray(300)))
    private val placeMoov = box("moov", box("mvhd", ByteArray(100)) + box("udta", copyright + "xyz".toByteArray() + "+38.7223-009.1393/".toByteArray()))

    @Test
    fun `a clean file carries no place`() {
        assertFalse(ReelMp4Check.carriesPlace(file(ftyp, box("mdat", randomData(5000)), cleanMoov)))
    }

    @Test
    fun `the quicktime place atom is found`() {
        assertTrue(ReelMp4Check.carriesPlace(file(ftyp, box("mdat", randomData(5000)), placeMoov)))
    }

    @Test
    fun `the 3GPP place atom and a place key are found`() {
        assertTrue(ReelMp4Check.carriesPlace(file(ftyp, box("moov", box("udta", box("loci", ByteArray(20)))))))
        assertTrue(ReelMp4Check.carriesPlace(file(ftyp, box("moov", box("meta", "com.android.capture.location".toByteArray())))))
    }

    @Test
    fun `the picture data is not searched, so chance bytes there are not a place`() {
        val mdat = box("mdat", randomData(3000) + copyright + "xyz".toByteArray() + "loci".toByteArray() + "location".toByteArray())
        assertFalse(ReelMp4Check.carriesPlace(file(ftyp, mdat, cleanMoov)))
        // Also when the metadata comes first (a file written for streaming).
        assertFalse(ReelMp4Check.carriesPlace(file(ftyp, cleanMoov, mdat)))
        assertTrue(ReelMp4Check.carriesPlace(file(ftyp, placeMoov, mdat)))
    }

    @Test
    fun `boxes with a 64-bit size and one that runs to the end are walked`() {
        assertTrue(ReelMp4Check.carriesPlace(file(ftyp, bigBox("mdat", randomData(2000)), placeMoov)))
        val toEnd = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply { writeInt(0); write("mdat".toByteArray()); write(randomData(100)) }
        }.toByteArray()
        assertFalse("a last box to the end, no metadata after it", ReelMp4Check.carriesPlace(file(ftyp, toEnd)))
    }

    @Test
    fun `a file with no metadata box says nothing was found`() {
        assertFalse(ReelMp4Check.carriesPlace(file(ftyp, box("mdat", randomData(100)))))
    }

    @Test
    fun `damaged files never throw`() {
        val rnd = Random(8)
        repeat(300) {
            val data = randomData(rnd.nextInt(400), it)
            ReelMp4Check.carriesPlace(folder.newFile().apply { writeBytes(data) })
        }
        // A box that claims to be bigger than the file, and one smaller than its own header.
        assertFalse(ReelMp4Check.carriesPlace(file(ftyp, byteArrayOf(0x7F, 0, 0, 0) + "moov".toByteArray() + ByteArray(10))))
        assertFalse(ReelMp4Check.carriesPlace(file(ftyp, byteArrayOf(0, 0, 0, 4) + "moov".toByteArray() + ByteArray(10))))
        assertFalse(ReelMp4Check.carriesPlace(folder.newFile()))
        assertFalse(ReelMp4Check.carriesPlace(File(folder.root, "missing.mp4")))
    }

    @Test
    fun `the search finds a pattern anywhere, including at the very end`() {
        val p = "loci".toByteArray()
        assertEquals(0, ReelMp4Check.indexOf(p, p))
        assertEquals(6, ReelMp4Check.indexOf(ByteArray(6) + p, p))
        assertEquals(-1, ReelMp4Check.indexOf("loc".toByteArray(), p))
        assertEquals(-1, ReelMp4Check.indexOf(ByteArray(0), p))
    }
}
