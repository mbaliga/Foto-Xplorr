package com.fotoxplorr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shuffled slideshows must never serve the photo already on screen — a "random" step that lands
 * where it started reads as the slideshow having stopped.
 *
 * [randomOtherIndex] draws from the other size-1 positions and steps over the current one rather
 * than re-rolling until it misses, which is what makes these properties provable instead of
 * merely probable: a retry loop is unbounded in the worst case and at size 2 would spin on the
 * one index it must not pick about half the time.
 */
class ShuffleIndexTest {

    @Test
    fun `never returns the current index`() {
        for (size in 2..12) {
            for (current in 0 until size) {
                repeat(200) {
                    assertNotEquals(
                        "size=$size current=$current",
                        current,
                        randomOtherIndex(size, current),
                    )
                }
            }
        }
    }

    @Test
    fun `always returns an index that exists`() {
        for (size in 2..12) {
            for (current in 0 until size) {
                repeat(200) {
                    val drawn = randomOtherIndex(size, current)
                    assertTrue("drawn=$drawn size=$size", drawn in 0 until size)
                }
            }
        }
    }

    @Test
    fun `at size two it always returns the other one`() {
        // The case a naive retry loop handles worst.
        repeat(50) {
            assertEquals(1, randomOtherIndex(2, 0))
            assertEquals(0, randomOtherIndex(2, 1))
        }
    }

    @Test
    fun `every other index is reachable`() {
        // A shuffle that can only ever reach half the library is not a shuffle. With 6 items and
        // 600 draws, missing a reachable index is vanishingly unlikely unless the maths is wrong.
        val size = 6
        val current = 3
        val seen = mutableSetOf<Int>()
        repeat(600) { seen += randomOtherIndex(size, current) }
        assertEquals((0 until size).toSet() - current, seen)
    }

    @Test
    fun `a single-item library degrades safely`() {
        assertEquals(0, randomOtherIndex(1, 0))
        assertEquals(0, randomOtherIndex(0, 0))
    }
}
