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
    fun `the default is private and marked, on the free tier`() {
        val defaults = ShareOptions()
        // Stripping is the DEFAULT, on owner direction: the safe thing has to be what happens
        // when nobody thinks about it.
        assertTrue("metadata stripping must default to on", defaults.stripMetadata)
        assertEquals(ShareFrame.NONE, defaults.frame)
        // The watermark flipped to on-by-default (owner, 2026-08-21): the free tier's whole shape
        // is that a share carries the mark unless Pro removed it, so the raw field has to start
        // true or a caller that forgets to resolve it against Pro status would silently ship a
        // clean, unmarked free share.
        assertTrue("watermark must default to on", defaults.watermark)
        assertTrue("a default share now has something to draw", defaults.requiresRender)
    }

    @Test
    fun `stripping metadata alone does not force a re-render`() {
        // EXIF is edited in place on the copied file; it needs no bitmap at all. Watermark must
        // be turned off explicitly here, or the new default would force a render regardless and
        // this test would stop isolating what it claims to isolate.
        assertFalse(ShareOptions(stripMetadata = true, watermark = false).requiresRender)
        assertFalse(ShareOptions(stripMetadata = false, watermark = false).requiresRender)
    }

    @Test
    fun `any frame forces a re-render`() {
        assertTrue(ShareOptions(frame = ShareFrame.POLAROID, watermark = false).requiresRender)
        assertTrue(ShareOptions(frame = ShareFrame.STAMP, watermark = false).requiresRender)
    }

    @Test
    fun `a watermark forces a re-render even with no frame`() {
        // The regression this guards: treating "no frame" as "nothing to draw" would silently
        // drop the watermark while reporting success.
        assertTrue(ShareOptions(frame = ShareFrame.NONE, watermark = true).requiresRender)
    }

    // ---- Pro resolution: ShareOptions.resolveWatermark / resolvedFor ----
    //
    // This is the actual gate the free tier depends on, so it is worth pinning independently of
    // the sheet UI (which merely locks a switch) and of SharePreparer (which is android.graphics
    // and cannot run on the plain JVM). See SharePreparer's own KDoc for why it calls resolvedFor
    // rather than trusting ShareOptions.watermark as handed in.

    @Test
    fun `a non-Pro sharer gets the watermark regardless of the raw flag`() {
        // Not just "true stays true" -- the whole point is that a non-Pro caller cannot opt out
        // by passing watermark = false, because nothing in the UI is wired to let them.
        assertTrue(ShareOptions(watermark = true).resolveWatermark(isPro = false))
        assertTrue(
            "a non-Pro sharer must be watermarked even if watermark=false reached this call " +
                "(defence against a bad caller, not an expected input)",
            ShareOptions(watermark = false).resolveWatermark(isPro = false),
        )
    }

    @Test
    fun `a Pro sharer never gets the watermark, regardless of the raw flag`() {
        assertFalse(ShareOptions(watermark = true).resolveWatermark(isPro = true))
        assertFalse(ShareOptions(watermark = false).resolveWatermark(isPro = true))
    }

    @Test
    fun `resolvedFor carries the resolved watermark and leaves everything else untouched`() {
        val requested = ShareOptions(
            frame = ShareFrame.STAMP,
            stripMetadata = false,
            watermark = true,
            seal = "MB",
        )

        val forFreeUser = requested.resolvedFor(isPro = false)
        assertTrue(forFreeUser.watermark)
        assertEquals(ShareFrame.STAMP, forFreeUser.frame)
        assertEquals("MB", forFreeUser.seal)
        assertFalse(forFreeUser.stripMetadata)

        val forProUser = requested.resolvedFor(isPro = true)
        assertFalse(forProUser.watermark)
        assertEquals(ShareFrame.STAMP, forProUser.frame)
        assertEquals("MB", forProUser.seal)
    }

    @Test
    fun `a Pro share with no frame requires no render, so it takes the cheap copy path`() {
        // The regression this guards: resolving the watermark LATE (after requiresRender has
        // already been read from the raw, pre-entitlement options) would charge a Pro user's
        // plain share for a decode-draw-encode cycle it does not need. Resolving first, via
        // resolvedFor, is what SharePreparer actually does.
        val resolved = ShareOptions(frame = ShareFrame.NONE, watermark = true).resolvedFor(isPro = true)
        assertFalse("a Pro share with no frame must not force a render", resolved.requiresRender)
    }

    @Test
    fun `a non-Pro share with no frame still requires a render, for the watermark`() {
        val resolved = ShareOptions(frame = ShareFrame.NONE, watermark = true).resolvedFor(isPro = false)
        assertTrue(resolved.requiresRender)
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
