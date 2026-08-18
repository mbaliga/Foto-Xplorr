package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar's grid arithmetic and the album stack's fan angles -- the two pieces of this round
 * that are pure and therefore actually checkable without a device.
 *
 * The grid maths is worth pinning because it is the kind that looks right on the month you happen
 * to test by hand and is wrong on February, or on a month starting on a Sunday, or in a leap year.
 */
class CalendarLayoutTest {

    @Test
    fun `a month grid is always whole weeks`() {
        // A ragged final row is the giveaway that the trailing fill is wrong.
        for (month in 0..11) {
            val cells = monthCells(YearMonth(2026, month), emptyMap())
            assertEquals("month $month", 0, cells.size % 7)
        }
    }

    @Test
    fun `every day of the month appears exactly once`() {
        // February 2026 has 28 days; 2024 was a leap year and has 29.
        val february2026 = monthCells(YearMonth(2026, 1), emptyMap()).filter { it.inMonth }
        assertEquals(28, february2026.size)
        assertEquals((1..28).toList(), february2026.map { it.dayOfMonth })

        val february2024 = monthCells(YearMonth(2024, 1), emptyMap()).filter { it.inMonth }
        assertEquals(29, february2024.size)
    }

    @Test
    fun `leading cells come from the previous month and are marked out of month`() {
        val cells = monthCells(YearMonth(2026, 3), emptyMap())
        val leading = cells.takeWhile { !it.inMonth }
        leading.forEach { assertTrue("leading days must not be in-month", !it.inMonth) }
        // They must be the END of the previous month, running up to its last day -- not 1, 2, 3.
        if (leading.isNotEmpty()) {
            val ascending = leading.map { it.dayOfMonth }
            assertEquals(ascending.sorted(), ascending)
            // March follows February, so the last leading day is February's last.
            assertTrue("leading days should be late in the month", leading.last().dayOfMonth >= 27)
        }
    }

    @Test
    fun `the first in-month cell is day one`() {
        for (month in 0..11) {
            val cells = monthCells(YearMonth(2026, month), emptyMap())
            assertEquals("month $month", 1, cells.first { it.inMonth }.dayOfMonth)
        }
    }

    @Test
    fun `cell keys are unique so the lazy grid cannot collide`() {
        // Duplicate keys in a LazyVerticalGrid throw at runtime; leading, in-month and trailing
        // cells can all carry the same day number, so their keys must be namespaced.
        val cells = monthCells(YearMonth(2026, 4), emptyMap())
        assertEquals(cells.size, cells.map { it.key }.toSet().size)
    }

    @Test
    fun `photos land on their own day`() {
        val byDay = mapOf(7 to listOf<com.fotoxplorr.app.media.MediaAsset>())
        val cells = monthCells(YearMonth(2026, 0), byDay)
        val seventh = cells.first { it.inMonth && it.dayOfMonth == 7 }
        assertEquals(0, seventh.assets.size)
        // And a day with no entry is empty rather than null-ish.
        assertTrue(cells.first { it.inMonth && it.dayOfMonth == 8 }.assets.isEmpty())
    }

    // ---- album stack ----

    @Test
    fun `the top of a stack never leans`() {
        // The front photo is the one being read; tilting it would make every album look crooked.
        assertEquals(0f, stackRotation("Camera", 0), 1e-5f)
        assertEquals(0f, stackRotation("anything", 0), 1e-5f)
    }

    @Test
    fun `a stacks lean is stable for the same album`() {
        // The regression this guards: a random angle re-rolling on every recomposition, which
        // makes the whole album grid twitch while scrolling.
        repeat(20) {
            assertEquals(stackRotation("DCIM/Camera", 1), stackRotation("DCIM/Camera", 1), 1e-6f)
            assertEquals(stackRotation("DCIM/Camera", 2), stackRotation("DCIM/Camera", 2), 1e-6f)
        }
    }

    @Test
    fun `different albums fan differently`() {
        // Otherwise every album on screen leans the same way and the effect reads as a skew bug.
        val angles = listOf("Camera", "Screenshots", "Downloads", "WhatsApp", "Pins")
            .map { stackRotation(it, 1) }
        assertTrue("expected variety, got $angles", angles.toSet().size > 1)
    }

    @Test
    fun `lean stays within a sane range`() {
        // A stack is a hint of depth, not a fan of cards thrown on a table.
        listOf("Camera", "Screenshots", "Downloads", "a", "", "0").forEach { key ->
            for (depth in 1..2) {
                val angle = kotlin.math.abs(stackRotation(key, depth))
                assertTrue("$key@$depth = $angle", angle in 1f..24f)
            }
        }
    }

    @Test
    fun `deeper layers lean further than the one above`() {
        listOf("Camera", "Screenshots", "Downloads").forEach { key ->
            val first = kotlin.math.abs(stackRotation(key, 1))
            val second = kotlin.math.abs(stackRotation(key, 2))
            assertTrue("$key: $first then $second", second > first)
            assertNotEquals(first, second)
        }
    }
}
