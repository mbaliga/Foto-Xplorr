package com.fotoxplorr.app.viewer.deepzoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileCacheTest {
    private fun k(i: Int) = TileKey(1, i, 0)
    private fun cache(budget: Long) = TileCache<Long>(budget) { it }

    @Test
    fun `evicts least recently used first when over budget`() {
        val c = cache(100)
        c.put(k(0), 40); c.put(k(1), 40)
        c.get(k(0)) // touch: k(1) is now the oldest
        c.put(k(2), 40)
        assertTrue(c.contains(k(0)))
        assertFalse(c.contains(k(1)))
        assertTrue(c.contains(k(2)))
        assertEquals(80L, c.bytes)
    }

    @Test
    fun `replacing a key does not double count`() {
        val c = cache(100)
        c.put(k(0), 60); c.put(k(0), 30)
        assertEquals(30L, c.bytes)
        assertEquals(1, c.count)
    }

    @Test
    fun `a value bigger than the whole budget is refused and evicts nothing`() {
        val c = cache(100)
        c.put(k(0), 50)
        c.put(k(1), 101)
        assertNull(c[k(1)])
        assertTrue(c.contains(k(0)))
        assertEquals(50L, c.bytes)
    }

    @Test
    fun `clear empties and zeroes the bytes`() {
        val c = cache(100)
        c.put(k(0), 10); c.put(k(1), 10)
        c.clear()
        assertEquals(0, c.count)
        assertEquals(0L, c.bytes)
    }

    @Test
    fun `remove frees its bytes`() {
        val c = cache(100)
        c.put(k(0), 10); c.put(k(1), 20)
        c.remove(k(0))
        assertEquals(20L, c.bytes)
    }
}
