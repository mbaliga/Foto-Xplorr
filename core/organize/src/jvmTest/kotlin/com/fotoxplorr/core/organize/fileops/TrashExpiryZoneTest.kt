package com.fotoxplorr.core.organize.fileops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Region zones need the JVM tz database, so the DST and year-boundary arithmetic is checked here, not in commonTest. */
class TrashExpiryZoneTest {
    private val ny = TimeZone.of("America/New_York")

    private fun wall(ms: Long, zone: TimeZone) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).toString()

    @Test
    fun thirtyCalendarDaysAcrossTheSpringForwardKeepTheWallClock() {
        // 2026-03-01 10:00 in New York (EST) + 30 days = 2026-03-31 10:00 (EDT): 29 days 23 hours of elapsed time.
        val start = Instant.parse("2026-03-01T15:00:00Z").toEpochMilliseconds()
        val end = TrashExpiry.expiresAtMs(start, 30, ny)
        assertEquals("2026-03-31T10:00", wall(end, ny))
        assertEquals(30 * 86_400_000L - 3_600_000L, end - start)
    }

    @Test
    fun thirtyCalendarDaysAcrossTheFallBackKeepTheWallClock() {
        val start = Instant.parse("2026-10-15T14:00:00Z").toEpochMilliseconds() // 10:00 EDT
        val end = TrashExpiry.expiresAtMs(start, 30, ny)
        assertEquals("2026-11-14T10:00", wall(end, ny))
        assertEquals(30 * 86_400_000L + 3_600_000L, end - start)
    }

    @Test
    fun yearBoundaryAndLeapDay() {
        val dec = Instant.parse("2026-12-20T12:00:00Z").toEpochMilliseconds()
        assertEquals("2027-01-19T12:00", wall(TrashExpiry.expiresAtMs(dec, 30, TimeZone.UTC), TimeZone.UTC))
        val feb = Instant.parse("2028-02-10T12:00:00Z").toEpochMilliseconds()
        assertEquals("2028-03-11T12:00", wall(TrashExpiry.expiresAtMs(feb, 30, TimeZone.UTC), TimeZone.UTC))
    }

    @Test
    fun daysLeftRoundsUpSoTheLastDayStillSaysOne() {
        val e = TrashEntry("i", "/p", "p", "i-p", 1, 0L, 90_000_000L)
        assertEquals(2, TrashExpiry.daysLeft(e, 0L))
        assertEquals(1, TrashExpiry.daysLeft(e, 90_000_000L - 1))
        assertEquals(0, TrashExpiry.daysLeft(e, 90_000_000L))
    }
}
