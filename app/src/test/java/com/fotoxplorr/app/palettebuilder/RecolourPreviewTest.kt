package com.fotoxplorr.app.palettebuilder

import com.fotoxplorr.app.palettebuilder.PaletteTestKit.BLUE
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.FakeHost
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.GREEN
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.RED
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The recolour preview: the same maths as the saved copy, asked of the host twice, and no file. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RecolourPreviewTest {

    private val colours = listOf(0xFFB03A2E.toInt(), 0xFFF39C12.toInt(), 0xFFFDEBD0.toInt())
    private val settings = RecolourSettings(100, keepShading = false)
    private val asset = PaletteTestKit.asset(5)

    private fun photo() = PaletteTestKit.bitmap(12, 8) { x, y -> listOf(RED, GREEN, BLUE)[(x + y) % 3] }

    private fun make(host: PaletteHost, colours: List<Int> = this.colours, a: com.fotoxplorr.app.media.MediaAsset = asset) =
        runBlocking { RecolourPreview.make(a, colours, settings, host) }

    @Test
    fun `the preview is the same maths as the saved copy, at a small size`() {
        val src = photo()
        val want = PaletteRecolour(colours).mapAll(PaletteTestKit.pixels(src))
        val host = FakeHost({ src })
        val r = make(host)
        assertTrue(r is PreviewResult.Ready)
        assertArrayEquals(want, PaletteTestKit.pixels((r as PreviewResult.Ready).bitmap))
        assertEquals(RecolourPreview.EDGE, host.limits.single().maxLongEdge)
        assertTrue("the decoded picture is released", src.isRecycled)
    }

    @Test
    fun `a refused photo is not read and no picture is made`() {
        val host = FakeHost({ photo() }, answers = listOf(false))
        val r = make(host)
        assertEquals(PaletteBuilderCopy.COPY_PROTECTED, (r as PreviewResult.Failed).message)
        assertTrue(host.limits.isEmpty())
    }

    @Test
    fun `a photo protected while the preview was made is not handed back`() {
        val host = FakeHost({ photo() }, answers = listOf(true, false))
        val r = make(host)
        assertEquals(PaletteBuilderCopy.COPY_PROTECTED, (r as PreviewResult.Failed).message)
    }

    @Test
    fun `a video, a short palette, an unreadable photo and an exception each give a sentence`() {
        assertEquals(PaletteBuilderCopy.COPY_PROTECTED, (make(FakeHost({ photo() }), a = PaletteTestKit.asset(6, "v.mp4", mime = "video/mp4")) as PreviewResult.Failed).message)
        assertEquals(PaletteBuilderCopy.COPY_FAILED, (make(FakeHost({ photo() }), colours = listOf(RED)) as PreviewResult.Failed).message)
        assertEquals(PaletteBuilderCopy.COPY_UNREADABLE, (make(FakeHost({ null })) as PreviewResult.Failed).message)
        assertEquals(PaletteBuilderCopy.COPY_OUT_OF_MEMORY, (make(FakeHost({ photo() }, outOfMemoryFirst = 1)) as PreviewResult.Failed).message)
        assertFalse(make(FakeHost({ photo() })) is PreviewResult.Failed)
    }
}
