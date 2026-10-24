package com.fotoxplorr.app.openwith

import com.fotoxplorr.app.media.MediaAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Reading a MediaStore row falls back from the collection query to the row's own Uri, because
 * only the second is covered by a per-Uri grant an app without standing access was handed.
 */
class MediaStoreRowFallbackTest {

    private fun row(id: Long): MediaAsset = mediaStoreRowAsset(
        rowId = id,
        contentUri = "content://media/external/file/$id",
        displayName = "a.jpg",
        mimeType = "image/jpeg",
        bucketId = 1L,
        bucketName = "Camera",
        relativePath = "DCIM/Camera/",
        isTrashed = false,
    )

    @Test
    fun `an empty collection query falls back to the item Uri`() {
        val fromItem = row(7)
        val result = firstRowOf({ null }, { fromItem })
        assertSame(fromItem, result)
    }

    @Test
    fun `a row found by the collection query never asks the item Uri`() {
        val calls = mutableListOf<String>()
        val first = row(7)
        val result = firstRowOf({ calls += "collection"; first }, { calls += "item"; row(8) })
        assertSame(first, result)
        assertEquals(listOf("collection"), calls)
    }

    @Test
    fun `a query that throws counts as empty and the next one still runs`() {
        val fromItem = row(7)
        val result = firstRowOf({ throw SecurityException("no access") }, { fromItem })
        assertSame(fromItem, result)
    }

    @Test
    fun `when every attempt is empty there is no row`() {
        assertNull(firstRowOf({ null }, { null }))
    }

    @Test
    fun `no row from either query is unverified and the row of the fallback is judged like any other`() {
        assertEquals(OpenRequestLookup.Unverified, lookupUncataloguedRow(firstRowOf({ null }, { null })) { it })
        assertEquals(OpenRequestLookup.Unknown, lookupUncataloguedRow(firstRowOf({ null }, { row(7) })) { it })
        assertEquals(OpenRequestLookup.Hidden, lookupUncataloguedRow(firstRowOf({ null }, { row(7) })) { emptyList() })
    }
}
