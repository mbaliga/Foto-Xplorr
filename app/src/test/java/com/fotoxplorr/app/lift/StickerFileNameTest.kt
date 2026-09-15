package com.fotoxplorr.app.lift

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The naming rule only -- mirrors [com.fotoxplorr.app.editor.EditRecipeTest]'s coverage of
 * `editedName`, the function [stickerFileName] deliberately copies the shape of.
 */
class StickerFileNameTest {

    @Test
    fun `a sticker is named from the source photo`() {
        assertEquals("DSC_0001-sticker.png", stickerFileName("DSC_0001.jpg"))
        assertEquals("holiday-sticker.png", stickerFileName("holiday.heic"))
    }

    @Test
    fun `re-lifting a sticker does not stack suffixes`() {
        assertEquals("mug-sticker.png", stickerFileName("mug-sticker.png"))
    }

    @Test
    fun `naming copes with the awkward real-world cases`() {
        assertEquals("no-extension-sticker.png", stickerFileName("no-extension"))
        assertEquals("a.b.c-sticker.png", stickerFileName("a.b.c.jpg"))
        assertEquals(".hidden-sticker.png", stickerFileName(".hidden"))
        assertEquals("photo-sticker.png", stickerFileName("   "))
    }
}
