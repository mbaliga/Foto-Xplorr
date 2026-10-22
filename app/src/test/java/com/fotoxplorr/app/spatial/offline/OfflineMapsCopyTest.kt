package com.fotoxplorr.app.spatial.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMapsCopyTest {

    private val everyLine: List<String> = buildList {
        add(OfflineMapsCopy.STUB_ROW)
        add(OfflineMapsCopy.PRIVACY)
        add(OfflineMapsCopy.UNAVAILABLE)
        add(OfflineMapsCopy.NOT_SAVED)
        add(OfflineMapsCopy.REDUCED_DETAIL)
        add(OfflineMapsCopy.METERED)
        add(OfflineMapsCopy.ROOM_NEEDED)
        TooLargeReason.entries.forEach { add(OfflineMapsCopy.tooLarge(it)) }
        FailureReason.entries.forEach { add(OfflineMapsCopy.failure(it)) }
        PauseReason.entries.forEach { add(OfflineMapsCopy.paused(it)) }
    }

    @Test
    fun `every line is one short sentence`() {
        for (line in everyLine) {
            assertTrue(line, line.length <= 70)
            assertTrue(line, line.endsWith(".") || line.endsWith("?"))
            assertTrue("two sentences in: $line", !line.dropLast(1).contains(". ") && !line.dropLast(1).contains("? "))
        }
    }

    @Test
    fun `the Offline build row uses the owner's wording`() {
        assertEquals("Offline maps need the Connect build.", OfflineMapsCopy.STUB_ROW)
    }

    @Test
    fun `the privacy line names both what is revealed and the attribution names all three sources`() {
        assertTrue(OfflineMapsCopy.PRIVACY.contains("area"))
        assertTrue(OfflineMapsCopy.PRIVACY.contains("IP address"))
        listOf("OpenStreetMap contributors", "OpenMapTiles", "OpenFreeMap").forEach {
            assertTrue(it, OfflineMapsCopy.ATTRIBUTION.contains(it))
        }
    }

    @Test
    fun `room has a line only when it is refused`() {
        assertNull(OfflineMapsCopy.room(RoomVerdict.Ok))
        assertEquals(OfflineMapsCopy.ROOM_NEEDED, OfflineMapsCopy.room(RoomVerdict.TooManyRegions(6)))
        assertEquals(OfflineMapsCopy.ROOM_NEEDED, OfflineMapsCopy.room(RoomVerdict.OverTotalBytes(1L, 1L)))
    }
}
