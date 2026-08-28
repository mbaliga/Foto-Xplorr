package com.fotoxplorr.app.lens

import org.junit.Assert.assertEquals
import org.junit.Test

class LensSearchQueryTest {

    @Test
    fun `collapses newlines and repeated whitespace to single spaces`() {
        assertEquals("PLATFORM 9 THIS WAY", LensSearchQuery.buildQuery("PLATFORM 9\n\nTHIS   WAY"))
    }

    @Test
    fun `trims leading and trailing whitespace`() {
        assertEquals("hello", LensSearchQuery.buildQuery("   hello  \n"))
    }

    @Test
    fun `blank text collapses to an empty query`() {
        assertEquals("", LensSearchQuery.buildQuery("   \n\t  "))
    }

    @Test
    fun `short text passes through unchanged once collapsed`() {
        val text = "Open 9am - 5pm"
        assertEquals(text, LensSearchQuery.buildQuery(text))
    }

    @Test
    fun `caps text longer than the query limit at 80 characters`() {
        val long = "x".repeat(120)
        val query = LensSearchQuery.buildQuery(long)
        assertEquals(80, query.length)
        assertEquals("x".repeat(80), query)
    }

    @Test
    fun `trims a trailing space left behind by the cap`() {
        // The 80th character lands exactly on the space between the two runs, so the cap
        // leaves a trailing space that trimEnd() must remove.
        val text = "a".repeat(79) + " " + "b".repeat(20)
        assertEquals("a".repeat(79), LensSearchQuery.buildQuery(text))
    }

    @Test
    fun `a receipt-shaped multi-line block collapses to one bounded line`() {
        val receipt = listOf(
            "CORNER STORE",
            "123 Main St",
            "Milk .......... 3.49",
            "Bread ......... 2.99",
            "Eggs .......... 4.29",
            "TOTAL ......... 10.77",
            "Thank you for shopping",
        ).joinToString("\n")
        val query = LensSearchQuery.buildQuery(receipt)
        assertEquals(-1, query.indexOf('\n'))
        assertEquals(true, query.length <= 80)
    }
}
