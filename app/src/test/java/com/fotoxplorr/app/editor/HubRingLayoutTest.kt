package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubRingLayoutTest {

    @Test
    fun `a portrait phone at a normal size gets the ring`() {
        assertEquals(HubLayout.RING, HubRingLayout.kindFor(360, 780, 1.0f))
        assertEquals(HubLayout.RING, HubRingLayout.kindFor(411, 900, 1.3f))
        assertEquals(HubLayout.RING, HubRingLayout.kindFor(HubRingLayout.MIN_RING_WIDTH_DP, 700, 1.0f))
    }

    @Test
    fun `landscape, a tablet, a narrow window and a large font get the list`() {
        assertEquals("landscape", HubLayout.LIST, HubRingLayout.kindFor(780, 360, 1.0f))
        assertEquals("tablet", HubLayout.LIST, HubRingLayout.kindFor(HubRingLayout.MIN_LIST_WIDTH_DP, 1000, 1.0f))
        assertEquals("a tall tablet in portrait", HubLayout.LIST, HubRingLayout.kindFor(800, 1280, 1.0f))
        assertEquals("narrow", HubLayout.LIST, HubRingLayout.kindFor(HubRingLayout.MIN_RING_WIDTH_DP - 1, 700, 1.0f))
        assertEquals("large font", HubLayout.LIST, HubRingLayout.kindFor(360, 780, 1.31f))
        assertEquals("200 percent", HubLayout.LIST, HubRingLayout.kindFor(360, 780, 2.0f))
    }

    @Test
    fun `a short window gets the list even when it is narrow and upright`() {
        assertEquals("split screen", HubLayout.LIST, HubRingLayout.kindFor(360, 500, 1.0f))
        assertEquals(
            "just under the line",
            HubLayout.LIST, HubRingLayout.kindFor(360, HubRingLayout.MIN_RING_HEIGHT_DP - 1, 1.0f),
        )
        assertEquals(
            "on the line",
            HubLayout.RING, HubRingLayout.kindFor(360, HubRingLayout.MIN_RING_HEIGHT_DP, 1.0f),
        )
        assertEquals("a small phone is still a ring", HubLayout.RING, HubRingLayout.kindFor(360, 640, 1.0f))
    }

    @Test
    fun `a ring button never gets less than 48 dp of the narrowest ring`() {
        // 16 dp gutters either side, five buttons.
        assertTrue((HubRingLayout.MIN_RING_WIDTH_DP - 32) / ToolGroup.entries.size >= 48)
    }

    @Test
    fun `the arc is a smile - ends high, middle low, symmetric`() {
        val offsets = HubRingLayout.arcOffsets(5, 14f)
        assertEquals(5, offsets.size)
        assertEquals(0f, offsets.first(), 0f)
        assertEquals(0f, offsets.last(), 0f)
        assertEquals(14f, offsets[2], 0f)
        assertEquals(offsets[1], offsets[3], 0f)
        assertTrue(offsets[1] > offsets[0] && offsets[1] < offsets[2])
        assertEquals(listOf(0f, 0f), HubRingLayout.arcOffsets(2, 14f))
        assertEquals(listOf(0f), HubRingLayout.arcOffsets(1, 14f))
        assertEquals(emptyList<Float>(), HubRingLayout.arcOffsets(0, 14f))
    }

    @Test
    fun `no depth is no arc`() {
        assertEquals(List(5) { 0f }, HubRingLayout.arcOffsets(5, 0f))
    }
}
