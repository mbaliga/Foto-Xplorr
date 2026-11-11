package com.fotoxplorr.core.formats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TileStateTest {

    private val cr3 = MediaFormat.Raw(RawVariant.CR3)
    private val refusal = DecoderVerdict.NotDecodable("Raw photos can't be previewed yet.")

    @Test
    fun `the full table of verdict by load result`() {
        val verdicts = listOf(DecoderVerdict.Decodable, DecoderVerdict.DecodableWithPack("p"), refusal)
        for (verdict in verdicts) {
            for (load in LoadResult.entries) {
                val state = tileState(MediaFormat.Jpeg, verdict, load)
                val expectShow = verdict.canShow && load != LoadResult.Failed
                assertEquals(expectShow, state is TileState.Show, "verdict=$verdict load=$load")
            }
        }
    }

    @Test
    fun `a refusal never tries the loader and names the format`() {
        val state = tileState(cr3, refusal, LoadResult.Pending)
        assertIs<TileState.Placeholder>(state)
        assertEquals("CR3", state.badge)
        assertEquals("Raw photos can't be previewed yet.", state.reason)
        assertEquals("CR3 raw photo, can't be shown here.", state.description)
    }

    @Test
    fun `a failed load of a decodable format is a placeholder with the generic reason`() {
        val state = tileState(MediaFormat.Jpeg, DecoderVerdict.Decodable, LoadResult.Failed)
        assertIs<TileState.Placeholder>(state)
        assertEquals("JPG", state.badge)
        assertEquals(FormatCopy.COULD_NOT_READ, state.reason)
    }

    @Test
    fun `a protected item shows no thumbnail and no format whatever the verdict`() {
        for (verdict in listOf(DecoderVerdict.Decodable, refusal)) {
            for (load in LoadResult.entries) {
                val state = tileState(MediaFormat.Jpeg, verdict, load, isProtected = true)
                assertIs<TileState.Placeholder>(state)
                assertEquals("", state.badge)
                assertEquals(FormatCopy.PROTECTED, state.reason)
            }
        }
    }

    @Test
    fun `no format can produce an empty tile`() {
        for (format in MediaFormat.all) {
            for (verdict in listOf(DecoderVerdict.Decodable, refusal)) {
                for (load in LoadResult.entries) {
                    val state = tileState(format, verdict, load)
                    if (state is TileState.Placeholder) {
                        assertTrue(state.badge.isNotEmpty(), "$format badge")
                        assertTrue(state.reason.isNotEmpty(), "$format reason")
                        assertTrue(state.description.endsWith("can't be shown here."), "$format description")
                    }
                }
            }
        }
    }

    @Test
    fun `copy is one short sentence each`() {
        listOf(
            FormatCopy.NO_DECODER, FormatCopy.COULD_NOT_READ, FormatCopy.PROTECTED,
            FormatCopy.needsPack("JPEG XL image"), FormatCopy.needsNewerAndroid("HEIC", "9"),
        ).forEach { sentence ->
            assertTrue(sentence.endsWith("."), sentence)
            assertEquals(1, sentence.count { it == '.' }, sentence)
            assertTrue(sentence.length <= 60, sentence)
        }
    }
}

class FormatResolutionTest {
    @Test
    fun `only an unknown classification is sniffed`() {
        assertTrue(FormatResolution.shouldSniff(MediaFormat.Other))
        MediaFormat.all.filter { it != MediaFormat.Other }.forEach {
            assertEquals(false, FormatResolution.shouldSniff(it), "$it")
            assertEquals(it, FormatResolution.resolve(it, MediaFormat.Tiff), "$it keeps its classification")
        }
    }

    @Test
    fun `a sniffed answer replaces other and null leaves it`() {
        assertEquals(MediaFormat.Tiff, FormatResolution.resolve(MediaFormat.Other, MediaFormat.Tiff))
        assertEquals(MediaFormat.Other, FormatResolution.resolve(MediaFormat.Other, null))
    }
}
