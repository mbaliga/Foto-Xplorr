package com.fotoxplorr.app

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.share.PreparedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P0-04 item 7: share MIME type is derived from what [com.fotoxplorr.app.share.SharePreparer]
 * actually produced, not the original assets or a render flag -- see [FotoXplorrActivity.shareWith].
 */
class FotoXplorrActivityShareTest {

    @Test
    fun `commonShareType narrows to image or video only when every prepared item agrees, else falls back to wildcard`() {
        assertEquals("image/*", commonShareType(listOf("image/jpeg", "image/png")))
        assertEquals("video/*", commonShareType(listOf("video/mp4", "video/mp4")))
        assertEquals("*/*", commonShareType(listOf("image/jpeg", "video/mp4")))
    }

    private fun asset(name: String) = MediaAsset(
        id = MediaId(1L),
        contentUriString = "content://media/external/file/1",
        displayName = name,
        mimeType = "image/jpeg",
        bucketName = null,
        dateTakenMillis = 0L,
        dateModifiedSeconds = 0L,
        width = 0,
        height = 0,
        sizeBytes = 0L,
        relativePath = null,
        isFavorite = false,
        isTrashed = false,
    )

    @Test
    fun `unpreparedShareItemsMessage is null when nothing failed`() {
        assertNull(unpreparedShareItemsMessage(emptyList()))
    }

    @Test
    fun `unpreparedShareItemsMessage names the one failure by name`() {
        val message = unpreparedShareItemsMessage(listOf(PreparedItem.Failed(asset("beach.jpg"), "reason")))
        assertEquals("\"beach.jpg\" couldn't be prepared without its location and wasn't shared.", message)
    }

    @Test
    fun `unpreparedShareItemsMessage names the first failure and counts the rest`() {
        val failed = listOf(
            PreparedItem.Failed(asset("beach.jpg"), "reason"),
            PreparedItem.Failed(asset("dog.jpg"), "reason"),
            PreparedItem.Failed(asset("cat.jpg"), "reason"),
        )
        val message = unpreparedShareItemsMessage(failed)
        assertEquals(
            "\"beach.jpg\" and 2 more couldn't be prepared without their location and weren't shared.",
            message,
        )
    }
}
