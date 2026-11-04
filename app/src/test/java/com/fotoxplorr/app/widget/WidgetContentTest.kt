package com.fotoxplorr.app.widget

import com.fotoxplorr.app.ScanState
import com.fotoxplorr.app.background.BackgroundWorkStatus
import com.fotoxplorr.app.widget.WidgetFixtures.ZONE
import com.fotoxplorr.app.widget.WidgetFixtures.asset
import com.fotoxplorr.app.widget.WidgetFixtures.collection
import com.fotoxplorr.app.widget.WidgetFixtures.folderKey
import com.fotoxplorr.app.widget.WidgetFixtures.library
import com.fotoxplorr.app.widget.WidgetFixtures.millis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WidgetContentTest {

    // ---- every widget: the shared states -------------------------------------------------------

    @Test
    fun `without photo access every widget says so and reads nothing`() {
        val lib = library(listOf(asset(1)), favoriteIds = setOf(1), hasMediaAccess = false)
        assertEquals(WidgetState.NoAccess, favouriteOfTheDay(lib, 0))
        assertEquals(WidgetState.NoAccess, onThisDay(lib, LocalDate.of(2026, 6, 1), ZONE))
        assertEquals(WidgetState.NoAccess, recentItems(lib))
        assertEquals(WidgetState.NoAccess, libraryCounts(lib))
        assertEquals(WidgetState.NoAccess, collectionRows(lib))
        assertEquals(WidgetState.NoAccess, indexingState(lib, 0, 0, null))
    }

    @Test
    fun `an empty library is Empty and a fully hidden one is Withheld`() {
        val empty = library(emptyList())
        assertEquals(WidgetState.Empty, recentItems(empty))
        assertEquals(WidgetState.Empty, libraryCounts(empty))
        assertEquals(WidgetState.Empty, indexingState(empty, 0, 0, null))
        val hidden = library(listOf(asset(1)), sensitiveIds = setOf(1))
        assertEquals(WidgetState.Withheld, recentItems(hidden))
        assertEquals(WidgetState.Withheld, libraryCounts(hidden))
        assertEquals(WidgetState.Withheld, indexingState(hidden, 0, 0, null))
        // Only trash left is genuinely empty, not withheld.
        assertEquals(WidgetState.Empty, recentItems(library(listOf(asset(1, trashed = true)))))
    }

    // ---- A favourite ---------------------------------------------------------------------------

    @Test
    fun `a favourite is stable within a day and always one of the shown favourites`() {
        val assets = (1L..12L).map { asset(it) }
        val lib = library(assets, favoriteIds = setOf(2, 4, 6, 8, 10), sensitiveIds = setOf(4))
        val shownFavourites = setOf(2L, 6L, 8L, 10L)
        val seen = mutableSetOf<Long>()
        for (day in 20_000L until 20_200L) {
            val first = favouriteOfTheDay(lib, day) as WidgetState.Ready
            val again = favouriteOfTheDay(lib, day) as WidgetState.Ready
            assertEquals(first, again)
            assertTrue(first.content.asset.id.value in shownFavourites)
            assertEquals(4, first.content.shownFavourites)
            seen += first.content.asset.id.value
        }
        // Over 200 days every shown favourite comes round.
        assertEquals(shownFavourites, seen)
    }

    @Test
    fun `a favourite does not depend on catalogue order`() {
        val assets = (1L..9L).map { asset(it) }
        val forward = library(assets, favoriteIds = setOf(1, 3, 5, 7))
        val reversed = library(assets.reversed(), favoriteIds = setOf(1, 3, 5, 7))
        for (day in 0L until 30L) assertEquals(favouriteOfTheDay(forward, day), favouriteOfTheDay(reversed, day))
    }

    @Test
    fun `no favourites is Empty, only hidden favourites is Withheld`() {
        assertEquals(WidgetState.Empty, favouriteOfTheDay(library(listOf(asset(1))), 0))
        val locked = asset(2, folder = "Private")
        assertEquals(
            WidgetState.Withheld,
            favouriteOfTheDay(library(listOf(asset(1), locked), favoriteIds = setOf(2), lockedFolders = setOf(folderKey(locked))), 0),
        )
        assertEquals(WidgetState.Withheld, favouriteOfTheDay(library(listOf(asset(1)), favoriteIds = setOf(1), archivedIds = setOf(1)), 0))
        // A favourite that is only in the trash is gone, not hidden.
        assertEquals(WidgetState.Empty, favouriteOfTheDay(library(listOf(asset(1, trashed = true)), favoriteIds = setOf(1)), 0))
    }

    // ---- On this day ---------------------------------------------------------------------------

    @Test
    fun `on this day matches today's month and day in earlier years only, by date taken`() {
        val today = LocalDate.of(2026, 11, 4)
        val lastYear = asset(1, millis(2025, 11, 4))
        val longAgo = asset(2, millis(2019, 11, 4, hour = 23))
        val thisYear = asset(3, millis(2026, 11, 4))
        val dayBefore = asset(4, millis(2025, 11, 3))
        val undated = asset(5, takenMillis = 0L)
        val state = onThisDay(library(listOf(lastYear, longAgo, thisYear, dayBefore, undated)), today, ZONE)
        val content = (state as WidgetState.Ready).content
        assertEquals(listOf(lastYear, longAgo), content.items)
        assertEquals(2, content.totalCount)
        assertEquals(2, content.yearCount)
        assertEquals(today, content.today)
    }

    @Test
    fun `on this day uses the device's zone to decide the day`() {
        // 23:30 in London on 3 November is 4 November in Kolkata.
        val taken = millis(2024, 11, 3, hour = 23, zone = ZONE) + 30 * 60_000L
        val lib = library(listOf(asset(1, taken)))
        val today = LocalDate.of(2026, 11, 4)
        assertEquals(WidgetState.Empty, onThisDay(lib, today, ZONE))
        assertTrue(onThisDay(lib, today, ZoneId.of("Asia/Kolkata")) is WidgetState.Ready)
    }

    @Test
    fun `on this day interleaves years, most recent first`() {
        val a1 = asset(1, millis(2025, 3, 10, hour = 9))
        val a2 = asset(2, millis(2025, 3, 10, hour = 10))
        val a3 = asset(3, millis(2025, 3, 10, hour = 11))
        val b1 = asset(4, millis(2022, 3, 10, hour = 9))
        val c1 = asset(5, millis(2018, 3, 10, hour = 9))
        val c2 = asset(6, millis(2018, 3, 10, hour = 10))
        val content = (onThisDay(library(listOf(c2, a3, b1, a1, c1, a2)), LocalDate.of(2026, 3, 10), ZONE) as WidgetState.Ready).content
        assertEquals(listOf(a1, b1, c1, a2, c2, a3), content.items)
        assertEquals(3, content.yearCount)
    }

    @Test
    fun `29 February photos appear on 28 February in a year without one`() {
        val leapDay = asset(1, millis(2024, 2, 29))
        val lib = library(listOf(leapDay))
        assertTrue(onThisDay(lib, LocalDate.of(2026, 2, 28), ZONE) is WidgetState.Ready)
        assertEquals(WidgetState.Empty, onThisDay(lib, LocalDate.of(2026, 3, 1), ZONE))
        // In a leap year they wait for the 29th itself.
        assertEquals(WidgetState.Empty, onThisDay(lib, LocalDate.of(2028, 2, 28), ZONE))
        assertTrue(onThisDay(lib, LocalDate.of(2028, 2, 29), ZONE) is WidgetState.Ready)
    }

    @Test
    fun `on this day withholds hidden matches and caps the run`() {
        val today = LocalDate.of(2026, 5, 5)
        val hidden = asset(1, millis(2020, 5, 5))
        assertEquals(WidgetState.Withheld, onThisDay(library(listOf(hidden), sensitiveIds = setOf(1)), today, ZONE))
        val many = (1L..100L).map { asset(it, millis(2000 + (it % 20).toInt(), 5, 5)) }
        val content = (onThisDay(library(many), today, ZONE) as WidgetState.Ready).content
        assertEquals(WIDGET_RUN_LIMIT, content.items.size)
        assertEquals(100, content.totalCount)
    }

    // ---- Recent --------------------------------------------------------------------------------

    @Test
    fun `recent is newest first and capped at two rows of four`() {
        val assets = (1L..20L).map { asset(it, millis(2026, 1, it.toInt())) }
        val items = (recentItems(library(assets.shuffled(java.util.Random(7)))) as WidgetState.Ready).content.items
        assertEquals((20L downTo 13L).toList(), items.map { it.id.value })
    }

    // ---- Library at a glance -------------------------------------------------------------------

    @Test
    fun `library counts only what the home screen may show`() {
        val photo = asset(1)
        val video = asset(2, video = true)
        val screenshot = asset(3, folder = "Screenshots")
        val other = asset(4, folder = "WhatsApp Images")
        val sensitive = asset(5)
        val protectedItem = asset(6, folder = "Private")
        val trashed = asset(7, trashed = true)
        val lib = library(
            listOf(photo, video, screenshot, other, sensitive, protectedItem, trashed),
            favoriteIds = setOf(1, 5, 6),
            sensitiveIds = setOf(5),
            lockedFolders = setOf(folderKey(protectedItem)),
            collections = listOf(
                collection("a", "A", emptySet()),
                collection("b", "B", setOf(6)),
                collection("c", "C", setOf(1, 6)),
                collection("d", "D", setOf(3)),
            ),
        )
        val counts = (libraryCounts(lib) as WidgetState.Ready).content
        // Collections: only "c" has something the Collections widget may draw. "a" is empty, "b"
        // holds only a protected photo, "d" only a screenshot; counting them would tell anyone
        // comparing the two widgets that they exist.
        assertEquals(LibraryCounts(total = 4, photos = 3, videos = 1, favourites = 1, screenshots = 1, albums = 3, collections = 1), counts)
        assertEquals(shownCollections(lib).size, counts.collections)
        assertEquals((collectionRows(lib) as WidgetState.Ready).content.shownCollections, counts.collections)
    }

    // ---- Collections ---------------------------------------------------------------------------

    @Test
    fun `collections keep the app's order, show only visible members and skip fully hidden ones`() {
        val a = asset(1, millis(2026, 1, 1))
        val b = asset(2, millis(2026, 2, 1))
        val secret = asset(3, folder = "Private")
        val lib = library(
            listOf(a, b, secret),
            lockedFolders = setOf(folderKey(secret)),
            collections = listOf(
                collection("1", "Beach", setOf(1, 2, 3)),
                collection("2", "Hidden things", setOf(3)),
                collection("3", "Empty", emptySet()),
                collection("4", "Winter", setOf(1)),
            ),
        )
        val content = (collectionRows(lib) as WidgetState.Ready).content
        assertEquals(listOf("1", "4"), content.rows.map { it.id })
        assertEquals(listOf(b, a), content.rows.first().members)
        assertEquals(b, content.rows.first().cover)
        assertEquals(2, content.shownCollections)
        // Rows are told apart by the dates their pictures span, never by the typed name.
        assertEquals(a.dateTakenMillis..b.dateTakenMillis, content.rows.first().takenSpan)
        assertEquals(a.dateTakenMillis..a.dateTakenMillis, content.rows.last().takenSpan)
        assertNull(CollectionRow("x", listOf(asset(9, takenMillis = 0L))).takenSpan)
    }

    @Test
    fun `collections states`() {
        assertEquals(WidgetState.Empty, collectionRows(library(listOf(asset(1)))))
        assertEquals(WidgetState.Empty, collectionRows(library(listOf(asset(1)), collections = listOf(collection("1", "New", emptySet())))))
        assertEquals(
            WidgetState.Withheld,
            collectionRows(library(listOf(asset(1)), sensitiveIds = setOf(1), collections = listOf(collection("1", "Private", setOf(1))))),
        )
        val many = (1..9).map { collection("$it", "C$it", setOf(1L)) }
        val content = (collectionRows(library(listOf(asset(1)), collections = many)) as WidgetState.Ready).content
        assertEquals(COLLECTION_ROWS, content.rows.size)
        assertEquals(9, content.shownCollections)
    }

    // ---- Indexing ------------------------------------------------------------------------------

    @Test
    fun `indexing counts visible photos and videos and clamps what it is told`() {
        val lib = library(
            listOf(asset(1), asset(2), asset(3), asset(4, video = true), asset(5), asset(6, trashed = true)),
            sensitiveIds = setOf(5),
        )
        assertEquals(3, indexingPhotos(lib).size)
        assertEquals(1, indexingVideos(lib).size)
        val content = (indexingState(lib, photosPending = 1, videosScanned = 5, activity = null) as WidgetState.Ready).content
        assertEquals(IndexingCounts(photosTotal = 3, photosPending = 1, videosTotal = 1, videosScanned = 1), content.counts)
        assertEquals(2, content.counts.photosDone)
    }

    @Test
    fun `percent rounds down so 100 means every item`() {
        assertEquals(0, percentDone(0, 0))
        assertEquals(99, percentDone(999, 1000))
        assertEquals(100, percentDone(1000, 1000))
        assertEquals(33, percentDone(1, 3))
        assertEquals(100, percentDone(5, 3))
        assertEquals(0, percentDone(-2, 3))
    }

    @Test
    fun `indexing activity is said only when this process knows it`() {
        assertNull(indexingActivity(BackgroundWorkStatus.Unknown, ScanState.Idle))
        assertNull(indexingActivity(BackgroundWorkStatus.Unrestricted, ScanState.Complete(10)))
        assertEquals(IndexingActivity.Running, indexingActivity(BackgroundWorkStatus.Running, ScanState.Idle))
        assertEquals(IndexingActivity.Scheduled, indexingActivity(BackgroundWorkStatus.Pending, ScanState.Idle))
        assertEquals(
            IndexingActivity.Waiting("Not charging", 42L),
            indexingActivity(BackgroundWorkStatus.Blocked("Not charging", 42L), ScanState.Idle),
        )
        // A library scan in progress is the more immediate thing to say.
        assertEquals(
            IndexingActivity.ReadingLibrary(10, 200),
            indexingActivity(BackgroundWorkStatus.Running, ScanState.Scanning(10, 200)),
        )
    }

    // ---- The midnight boundary -----------------------------------------------------------------

    @Test
    fun `a stamp prints the date as well unless it is from today`() {
        val now = millis(2026, 11, 4, hour = 10)
        assertEquals(StampStyle.TIME, stampStyle(millis(2026, 11, 4, hour = 0), now, ZONE))
        assertEquals(StampStyle.TIME, stampStyle(millis(2026, 11, 4, hour = 9), now, ZONE))
        assertEquals(StampStyle.DATE_AND_TIME, stampStyle(millis(2026, 11, 3, hour = 23), now, ZONE))
        assertEquals(StampStyle.DATE_AND_TIME, stampStyle(millis(2026, 10, 1), now, ZONE))
        assertEquals(StampStyle.YEAR_DATE_AND_TIME, stampStyle(millis(2025, 12, 31, hour = 23), now, ZONE))
        // A clock moved back since: a stamp "from tomorrow" says which day too.
        assertEquals(StampStyle.DATE_AND_TIME, stampStyle(millis(2026, 11, 5, hour = 1), now, ZONE))
        // The day is the device's own: 14:00 and 23:00 on one London day straddle midnight in Tokyo.
        assertEquals(StampStyle.TIME, stampStyle(millis(2026, 11, 3, hour = 14), millis(2026, 11, 3, hour = 23), ZONE))
        assertEquals(
            StampStyle.DATE_AND_TIME,
            stampStyle(millis(2026, 11, 3, hour = 14), millis(2026, 11, 3, hour = 23), ZoneId.of("Asia/Tokyo")),
        )
    }

    @Test
    fun `next local midnight is exact, including across a clock change`() {
        val now = millis(2026, 6, 1, hour = 22)
        assertEquals(2L * 60 * 60 * 1000, millisUntilNextLocalMidnight(now, ZONE))
        // The night the UK clocks go back (25 October 2026) is 25 hours long from midnight.
        val beforeFallBack = millis(2026, 10, 24, hour = 23)
        assertEquals(1L * 60 * 60 * 1000, millisUntilNextLocalMidnight(beforeFallBack, ZONE))
        val dayOfFallBack = millis(2026, 10, 25, hour = 0) + 1
        assertEquals(25L * 60 * 60 * 1000 - 1, millisUntilNextLocalMidnight(dayOfFallBack, ZONE))
        assertTrue(millisUntilNextLocalMidnight(millis(2026, 1, 1, hour = 0), ZONE) > 0)
    }
}
