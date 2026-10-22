package com.fotoxplorr.app.spatial.offline

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMapsPaneModelTest {

    private val paris = GeoBox.clamped(2.30, 48.83, 2.38, 48.89)!!
    private val london = GeoBox.clamped(-0.51, 51.28, 0.33, 51.69)!!
    private val switzerland = GeoBox.clamped(5.96, 45.82, 10.49, 47.81)!!
    private val point = GeoBox.clamped(2.0, 48.0, 2.0, 48.0)!!

    private fun optionOf(box: GeoBox, key: String = "view", photos: Int = 0) = AreaOption(
        key = key,
        source = if (key == "view") AreaSource.THIS_VIEW else AreaSource.PLACE,
        box = box,
        defaultName = RegionSuggestions.placeholderName(box),
        photoCount = photos,
        verdict = RegionBudget.plan(box),
    )

    private fun env(
        throttle: Boolean = true,
        saved: List<RegionRecord> = emptyList(),
        running: Boolean = false,
        canDownload: (GeoBox) -> Boolean = { true },
    ) = PaneEnv(throttle, canDownload, saved, running)

    private fun record(
        name: String = "Paris",
        box: GeoBox = paris,
        maxZoom: Int = 14,
        bytes: Long = 12_000_000L,
        lastUsed: Long = 1_000L,
        measured: Boolean = false,
    ) = RegionRecord(
        name = name, box = box, minZoom = 0, maxZoom = maxZoom, styleId = "liberty",
        createdAtMillis = 1_000L, lastUsedMillis = lastUsed, bytes = bytes, tileCount = 100L,
        bytesMeasured = measured,
    )

    private fun stored(
        id: Long,
        record: RegionRecord,
        complete: Boolean = false,
        running: Boolean = false,
        progress: RegionProgress = RegionProgress.EMPTY.copy(complete = complete),
    ) = StoredRegion(id, record, progress, running)

    // ---- options ----

    @Test
    fun `this view comes first, then the suggested places with stable keys`() {
        val points = listOf(GeoPoint(48.86, 2.35), GeoPoint(51.5, -0.12))
        val options = OfflineMapsPaneModel.options(points, paris)
        assertEquals("view", options.first().key)
        assertEquals(AreaSource.THIS_VIEW, options.first().source)
        assertEquals(0, options.first().photoCount)
        assertEquals(listOf("place-0", "place-1"), options.drop(1).map { it.key })
        assertTrue(options.drop(1).all { it.source == AreaSource.PLACE && it.photoCount >= 1 })
    }

    @Test
    fun `no map view means no this-view option and no points means no places`() {
        assertEquals(listOf("place-0"), OfflineMapsPaneModel.options(listOf(GeoPoint(48.86, 2.35)), null).map { it.key })
        assertEquals(listOf("view"), OfflineMapsPaneModel.options(emptyList(), paris).map { it.key })
        assertTrue(OfflineMapsPaneModel.options(emptyList(), null).isEmpty())
    }

    @Test
    fun `a view that is a point is not offered`() {
        assertTrue(OfflineMapsPaneModel.options(emptyList(), point).isEmpty())
    }

    // ---- names ----

    @Test
    fun `a typed name is trimmed and an empty one falls back to the coordinates`() {
        val option = optionOf(paris)
        assertEquals("My trip", OfflineMapsPaneModel.nameFor(option, "  My trip \n"))
        assertEquals(option.defaultName, OfflineMapsPaneModel.nameFor(option, "   "))
        assertEquals(option.defaultName, OfflineMapsPaneModel.nameFor(option, null))
        assertEquals(RegionRecord.MAX_NAME_LENGTH, OfflineMapsPaneModel.nameFor(option, "x".repeat(200)).length)
    }

    @Test
    fun `a name already used gets a number`() {
        assertEquals("Paris", OfflineMapsPaneModel.uniqueName("Paris", emptyList()))
        assertEquals("Paris 2", OfflineMapsPaneModel.uniqueName("Paris", listOf(record("Paris"))))
        assertEquals(
            "Paris 3",
            OfflineMapsPaneModel.uniqueName("Paris", listOf(record("Paris"), record("Paris 2"))),
        )
    }

    @Test
    fun `a numbered name still fits the record's name limit`() {
        val long = "y".repeat(RegionRecord.MAX_NAME_LENGTH)
        val unique = OfflineMapsPaneModel.uniqueName(long, listOf(record(long)))
        assertEquals(RegionRecord.MAX_NAME_LENGTH, unique.length)
        assertTrue(unique.endsWith(" 2"))
    }

    // ---- the Download button ----

    @Test
    fun `a small area is enabled and the estimate is on the button`() {
        val button = OfflineMapsPaneModel.button(optionOf(paris), env())
        val estimate = (optionOf(paris).verdict as BudgetVerdict.Fits).estimate
        assertTrue(button.enabled)
        assertEquals(OfflineMapsCopy.downloadWithSize(estimate.bytes), button.label)
        assertTrue(button.label.startsWith("Download, about "))
        assertNull(button.note)
    }

    @Test
    fun `a reduced area is enabled and says so`() {
        val option = optionOf(london)
        assertTrue(option.verdict is BudgetVerdict.Reduced)
        val button = OfflineMapsPaneModel.button(option, env())
        assertTrue(button.enabled)
        assertEquals(OfflineMapsCopy.REDUCED_DETAIL, button.note)
    }

    @Test
    fun `without the request limit nothing can be downloaded and the reason is unavailable`() {
        for (box in listOf(paris, london, switzerland)) {
            val button = OfflineMapsPaneModel.button(optionOf(box), env(throttle = false))
            assertFalse(button.enabled)
            assertEquals(OfflineMapsCopy.UNAVAILABLE, button.note)
            assertEquals(OfflineMapsCopy.DOWNLOAD, button.label)
        }
    }

    @Test
    fun `a running download disables the others`() {
        val button = OfflineMapsPaneModel.button(optionOf(paris), env(running = true))
        assertFalse(button.enabled)
        assertEquals(OfflineMapsCopy.ONE_AT_A_TIME, button.note)
    }

    @Test
    fun `a too big area is disabled with the cap it ran into`() {
        val option = optionOf(switzerland)
        val verdict = option.verdict as BudgetVerdict.TooLarge
        val button = OfflineMapsPaneModel.button(option, env())
        assertFalse(button.enabled)
        assertEquals(OfflineMapsCopy.tooLarge(verdict.reason), button.note)
    }

    @Test
    fun `an area that is not an area is disabled`() {
        val option = AreaOption("view", AreaSource.THIS_VIEW, point, "x", 0, RegionBudget.plan(point))
        val button = OfflineMapsPaneModel.button(option, env())
        assertFalse(button.enabled)
        assertEquals(OfflineMapsCopy.failure(FailureReason.NO_AREA), button.note)
    }

    @Test
    fun `an area the engine will not take is disabled with the date line sentence`() {
        val button = OfflineMapsPaneModel.button(optionOf(paris), env(canDownload = { false }))
        assertFalse(button.enabled)
        assertEquals(OfflineMapsCopy.DATE_LINE, button.note)
    }

    @Test
    fun `an area already inside a saved one is disabled`() {
        val around = GeoBox.clamped(2.0, 48.5, 2.7, 49.2)!!
        val button = OfflineMapsPaneModel.button(optionOf(paris), env(saved = listOf(record(box = around))))
        assertFalse(button.enabled)
        assertEquals(OfflineMapsCopy.ALREADY_SAVED, button.note)
    }

    @Test
    fun `six saved areas leave no room and the seventh says to delete one`() {
        val saved = List(OfflineMapLimits.MAX_REGIONS) { record("Area $it", box = london, bytes = 1_000L) }
        val button = OfflineMapsPaneModel.button(optionOf(paris), env(saved = saved))
        assertFalse(button.enabled)
        assertEquals(OfflineMapsCopy.ROOM_NEEDED, button.note)
    }

    @Test
    fun `too many bytes in total leaves no room either`() {
        val saved = listOf(record("Big", box = london, bytes = OfflineMapLimits.MAX_TOTAL_BYTES))
        val button = OfflineMapsPaneModel.button(optionOf(paris), env(saved = saved))
        assertFalse(button.enabled)
        assertEquals(OfflineMapsCopy.ROOM_NEEDED, button.note)
    }

    @Test
    fun `the reasons keep their order`() {
        // Unavailable beats a running download, which beats too large, which beats the date line.
        val big = optionOf(switzerland)
        assertEquals(OfflineMapsCopy.UNAVAILABLE, OfflineMapsPaneModel.button(big, env(throttle = false, running = true)).note)
        assertEquals(OfflineMapsCopy.ONE_AT_A_TIME, OfflineMapsPaneModel.button(big, env(running = true)).note)
        assertEquals(
            OfflineMapsCopy.tooLarge((big.verdict as BudgetVerdict.TooLarge).reason),
            OfflineMapsPaneModel.button(big, env(canDownload = { false })).note,
        )
    }

    // ---- already saved ----

    @Test
    fun `already saved needs full containment at the same or better detail and no date line`() {
        val option = optionOf(paris)
        val estimate = option.estimate!!
        val around = GeoBox.clamped(2.0, 48.5, 2.7, 49.2)!!
        val half = GeoBox.clamped(2.34, 48.5, 2.7, 49.2)!!
        assertTrue(OfflineMapsPaneModel.isAlreadySaved(option, estimate, listOf(record(box = around))))
        assertFalse(OfflineMapsPaneModel.isAlreadySaved(option, estimate, listOf(record(box = half))))
        assertFalse(OfflineMapsPaneModel.isAlreadySaved(option, estimate, listOf(record(box = around, maxZoom = estimate.maxZoom - 1))))
        val crossing = GeoBox.clamped(170.0, 0.0, -170.0, 10.0)!!
        assertFalse(OfflineMapsPaneModel.isAlreadySaved(optionOf(crossing), estimate, listOf(record(box = crossing))))
    }

    // ---- saved rows ----

    private val usEnglish = Locale.US
    private val utc = TimeZone.getTimeZone("UTC")

    private fun rows(
        regions: List<StoredRegion>,
        reasons: Map<Long, PauseReason> = emptyMap(),
        live: Map<Long, RegionProgress> = emptyMap(),
        env: PaneEnv = env(),
    ) = OfflineMapsPaneModel.savedRows(regions, reasons, live, env, usEnglish, utc)

    @Test
    fun `a finished area shows its size, date and a delete`() {
        val row = rows(listOf(stored(1, record(measured = true, bytes = 38_200_000L), complete = true))).single()
        assertEquals(RowStatus.SAVED, row.status)
        assertEquals("39 MB", row.sizeText)
        assertEquals("Jan 1, 1970", row.dateText)
        assertNull(row.statusLine)
        assertTrue(row.canDelete)
        assertFalse(row.canResume)
        assertFalse(row.canPause)
    }

    @Test
    fun `an estimated size says about`() {
        val row = rows(listOf(stored(1, record(bytes = 12_000_000L), complete = true))).single()
        assertEquals("about 12 MB", row.sizeText)
    }

    @Test
    fun `an area found after a relaunch is paused with tap resume`() {
        val row = rows(listOf(stored(1, record()))).single()
        assertEquals(RowStatus.PAUSED, row.status)
        assertEquals(OfflineMapsCopy.paused(PauseReason.RESTARTED), row.statusLine)
        assertTrue(row.canResume)
        assertNull(row.resumeNote)
        assertTrue(row.canDelete)
    }

    @Test
    fun `a pause the person made says paused`() {
        val row = rows(listOf(stored(1, record())), reasons = mapOf(1L to PauseReason.USER)).single()
        assertEquals("Paused.", row.statusLine)
    }

    @Test
    fun `resume is off without the limit, with the reason, and while another download runs`() {
        val region = listOf(stored(1, record()))
        val noLimit = rows(region, env = env(throttle = false)).single()
        assertFalse(noLimit.canResume)
        assertEquals(OfflineMapsCopy.UNAVAILABLE, noLimit.resumeNote)
        val busy = rows(region, env = env(running = true)).single()
        assertFalse(busy.canResume)
        assertEquals(OfflineMapsCopy.ONE_AT_A_TIME, busy.resumeNote)
    }

    @Test
    fun `a running area can be paused but not deleted and shows live progress`() {
        val reading = RegionProgress(40, 100, true, 4_000_000L, 30)
        val row = rows(listOf(stored(1, record(), running = true)), live = mapOf(1L to reading)).single()
        assertEquals(RowStatus.DOWNLOADING, row.status)
        assertEquals("Downloading, 40%.", row.statusLine)
        assertEquals(0.4f, row.fraction!!, 1e-6f)
        assertTrue(row.canPause)
        assertFalse(row.canDelete)
        assertFalse(row.canResume)
    }

    @Test
    fun `before the total is known the download has no percent`() {
        val row = rows(listOf(stored(1, record(), running = true))).single()
        assertEquals("Downloading.", row.statusLine)
        assertNull(row.fraction)
    }

    @Test
    fun `an almost finished download never says one hundred percent`() {
        val reading = RegionProgress(999, 1000, true, 4_000_000L, 30)
        val row = rows(listOf(stored(1, record(), running = true)), live = mapOf(1L to reading)).single()
        assertEquals("Downloading, 99%.", row.statusLine)
    }

    @Test
    fun `downloads come first, then paused, then saved, newest used first`() {
        val rows = rows(
            listOf(
                stored(1, record("old saved", lastUsed = 10), complete = true),
                stored(2, record("new saved", lastUsed = 90), complete = true),
                stored(3, record("paused", lastUsed = 50)),
                stored(4, record("running", lastUsed = 5), running = true),
            ),
        )
        assertEquals(listOf("running", "paused", "new saved", "old saved"), rows.map { it.name })
    }

    // ---- the quota line and the date ----

    @Test
    fun `the quota line counts areas and megabytes against the caps`() {
        assertEquals("0 of 6 areas saved, 0 of 300 MB used.", OfflineMapsPaneModel.quotaLine(emptyList()))
        assertEquals(
            "2 of 6 areas saved, 25 of 300 MB used.",
            OfflineMapsPaneModel.quotaLine(listOf(record(bytes = 12_000_000L), record(bytes = 12_400_000L))),
        )
    }

    @Test
    fun `a date is formatted for the locale and the zone`() {
        val millis = 1_792_670_400_000L // 22 Oct 2026, 12:00 UTC
        assertEquals("Oct 22, 2026", OfflineMapsPaneModel.formatDate(millis, Locale.US, utc))
        assertEquals("22 Oct 2026", OfflineMapsPaneModel.formatDate(millis, Locale.UK, utc))
    }
}
