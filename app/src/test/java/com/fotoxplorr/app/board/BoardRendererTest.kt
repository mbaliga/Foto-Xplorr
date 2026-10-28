package com.fotoxplorr.app.board

import android.graphics.Bitmap
import android.graphics.Color
import com.fotoxplorr.app.board.BoardTestKit.BLUE
import com.fotoxplorr.app.board.BoardTestKit.RED
import com.fotoxplorr.app.board.BoardTestKit.WHITE
import com.fotoxplorr.app.board.BoardTestKit.layer
import com.fotoxplorr.app.board.BoardTestKit.photo
import com.fotoxplorr.app.editor.CropRect
import com.fotoxplorr.app.editor.ExportOptions
import com.fotoxplorr.app.editor.MaxEdgePreset
import com.fotoxplorr.app.lift.NormPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The compositor on tiny boards: a 64 by 64 canvas drawn one pixel to the unit, with flat-colour
 * fakes for photos, under Robolectric's native graphics (real Skia). What this proves is the pixel
 * values the blend, opacity, mask and turn must give. What it cannot prove: how it looks on a
 * device, the speed of a 12 megapixel photo, or the memory ladder against a real heap (the ladder
 * test throws a fake [OutOfMemoryError]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BoardRendererTest {

    private val small = CanvasSize(64, 64)
    private val one = RenderSize(64, 64, 1f)

    private fun board(vararg layers: Layer, background: BoardBackground = BoardBackground.Colour(WHITE)) =
        Board(small, background, layers.toList(), (layers.maxOfOrNull { it.id } ?: 0L) + 1)

    private fun full(id: Long = 1, content: LayerContent = LayerContent.Colour(RED), mask: LayerMask = LayerMask.NONE, opacity: Float = 1f, blend: LayerBlend = LayerBlend.NORMAL) =
        layer(id, 32f, 32f, 64f, 64f, content = content, mask = mask, opacity = opacity, blend = blend)

    private fun draw(b: Board, source: FakePhotoSource = FakePhotoSource(), policy: MissingPhotos = MissingPhotos.PLACEHOLDER): BoardRender.Done =
        runBlocking { BoardRenderer(source).render(b, one, policy) } as BoardRender.Done

    private fun Bitmap.r(x: Int, y: Int) = Color.red(getPixel(x, y))
    private fun Bitmap.g(x: Int, y: Int) = Color.green(getPixel(x, y))
    private fun Bitmap.b(x: Int, y: Int) = Color.blue(getPixel(x, y))
    private fun Bitmap.a(x: Int, y: Int) = Color.alpha(getPixel(x, y))

    private fun near(expected: Number, actual: Int, tol: Int = 2) =
        assertTrue("expected about $expected, was $actual", abs(expected.toDouble() - actual) <= tol)

    @Test
    fun `the background fills the canvas`() {
        val out = draw(board(background = BoardBackground.Colour(0xFF336699.toInt()))).bitmap
        assertEquals(64, out.width)
        assertEquals(0xFF336699.toInt(), out.getPixel(10, 10))
    }

    @Test
    fun `a transparent background leaves alpha zero where nothing is drawn, and full where something is`() {
        val b = board(layer(1, 32f, 32f, 20f, 20f), background = BoardBackground.Transparent)
        val out = draw(b).bitmap
        assertEquals(0, out.a(0, 0))
        assertEquals(255, out.a(32, 32))
        assertEquals(RED, out.getPixel(32, 32))
    }

    @Test
    fun `a layer at half opacity is half way between itself and what is behind it`() {
        val out = draw(board(full(opacity = 0.5f))).bitmap
        near(255, out.r(32, 32))
        near(127.5, out.g(32, 32))
        near(127.5, out.b(32, 32))
    }

    @Test
    fun `a photo at a quarter opacity lets three quarters of the white through`() {
        val out = draw(board(full(content = photo(1, 64, 64), opacity = 0.25f)), FakePhotoSource(mapOf(1L to BLUE))).bitmap
        near(255 * 0.75, out.r(32, 32))
        near(255, out.b(32, 32))
    }

    @Test
    fun `a layer at no opacity is not drawn and its photo is not even decoded`() {
        val source = FakePhotoSource(mapOf(1L to BLUE))
        val out = draw(board(full(content = photo(1, 64, 64), opacity = 0f)), source).bitmap
        assertEquals(WHITE, out.getPixel(32, 32))
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `multiply darkens by the layer`() {
        val b = board(full(content = LayerContent.Colour(0xFFFF8000.toInt()), blend = LayerBlend.MULTIPLY), background = BoardBackground.Colour(0xFF808080.toInt()))
        val out = draw(b).bitmap
        near(128, out.r(32, 32))
        near(64, out.g(32, 32))
        near(0, out.b(32, 32))
    }

    @Test
    fun `screen lightens`() {
        val b = board(full(content = LayerContent.Colour(0xFFC8C8C8.toInt()), blend = LayerBlend.SCREEN), background = BoardBackground.Colour(0xFF646464.toInt()))
        val s = 200 / 255.0
        val d = 100 / 255.0
        near(255 * (s + d - s * d), draw(b).bitmap.r(32, 32))
    }

    @Test
    fun `overlay doubles the multiply on a dark backdrop`() {
        val b = board(full(content = LayerContent.Colour(0xFFC8C8C8.toInt()), blend = LayerBlend.OVERLAY), background = BoardBackground.Colour(0xFF646464.toInt()))
        near(255 * 2 * (200 / 255.0) * (100 / 255.0), draw(b).bitmap.r(32, 32), tol = 3)
    }

    @Test
    fun `multiply at half opacity is half way between the backdrop and the product`() {
        val b = board(full(content = LayerContent.Colour(0xFFFF8000.toInt()), blend = LayerBlend.MULTIPLY, opacity = 0.5f), background = BoardBackground.Colour(0xFF808080.toInt()))
        val out = draw(b).bitmap
        near((128 + 64) / 2.0, out.g(32, 32), tol = 3)
    }

    @Test
    fun `multiply under a mask leaves the backdrop alone outside the mask`() {
        val b = board(
            full(content = LayerContent.Colour(0xFFFF8000.toInt()), blend = LayerBlend.MULTIPLY, mask = LayerMask(MaskShape.Ellipse)),
            background = BoardBackground.Colour(0xFF808080.toInt()),
        )
        val out = draw(b).bitmap
        near(64, out.g(32, 32))
        assertEquals(0xFF808080.toInt(), out.getPixel(1, 1))
        assertEquals(255, out.a(32, 32))
    }

    @Test
    fun `a layer turned a quarter covers the turned box and not the unturned one`() {
        val turned = draw(board(layer(1, 32f, 32f, 40f, 20f, rot = 90f))).bitmap
        assertEquals(RED, turned.getPixel(32, 16))
        assertEquals(WHITE, turned.getPixel(47, 32))
        assertEquals(WHITE, turned.getPixel(32, 8))
        val straight = draw(board(layer(1, 32f, 32f, 40f, 20f))).bitmap
        assertEquals(RED, straight.getPixel(47, 32))
    }

    @Test
    fun `the corners drawn are the corners the geometry gives`() {
        val l = layer(1, 30f, 34f, 30f, 14f, rot = 33f, scale = 1.2f)
        val out = draw(board(l)).bitmap
        for (c in BoardGeometry.corners(l)) {
            // A point a little inside each corner is covered, a little outside is not.
            val centre = BoardGeometry.pointOf(l, 0.5f, 0.5f)
            val inside = Pt(c.x + (centre.x - c.x) * 0.15f, c.y + (centre.y - c.y) * 0.15f)
            val outside = Pt(c.x - (centre.x - c.x) * 0.15f, c.y - (centre.y - c.y) * 0.15f)
            assertEquals(RED, out.getPixel(inside.x.toInt(), inside.y.toInt()))
            if (outside.x in 0f..63f && outside.y in 0f..63f) assertEquals(WHITE, out.getPixel(outside.x.toInt(), outside.y.toInt()))
        }
    }

    @Test
    fun `an oval mask shows the middle and leaves the corners`() {
        val out = draw(board(full(mask = LayerMask(MaskShape.Ellipse)))).bitmap
        assertEquals(RED, out.getPixel(32, 32))
        assertEquals(WHITE, out.getPixel(1, 1))
        assertEquals(WHITE, out.getPixel(62, 2))
    }

    @Test
    fun `a rounded mask cuts the corners and keeps the sides`() {
        val out = draw(board(full(mask = LayerMask(MaskShape.RoundedRect(0.25f))))).bitmap
        assertEquals(WHITE, out.getPixel(1, 1))
        assertEquals(RED, out.getPixel(32, 2))
        assertEquals(RED, out.getPixel(2, 32))
        assertEquals(RED, out.getPixel(8, 8).let { if (Color.green(it) < 40) RED else it })
    }

    @Test
    fun `a polygon mask shows its inside`() {
        val tri = MaskShape.Polygon(listOf(NormPoint(0.5f, 0f), NormPoint(1f, 1f), NormPoint(0f, 1f)))
        val out = draw(board(full(mask = LayerMask(tri)))).bitmap
        assertEquals(RED, out.getPixel(32, 48))
        assertEquals(WHITE, out.getPixel(4, 4))
        assertEquals(WHITE, out.getPixel(60, 4))
    }

    @Test
    fun `a masked layer at half opacity is half shown inside the mask and not at all outside`() {
        val out = draw(board(full(mask = LayerMask(MaskShape.Ellipse), opacity = 0.5f))).bitmap
        near(127.5, out.g(32, 32))
        assertEquals(WHITE, out.getPixel(1, 1))
    }

    @Test
    fun `a feathered edge fades from the edge in`() {
        val out = draw(board(full(mask = LayerMask(MaskShape.Full, 0.5f)))).bitmap
        assertEquals(RED, out.getPixel(32, 32))
        assertTrue("edge pixel is nearly white: ${out.g(0, 32)}", out.g(0, 32) > 240)
        val mid = out.g(16, 32)
        assertTrue("a quarter in is about half shown: $mid", mid in 105..145)
        var last = 300
        for (x in 0..32) {
            assertTrue("fades monotonically at $x", out.g(x, 32) <= last)
            last = out.g(x, 32)
        }
    }

    @Test
    fun `a colour layer keeps the alpha of its colour`() {
        val out = draw(board(full(content = LayerContent.Colour(0x80FF0000.toInt())))).bitmap
        near(127.5, out.g(32, 32))
    }

    @Test
    fun `an oval shape has an outline and a fill`() {
        val shape = LayerContent.Shape(ShapeKind.ELLIPSE, RED, BLUE, 8f)
        val out = draw(board(full(content = shape))).bitmap
        assertEquals(RED, out.getPixel(32, 32))
        assertEquals(BLUE, out.getPixel(32, 2))
        assertEquals(WHITE, out.getPixel(1, 1))
        assertEquals(RED, out.getPixel(32, 20))
    }

    @Test
    fun `a triangle shape points up`() {
        val out = draw(board(full(content = LayerContent.Shape(ShapeKind.TRIANGLE, RED)))).bitmap
        assertEquals(RED, out.getPixel(32, 50))
        assertEquals(WHITE, out.getPixel(32 - 28, 8))
        assertEquals(WHITE, out.getPixel(3, 30))
    }

    @Test
    fun `text puts dark pixels in its box and none outside it`() {
        val text = LayerContent.Text("W", argb = 0xFF000000.toInt(), sizeUnits = 40f, bold = true, align = TextAlign.CENTRE)
        val out = draw(board(layer(1, 32f, 32f, 64f, 64f, content = text))).bitmap
        var dark = 0
        for (y in 0 until 64) for (x in 0 until 64) if (out.r(x, y) < 100) dark++
        assertTrue("some of the letter is drawn: $dark", dark > 30)
        val clipped = draw(board(layer(1, 32f, 32f, 20f, 20f, content = text))).bitmap
        assertEquals(WHITE, clipped.getPixel(60, 60))
        assertEquals(WHITE, clipped.getPixel(2, 2))
    }

    @Test
    fun `a crop shows only the part of the photo asked for`() {
        val split = FakePhotoSource(painter = { _, u, _ -> if (u < 0.5f) RED else BLUE })
        val whole = draw(board(full(content = photo(1, 64, 64))), split).bitmap
        assertEquals(RED, whole.getPixel(10, 32))
        assertEquals(BLUE, whole.getPixel(54, 32))
        val left = draw(board(full(content = photo(1, 64, 64)).copy(crop = CropRect(0f, 0f, 0.5f, 1f))), FakePhotoSource(painter = { _, u, _ -> if (u < 0.5f) RED else BLUE })).bitmap
        assertEquals(RED, left.getPixel(10, 32))
        assertEquals(RED, left.getPixel(54, 32))
        val right = draw(board(full(content = photo(1, 64, 64)).copy(crop = CropRect(0.5f, 0f, 1f, 1f))), FakePhotoSource(painter = { _, u, _ -> if (u < 0.5f) RED else BLUE })).bitmap
        assertEquals(BLUE, right.getPixel(10, 32))
    }

    @Test
    fun `a missing photo is a grey placeholder in a preview and is reported`() {
        val source = FakePhotoSource(available = { false })
        val done = draw(board(full(content = photo(1, 64, 64))), source)
        assertEquals(listOf(1L), done.missing)
        near(0xD0, done.bitmap.r(32, 10), tol = 1)
        assertEquals(done.bitmap.r(32, 10), done.bitmap.b(32, 10))
    }

    @Test
    fun `a missing photo under a mask is a placeholder in the mask`() {
        val done = draw(board(full(content = photo(1, 64, 64), mask = LayerMask(MaskShape.Ellipse))), FakePhotoSource(available = { false }))
        assertEquals(WHITE, done.bitmap.getPixel(1, 1))
        near(0xD0, done.bitmap.r(32, 10), tol = 1)
    }

    @Test
    fun `an export refuses a board with a missing photo before it decodes or draws anything`() {
        val source = FakePhotoSource(mapOf(2L to BLUE), available = { it.mediaId != 1L })
        val b = board(full(1, photo(1, 64, 64)), layer(2, 10f, 10f, 10f, 10f, content = photo(2, 64, 64)))
        val result = runBlocking { BoardRenderer(source).export(b) }
        assertEquals(listOf(1L), (result as BoardRender.Refused).missing)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `an export that finds a photo gone midway refuses and frees what it made`() {
        // Available when asked, gone when read: the photo was locked in between.
        val source = FakePhotoSource(mapOf(1L to RED), available = { true })
        val b = board(full(1, photo(1, 64, 64)), layer(2, 10f, 10f, 10f, 10f, content = photo(2, 64, 64)))
        val flaky = object : BoardPhotoSource by source {
            override suspend fun decode(ref: PhotoRef, limits: com.fotoxplorr.app.media.DecodeLimits) =
                if (ref.mediaId == 2L) null else source.decode(ref, limits)
        }
        val result = runBlocking { BoardRenderer(flaky).export(b, ExportOptions(maxEdge = MaxEdgePreset.SMALL)) }
        assertEquals(listOf(2L), (result as BoardRender.Refused).missing)
        assertTrue(source.made.all { it.isRecycled })
    }

    @Test
    fun `a photo is decoded once and every bitmap is recycled when the board is done`() {
        val source = FakePhotoSource(mapOf(1L to RED, 2L to BLUE))
        val b = board(
            layer(1, 16f, 16f, 20f, 20f, content = photo(1, 64, 64)),
            layer(2, 48f, 16f, 20f, 20f, content = photo(2, 64, 64)),
            layer(3, 16f, 48f, 20f, 20f, content = photo(1, 64, 64), mask = LayerMask(MaskShape.Ellipse)),
        )
        val done = draw(b, source)
        assertEquals(listOf(1L, 2L), source.calls.map { it.first })
        assertEquals(RED, done.bitmap.getPixel(16, 16))
        assertEquals(BLUE, done.bitmap.getPixel(48, 16))
        assertEquals(RED, done.bitmap.getPixel(16, 48))
        assertTrue(source.made.all { it.isRecycled })
        assertFalse(done.bitmap.isRecycled)
    }

    @Test
    fun `a render that is cancelled stops at the next layer and frees what it made`() {
        val source = FakePhotoSource(mapOf(1L to RED, 2L to BLUE))
        val b = board(
            layer(1, 16f, 16f, 20f, 20f, content = photo(1, 64, 64)),
            layer(2, 48f, 16f, 20f, 20f, content = photo(2, 64, 64)),
        )
        val job = kotlinx.coroutines.CompletableDeferred<Unit>()
        lateinit var running: kotlinx.coroutines.Job
        source.onDecoded = { running.cancel() }
        runBlocking {
            // Started only once `running` is set: the decode hook cancels it, and on another thread it could run first.
            running = launch(kotlinx.coroutines.Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    BoardRenderer(source).render(b, one, MissingPhotos.PLACEHOLDER)
                } finally {
                    job.complete(Unit)
                }
            }
            running.start()
            job.await()
            running.join()
            assertTrue(running.isCancelled)
        }
        assertEquals("the second photo is never asked for", listOf(1L), source.calls.map { it.first })
        assertTrue(source.made.all { it.isRecycled })
    }

    @Test
    fun `a layer off the canvas is not decoded`() {
        val source = FakePhotoSource()
        draw(board(layer(1, 500f, 500f, 20f, 20f, content = photo(1, 64, 64))), source)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `a photo is asked for at the size it will be drawn`() {
        val source = FakePhotoSource()
        draw(board(layer(1, 32f, 32f, 64f, 48f, content = photo(1, 4000, 3000))), source)
        assertEquals(64, source.calls.single().second.maxLongEdge)
        val half = FakePhotoSource()
        runBlocking { BoardRenderer(half).preview(board(layer(1, 32f, 32f, 64f, 48f, content = photo(1, 4000, 3000))), 32) }
        assertEquals(32, half.calls.single().second.maxLongEdge)
    }

    @Test
    fun `a preview is the size asked for`() {
        val done = runBlocking { BoardRenderer(FakePhotoSource()).preview(board(full()), 32) } as BoardRender.Done
        assertEquals(32, done.bitmap.width)
        assertEquals(32, done.edge)
        assertEquals(RED, done.bitmap.getPixel(16, 16))
    }

    @Test
    fun `an export honours the person's cap`() {
        val source = FakePhotoSource(mapOf(1L to BLUE))
        val b = board(layer(1, 32f, 32f, 64f, 48f, content = photo(1, 4000, 3000)))
        val done = runBlocking { BoardRenderer(source).export(b, ExportOptions(maxEdge = MaxEdgePreset.SMALL)) } as BoardRender.Done
        assertEquals(1024, done.bitmap.width)
        assertFalse(done.steppedDown)
        assertTrue(done.missing.isEmpty())
        assertEquals(BLUE, done.bitmap.getPixel(512, 400))
        assertEquals(WHITE, done.bitmap.getPixel(512, 1000))
    }

    @Test
    fun `when memory runs out the export steps down one size and says so`() {
        val source = FakePhotoSource(mapOf(1L to BLUE))
        source.outOfMemoryTimes = 1
        val b = board(layer(1, 32f, 32f, 64f, 48f, content = photo(1, 4000, 3000)))
        val done = runBlocking { BoardRenderer(source).export(b, ExportOptions(maxEdge = MaxEdgePreset.MEDIUM)) } as BoardRender.Done
        assertTrue(done.steppedDown)
        assertEquals(1536, done.edge)
        assertEquals(2, source.calls.size)
        assertTrue(source.made.all { it.isRecycled })
    }

    @Test
    fun `a single try that runs out of memory reports it rather than throwing`() {
        val source = FakePhotoSource()
        source.outOfMemoryTimes = 1
        val result = runBlocking { BoardRenderer(source).render(board(full(content = photo(1, 64, 64))), one, MissingPhotos.PLACEHOLDER) }
        assertEquals(BoardRender.OutOfMemory, result)
    }

    @Test
    fun `the subject of a photo is cut out by re-running the lift segmenter on the layer's own pixels`() {
        // A red square on blue; one mark inside the square, two on the blue.
        val scene = FakePhotoSource(painter = { _, u, v -> if (u in 0.3f..0.7f && v in 0.3f..0.7f) RED else BLUE })
        val ops = listOf(BoardTestKit.hint(true, 0.5f, 0.5f, 0.05f), BoardTestKit.hint(false, 0.06f, 0.06f, 0.05f), BoardTestKit.hint(false, 0.94f, 0.94f, 0.05f))
        val cut = draw(board(full(content = photo(1, 64, 64), mask = LayerMask(MaskShape.Subject(ops)))), scene).bitmap
        assertEquals(RED, cut.getPixel(32, 32))
        assertEquals(WHITE, cut.getPixel(4, 4))
        assertEquals(WHITE, cut.getPixel(58, 30))
    }

    @Test
    fun `a subject mask with no mark, or on something that is not a photo, shows the whole layer`() {
        val none = draw(board(full(content = photo(1, 64, 64), mask = LayerMask(MaskShape.Subject(emptyList())))), FakePhotoSource(mapOf(1L to BLUE))).bitmap
        assertEquals(BLUE, none.getPixel(2, 2))
        val onColour = draw(board(full(mask = LayerMask(MaskShape.Subject(listOf(BoardTestKit.hint(true, 0.5f, 0.5f))))))).bitmap
        assertEquals(RED, onColour.getPixel(2, 2))
    }

    @Test
    fun `a subject mask on a cropped photo lines up with the part that shows`() {
        // The square is in the left half; crop to that half and the cut must still be the square.
        val scene = { FakePhotoSource(painter = { _, u, v -> if (u in 0.1f..0.4f && v in 0.3f..0.7f) RED else BLUE }) }
        val ops = listOf(BoardTestKit.hint(true, 0.25f, 0.5f, 0.04f), BoardTestKit.hint(false, 0.05f, 0.05f, 0.04f), BoardTestKit.hint(false, 0.9f, 0.9f, 0.04f))
        val layer = full(content = photo(1, 64, 64), mask = LayerMask(MaskShape.Subject(ops))).copy(crop = CropRect(0f, 0f, 0.5f, 1f))
        val cut = draw(board(layer), scene()).bitmap
        // In the cropped view the square spans x = 0.2..0.8 of the box, y = 0.3..0.7.
        assertEquals(RED, cut.getPixel(32, 32))
        assertEquals(WHITE, cut.getPixel(3, 3))
        assertEquals(WHITE, cut.getPixel(32, 6))
    }
}
