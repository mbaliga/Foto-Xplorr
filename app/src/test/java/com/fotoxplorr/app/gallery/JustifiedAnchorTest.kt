package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import com.fotoxplorr.core.organize.GridIndexMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Justified rows keep the photo at the top across a rung change and across the switch to and from
 * the square grid. A row index and an item index mean different photos, so neither may be kept as
 * a bare number (the pinch regression [LayoutAnchor] exists to prevent).
 */
class JustifiedAnchorTest {

    // Mixed shapes so rows hold different numbers of photos at different row heights.
    private fun asset(i: Int) = MediaAsset(
        id = MediaId(1000L + i), contentUriString = "content://m/$i", displayName = "$i.jpg",
        mimeType = "image/jpeg", bucketName = null, dateTakenMillis = 0, dateModifiedSeconds = 0,
        width = if (i % 3 == 0) 2000 else 3000, height = if (i % 3 == 0) 3000 else 2000,
        sizeBytes = 1, relativePath = null, isFavorite = false, isTrashed = false,
    )

    private val assets = List(9000) { asset(it) }
    private val width = 1080f
    private fun rows(columns: Int) =
        JustifiedLayout.build(assets.size, { assets[it].aspectRatio }, width, width / columns, 2f)
    private val square = GridIndexMap(listOf(assets.size), hasHeaders = false, trailingFooter = true)

    private fun LayoutAnchor.drawSquare(firstVisible: Int, list: List<MediaAsset> = assets, map: GridIndexMap = square) =
        settle(map, false, TimelineGrouping.MONTH, sideways = false, firstVisible = firstVisible, assets = list)

    @Test
    fun `a rung change keeps the photo heading the top row`() {
        val three = rows(3)
        val six = rows(6)
        val anchor = LayoutAnchor()
        anchor.settleJustified(three, assets, firstVisible = 0)
        val topRow = 1500
        val photo = three.rowItems(topRow).first
        val target = anchor.settleJustified(six, assets, firstVisible = topRow)!!
        assertTrue("row $target holds photo $photo", photo in six.rowItems(target))
    }

    @Test
    fun `the same rows drawn again need no anchor`() {
        val three = rows(3)
        val anchor = LayoutAnchor()
        anchor.settleJustified(three, assets, firstVisible = 0)
        assertNull(anchor.settleJustified(three, assets, firstVisible = 40))
    }

    @Test
    fun `leaving the rows for the square grid lands on the row's photo, not on the row number`() {
        val three = rows(3)
        val anchor = LayoutAnchor()
        anchor.settleJustified(three, assets, firstVisible = 0)
        val topRow = 1500
        val target = anchor.drawSquare(firstVisible = topRow)
        assertEquals(square.gridIndexOf(three.rowItems(topRow).first), target)
    }

    @Test
    fun `entering the rows from the square grid reads the old index as a photo`() {
        val anchor = LayoutAnchor()
        anchor.drawSquare(firstVisible = 0)
        val three = rows(3)
        val target = anchor.settleJustified(three, assets, firstVisible = 4500)!!
        assertTrue(4500 in three.rowItems(target))
    }

    @Test
    fun `a changed list finds the top photo again by its id`() {
        val three = rows(3)
        val anchor = LayoutAnchor()
        anchor.settleJustified(three, assets, firstVisible = 0)
        val topRow = 1500
        val photo = assets[three.rowItems(topRow).first]
        // Ten new photos arrive at the front of the list.
        val grown = List(10) { asset(20_000 + it) } + assets
        val grownSquare = GridIndexMap(listOf(grown.size), hasHeaders = false, trailingFooter = true)
        val target = anchor.drawSquare(firstVisible = topRow, list = grown, map = grownSquare)
        assertEquals(photo.id, grown[grownSquare.assetIndexAt(target!!)].id)
    }

    @Test
    fun `a forgotten anchor does not carry rows`() {
        val anchor = LayoutAnchor()
        anchor.settleJustified(rows(3), assets, firstVisible = 0)
        anchor.forget()
        assertNull(anchor.drawSquare(firstVisible = 1500))
    }
}
