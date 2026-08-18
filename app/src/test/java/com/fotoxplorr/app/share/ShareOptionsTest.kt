package com.fotoxplorr.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShareOptions.requiresRender] decides whether a share streams bytes or decodes, draws and
 * re-encodes a bitmap. Getting it wrong is expensive in one direction (needlessly decoding a
 * 48-megapixel original to change nothing) and wrong in the other (claiming a frame was applied
 * to a file that was copied untouched), so it is worth pinning.
 *
 * The frame *drawing* itself is not covered here: it is android.graphics, which throws
 * "not mocked" under plain JVM unit tests, and a look is judged by eye in any case. What is
 * testable is the decision logic around it.
 */
class ShareOptionsTest {

    @Test
    fun `the default is private and cheap`() {
        val defaults = ShareOptions()
        // Stripping is the DEFAULT, on owner direction: the safe thing has to be what happens
        // when nobody thinks about it.
        assertTrue("metadata stripping must default to on", defaults.stripMetadata)
        assertEquals(ShareFrame.NONE, defaults.frame)
        assertFalse(defaults.watermark)
        // And with no frame and no watermark there is nothing to draw, so a plain share must not
        // pay for a decode-encode round trip.
        assertFalse("a default share must not force a re-render", defaults.requiresRender)
    }

    @Test
    fun `stripping metadata alone does not force a re-render`() {
        // EXIF is edited in place on the copied file; it needs no bitmap at all.
        assertFalse(ShareOptions(stripMetadata = true).requiresRender)
        assertFalse(ShareOptions(stripMetadata = false).requiresRender)
    }

    @Test
    fun `any frame forces a re-render`() {
        assertTrue(ShareOptions(frame = ShareFrame.POLAROID).requiresRender)
        assertTrue(ShareOptions(frame = ShareFrame.STAMP).requiresRender)
    }

    @Test
    fun `a watermark forces a re-render even with no frame`() {
        // The regression this guards: treating "no frame" as "nothing to draw" would silently
        // drop the watermark while reporting success.
        assertTrue(ShareOptions(frame = ShareFrame.NONE, watermark = true).requiresRender)
    }

    @Test
    fun `every frame carries a label and a description for the sheet`() {
        // The sheet renders both; a blank one would show an unlabelled chip.
        ShareFrame.entries.forEach { frame ->
            assertTrue("${frame.name} needs a label", frame.label.isNotBlank())
            assertTrue("${frame.name} needs a description", frame.description.isNotBlank())
        }
    }

    @Test
    fun `caption and seal are optional and independent of the frame choice`() {
        // Both are carried on every ShareOptions regardless of frame -- the renderer ignores the
        // one that does not apply. Switching frames back and forth must not lose what was typed.
        val options = ShareOptions(frame = ShareFrame.POLAROID, caption = "Lisbon", seal = "MB")
        assertEquals("Lisbon", options.copy(frame = ShareFrame.STAMP).caption)
        assertEquals("MB", options.copy(frame = ShareFrame.POLAROID).seal)
    }
}
