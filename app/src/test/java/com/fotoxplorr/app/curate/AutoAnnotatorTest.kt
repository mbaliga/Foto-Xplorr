package com.fotoxplorr.app.curate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoAnnotatorTest {

    @Test
    fun `fills a genuinely empty caption`() {
        assertEquals(
            "A photo of a dog",
            AutoAnnotator.apply(currentCaption = "", currentIsMachineWritten = false, candidateCaption = "A photo of a dog"),
        )
    }

    @Test
    fun `fills a whitespace-only caption the same as an empty one`() {
        assertEquals(
            "A photo of a dog",
            AutoAnnotator.apply(currentCaption = "   ", currentIsMachineWritten = false, candidateCaption = "A photo of a dog"),
        )
    }

    @Test
    fun `never overwrites a non-blank human-written caption -- the one unrecoverable mistake`() {
        assertNull(
            AutoAnnotator.apply(
                currentCaption = "My cat Whiskers on the porch",
                currentIsMachineWritten = false,
                candidateCaption = "A photo of a cat",
            ),
        )
    }

    @Test
    fun `refreshes a caption this function itself wrote before`() {
        assertEquals(
            "A photo of two dogs",
            AutoAnnotator.apply(
                currentCaption = "A photo of a dog",
                currentIsMachineWritten = true,
                candidateCaption = "A photo of two dogs",
            ),
        )
    }

    @Test
    fun `a blank candidate never writes, even into an empty slot`() {
        assertNull(AutoAnnotator.apply(currentCaption = "", currentIsMachineWritten = false, candidateCaption = ""))
        assertNull(AutoAnnotator.apply(currentCaption = "", currentIsMachineWritten = false, candidateCaption = "   "))
    }

    @Test
    fun `a blank candidate does not clear an existing machine caption either`() {
        assertNull(
            AutoAnnotator.apply(
                currentCaption = "A photo of a dog",
                currentIsMachineWritten = true,
                candidateCaption = "",
            ),
        )
    }

    @Test
    fun `currentIsMachineWritten is ignored when the current caption is blank`() {
        // A stray blank caption that happens to carry a stale machine flag is still fillable --
        // there is no human content to protect either way.
        assertEquals(
            "A photo of a dog",
            AutoAnnotator.apply(currentCaption = "", currentIsMachineWritten = true, candidateCaption = "A photo of a dog"),
        )
    }
}
