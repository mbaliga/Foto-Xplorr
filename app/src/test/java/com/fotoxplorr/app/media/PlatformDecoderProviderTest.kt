package com.fotoxplorr.app.media

import com.fotoxplorr.core.formats.DecoderRegistry
import com.fotoxplorr.core.formats.DecoderVerdict
import com.fotoxplorr.core.formats.FormatCopy
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.formats.RawVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The platform provider's table at this app's minSdk (26) and at the newest releases. */
class PlatformDecoderProviderTest {

    private fun provider(sdk: Int, supported: (String) -> Boolean? = { null }) = PlatformDecoderProvider(sdk, supported)

    private fun verdict(sdk: Int, format: MediaFormat, supported: (String) -> Boolean? = { null }) =
        provider(sdk, supported).probe(format, "", null)

    @Test
    fun `the baseline formats decode on every release`() {
        for (format in listOf(MediaFormat.Jpeg, MediaFormat.Png, MediaFormat.Gif, MediaFormat.Bmp, MediaFormat.WebP, MediaFormat.Video, MediaFormat.Svg)) {
            assertEquals(DecoderVerdict.Decodable, verdict(26, format), "$format")
        }
    }

    @Test
    fun `heic needs android 9 and avif needs android 12`() {
        assertEquals(DecoderVerdict.NotDecodable(FormatCopy.needsNewerAndroid("HEIC", "9")), verdict(27, MediaFormat.Heif))
        assertEquals(DecoderVerdict.Decodable, verdict(28, MediaFormat.Heif))
        assertEquals(DecoderVerdict.NotDecodable(FormatCopy.needsNewerAndroid("AVIF", "12")), verdict(30, MediaFormat.Avif))
        assertEquals(DecoderVerdict.Decodable, verdict(31, MediaFormat.Avif))
    }

    @Test
    fun `from api 29 a device that says no to avif wins over the table and null leaves it`() {
        val noAvif: (String) -> Boolean? = { it != "image/avif" }
        assertEquals(DecoderVerdict.NotDecodable(FormatCopy.notOnThisPhone("AVIF")), verdict(34, MediaFormat.Avif, noAvif))
        assertEquals(DecoderVerdict.Decodable, verdict(34, MediaFormat.Avif) { null })
        // HEIC is never asked: a device that "denies" it still gets the table's answer.
        assertEquals(DecoderVerdict.Decodable, verdict(34, MediaFormat.Heif) { false })
        // The baseline formats are never asked at all.
        assertEquals(DecoderVerdict.Decodable, verdict(34, MediaFormat.Jpeg) { false })
    }

    @Test
    fun `an answerer that denies jpeg is not trusted so it cannot hide photos`() {
        val broken = trustedMimeProbe { false }
        assertEquals(null, broken("image/heif"))
        val honest = trustedMimeProbe { it != "image/avif" }
        assertEquals(true, honest("image/heif"))
        assertEquals(false, honest("image/avif"))
        assertEquals(DecoderVerdict.Decodable, verdict(34, MediaFormat.Avif, broken))
    }

    @Test
    fun `raw and the pack formats are refused honestly`() {
        val cr3 = verdict(36, MediaFormat.Raw(RawVariant.CR3))
        assertEquals(DecoderVerdict.NotDecodable(FormatCopy.RAW_NO_PREVIEW), cr3)
        assertEquals(DecoderVerdict.Decodable, verdict(36, MediaFormat.Raw(RawVariant.DNG)))
        for (format in listOf(MediaFormat.Exr, MediaFormat.Psd, MediaFormat.Tiff, MediaFormat.JpegXl, MediaFormat.Qoi)) {
            val v = verdict(36, format)
            assertTrue("$format is $v", v is DecoderVerdict.NotDecodable)
        }
        for (format in listOf(MediaFormat.Psb, MediaFormat.Xcf, MediaFormat.Icns, MediaFormat.Pcx)) {
            assertEquals(DecoderVerdict.NotDecodable(FormatCopy.NO_DECODER), verdict(36, format))
        }
    }

    @Test
    fun `every format gets an answer and every refusal is one short sentence`() {
        for (sdk in listOf(26, 28, 31, 36)) {
            for (format in MediaFormat.all) {
                val v = verdict(sdk, format)
                if (v is DecoderVerdict.NotDecodable) {
                    assertTrue("$format: ${v.reason}", v.reason.endsWith(".") && v.reason.length <= 60)
                }
            }
        }
    }

    @Test
    fun `a later pack provider turns a refusal into a decode`() {
        val pack = object : com.fotoxplorr.core.formats.DecoderProvider {
            override val id = "pack-exr"
            override fun probe(format: MediaFormat, mime: String, sizeHint: com.fotoxplorr.core.formats.SizeHint?) =
                if (format == MediaFormat.Exr) DecoderVerdict.DecodableWithPack("exr") else DecoderVerdict.NotDecodable("no")
        }
        val registry = DecoderRegistry(listOf(pack, provider(34)))
        assertEquals(DecoderVerdict.DecodableWithPack("exr"), registry.probe(MediaFormat.Exr))
        assertEquals(DecoderVerdict.Decodable, registry.probe(MediaFormat.Jpeg))
    }

    private fun assertEquals(expected: Any?, actual: Any?, message: String) = org.junit.Assert.assertEquals(message, expected, actual)
}
