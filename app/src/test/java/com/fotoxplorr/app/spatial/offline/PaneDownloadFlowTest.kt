package com.fotoxplorr.app.spatial.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pane's taps against [DownloadGuard]: a download can start from a tap on Download, Resume or Confirm and nothing else. */
class PaneDownloadFlowTest {

    private val paris = GeoBox.clamped(2.30, 48.83, 2.38, 48.89)!!
    private val switzerland = GeoBox.clamped(5.96, 45.82, 10.49, 47.81)!!
    private val point = GeoBox.clamped(2.0, 48.0, 2.0, 48.0)!!

    private val record = RegionRecord(
        name = "Paris", box = paris, minZoom = 0, maxZoom = 14, styleId = "liberty",
        createdAtMillis = 1L, lastUsedMillis = 1L, bytes = 12_000_000L, tileCount = 100L,
    )
    private val halfway = RegionProgress(50, 100, true, 6_000_000L, 40)

    @Test
    fun `a tap on Download on Wi-Fi launches`() {
        val step = PaneDownloadFlow.tapDownload(paris, RegionBudget.plan(paris), throttleInstalled = true, metered = false)
        val launch = step as PaneStep.Launch
        assertEquals(RegionProgress.EMPTY, launch.from.progress)
        assertEquals(paris, launch.from.estimate.box)
    }

    @Test
    fun `a tap on mobile data asks first and launches nothing`() {
        val step = PaneDownloadFlow.tapDownload(paris, RegionBudget.plan(paris), throttleInstalled = true, metered = true)
        assertTrue(step is PaneStep.AskMetered)
    }

    @Test
    fun `accepting the question launches and declining goes back without launching`() {
        val asked = (PaneDownloadFlow.tapDownload(paris, RegionBudget.plan(paris), true, true) as PaneStep.AskMetered).asked
        assertTrue(PaneDownloadFlow.confirm(asked, throttleInstalled = true) is PaneStep.Launch)
        assertTrue(PaneDownloadFlow.decline(asked) is DownloadState.Ready)
    }

    @Test
    fun `accepting the question when the limit is gone refuses`() {
        val asked = (PaneDownloadFlow.tapDownload(paris, RegionBudget.plan(paris), true, true) as PaneStep.AskMetered).asked
        val step = PaneDownloadFlow.confirm(asked, throttleInstalled = false)
        assertEquals(PaneStep.Refused(OfflineMapsCopy.UNAVAILABLE), step)
    }

    @Test
    fun `without the limit a tap refuses with the unavailable sentence`() {
        val step = PaneDownloadFlow.tapDownload(paris, RegionBudget.plan(paris), throttleInstalled = false, metered = false)
        assertEquals(PaneStep.Refused(OfflineMapsCopy.UNAVAILABLE), step)
    }

    @Test
    fun `a too big area and a point refuse and never launch`() {
        val big = RegionBudget.plan(switzerland) as BudgetVerdict.TooLarge
        assertEquals(
            PaneStep.Refused(OfflineMapsCopy.tooLarge(big.reason)),
            PaneDownloadFlow.tapDownload(switzerland, big, true, false),
        )
        assertEquals(
            PaneStep.Refused(OfflineMapsCopy.failure(FailureReason.NO_AREA)),
            PaneDownloadFlow.tapDownload(point, RegionBudget.plan(point), true, false),
        )
    }

    @Test
    fun `a tap on Resume continues from the stored progress`() {
        val step = PaneDownloadFlow.tapResume(record, halfway, PauseReason.RESTARTED, throttleInstalled = true, metered = false)
        assertEquals(halfway, (step as PaneStep.Launch).from.progress)
    }

    @Test
    fun `resume on mobile data asks and keeps the stored progress for after the answer`() {
        val asked = (PaneDownloadFlow.tapResume(record, halfway, PauseReason.USER, true, true) as PaneStep.AskMetered).asked
        assertEquals(halfway, asked.resumeFrom)
        assertEquals(halfway, (PaneDownloadFlow.confirm(asked, true) as PaneStep.Launch).from.progress)
        val back = PaneDownloadFlow.decline(asked)
        assertTrue(back is DownloadState.Paused)
        assertEquals(halfway, (back as DownloadState.Paused).progress)
    }

    @Test
    fun `resume without the limit refuses`() {
        val step = PaneDownloadFlow.tapResume(record, halfway, PauseReason.RESTARTED, throttleInstalled = false, metered = false)
        assertEquals(PaneStep.Refused(OfflineMapsCopy.UNAVAILABLE), step)
    }

    @Test
    fun `the paused estimate of a stored area is the one it was planned at`() {
        val estimate = PaneDownloadFlow.estimateOf(record)
        assertEquals(paris, estimate.box)
        assertEquals(14, estimate.maxZoom)
        assertEquals(12_000_000L, estimate.bytes)
        assertEquals(100L, estimate.tileCount)
    }
}
