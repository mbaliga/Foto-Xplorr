package com.fotoxplorr.core.formats.simple

import com.fotoxplorr.core.formats.MediaFormat
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hostile-input sweep over the corpus: every truncation and 400 seeded random corruptions of every
 * simple-format file must end as a result (Success or Failure), never an exception, and quickly.
 */
class SimpleFuzzTest {
    private val dir = File("../../testdata/formats").takeIf { it.isDirectory } ?: File("testdata/formats")
    private val files = mapOf(
        "tga-24.tga" to MediaFormat.Tga, "tga-rle-32.tga" to MediaFormat.Tga, "qoi-rgb.qoi" to MediaFormat.Qoi,
        "pnm-p1.pbm" to MediaFormat.Pnm, "pnm-p3.ppm" to MediaFormat.Pnm, "pnm-p4.pbm" to MediaFormat.Pnm,
        "pnm-p6.ppm" to MediaFormat.Pnm, "psd-raw.psd" to MediaFormat.Psd, "psd-rle.psd" to MediaFormat.Psd,
        "hdr-rgbe.hdr" to MediaFormat.Hdr, "icns-png.icns" to MediaFormat.Icns, "ora-valid.ora" to MediaFormat.Ora,
        "kra-valid.kra" to MediaFormat.Kra, "tiff-none-le.tif" to MediaFormat.Tiff, "tiff-none-be.tif" to MediaFormat.Tiff,
        "tiff-packbits.tif" to MediaFormat.Tiff, "tiff-lzw.tif" to MediaFormat.Tiff, "tiff-deflate.tif" to MediaFormat.Tiff,
    )
    private val tight = SimpleLimits(maxPixels = 1_000_000, maxSide = 4096, maxInflated = 4L shl 20)

    @Test fun truncationsAndCorruptionsNeverThrow() {
        val started = System.nanoTime()
        for ((name, format) in files) {
            val original = File(dir, name).readBytes()
            for (n in 0 until original.size) SimpleFormatDecoders.decode(format, original.copyOf(n), tight)
            val rnd = Random(name.hashCode())
            repeat(400) {
                val copy = original.copyOf()
                repeat(1 + rnd.nextInt(4)) { copy[rnd.nextInt(copy.size)] = rnd.nextInt(256).toByte() }
                SimpleFormatDecoders.decode(format, copy, tight)
            }
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 60_000, "fuzz sweep took too long")
    }
}
