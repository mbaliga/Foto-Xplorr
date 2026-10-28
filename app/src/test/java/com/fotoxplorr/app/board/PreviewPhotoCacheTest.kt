package com.fotoxplorr.app.board

import com.fotoxplorr.app.board.BoardTestKit.BLUE
import com.fotoxplorr.app.board.BoardTestKit.RED
import com.fotoxplorr.app.media.DecodeLimits
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The preview's memory of decoded photos: how often it decodes, what it hands out, and that it never outlives a protection. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PreviewPhotoCacheTest {

    private val ref1 = PhotoRef(1, null, 4000, 3000)
    private val ref2 = PhotoRef(2, null, 4000, 3000)
    private val limits = DecodeLimits(400, 160_000)

    private val inner = FakePhotoSource(mapOf(1L to RED, 2L to BLUE))

    private fun get(cache: PreviewPhotoCache, ref: PhotoRef, l: DecodeLimits = limits) = runBlocking { cache.decode(ref, l) }

    @Test
    fun `a photo is decoded once however many times it is asked for`() {
        val cache = PreviewPhotoCache(inner)
        repeat(5) { get(cache, ref1)!!.recycle() }
        assertEquals(1, inner.calls.size)
        assertEquals(1, cache.size)
    }

    @Test
    fun `what is handed out is a copy the caller may recycle`() {
        val cache = PreviewPhotoCache(inner)
        val a = get(cache, ref1)!!
        val b = get(cache, ref1)!!
        assertNotSame(a, b)
        a.recycle()
        assertFalse("the remembered picture is not the one recycled", b.isRecycled)
        assertEquals(RED, b.getPixel(3, 3))
        val c = get(cache, ref1)!!
        assertEquals(RED, c.getPixel(3, 3))
    }

    @Test
    fun `a smaller ask is answered from the picture held, shrunk to what the limits allow`() {
        val cache = PreviewPhotoCache(inner)
        val big = get(cache, ref1)!!
        assertEquals(400, big.width)
        val small = get(cache, ref1, DecodeLimits(100, 10_000))!!
        assertEquals(100, small.width)
        assertEquals(75, small.height)
        assertEquals("no second decode", 1, inner.calls.size)
        assertEquals(RED, small.getPixel(10, 10))
    }

    @Test
    fun `a larger ask decodes again and replaces what was held`() {
        val cache = PreviewPhotoCache(inner)
        get(cache, ref1, DecodeLimits(100, 10_000))!!.recycle()
        val bigger = get(cache, ref1, DecodeLimits(300, 90_000))!!
        assertEquals(300, bigger.width)
        assertEquals(2, inner.calls.size)
        assertEquals(1, cache.size)
        get(cache, ref1, DecodeLimits(200, 40_000))!!.recycle()
        assertEquals("answered from the bigger one", 2, inner.calls.size)
    }

    @Test
    fun `a photo that is no longer available is not served from memory and is forgotten`() {
        val cache = PreviewPhotoCache(inner)
        get(cache, ref1)!!.recycle()
        assertEquals(1, cache.size)
        inner.available = { false }
        assertNull(get(cache, ref1))
        assertFalse(cache.isAvailable(ref1))
        assertEquals("its pixels are gone from memory", 0, cache.size)
        assertEquals(0L, cache.heldBytes)
        inner.available = { true }
        get(cache, ref1)!!.recycle()
        assertEquals("and asking again goes back to the source", 2, inner.calls.size)
    }

    @Test
    fun `availability is always the source's answer`() {
        val cache = PreviewPhotoCache(inner)
        assertTrue(cache.isAvailable(ref1))
        inner.available = { it.mediaId == 2L }
        assertFalse(cache.isAvailable(ref1))
        assertTrue(cache.isAvailable(ref2))
    }

    @Test
    fun `a photo the source cannot read is null and not remembered`() {
        val cache = PreviewPhotoCache(inner)
        inner.available = { true }
        val failing = object : BoardPhotoSource {
            override fun isAvailable(ref: PhotoRef) = true
            override suspend fun decode(ref: PhotoRef, limits: DecodeLimits) = null
        }
        val c = PreviewPhotoCache(failing)
        assertNull(get(c, ref1))
        assertEquals(0, c.size)
        assertEquals(0, cache.size)
    }

    @Test
    fun `memory is bounded and the one used longest ago goes first`() {
        // 400 x 300 is 480 000 bytes; room for two.
        val cache = PreviewPhotoCache(inner, maxBytes = 1_000_000L)
        val ref3 = PhotoRef(3, null, 4000, 3000)
        get(cache, ref1)!!.recycle()
        get(cache, ref2)!!.recycle()
        get(cache, ref1)!!.recycle() // 1 is now the newer
        get(cache, ref3)!!.recycle()
        assertEquals(2, cache.size)
        assertTrue(cache.heldBytes <= 1_000_000L)
        val before = inner.calls.size
        get(cache, ref1)!!.recycle()
        assertEquals("photo 1 was kept", before, inner.calls.size)
        get(cache, ref2)!!.recycle()
        assertEquals("photo 2 was the one dropped", before + 1, inner.calls.size)
    }

    @Test
    fun `a photo bigger than the whole bound is handed over and not kept`() {
        val cache = PreviewPhotoCache(inner, maxBytes = 100_000L)
        val b = get(cache, ref1)!!
        assertEquals(400, b.width)
        assertEquals(0, cache.size)
        assertFalse(b.isRecycled)
    }

    @Test
    fun `clearing frees everything`() {
        val cache = PreviewPhotoCache(inner)
        get(cache, ref1)!!.recycle()
        get(cache, ref2)!!.recycle()
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0L, cache.heldBytes)
        assertTrue("the held bitmaps were recycled", inner.made.isNotEmpty() && inner.made.all { it.isRecycled })
    }
}
