package com.fotoxplorr.app.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MetadataEdit.merge] (P0-08 item 6): pure, no Android in sight. */
class MetadataEditTest {

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
}
