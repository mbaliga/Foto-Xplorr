package com.fotoxplorr.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors `com.fotoxplorr.app.editor.EditRecipeTest`'s `editedName` coverage — same shape, same
 *  real-world cases, for the video counterpart of the same naming convention. */
class ConvertedNameTest {

    @Test
    fun `a converted copy is named from the source`() {
        assertEquals("clip-converted.mp4", convertedName("clip.mov"))
        assertEquals("holiday-converted.mp4", convertedName("holiday.webm"))
    }

    @Test
    fun `re-converting a conversion does not stack suffixes`() {
        assertEquals("clip-converted.mp4", convertedName("clip-converted.mp4"))
        assertEquals("clip-converted.mp4", convertedName(convertedName(convertedName("clip.mov"))))
    }

    @Test
    fun `naming copes with the awkward real-world cases`() {
        assertEquals("no-extension-converted.mp4", convertedName("no-extension"))
        assertEquals("a.b.c-converted.mp4", convertedName("a.b.c.mov"))
        assertEquals(".hidden-converted.mp4", convertedName(".hidden"))
        assertEquals("video-converted.mp4", convertedName("   "))
    }
}
