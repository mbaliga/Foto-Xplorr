package com.fotoxplorr.app.spatial.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionRecordTest {

    private fun record(
        name: String = "Paris",
        box: GeoBox = GeoBox.clamped(2.25, 48.8, 2.45, 48.92)!!,
        bytes: Long = 12_000_000L,
        lastUsed: Long = 2_000L,
        created: Long = 1_000L,
    ) = RegionRecord(
        name = name,
        box = box,
        minZoom = 0,
        maxZoom = 14,
        styleId = "liberty",
        createdAtMillis = created,
        lastUsedMillis = lastUsed,
        bytes = bytes,
        tileCount = 140L,
        bytesMeasured = false,
    )

    // ---- the codec ----

    @Test
    fun `a record survives a round trip unchanged`() {
        val original = record()
        assertEquals(original, RegionRecord.decode(original.encode()))
    }

    @Test
    fun `a measured size and an antimeridian box survive too`() {
        val original = record(box = GeoBox.clamped(179.5, -17.0, -179.5, -16.0)!!).copy(bytesMeasured = true)
        val back = RegionRecord.decode(original.encode())!!
        assertEquals(original, back)
        assertTrue(back.box.crossesAntimeridian)
        assertTrue(back.bytesMeasured)
    }

    @Test
    fun `a name with line breaks equals signs and backslashes survives`() {
        val tricky = "a=b\nc\r\nd\\e\\nf"
        assertEquals(tricky, RegionRecord.decode(record(name = tricky).encode())!!.name)
    }

    @Test
    fun `a name in another script survives`() {
        val name = "東京 🗼"
        assertEquals(name, RegionRecord.decode(record(name = name).encode())!!.name)
    }

    @Test
    fun `an over long name is cut without splitting a surrogate pair`() {
        val name = "x".repeat(RegionRecord.MAX_NAME_LENGTH - 1) + "🗼"
        val back = RegionRecord.decode(record(name = name).encode())!!.name
        assertTrue(back.length <= RegionRecord.MAX_NAME_LENGTH)
        assertFalse(Character.isHighSurrogate(back.last()))
    }

    @Test
    fun `an unknown key is ignored so a newer writer can add fields`() {
        val text = record().encode() + "\nfuture=whatever\nanother-key=1=2=3\n"
        assertEquals(record(), RegionRecord.decode(text))
    }

    @Test
    fun `a line with no equals sign is skipped`() {
        val text = "garbage line\n" + record().encode() + "\n\u0000\u0001\n"
        assertEquals(record(), RegionRecord.decode(text))
    }

    @Test
    fun `blank lines and windows line endings are tolerated`() {
        val text = record().encode().replace("\n", "\r\n\r\n")
        assertEquals(record(), RegionRecord.decode(text))
    }

    @Test
    fun `a corrupt value for a key we need makes the record undecodable`() {
        val good = record().encode()
        assertNull(RegionRecord.decode(good.replace(Regex("west=.*"), "west=banana")))
        assertNull(RegionRecord.decode(good.replace(Regex("maxZoom=.*"), "maxZoom=")))
        assertNull(RegionRecord.decode(good.replace(Regex("bytes=.*"), "bytes=-5")))
        assertNull(RegionRecord.decode(good.replace(Regex("west=.*"), "west=NaN")))
    }

    @Test
    fun `a missing box edge or zoom makes the record undecodable`() {
        val lines = record().encode().lines()
        for (key in listOf("west", "south", "east", "north", "minZoom", "maxZoom")) {
            val without = lines.filterNot { it.startsWith("$key=") }.joinToString("\n")
            assertNull("without $key", RegionRecord.decode(without))
        }
    }

    @Test
    fun `an out of range box is refused not repaired`() {
        val good = record().encode()
        assertNull(RegionRecord.decode(good.replace(Regex("north=.*"), "north=89.0")))
        assertNull(RegionRecord.decode(good.replace(Regex("east=.*"), "east=200.0")))
    }

    @Test
    fun `reversed or absurd zooms are refused`() {
        val good = record().encode()
        assertNull(RegionRecord.decode(good.replace(Regex("minZoom=.*"), "minZoom=15")))
        assertNull(RegionRecord.decode(good.replace(Regex("maxZoom=.*"), "maxZoom=99")))
    }

    @Test
    fun `optional fields default when absent`() {
        val minimal = "west=1.0\nsouth=2.0\neast=3.0\nnorth=4.0\nminZoom=0\nmaxZoom=10"
        val decoded = RegionRecord.decode(minimal)!!
        assertEquals("", decoded.name)
        assertEquals(0L, decoded.bytes)
        assertEquals(0L, decoded.createdAtMillis)
        assertFalse(decoded.bytesMeasured)
    }

    @Test
    fun `the last of a repeated key wins`() {
        val text = record().encode() + "\nname=Second"
        assertEquals("Second", RegionRecord.decode(text)!!.name)
    }

    @Test
    fun `empty and blank input decode to nothing`() {
        assertNull(RegionRecord.decode(""))
        assertNull(RegionRecord.decode("   \n\n"))
    }

    // ---- the cap on regions and bytes ----

    private fun regions(count: Int, bytes: Long = 10_000_000L) =
        List(count) { record(name = "r$it", bytes = bytes, lastUsed = (count - it).toLong()) }

    @Test
    fun `total bytes is the sum of the records`() {
        assertEquals(0L, RegionLedger.totalBytes(emptyList()))
        assertEquals(30_000_000L, RegionLedger.totalBytes(regions(3)))
    }

    @Test
    fun `total bytes saturates instead of overflowing`() {
        val huge = regions(2, bytes = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, RegionLedger.totalBytes(huge))
    }

    @Test
    fun `the sixth region is accepted and the seventh refused`() {
        assertEquals(RoomVerdict.Ok, RegionLedger.checkRoom(regions(5), 1_000_000L))
        val verdict = RegionLedger.checkRoom(regions(6), 1_000_000L)
        assertEquals(RoomVerdict.TooManyRegions(OfflineMapLimits.MAX_REGIONS), verdict)
    }

    @Test
    fun `a download that lands exactly on the total cap is accepted`() {
        val existing = regions(5, bytes = 50_000_000L) // 250 MB
        assertEquals(RoomVerdict.Ok, RegionLedger.checkRoom(existing, 50_000_000L))
    }

    @Test
    fun `one byte over the total cap is refused with the overshoot`() {
        val existing = regions(5, bytes = 50_000_000L)
        val verdict = RegionLedger.checkRoom(existing, 50_000_001L)
        assertEquals(RoomVerdict.OverTotalBytes(OfflineMapLimits.MAX_TOTAL_BYTES, overBy = 1L), verdict)
    }

    @Test
    fun `the region count is checked before the bytes`() {
        val verdict = RegionLedger.checkRoom(regions(6, bytes = 100_000_000L), 100_000_000L)
        assertTrue(verdict is RoomVerdict.TooManyRegions)
    }

    @Test
    fun `refusing never removes anything`() {
        val existing = regions(6)
        RegionLedger.checkRoom(existing, 1L)
        assertEquals(6, existing.size)
    }

    @Test
    fun `the oldest unused region is listed first for the person to delete`() {
        val a = record(name = "a", lastUsed = 500L)
        val b = record(name = "b", lastUsed = 100L)
        val c = record(name = "c", lastUsed = 900L)
        assertEquals(listOf("b", "a", "c"), RegionLedger.oldestUnusedFirst(listOf(a, b, c)).map { it.name })
    }

    @Test
    fun `ties on last use fall back to creation time then name`() {
        val a = record(name = "b", lastUsed = 5L, created = 2L)
        val b = record(name = "a", lastUsed = 5L, created = 2L)
        val c = record(name = "z", lastUsed = 5L, created = 1L)
        assertEquals(listOf("z", "a", "b"), RegionLedger.oldestUnusedFirst(listOf(a, b, c)).map { it.name })
    }

    @Test
    fun `touching a record moves its last use forward and nothing else`() {
        val r = record(lastUsed = 5L)
        val touched = r.touched(9_999L)
        assertEquals(9_999L, touched.lastUsedMillis)
        assertEquals(r.copy(lastUsedMillis = 9_999L), touched)
    }
}
