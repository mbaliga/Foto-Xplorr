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
        add(OfflineMapsCopy.ROW_CAPTION)
        add(OfflineMapsCopy.EMPTY)
        add(OfflineMapsCopy.NOTHING_SAVED)
        add(OfflineMapsCopy.SAVED)
        add(OfflineMapsCopy.ONE_AT_A_TIME)
        add(OfflineMapsCopy.DATE_LINE)
        add(OfflineMapsCopy.ALREADY_SAVED)
        add(OfflineMapsCopy.quota(6, 300_000_000L))
        add(OfflineMapsCopy.meteredBody(45_000_000L))
        add(OfflineMapsCopy.deleteTitle("A".repeat(10)))
        add(OfflineMapsCopy.deleteBody(45_000_000L))
        add(OfflineMapsCopy.downloading(42))
        add(OfflineMapsCopy.downloading(null))
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

    @Test
    fun `sizes are whole megabytes rounded up and never zero`() {
        assertEquals("1 MB", OfflineMapsCopy.megabytes(0L))
        assertEquals("1 MB", OfflineMapsCopy.megabytes(1L))
        assertEquals("1 MB", OfflineMapsCopy.megabytes(1_000_000L))
        assertEquals("2 MB", OfflineMapsCopy.megabytes(1_000_001L))
        assertEquals("45 MB", OfflineMapsCopy.megabytes(OfflineMapLimits.MAX_BYTES))
        assertEquals("Download, about 12 MB", OfflineMapsCopy.downloadWithSize(11_200_000L))
    }

    @Test
    fun `the photo count agrees with its number`() {
        assertEquals("1 photo", OfflineMapsCopy.photos(1))
        assertEquals("2 photos", OfflineMapsCopy.photos(2))
    }

    @Test
    fun `the labels are short and carry no sentence ending`() {
        listOf(
            OfflineMapsCopy.ROW_LABEL, OfflineMapsCopy.PILL, OfflineMapsCopy.THIS_VIEW, OfflineMapsCopy.DOWNLOAD,
            OfflineMapsCopy.RESUME, OfflineMapsCopy.PAUSE, OfflineMapsCopy.DELETE, OfflineMapsCopy.KEEP,
            OfflineMapsCopy.RENAME, OfflineMapsCopy.NOT_NOW,
        ).forEach { assertTrue(it, it.length <= 24 && !it.endsWith(".")) }
    }
}
