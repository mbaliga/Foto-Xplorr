package com.fotoxplorr.app.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MetadataEdit] -- pure, no Android in sight.
 *
 * - [MetadataEdit.isEmpty], field by field: the check both [MetadataWriter.write] (skip opening
 *   the file at all) and [applyMetadataEdit] (skip touching bytes) rely on to make a no-op edit
 *   genuinely free rather than merely harmless.
 * - [MetadataEdit.merge] (P0-08 item 6): how two edits queued against the same photo before the
 *   first has been written combine.
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

    @Test
    fun `a later edit's non-null fields win over an earlier one's`() {
        val earlier = MetadataEdit(caption = "old caption", creator = "old creator")
        val later = MetadataEdit(caption = "new caption")

        val merged = earlier.merge(later)

        assertEquals("new caption", merged.caption)
        assertEquals("old creator", merged.creator)
    }

    @Test
    fun `a field the later edit left null keeps the earlier edit's value, not null`() {
        val earlier = MetadataEdit(rating = 3)
        val later = MetadataEdit(copyright = "new copyright")

        val merged = earlier.merge(later)

        assertEquals(3, merged.rating)
        assertEquals("new copyright", merged.copyright)
    }

    @Test
    fun `keywordsToAdd unions rather than one side replacing the other`() {
        val earlier = MetadataEdit(keywordsToAdd = listOf("beach", "sunset"))
        val later = MetadataEdit(keywordsToAdd = listOf("sunset", "family"))

        val merged = earlier.merge(later)

        assertEquals(listOf("beach", "sunset", "family"), merged.keywordsToAdd)
    }

    @Test
    fun `a later setLocation overrides an earlier one`() {
        val earlier = MetadataEdit(setLocation = GpsCoordinate(1.0, 2.0))
        val later = MetadataEdit(setLocation = GpsCoordinate(3.0, 4.0))

        val merged = earlier.merge(later)

        assertEquals(GpsCoordinate(3.0, 4.0), merged.setLocation)
    }

    @Test
    fun `a later clearLocation overrides an earlier setLocation`() {
        val earlier = MetadataEdit(setLocation = GpsCoordinate(1.0, 2.0))
        val later = MetadataEdit(clearLocation = true)

        val merged = earlier.merge(later)

        assertTrue(merged.clearLocation)
        assertEquals(null, merged.setLocation)
    }

    @Test
    fun `a later setLocation overrides an earlier clearLocation`() {
        val earlier = MetadataEdit(clearLocation = true)
        val later = MetadataEdit(setLocation = GpsCoordinate(1.0, 2.0))

        val merged = earlier.merge(later)

        assertFalse(merged.clearLocation)
        assertEquals(GpsCoordinate(1.0, 2.0), merged.setLocation)
    }

    @Test
    fun `a later edit that never touched location leaves the earlier edit's location intent alone`() {
        val earlier = MetadataEdit(clearLocation = true)
        val later = MetadataEdit(caption = "new caption")

        val merged = earlier.merge(later)

        assertTrue("the earlier edit's clearLocation must survive an unrelated later edit", merged.clearLocation)
        assertEquals("new caption", merged.caption)
    }

    @Test
    fun `merging two entirely unrelated edits keeps every field from both`() {
        val earlier = MetadataEdit(caption = "caption", keywordsToAdd = listOf("a"))
        val later = MetadataEdit(creator = "creator", rating = 5)

        val merged = earlier.merge(later)

        assertEquals(MetadataEdit(caption = "caption", creator = "creator", rating = 5, keywordsToAdd = listOf("a")), merged)
    }

    @Test
    fun `merging with an empty later edit is a no-op`() {
        val earlier = MetadataEdit(caption = "caption", rating = 4, keywordsToAdd = listOf("a"))

        val merged = earlier.merge(MetadataEdit())

        assertEquals(earlier, merged)
    }

    @Test
    fun `keywordsToRemove unions across two edits`() {
        val earlier = MetadataEdit(keywordsToRemove = listOf("beach", "sunset"))
        val later = MetadataEdit(keywordsToRemove = listOf("sunset", "family"))

        val merged = earlier.merge(later)

        assertEquals(listOf("beach", "sunset", "family"), merged.keywordsToRemove)
        assertEquals(emptyList<String>(), merged.keywordsToAdd)
    }

    @Test
    fun `a later removal of a keyword an earlier edit added cancels that add and still removes it`() {
        val earlier = MetadataEdit(keywordsToAdd = listOf("beach", "sunset"))
        val later = MetadataEdit(keywordsToRemove = listOf("sunset"))

        val merged = earlier.merge(later)

        assertEquals(listOf("beach"), merged.keywordsToAdd)
        assertEquals(listOf("sunset"), merged.keywordsToRemove)
    }

    @Test
    fun `a later add of a keyword an earlier edit removed cancels that removal and adds it`() {
        val earlier = MetadataEdit(keywordsToRemove = listOf("beach", "sunset"))
        val later = MetadataEdit(keywordsToAdd = listOf("sunset"))

        val merged = earlier.merge(later)

        assertEquals(listOf("sunset"), merged.keywordsToAdd)
        assertEquals(listOf("beach"), merged.keywordsToRemove)
    }
}
