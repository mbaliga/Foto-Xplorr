package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * P0-15: `GridIndexMap` is what keeps the scrubber, the pill caption and arrow-key navigation
 * agreeing with what the grid actually draws once date headers (or any other row that is not
 * 1:1 with an asset) are on screen. See docs/TRAPS.md #5 and #9.
 */
class GridIndexMapTest {

    @Test
    fun `no groups is an empty grid, footer aside`() {
        val withFooter = GridIndexMap(groupSizes = emptyList(), hasHeaders = true, trailingFooter = true)
        assertEquals(1, withFooter.itemCount)
        assertEquals(0, withFooter.gridIndexOf(0))
        assertEquals(0, withFooter.assetIndexAt(0))

        val withoutFooter = GridIndexMap(groupSizes = emptyList(), hasHeaders = true, trailingFooter = false)
        assertEquals(0, withoutFooter.itemCount)
    }

    @Test
    fun `one group with headers offsets every asset by the one header row`() {
        val map = GridIndexMap(groupSizes = listOf(5), hasHeaders = true, trailingFooter = false)
        assertEquals(6, map.itemCount)
        // Grid item 0 is the header; assets start at grid item 1.
        assertEquals(0, map.assetIndexAt(0))
        for (assetIndex in 0..4) {
            assertEquals(assetIndex + 1, map.gridIndexOf(assetIndex))
            assertEquals(assetIndex, map.assetIndexAt(assetIndex + 1))
        }
    }

    @Test
    fun `many groups each get their own header before their own assets`() {
        // Groups of 3, 1, 4 assets: grid layout is H,a,a,a, H,a, H,a,a,a,a (11 items).
        val map = GridIndexMap(groupSizes = listOf(3, 1, 4), hasHeaders = true, trailingFooter = false)
        assertEquals(11, map.itemCount)

        // Group 0: header at grid 0, assets 0..2 at grid 1..3.
        assertEquals(1, map.gridIndexOf(0))
        assertEquals(3, map.gridIndexOf(2))
        assertEquals(0, map.assetIndexAt(0)) // header -> group's first asset
        assertEquals(0, map.assetIndexAt(1))
        assertEquals(2, map.assetIndexAt(3))

        // Group 1: header at grid 4, asset 3 at grid 5.
        assertEquals(5, map.gridIndexOf(3))
        assertEquals(3, map.assetIndexAt(4)) // header -> group's first (only) asset
        assertEquals(3, map.assetIndexAt(5))

        // Group 2: header at grid 6, assets 4..7 at grid 7..10.
        assertEquals(7, map.gridIndexOf(4))
        assertEquals(10, map.gridIndexOf(7))
        assertEquals(4, map.assetIndexAt(6))
        assertEquals(7, map.assetIndexAt(10))
    }

    @Test
    fun `trailing footer adds exactly one item past the last group, mapping to the last asset`() {
        val map = GridIndexMap(groupSizes = listOf(2, 2), hasHeaders = true, trailingFooter = true)
        // H,a,a, H,a,a, footer = 7 items.
        assertEquals(7, map.itemCount)
        assertEquals(3, map.assetIndexAt(6)) // the footer row maps to the very last asset
    }

    @Test
    fun `with headers off the map is the identity, plus the existing footer`() {
        val withFooter = GridIndexMap(groupSizes = listOf(3, 4), hasHeaders = false, trailingFooter = true)
        assertEquals(8, withFooter.itemCount) // 7 assets + 1 footer, no header rows at all
        for (i in 0..6) {
            assertEquals(i, withFooter.gridIndexOf(i))
            assertEquals(i, withFooter.assetIndexAt(i))
        }
        assertEquals(6, withFooter.assetIndexAt(7)) // footer -> last asset

        val withoutFooter = GridIndexMap(groupSizes = listOf(3, 4), hasHeaders = false, trailingFooter = false)
        assertEquals(7, withoutFooter.itemCount)
        for (i in 0..6) {
            assertEquals(i, withoutFooter.gridIndexOf(i))
            assertEquals(i, withoutFooter.assetIndexAt(i))
        }
    }

    @Test
    fun `round trip holds over random group sizes, headers and footer on or off`() {
        val random = Random(seed = 20260924)
        repeat(200) {
            val groupCount = random.nextInt(0, 12)
            val groupSizes = List(groupCount) { random.nextInt(1, 20) }
            val hasHeaders = random.nextBoolean()
            val trailingFooter = random.nextBoolean()
            val map = GridIndexMap(groupSizes, hasHeaders, trailingFooter)
            val totalAssets = groupSizes.sum()
            for (assetIndex in 0 until totalAssets) {
                val gridIndex = map.gridIndexOf(assetIndex)
                assertEquals(
                    "assetIndexAt(gridIndexOf($assetIndex)) should round-trip " +
                        "(groupSizes=$groupSizes, hasHeaders=$hasHeaders, trailingFooter=$trailingFooter)",
                    assetIndex,
                    map.assetIndexAt(gridIndex),
                )
            }
        }
    }
}
