package com.fotoxplorr.app.metadata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MetadataEdit.isEmpty], field by field -- the check both [MetadataWriter.write] (skip opening
 * the file at all) and [applyMetadataEdit] (skip touching bytes) rely on to make a no-op edit
 * genuinely free rather than merely harmless.
 */
class MetadataEditTest {

    @Test
    fun `the default edit is empty`() {
        assertTrue(MetadataEdit().isEmpty)
    }

    @Test
    fun `any single field being set makes the edit non-empty`() {
        assertFalse(MetadataEdit(caption = "x").isEmpty)
        assertFalse(MetadataEdit(caption = "").isEmpty) // blank still means "clear this field"
        assertFalse(MetadataEdit(creator = "x").isEmpty)
        assertFalse(MetadataEdit(copyright = "x").isEmpty)
        assertFalse(MetadataEdit(rating = 0).isEmpty) // 0 still means "clear the rating"
        assertFalse(MetadataEdit(rating = 4).isEmpty)
        assertFalse(MetadataEdit(keywordsToAdd = listOf("a")).isEmpty)
        assertFalse(MetadataEdit(keywordsToRemove = listOf("a")).isEmpty)
        assertFalse(MetadataEdit(setLocation = GpsCoordinate(1.0, 2.0)).isEmpty)
        assertFalse(MetadataEdit(clearLocation = true).isEmpty)
    }

    @Test
    fun `an explicit empty list for keywords is still an empty edit`() {
        assertTrue(MetadataEdit(keywordsToAdd = emptyList(), keywordsToRemove = emptyList()).isEmpty)
    }
}
