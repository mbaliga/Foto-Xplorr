package com.fotoxplorr.app.hyle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The photo stack's model: order, flip, wrap, ruffle and the seeded fan. */
class PhotoDeckTest {

    @Test
    fun `flipping forward moves the top card to the back and wraps`() {
        var deck = PhotoDeck(3)
        assertEquals(listOf(0, 1, 2), deck.visible())
        deck = deck.flipForward()
        assertEquals(listOf(1, 2, 0), deck.visible())
        deck = deck.flipForward().flipForward()
        assertEquals(0, deck.topIndex)
    }

    @Test
    fun `flipping back wraps the other way and undoes a forward flip`() {
        val deck = PhotoDeck(4)
        assertEquals(3, deck.flipBack().topIndex)
        assertEquals(deck, deck.flipForward().flipBack())
    }

    @Test
    fun `a deck of one or none never flips`() {
        assertEquals(PhotoDeck(1), PhotoDeck(1).flipForward())
        assertEquals(PhotoDeck(0), PhotoDeck(0).flipBack())
        assertTrue(PhotoDeck(0).visible().isEmpty())
        assertEquals("", PhotoDeck(0).counter())
    }

    @Test
    fun `many steps wrap in both directions`() {
        val deck = PhotoDeck(5)
        assertEquals(2, deck.flip(7).topIndex)
        assertEquals(3, deck.flip(-7).topIndex)
        assertEquals(deck, deck.flip(0))
    }

    @Test
    fun `visible is capped so a deck of eight draws four`() {
        assertEquals(4, PhotoDeck(8).visible().size)
        assertEquals(2, PhotoDeck(2).visible().size)
        assertTrue(PhotoDeck(8).visible(0).isEmpty())
    }

    @Test
    fun `counter is one based`() {
        assertEquals("1 of 8", PhotoDeck(8).counter())
        assertEquals("8 of 8", PhotoDeck(8).flipBack().counter())
    }

    @Test
    fun `resizing keeps the top card or clamps to the last`() {
        assertEquals(2, PhotoDeck(5, 2).resizedTo(8).topIndex)
        assertEquals(2, PhotoDeck(5, 4).resizedTo(3).topIndex)
        assertEquals(PhotoDeck(0), PhotoDeck(5, 4).resizedTo(0))
    }

    @Test
    fun `fan is the same every time for the same photo and seed`() {
        assertEquals(fanTransform(42L, 7L, false), fanTransform(42L, 7L, false))
        assertNotEquals(fanTransform(42L, 7L, false), fanTransform(43L, 7L, false))
        assertNotEquals(fanTransform(42L, 7L, false), fanTransform(42L, 8L, false))
    }

    @Test
    fun `fan stays within its limits and the top card lies flatter`() {
        for (key in 0L..500L) {
            val under = fanTransform(key, 1L, isTop = false)
            val top = fanTransform(key, 1L, isTop = true)
            assertTrue(kotlin.math.abs(under.rotationDeg) <= MAX_TILT_DEG)
            assertTrue(kotlin.math.abs(under.offsetXDp) <= MAX_NUDGE_DP)
            assertTrue(kotlin.math.abs(under.offsetYDp) <= MAX_NUDGE_DP)
            assertTrue(kotlin.math.abs(top.rotationDeg) <= MAX_TILT_DEG * TOP_FLATNESS + 1e-4f)
        }
        // Not all one way: the fan must actually spread both sides.
        val spins = (0L..50L).map { fanTransform(it, 1L, false).rotationDeg }
        assertTrue(spins.any { it > 1f } && spins.any { it < -1f })
    }

    @Test
    fun `reduce motion lays every card square`() {
        assertEquals(FanTransform(0f, 0f, 0f), fanTransform(9L, 1L, false, instant = true))
    }

    @Test
    fun `ruffle turns a drag into whole flips and carries the rest`() {
        val ruffle = RuffleAccumulator(stepPx = 40f)
        assertEquals(0, ruffle.add(-30f))
        assertEquals(1, ruffle.add(-30f))      // 60 travelled left: one flip, 20 carried
        assertEquals(1, ruffle.add(-20f))      // 40 again
        assertEquals(-2, ruffle.add(90f))      // back to the right: two flips back
    }

    @Test
    fun `ruffle takes the larger axis`() {
        assertEquals(-12f, ruffleDelta(-12f, 5f), 0f)
        assertEquals(-30f, ruffleDelta(4f, -30f), 0f)
        assertEquals(8f, ruffleDelta(8f, 8f), 0f)
    }

    @Test
    fun `the stack shows the most recently processed photos newest first`() {
        val photos = (1..20).toList()
        assertEquals(listOf(12, 11, 10, 9, 8, 7, 6, 5), recentlyProcessed(photos, 12))
        assertEquals(listOf(3, 2, 1), recentlyProcessed(photos, 3))
        // Nothing done yet: the first photos stand in, in order.
        assertEquals((1..8).toList(), recentlyProcessed(photos, 0))
        // Past the end clamps.
        assertEquals(20, recentlyProcessed(photos, 99).first())
        assertTrue(recentlyProcessed(emptyList<Int>(), 5).isEmpty())
    }
}
