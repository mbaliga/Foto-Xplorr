package com.fotoxplorr.app.palettebuilder

import android.graphics.Bitmap
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.BLUE
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.FakeHost
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.GREEN
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.RED
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.YELLOW
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections

/** Photos to a palette over a fake host: which colours, what is asked, and that a protected photo is never used. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PhotoPalettesTest {

    private val a = PaletteTestKit.asset(1)
    private val b = PaletteTestKit.asset(2)

    private fun halves(left: Int, right: Int): Bitmap = PaletteTestKit.bitmap(20, 10) { x, _ -> if (x < 10) left else right }

    private fun build(assets: List<com.fotoxplorr.app.media.MediaAsset>, host: PaletteHost, progress: MutableList<Float>? = null): PhotoPaletteResult =
        runBlocking { PhotoPalettes.build(assets, host) { progress?.add(it) } }

    private fun ready(r: PhotoPaletteResult): PhotoPaletteResult.Ready {
        assertTrue("expected a palette, got ${(r as? PhotoPaletteResult.Failed)?.message}", r is PhotoPaletteResult.Ready)
        return r as PhotoPaletteResult.Ready
    }

    private fun failed(r: PhotoPaletteResult): String {
        assertTrue("expected a failure", r is PhotoPaletteResult.Failed)
        return (r as PhotoPaletteResult.Failed).message
    }

    @Test
    fun `one photo gives its colours, in a palette with the default name, and remembers the photo`() {
        val host = FakeHost({ halves(RED, BLUE) })
        val r = ready(build(listOf(a), host))
        assertEquals(setOf(RED, BLUE), r.palette.argbs.toSet())
        assertEquals(PaletteBuilderCopy.DEFAULT_NAME, r.palette.name)
        assertEquals(listOf(1L), r.sources)
    }

    @Test
    fun `the file name of a photo never becomes the palette's name`() {
        val named = PaletteTestKit.asset(3, "Mum at the beach 2026.jpg")
        assertEquals(PaletteBuilderCopy.DEFAULT_NAME, ready(build(listOf(named), FakeHost({ halves(RED, BLUE) }))).palette.name)
    }

    @Test
    fun `the photo is read small, through the host's decoder`() {
        val host = FakeHost({ halves(RED, BLUE) })
        build(listOf(a), host)
        assertEquals(1, host.limits.size)
        assertEquals(PhotoPalettes.SAMPLE_EDGE, host.limits[0].maxLongEdge)
        assertTrue(host.limits[0].maxPixels <= PhotoPalettes.SAMPLE_EDGE.toLong() * PhotoPalettes.SAMPLE_EDGE)
    }

    @Test
    fun `several photos are pooled, each counted equally`() {
        val host = FakeHost({ if (it.id.value == 1L) halves(RED, GREEN) else halves(BLUE, YELLOW) })
        val r = ready(build(listOf(a, b), host))
        assertEquals(setOf(RED, GREEN, BLUE, YELLOW), r.palette.argbs.toSet())
        assertEquals(listOf(1L, 2L), r.sources)
        assertEquals(listOf(1L, 2L), host.decoded.sorted())
    }

    @Test
    fun `the same colour in two photos is one swatch`() {
        val nearRed = 0xFFDD2A2A.toInt()
        val host = FakeHost({ if (it.id.value == 1L) halves(RED, BLUE) else halves(nearRed, YELLOW) })
        val r = ready(build(listOf(a, b), host))
        assertEquals(3, r.palette.size)
    }

    @Test
    fun `a photo of one flat colour is too few colours for a palette`() {
        val flat = PaletteTestKit.bitmap(10, 10) { _, _ -> RED }
        assertEquals(PaletteBuilderCopy.TOO_FEW_COLOURS, failed(build(listOf(a), FakeHost({ flat }))))
    }

    @Test
    fun `a photo the host refuses is never read`() {
        val host = FakeHost({ halves(RED, BLUE) }, blocked = setOf(2L))
        assertEquals(PaletteBuilderCopy.PHOTO_PROTECTED, failed(build(listOf(a, b), host)))
        assertTrue("not even the allowed one is read", host.decoded.isEmpty())
    }

    @Test
    fun `a photo protected while the others were being read is refused at the end`() {
        // Two photos: asked 2 times up front and 2 times at the end; the fourth answer is no.
        val host = FakeHost({ halves(RED, BLUE) }, answers = listOf(true, true, true, false))
        assertEquals(PaletteBuilderCopy.PHOTO_PROTECTED, failed(build(listOf(a, b), host)))
        assertEquals(2, host.decoded.size)
    }

    @Test
    fun `a video is refused`() {
        val v = PaletteTestKit.asset(4, "v.mp4", mime = "video/mp4")
        assertEquals(PaletteBuilderCopy.PHOTO_PROTECTED, failed(build(listOf(v), FakeHost({ halves(RED, BLUE) }))))
    }

    @Test
    fun `a photo that cannot be read says so`() {
        val host = FakeHost({ if (it.id.value == 2L) null else halves(RED, BLUE) })
        assertEquals(PaletteBuilderCopy.PHOTO_UNREADABLE, failed(build(listOf(a, b), host)))
    }

    @Test
    fun `none, or more than six, is refused before anything is read`() {
        val host = FakeHost({ halves(RED, BLUE) })
        assertEquals(PaletteBuilderCopy.READ_FAILED, failed(build(emptyList(), host)))
        assertEquals(PaletteBuilderCopy.READ_FAILED, failed(build((1L..7L).map { PaletteTestKit.asset(it) }, host)))
        assertTrue(host.decoded.isEmpty())
    }

    @Test
    fun `an exception or a lack of memory from the host is a sentence, not a crash`() {
        val boom = object : PaletteHost {
            override fun mayExport(asset: com.fotoxplorr.app.media.MediaAsset) = true
            override suspend fun decode(asset: com.fotoxplorr.app.media.MediaAsset, limits: com.fotoxplorr.app.media.DecodeLimits): com.fotoxplorr.app.media.DecodedBitmap? =
                throw IllegalStateException("boom")
        }
        assertEquals(PaletteBuilderCopy.READ_FAILED, failed(build(listOf(a), boom)))
        assertEquals(PaletteBuilderCopy.READ_FAILED, failed(build(listOf(a), FakeHost({ halves(RED, BLUE) }, outOfMemoryFirst = 1))))
    }

    @Test
    fun `every decoded bitmap is released`() {
        val made = ArrayList<Bitmap>()
        build(listOf(a, b), FakeHost({ halves(RED, BLUE).also { made += it } }))
        assertEquals(2, made.size)
        assertTrue(made.all { it.isRecycled })
    }

    @Test
    fun `progress rises and ends at one`() {
        val progress = Collections.synchronizedList(ArrayList<Float>())
        ready(build(listOf(a, b), FakeHost({ halves(RED, BLUE) }), progress))
        assertEquals(progress.sorted(), progress.toList())
        assertEquals(1f, progress.last(), 0f)
    }

    @Test
    fun `a cancel stops it and nothing is returned`() = runBlocking {
        val host = FakeHost({ halves(RED, BLUE) }, onDecode = { awaitCancellation() })
        val job: Job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { PhotoPalettes.build(listOf(a, b), host) }
        job.start()
        while (host.limits.isEmpty()) kotlinx.coroutines.yield()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertNotNull(host)
    }

    @Test
    fun `pixels are read from a software bitmap as packed colours`() {
        val bmp = halves(RED, BLUE)
        val px = PhotoPalettes.pixelsOf(bmp)
        assertEquals(200, px.size)
        assertEquals(RED, px[0])
        assertEquals(BLUE, px[19])
    }
}
