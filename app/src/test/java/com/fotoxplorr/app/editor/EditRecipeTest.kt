package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor is non-destructive by construction, so the recipe — not the pixels — is where its
 * correctness lives. These pin the parts that are pure: identity detection (which is what stops
 * a no-op "save" writing a needless second copy of a photo), crop geometry, and the naming of the
 * copy.
 *
 * The colour matrix is not covered here at all -- see the note further down for why it cannot be,
 * rather than a test that pretends otherwise.
 */
class EditRecipeTest {

    @Test
    fun `a fresh recipe is the identity`() {
        assertTrue(EditRecipe().isIdentity)
    }

    @Test
    fun `any single adjustment breaks the identity`() {
        assertFalse(EditRecipe(quarterTurns = 1).isIdentity)
        assertFalse(EditRecipe(flipHorizontal = true).isIdentity)
        // Colour now lives in Adjustments, which replaced four fields and a ColorMatrix here.
        // The recipe must still notice a colour edit, or Save would stay disabled after one.
        assertFalse(EditRecipe(adjustments = Adjustments(exposure = 0.1f)).isIdentity)
        assertFalse(EditRecipe(adjustments = Adjustments(contrast = -0.1f)).isIdentity)
        assertFalse(EditRecipe(adjustments = Adjustments(saturation = 0.5f)).isIdentity)
        assertFalse(EditRecipe(adjustments = Adjustments(temperature = -0.3f)).isIdentity)
        assertFalse(EditRecipe(adjustments = Adjustments(sharpen = 0.4f)).isIdentity)
        assertFalse(EditRecipe(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f)).isIdentity)
        // straightenDegrees follows the same "0 is neutral" rule as everything in Adjustments --
        // a fresh recipe from before this field existed decodes with 0f and must stay identical.
        assertFalse(EditRecipe(straightenDegrees = 0.5f).isIdentity)
        assertFalse(EditRecipe(straightenDegrees = -0.5f).isIdentity)
    }

    @Test
    fun `a recipe with straightenDegrees at exactly zero is still the identity`() {
        assertTrue(EditRecipe(straightenDegrees = 0f).isIdentity)
    }

    @Test
    fun `four quarter turns is back to the identity`() {
        // Otherwise "rotate four times" would offer to save a copy identical to the original.
        val spun = EditRecipe()
            .rotatedClockwise().rotatedClockwise().rotatedClockwise().rotatedClockwise()
        assertEquals(0, spun.quarterTurns)
        assertTrue(spun.isIdentity)
    }

    @Test
    fun `rotation wraps rather than growing without bound`() {
        // The fourth turn returns to zero, it does not become 4 -- otherwise quarterTurns would
        // climb forever and isIdentity would stop recognising a photo that is back where it began.
        assertEquals(0, EditRecipe(quarterTurns = 3).rotatedClockwise().quarterTurns)
        assertEquals(1, EditRecipe(quarterTurns = 0).rotatedClockwise().quarterTurns)
    }

    @Test
    fun `a crop must have positive area`() {
        // Guards createBitmap, which throws on a zero-width rect -- reachable from a slider.
        var threw = false
        try {
            CropRect(0.5f, 0f, 0.5f, 1f)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a zero-width crop must be rejected", threw)
    }

    @Test
    fun `a crop must stay inside the image`() {
        var threw = false
        try {
            CropRect(-0.1f, 0f, 1f, 1f)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("an out-of-bounds crop must be rejected", threw)
    }

    @Test
    fun `fitting a square preset to a square image fills it`() {
        val fitted = CropRect.FULL.fitTo(aspect = 1f, imageAspect = 1f)
        assertEquals(0f, fitted.left, 1e-4f)
        assertEquals(1f, fitted.right, 1e-4f)
        assertEquals(0f, fitted.top, 1e-4f)
        assertEquals(1f, fitted.bottom, 1e-4f)
    }

    @Test
    fun `fitting a square preset to a landscape image pillarboxes and stays centred`() {
        // A 2:1 image cropped square keeps its full height and half its width, centred.
        val fitted = CropRect.FULL.fitTo(aspect = 1f, imageAspect = 2f)
        assertEquals(1f, fitted.height, 1e-4f)
        assertEquals(0.5f, fitted.width, 1e-4f)
        assertEquals(0.5f, (fitted.left + fitted.right) / 2f, 1e-4f)
    }

    @Test
    fun `a preset never grows beyond the crop it is applied to`() {
        // Applying an aspect preset must not silently undo a crop the user already made.
        val existing = CropRect(0.25f, 0.25f, 0.75f, 0.75f)
        val fitted = existing.fitTo(aspect = 16f / 9f, imageAspect = 1f)
        assertTrue(fitted.width <= existing.width + 1e-4f)
        assertTrue(fitted.height <= existing.height + 1e-4f)
    }

    // NOT tested here: EditRecipe.toColorMatrix(). android.graphics.ColorMatrix is a framework
    // class whose methods throw "not mocked" under plain JVM unit tests, so any assertion about
    // it would need Robolectric or an instrumented run -- neither of which this module has. An
    // earlier version of this file asserted on it anyway and failed for exactly that reason.
    // Rather than fake the coverage, the matrix is left to the device checklist: its coefficients
    // are a look, and a look is judged by eye.

    @Test
    fun `an edited copy is named from the original`() {
        assertEquals("DSC_0001-edited.jpg", editedName("DSC_0001.jpg"))
        assertEquals("holiday-edited.jpg", editedName("holiday.png"))
    }

    @Test
    fun `re-editing an edit does not stack suffixes`() {
        // The bug this guards: shot-edited-edited-edited-edited.jpg after four passes.
        assertEquals("shot-edited.jpg", editedName("shot-edited.jpg"))
        assertEquals("shot-edited.jpg", editedName(editedName(editedName("shot.jpg"))))
    }

    @Test
    fun `naming copes with the awkward real-world cases`() {
        assertEquals("no-extension-edited.jpg", editedName("no-extension"))
        assertEquals("a.b.c-edited.jpg", editedName("a.b.c.jpg"))
        // A leading dot is a hidden file, not an extension: the stem must survive.
        assertEquals(".hidden-edited.jpg", editedName(".hidden"))
        assertEquals("photo-edited.jpg", editedName("   "))
    }
}
