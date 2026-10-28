package com.fotoxplorr.app.editor

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The out-of-memory ladder around the places that allocate on top of the render: the flatten in
 * the render attempt ([renderThenFlatten]) and the encode of a COPY ([encodeDownTheLadder]). The
 * bitmaps are wide and thin so a real rung below [MIN_LADDER_EDGE] is cheap to build.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EditorOomLadderTest {

    private val made = mutableListOf<File>()

    @After
    fun cleanUp() = made.forEach { it.delete() }

    private fun bitmap(width: Int = 3200, height: Int = 20): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun staged(): File = File.createTempFile("fx-oom", ".bin").also { made += it }

    // ---- scaledSizeFor ----

    @Test
    fun `the long edge becomes the rung and the short edge keeps the ratio`() {
        assertEquals(1600 to 10, scaledSizeFor(3200, 20, 1600))
        assertEquals(10 to 1600, scaledSizeFor(20, 3200, 1600))
        assertEquals("never below one pixel", 1600 to 1, scaledSizeFor(3200, 1, 1600))
    }

    // ---- encodeDownTheLadder ----

    @Test
    fun `an encode that fits is made once, on the bitmap itself`() {
        val source = bitmap()
        val seen = mutableListOf<Bitmap>()

        val result = runBlocking { encodeDownTheLadder(source) { seen += it; staged() } }

        assertFalse(result.steppedDown)
        assertEquals(1, seen.size)
        assertSame(source, seen.single())
        assertEquals(3200, result.width)
    }

    @Test
    fun `an encode that runs out of memory is retried one rung down on a scaled copy`() {
        val source = bitmap()
        val seen = mutableListOf<Bitmap>()

        val result = runBlocking {
            encodeDownTheLadder(source) { b ->
                seen += b
                if (b.width > 2000) throw OutOfMemoryError("encode") else staged()
            }
        }

        assertTrue(result.steppedDown)
        assertEquals(1600, result.width)
        assertEquals(10, result.height)
        assertEquals(2, seen.size)
        assertTrue("the scaled copy is recycled once its encode is done", seen[1].isRecycled)
        assertFalse("the caller's bitmap is never recycled", source.isRecycled)
    }

    @Test
    fun `every rung running out of memory throws a readable OutOfMemoryError`() {
        val source = bitmap()

        try {
            runBlocking { encodeDownTheLadder(source) { throw OutOfMemoryError("encode") } }
            fail("expected an OutOfMemoryError")
        } catch (e: OutOfMemoryError) {
            assertTrue(e.message!!, "memory" in e.message!!)
        }
        assertFalse(source.isRecycled)
    }

    @Test
    fun `a failure that is not an out-of-memory is not retried`() {
        var calls = 0
        try {
            runBlocking { encodeDownTheLadder(bitmap()) { calls++; throw IllegalStateException("could not encode") } }
            fail("expected the failure")
        } catch (e: IllegalStateException) {
            assertEquals(1, calls)
        }
    }

    // ---- renderThenFlatten ----

    @Test
    fun `the decoded input is recycled and the flattened render is what comes back`() {
        val decoded = bitmap(10, 10)
        val rendered = bitmap(10, 10)

        val result = runBlocking {
            renderThenFlatten(decoded, render = { rendered }, flatten = { compositeOntoWhiteIfNeeded(it, OutputFormat.JPEG) })
        }!!

        assertTrue(decoded.isRecycled)
        assertTrue("the unflattened render is superseded", rendered.isRecycled)
        assertFalse(result.isRecycled)
        assertFalse(result.hasAlpha())
    }

    @Test
    fun `a format that needs no flatten returns the render itself and only the input is recycled`() {
        val decoded = bitmap(10, 10)
        val rendered = bitmap(10, 10)

        val result = runBlocking { renderThenFlatten(decoded, render = { rendered }, flatten = { it }) }

        assertSame(rendered, result)
        assertTrue(decoded.isRecycled)
        assertFalse(rendered.isRecycled)
    }

    @Test
    fun `an out-of-memory in the flatten recycles both bitmaps and reaches the ladder`() {
        val decoded = bitmap(10, 10)
        val rendered = bitmap(10, 10)

        try {
            runBlocking { renderThenFlatten(decoded, render = { rendered }, flatten = { throw OutOfMemoryError("flatten") }) }
            fail("expected an OutOfMemoryError")
        } catch (e: OutOfMemoryError) {
            // reaches renderDownTheLadder
        }
        assertTrue(decoded.isRecycled)
        assertTrue(rendered.isRecycled)
    }

    @Test
    fun `a render that gives up returns null and still frees the input`() {
        val decoded = bitmap(10, 10)

        assertNull(runBlocking { renderThenFlatten(decoded, render = { null }, flatten = { it }) })
        assertTrue(decoded.isRecycled)
    }

    @Test
    fun `an out-of-memory in the render frees the input`() {
        val decoded = bitmap(10, 10)

        try {
            runBlocking { renderThenFlatten(decoded, render = { throw OutOfMemoryError("render") }, flatten = { it }) }
            fail("expected an OutOfMemoryError")
        } catch (e: OutOfMemoryError) {
            // reaches renderDownTheLadder
        }
        assertTrue(decoded.isRecycled)
    }

    // ---- the flatten marks its result opaque ----

    @Test
    fun `a flattened bitmap is not flattened again`() {
        val source = bitmap(4, 4).apply { eraseColor(Color.TRANSPARENT) }

        val once = compositeOntoWhiteIfNeeded(source, OutputFormat.JPEG)
        val twice = compositeOntoWhiteIfNeeded(once, OutputFormat.JPEG)

        assertSame("the writer's own flatten must be a no-op after the render's", once, twice)
        assertEquals(Color.WHITE, once.getPixel(1, 1))
    }
}
