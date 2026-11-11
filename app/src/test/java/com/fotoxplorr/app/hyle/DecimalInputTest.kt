package com.fotoxplorr.app.hyle

import org.junit.Assert.assertEquals
import org.junit.Test

class DecimalInputTest {
    @Test fun `a comma is a decimal point`() {
        assertEquals("17.4435", normalizeDecimalInput("17,4435"))
        assertEquals(17.4435, normalizeDecimalInput("17,4435").toDouble(), 0.0)
    }

    @Test fun `non-ASCII digits and the typographic minus are read`() {
        assertEquals("-12.5", normalizeDecimalInput("−12.5"))
        assertEquals("123", normalizeDecimalInput("١٢٣"))
        assertEquals("45.6", normalizeDecimalInput("４５.６"))
    }

    @Test fun `ordinary text is left alone`() {
        assertEquals("abc-1.5", normalizeDecimalInput("abc-1.5"))
        assertEquals("", normalizeDecimalInput(""))
    }
}
