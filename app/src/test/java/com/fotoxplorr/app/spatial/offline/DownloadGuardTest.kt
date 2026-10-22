package com.fotoxplorr.app.spatial.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadGuardTest {

    private val box = GeoBox.clamped(2.30, 48.83, 2.38, 48.89)!!
    private val estimate = (RegionBudget.plan(box) as BudgetVerdict.Fits).estimate
    private val someProgress = RegionProgress(
        completedResources = 40, requiredResources = 100, requiredIsPrecise = true,
        completedBytes = 4_000_000, completedTiles = 30,
    )

    private val idle = DownloadState.Idle
    private val estimating = DownloadState.Estimating(box)
    private val ready = DownloadState.Ready(estimate)
    private val confirm = DownloadState.ConfirmMetered(estimate, resumeFrom = null)
    private val downloading = DownloadState.Downloading(estimate, someProgress)
    private val paused = DownloadState.Paused(estimate, someProgress, PauseReason.USER)
    private val done = DownloadState.Done(estimate, someProgress.copy(complete = true))
    private val failed = DownloadState.Failed(FailureReason.SERVER, estimate)

    private val everyState = listOf(idle, estimating, ready, confirm, downloading, paused, done, failed)

    private fun DownloadState.after(event: DownloadEvent): DownloadState =
        (DownloadGuard.reduce(this, event) as GuardResult.Moved).state

    private fun assertRejected(state: DownloadState, event: DownloadEvent): GuardResult.Rejected {
        val result = DownloadGuard.reduce(state, event)
        assertTrue("$event from $state should be rejected but was $result", result is GuardResult.Rejected)
        result as GuardResult.Rejected
        assertSame("a rejection leaves the state alone", state, result.state)
        return result
    }

    // ---- the happy path ----

    @Test
    fun `a full run goes idle to estimating to ready to downloading to done`() {
        var state: DownloadState = idle
        state = state.after(DownloadEvent.Select(box))
        assertEquals(estimating, state)
        state = state.after(DownloadEvent.Estimated(BudgetVerdict.Fits(estimate)))
        assertEquals(ready, state)
        state = state.after(DownloadEvent.Start(throttleInstalled = true, metered = false))
        assertTrue(state is DownloadState.Downloading)
        state = state.after(DownloadEvent.Progress(someProgress))
        assertEquals(someProgress, (state as DownloadState.Downloading).progress)
        state = state.after(DownloadEvent.Progress(someProgress.copy(complete = true, completedResources = 100)))
        assertTrue(state is DownloadState.Done)
    }

    @Test
    fun `a reduced detail verdict is ready with its own estimate`() {
        val reduced = estimate.copy(maxZoom = 13)
        val state = estimating.after(DownloadEvent.Estimated(BudgetVerdict.Reduced(reduced)))
        assertEquals(DownloadState.Ready(reduced), state)
    }

    @Test
    fun `a too large estimate fails with a reason and starts nothing`() {
        val verdict = BudgetVerdict.TooLarge(TooLargeReason.TOO_MANY_TILES, estimate)
        assertEquals(DownloadState.Failed(FailureReason.TOO_LARGE, estimate), estimating.after(DownloadEvent.Estimated(verdict)))
        assertEquals(DownloadState.Failed(FailureReason.NO_AREA), estimating.after(DownloadEvent.Estimated(BudgetVerdict.NoArea)))
    }

    // ---- the throttle rule ----

    @Test
    fun `download is refused when the throttle is not installed`() {
        val result = assertRejected(ready, DownloadEvent.Start(throttleInstalled = false, metered = false))
        assertEquals(RejectReason.THROTTLE_NOT_INSTALLED, result.reason)
        assertEquals(RejectReason.THROTTLE_NOT_INSTALLED, assertRejected(confirm, DownloadEvent.Confirm(throttleInstalled = false)).reason)
        assertEquals(RejectReason.THROTTLE_NOT_INSTALLED, assertRejected(paused, DownloadEvent.Resume(throttleInstalled = false, metered = false)).reason)
    }

    @Test
    fun `the button is enabled only where a download is legal and the throttle is there`() {
        assertTrue(DownloadGuard.canStart(ready, throttleInstalled = true))
        assertTrue(DownloadGuard.canStart(confirm, throttleInstalled = true))
        assertFalse(DownloadGuard.canStart(ready, throttleInstalled = false))
        assertFalse(DownloadGuard.canStart(confirm, throttleInstalled = false))
        for (state in everyState - listOf(ready, confirm)) {
            assertFalse("$state", DownloadGuard.canStart(state, throttleInstalled = true))
        }
        assertTrue(DownloadGuard.canResume(paused, throttleInstalled = true))
        assertFalse(DownloadGuard.canResume(paused, throttleInstalled = false))
        assertFalse(DownloadGuard.canResume(downloading, throttleInstalled = true))
    }

    // ---- metered connections ----

    @Test
    fun `a metered connection asks before starting and a decline goes back to ready`() {
        val asking = ready.after(DownloadEvent.Start(throttleInstalled = true, metered = true))
        assertEquals(DownloadState.ConfirmMetered(estimate, null), asking)
        assertEquals(ready, asking.after(DownloadEvent.Decline))
        assertTrue(asking.after(DownloadEvent.Confirm(throttleInstalled = true)) is DownloadState.Downloading)
    }

    @Test
    fun `resuming on a metered connection asks too and keeps what was fetched`() {
        val asking = paused.after(DownloadEvent.Resume(throttleInstalled = true, metered = true))
        assertEquals(DownloadState.ConfirmMetered(estimate, someProgress), asking)
        val resumed = asking.after(DownloadEvent.Confirm(throttleInstalled = true)) as DownloadState.Downloading
        assertEquals(someProgress, resumed.progress)
        assertEquals(paused, asking.after(DownloadEvent.Decline))
    }

    @Test
    fun `resuming on an unmetered connection goes straight to downloading`() {
        val state = paused.after(DownloadEvent.Resume(throttleInstalled = true, metered = false))
        assertEquals(DownloadState.Downloading(estimate, someProgress), state)
    }

    // ---- cancel means pause ----

    @Test
    fun `cancelling a download pauses it and keeps the progress`() {
        val state = downloading.after(DownloadEvent.Cancel)
        assertEquals(DownloadState.Paused(estimate, someProgress, PauseReason.USER), state)
    }

    @Test
    fun `cancelling before a download starts goes back without starting anything`() {
        assertEquals(idle, estimating.after(DownloadEvent.Cancel))
        assertEquals(idle, ready.after(DownloadEvent.Cancel))
        assertEquals(ready, confirm.after(DownloadEvent.Cancel))
    }

    @Test
    fun `a restart finds a stored region paused and waits for a tap`() {
        val state = idle.after(DownloadEvent.Restored(estimate, someProgress))
        assertEquals(DownloadState.Paused(estimate, someProgress, PauseReason.RESTARTED), state)
        assertTrue(state.after(DownloadEvent.Resume(throttleInstalled = true, metered = false)) is DownloadState.Downloading)
    }

    // ---- the caps ----

    @Test
    fun `going over the byte cap stops the download`() {
        val over = someProgress.copy(completedBytes = OfflineMapLimits.MAX_BYTES + 1)
        assertEquals(DownloadState.Failed(FailureReason.OVER_CAP, estimate), downloading.after(DownloadEvent.Progress(over)))
    }

    @Test
    fun `going over the tile cap stops the download`() {
        val over = someProgress.copy(completedTiles = OfflineMapLimits.MAX_TILES + 1)
        assertEquals(DownloadState.Failed(FailureReason.OVER_CAP, estimate), downloading.after(DownloadEvent.Progress(over)))
    }

    @Test
    fun `exactly on a cap keeps going`() {
        val atCap = someProgress.copy(completedBytes = OfflineMapLimits.MAX_BYTES, completedTiles = OfflineMapLimits.MAX_TILES)
        assertTrue(downloading.after(DownloadEvent.Progress(atCap)) is DownloadState.Downloading)
    }

    @Test
    fun `an area stopped at the size limit cannot be resumed, whatever the throttle says`() {
        val over = someProgress.copy(completedBytes = OfflineMapLimits.MAX_BYTES + 1)
        val stopped = DownloadState.Paused(estimate, over, PauseReason.RESTARTED)
        assertTrue(DownloadGuard.isOverCap(over))
        assertFalse(DownloadGuard.canResume(stopped, throttleInstalled = true))
        assertRejected(stopped, DownloadEvent.Resume(throttleInstalled = true, metered = false))
        assertRejected(stopped, DownloadEvent.Resume(throttleInstalled = true, metered = true))
        val tiles = DownloadState.Paused(estimate, someProgress.copy(completedTiles = OfflineMapLimits.MAX_TILES + 1), PauseReason.USER)
        assertFalse(DownloadGuard.canResume(tiles, throttleInstalled = true))
    }

    @Test
    fun `an area exactly at a cap can still be resumed`() {
        val atCap = someProgress.copy(completedBytes = OfflineMapLimits.MAX_BYTES, completedTiles = OfflineMapLimits.MAX_TILES)
        assertFalse(DownloadGuard.isOverCap(atCap))
        assertTrue(DownloadGuard.canResume(DownloadState.Paused(estimate, atCap, PauseReason.USER), true))
    }

    @Test
    fun `a download that completes is done even if it landed over a cap`() {
        val finished = someProgress.copy(complete = true, completedBytes = OfflineMapLimits.MAX_BYTES + 5)
        assertTrue(downloading.after(DownloadEvent.Progress(finished)) is DownloadState.Done)
    }

    // ---- errors ----

    private fun errorOf(kind: DownloadErrorKind) = DownloadEvent.Progress(someProgress.copy(error = kind))

    @Test
    fun `a missing tile is ignored however often it happens`() {
        var state: DownloadState = downloading
        repeat(50) { state = state.after(errorOf(DownloadErrorKind.NOT_FOUND)) }
        assertEquals(0, (state as DownloadState.Downloading).errorStreak)
    }

    @Test
    fun `repeated server errors fail the download`() {
        var state: DownloadState = downloading
        repeat(OfflineMapLimits.MAX_CONSECUTIVE_ERRORS - 1) { state = state.after(errorOf(DownloadErrorKind.SERVER)) }
        assertTrue(state is DownloadState.Downloading)
        state = state.after(errorOf(DownloadErrorKind.SERVER))
        assertEquals(DownloadState.Failed(FailureReason.SERVER, estimate), state)
    }

    @Test
    fun `repeated connection errors pause the download so it can resume`() {
        var state: DownloadState = downloading
        repeat(OfflineMapLimits.MAX_CONSECUTIVE_ERRORS) { state = state.after(errorOf(DownloadErrorKind.CONNECTION)) }
        assertTrue(state is DownloadState.Paused)
        assertEquals(PauseReason.CONNECTION, (state as DownloadState.Paused).reason)
    }

    @Test
    fun `progress between errors resets the streak`() {
        var state: DownloadState = downloading
        repeat(OfflineMapLimits.MAX_CONSECUTIVE_ERRORS - 1) { state = state.after(errorOf(DownloadErrorKind.SERVER)) }
        val moved = someProgress.copy(completedResources = someProgress.completedResources + 1)
        state = state.after(DownloadEvent.Progress(moved))
        assertEquals(0, (state as DownloadState.Downloading).errorStreak)
        repeat(OfflineMapLimits.MAX_CONSECUTIVE_ERRORS - 1) { state = state.after(errorOf(DownloadErrorKind.SERVER)) }
        assertTrue(state is DownloadState.Downloading)
    }

    @Test
    fun `an engine failure ends the download`() {
        assertEquals(DownloadState.Failed(FailureReason.ENGINE, estimate), downloading.after(DownloadEvent.EngineFailed))
    }

    // ---- illegal transitions ----

    @Test
    fun `start is legal only from ready`() {
        for (state in everyState - ready) {
            assertRejected(state, DownloadEvent.Start(throttleInstalled = true, metered = false))
        }
    }

    @Test
    fun `confirm is legal only from the metered question`() {
        for (state in everyState - confirm) assertRejected(state, DownloadEvent.Confirm(throttleInstalled = true))
    }

    @Test
    fun `resume is legal only from paused`() {
        for (state in everyState - paused) assertRejected(state, DownloadEvent.Resume(throttleInstalled = true, metered = false))
    }

    @Test
    fun `progress is legal only while downloading and a late one after a pause is harmless`() {
        for (state in everyState - downloading) assertRejected(state, DownloadEvent.Progress(someProgress))
    }

    @Test
    fun `an estimate is legal only while estimating`() {
        for (state in everyState - estimating) assertRejected(state, DownloadEvent.Estimated(BudgetVerdict.Fits(estimate)))
    }

    @Test
    fun `restored is legal only from idle`() {
        for (state in everyState - idle) assertRejected(state, DownloadEvent.Restored(estimate, someProgress))
    }

    @Test
    fun `a new selection is refused while downloading or paused`() {
        assertRejected(downloading, DownloadEvent.Select(box))
        assertRejected(paused, DownloadEvent.Select(box))
        for (state in listOf(idle, estimating, ready, confirm, done, failed)) {
            assertEquals(estimating, state.after(DownloadEvent.Select(box)))
        }
    }

    @Test
    fun `cancel is refused where there is nothing to cancel`() {
        for (state in listOf(idle, paused, done, failed)) assertRejected(state, DownloadEvent.Cancel)
    }

    @Test
    fun `an engine failure is refused outside a download`() {
        for (state in everyState - downloading) assertRejected(state, DownloadEvent.EngineFailed)
    }

    @Test
    fun `dismiss returns to idle from anywhere except a running download`() {
        for (state in everyState - downloading) assertEquals(idle, state.after(DownloadEvent.Dismiss))
        assertEquals(RejectReason.NOT_ALLOWED_NOW, assertRejected(downloading, DownloadEvent.Dismiss).reason)
    }

    @Test
    fun `decline is legal only from the metered question`() {
        for (state in everyState - confirm) assertRejected(state, DownloadEvent.Decline)
    }

    @Test
    fun `no event can reach downloading without passing through start confirm or resume`() {
        val events = listOf(
            DownloadEvent.Select(box), DownloadEvent.Estimated(BudgetVerdict.Fits(estimate)), DownloadEvent.Cancel,
            DownloadEvent.Decline, DownloadEvent.Dismiss, DownloadEvent.Restored(estimate, someProgress),
            DownloadEvent.Progress(someProgress), DownloadEvent.EngineFailed,
        )
        for (state in everyState - downloading) {
            for (event in events) {
                val result = DownloadGuard.reduce(state, event)
                assertFalse("$event from $state", result.state is DownloadState.Downloading)
            }
        }
    }
}
