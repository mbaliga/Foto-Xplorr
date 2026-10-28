package com.fotoxplorr.app.board

import android.graphics.Bitmap
import android.graphics.Color
import com.fotoxplorr.app.board.BoardTestKit.layer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Below API 29 the platform's multiply is a plain product that also multiplies alpha, so the
 * renderer lays the layer on white first. These are the multiply cases of [BoardRendererTest] on
 * SDK 28 (Robolectric's native graphics is Skia, so this checks the fallback's arithmetic, not how a
 * phone of that age behaves).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class BoardRendererOlderApiTest {

    private val small = CanvasSize(64, 64)
    private val one = RenderSize(64, 64, 1f)
    private val grey = BoardBackground.Colour(0xFF808080.toInt())

    private fun draw(vararg layers: Layer): Bitmap =
        (runBlocking { BoardRenderer(FakePhotoSource()).render(Board(small, grey, layers.toList(), 99), one, MissingPhotos.PLACEHOLDER) } as BoardRender.Done).bitmap

    private fun near(expected: Double, actual: Int, tol: Int = 3) = assertTrue("expected about $expected, was $actual", abs(expected - actual) <= tol)

    private fun multiply(opacity: Float = 1f, mask: LayerMask = LayerMask.NONE) =
        layer(1, 32f, 32f, 64f, 64f, content = LayerContent.Colour(0xFFFF8000.toInt()), blend = LayerBlend.MULTIPLY, opacity = opacity, mask = mask)

    @Test
    fun `multiply is the product with the backdrop and the backdrop stays opaque`() {
        val out = draw(multiply())
        near(128.0, Color.red(out.getPixel(32, 32)))
        near(64.0, Color.green(out.getPixel(32, 32)))
        assertEquals(255, Color.alpha(out.getPixel(32, 32)))
    }

    @Test
    fun `multiply at half opacity is half way between the backdrop and the product`() {
        val out = draw(multiply(opacity = 0.5f))
        near(96.0, Color.green(out.getPixel(32, 32)))
        assertEquals(255, Color.alpha(out.getPixel(32, 32)))
    }

    @Test
    fun `multiply under a mask leaves the backdrop alone outside it`() {
        val out = draw(multiply(mask = LayerMask(MaskShape.Ellipse)))
        near(64.0, Color.green(out.getPixel(32, 32)))
        assertEquals(0xFF808080.toInt(), out.getPixel(1, 1))
    }
}
