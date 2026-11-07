package com.fotoxplorr.core.formats.simple

import com.fotoxplorr.core.formats.MediaFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Decodes every simple-format file of the synthetic corpus (testdata/formats) and checks the quad. */
class SimpleCorpusTest {
    private val dir = File("../../testdata/formats").takeIf { it.isDirectory } ?: File("testdata/formats")

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val yellow = 0xFFFFFF00.toInt()

    private fun decode(name: String, format: MediaFormat): DecodedImage {
        val f = File(dir, name)
        assertTrue(f.isFile, "corpus file missing: $f")
        return when (val r = SimpleFormatDecoders.decode(format, f.readBytes())) {
            is SimpleResult.Success -> r.image
            is SimpleResult.Failure -> fail("$name: ${r.reason}")
        }
    }

    private fun assertQuad(name: String, format: MediaFormat) {
        val img = decode(name, format)
        assertEquals(16, img.width, name)
        assertEquals(16, img.height, name)
        fun px(x: Int, y: Int) = img.argb[y * 16 + x]
        assertEquals(red, px(0, 0), "$name top-left")
        assertEquals(green, px(15, 0), "$name top-right")
        assertEquals(blue, px(0, 15), "$name bottom-left")
        assertEquals(yellow, px(15, 15), "$name bottom-right")
    }

    @Test fun tga() { assertQuad("tga-24.tga", MediaFormat.Tga); assertQuad("tga-rle-32.tga", MediaFormat.Tga) }
    @Test fun qoi() = assertQuad("qoi-rgb.qoi", MediaFormat.Qoi)
    @Test fun pnmColour() { assertQuad("pnm-p3.ppm", MediaFormat.Pnm); assertQuad("pnm-p6.ppm", MediaFormat.Pnm) }
    @Test fun psd() { assertQuad("psd-raw.psd", MediaFormat.Psd); assertQuad("psd-rle.psd", MediaFormat.Psd) }
    @Test fun hdr() = assertQuad("hdr-rgbe.hdr", MediaFormat.Hdr)
    @Test fun icns() = assertQuad("icns-png.icns", MediaFormat.Icns)
    @Test fun ora() = assertQuad("ora-valid.ora", MediaFormat.Ora)
    @Test fun kra() = assertQuad("kra-valid.kra", MediaFormat.Kra)

    @Test fun tiffAllCompressionsAndByteOrders() {
        for (n in listOf("tiff-none-le.tif", "tiff-none-be.tif", "tiff-packbits.tif", "tiff-lzw.tif", "tiff-deflate.tif")) {
            assertQuad(n, MediaFormat.Tiff)
        }
    }

    @Test fun pnmGreyAndBitmapsHaveTheRightShape() {
        for (n in listOf("pnm-p1.pbm", "pnm-p2.pgm", "pnm-p4.pbm", "pnm-p5.pgm")) {
            val img = decode(n, MediaFormat.Pnm)
            assertEquals(16 to 16, img.width to img.height, n)
        }
        // P1 and P4 hold the same 4x4-block checkerboard; bit 1 is black.
        assertEquals(decode("pnm-p1.pbm", MediaFormat.Pnm).argb.toList(), decode("pnm-p4.pbm", MediaFormat.Pnm).argb.toList())
        assertEquals(0xFF000000.toInt(), decode("pnm-p1.pbm", MediaFormat.Pnm).argb[0])
        // P2 and P5 hold the same ramp (x * 16).
        assertEquals(decode("pnm-p2.pgm", MediaFormat.Pnm).argb.toList(), decode("pnm-p5.pgm", MediaFormat.Pnm).argb.toList())
        assertEquals(0xFF101010.toInt(), decode("pnm-p5.pgm", MediaFormat.Pnm).argb[1])
    }

    @Test fun stubsAndOtherFilesAreRefusedNotCrashed() {
        val cases = mapOf(
            "stub-dds.dds" to MediaFormat.Dds, "stub-pcx.pcx" to MediaFormat.Pcx,
            "zip-plain.zip" to MediaFormat.Ora, "stub-random-text.txt" to MediaFormat.Tiff,
            "png-rgb8.png" to MediaFormat.Qoi, "stub-nef.nef" to MediaFormat.Tiff,
        )
        for ((n, f) in cases) {
            assertTrue(SimpleFormatDecoders.decode(f, File(dir, n).readBytes()) is SimpleResult.Failure, n)
        }
    }
}
