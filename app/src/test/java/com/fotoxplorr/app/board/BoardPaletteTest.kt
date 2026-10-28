package com.fotoxplorr.app.board

import com.fotoxplorr.app.board.BoardTestKit.BLUE
import com.fotoxplorr.app.board.BoardTestKit.GREEN
import com.fotoxplorr.app.board.BoardTestKit.RED
import com.fotoxplorr.app.palette.PaletteSampling
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BoardPaletteTest {

    private fun ref(id: Long) = PhotoRef(id, null, 400, 300)

    @Test
    fun `two flat photos give their two colours in equal shares`() {
        val source = FakePhotoSource(mapOf(1L to RED, 2L to BLUE))
        val swatches = runBlocking { BoardPalette.of(source, listOf(ref(1), ref(2))) }
        assertEquals(setOf(RED, BLUE), swatches.map { it.argb }.toSet())
        assertTrue(swatches.all { kotlin.math.abs(it.proportion - 0.5f) < 0.01f })
    }

    @Test
    fun `each photo is read once, at the palette's own small size`() {
        val source = FakePhotoSource(mapOf(1L to RED))
        runBlocking { BoardPalette.of(source, listOf(ref(1), ref(1))) }
        assertEquals(1, source.calls.size)
        assertEquals(PaletteSampling.DECODE_LIMITS, source.calls.single().second)
        assertTrue("and the bitmap was freed", source.made.all { it.isRecycled })
    }

    @Test
    fun `a photo that cannot be read is left out and the rest still count`() {
        val source = FakePhotoSource(mapOf(1L to GREEN, 2L to RED), available = { it.mediaId != 2L })
        val swatches = runBlocking { BoardPalette.of(source, listOf(ref(1), ref(2))) }
        assertEquals(listOf(GREEN), swatches.map { it.argb })
    }

    @Test
    fun `no photos or none readable is an empty strip`() {
        assertTrue(runBlocking { BoardPalette.of(FakePhotoSource(), emptyList()) }.isEmpty())
        assertTrue(runBlocking { BoardPalette.of(FakePhotoSource(available = { false }), listOf(ref(1))) }.isEmpty())
    }

    @Test
    fun `no more than the limit of photos are read`() {
        val source = FakePhotoSource()
        runBlocking { BoardPalette.of(source, (1L..40L).map(::ref)) }
        assertEquals(BoardPalette.MAX_PHOTOS, source.calls.size)
    }

    @Test
    fun `the colour count is respected`() {
        val source = FakePhotoSource(painter = { _, u, v -> if (u < 0.5f) (if (v < 0.5f) RED else GREEN) else (if (v < 0.5f) BLUE else 0xFFFFFF00.toInt()) })
        val swatches = runBlocking { BoardPalette.of(source, listOf(ref(1)), maxColours = 2) }
        assertEquals(2, swatches.size)
    }
}
