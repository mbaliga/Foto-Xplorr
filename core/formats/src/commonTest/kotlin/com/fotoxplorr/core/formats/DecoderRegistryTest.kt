package com.fotoxplorr.core.formats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DecoderRegistryTest {

    private class Fake(
        override val id: String,
        override val priority: Int = 100,
        private val answer: (MediaFormat, String, SizeHint?) -> DecoderVerdict,
    ) : DecoderProvider {
        override fun probe(format: MediaFormat, mime: String, sizeHint: SizeHint?) = answer(format, mime, sizeHint)
    }

    private fun always(id: String, verdict: DecoderVerdict, priority: Int = 100) =
        Fake(id, priority) { _, _, _ -> verdict }

    @Test
    fun `no providers means nothing is decodable`() {
        val verdict = DecoderRegistry(emptyList()).probe(MediaFormat.Jpeg)
        assertEquals(DecoderVerdict.NotDecodable(FormatCopy.NO_DECODER), verdict)
    }

    @Test
    fun `decodable beats a pack beats a refusal`() {
        val no = always("a", DecoderVerdict.NotDecodable("no"))
        val pack = always("b", DecoderVerdict.DecodableWithPack("jxl"))
        val yes = always("c", DecoderVerdict.Decodable)
        assertEquals(DecoderVerdict.Decodable, DecoderRegistry(listOf(no, pack, yes)).probe(MediaFormat.JpegXl))
        assertEquals(DecoderVerdict.DecodableWithPack("jxl"), DecoderRegistry(listOf(no, pack)).probe(MediaFormat.JpegXl))
        assertEquals(DecoderVerdict.NotDecodable("no"), DecoderRegistry(listOf(no)).probe(MediaFormat.JpegXl))
    }

    @Test
    fun `order is priority then id and not registration order`() {
        val a = always("a", DecoderVerdict.NotDecodable("from a"), priority = 50)
        val b = always("b", DecoderVerdict.NotDecodable("from b"), priority = 10)
        val c = always("c", DecoderVerdict.NotDecodable("from c"), priority = 10)
        val forward = DecoderRegistry(listOf(a, b, c))
        val backward = DecoderRegistry(listOf(c, b, a))
        assertEquals(listOf("b", "c", "a"), forward.providerIds)
        assertEquals(forward.providerIds, backward.providerIds)
        // Equal quality: the earliest in (priority, id) order supplies the reason.
        assertEquals(DecoderVerdict.NotDecodable("from b"), forward.probe(MediaFormat.Tiff))
        assertEquals(DecoderVerdict.NotDecodable("from b"), backward.probe(MediaFormat.Tiff))
    }

    @Test
    fun `two packs for one format pick the first by order`() {
        val x = always("x", DecoderVerdict.DecodableWithPack("pack-x"))
        val y = always("y", DecoderVerdict.DecodableWithPack("pack-y"))
        assertEquals(DecoderVerdict.DecodableWithPack("pack-x"), DecoderRegistry(listOf(y, x)).probe(MediaFormat.Exr))
    }

    @Test
    fun `a provider can decide per format mime and size`() {
        val picky = Fake("picky") { format, mime, size ->
            when {
                format != MediaFormat.Tiff -> DecoderVerdict.NotDecodable("not tiff")
                mime == "image/tiff" && (size?.pixels ?: 0L) > 100_000_000L -> DecoderVerdict.NotDecodable("too big")
                else -> DecoderVerdict.DecodableWithPack("tiff")
            }
        }
        val registry = DecoderRegistry(listOf(picky))
        assertEquals(DecoderVerdict.DecodableWithPack("tiff"), registry.probe(MediaFormat.Tiff, "image/tiff", SizeHint(1000, 1000)))
        assertEquals(DecoderVerdict.NotDecodable("too big"), registry.probe(MediaFormat.Tiff, "image/tiff", SizeHint(20_000, 20_000)))
        assertEquals(DecoderVerdict.NotDecodable("not tiff"), registry.probe(MediaFormat.Png))
    }

    @Test
    fun `duplicate provider ids are refused`() {
        assertFailsWith<IllegalArgumentException> {
            DecoderRegistry(listOf(always("same", DecoderVerdict.Decodable), always("same", DecoderVerdict.Decodable)))
        }
    }

    @Test
    fun `canShow is false only for a refusal`() {
        assertTrue(DecoderVerdict.Decodable.canShow)
        assertTrue(DecoderVerdict.DecodableWithPack("p").canShow)
        assertEquals(false, DecoderVerdict.NotDecodable("x").canShow)
    }
}
