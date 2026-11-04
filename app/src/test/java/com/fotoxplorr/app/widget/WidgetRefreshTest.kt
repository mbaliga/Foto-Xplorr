package com.fotoxplorr.app.widget

import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The rules that decide when a pushed redraw runs ([RefreshCoalescer]), and that the job carrying it
 * is a real, schedulable JobScheduler job on the oldest supported API, so a redraw asked for just
 * before the process dies is not lost with it.
 */
class RefreshCoalescerTest {
    private val debounce = WidgetRefreshJob.DEBOUNCE_MS

    @Test
    fun `the first write waits briefly and later writes ride along without postponing it`() {
        val c = RefreshCoalescer(debounce)
        assertEquals(debounce, c.request(urgent = false))
        repeat(50) { assertNull(c.request(urgent = false)) }
    }

    @Test
    fun `something leaving the home screen is drawn at once, even over a waiting redraw`() {
        val c = RefreshCoalescer(debounce)
        assertEquals(0L, c.request(urgent = true))
        assertNull(c.request(urgent = true))
        assertNull(c.request(urgent = false))

        val d = RefreshCoalescer(debounce)
        assertEquals(debounce, d.request(urgent = false))
        assertEquals("an urgent write brings the waiting redraw forward", 0L, d.request(urgent = true))
        assertNull(d.request(urgent = true))
    }

    @Test
    fun `a write during a pass makes the run go again instead of scheduling over it`() {
        val c = RefreshCoalescer(debounce)
        c.request(urgent = false)
        val run = c.runStarting()
        val read = c.passStarting()
        // Nothing may be scheduled while the job runs: scheduling its own id would stop it.
        assertNull(c.request(urgent = false))
        assertEquals(debounce, c.passEnded(run, read))
        val again = c.passStarting()
        assertNull(c.request(urgent = true))
        assertEquals("urgent during the pass: no wait before the next one", 0L, c.passEnded(run, again))
        val last = c.passStarting()
        assertNull("nothing new: the run ends", c.passEnded(run, last))
        // With the run over, the next write schedules a new job.
        assertEquals(debounce, c.request(urgent = false))
    }

    @Test
    fun `an urgent write cuts the wait between passes short`() = runBlocking {
        val c = RefreshCoalescer(60_000L)
        val run = c.runStarting()
        val read = c.passStarting()
        c.request(urgent = false)
        assertEquals(60_000L, c.passEnded(run, read))
        c.request(urgent = true)
        val started = System.nanoTime()
        c.awaitNextPass(60_000L)
        assertTrue((System.nanoTime() - started) / 1_000_000L < 5_000L)
    }

    @Test
    fun `a refused schedule or a stopped run leaves nothing stuck`() {
        val c = RefreshCoalescer(debounce)
        assertEquals(debounce, c.request(urgent = false))
        c.scheduleFailed()
        assertEquals(debounce, c.request(urgent = false))

        val run = c.runStarting()
        c.passStarting()
        c.runStopped(run)
        assertEquals(debounce, c.request(urgent = false))
    }

    @Test
    fun `a run replaced by a newer one cannot end the newer one`() {
        val c = RefreshCoalescer(debounce)
        val old = c.runStarting()
        val newer = c.runStarting()
        c.runStopped(old)
        val read = c.passStarting()
        assertNull("the newer run is still going", c.request(urgent = false))
        assertNull(c.passEnded(old, read))
        assertEquals(debounce, c.passEnded(newer, read))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetRefreshJobTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    @Config(sdk = [26])
    fun `with a widget placed, a write schedules the redraw job, buildable on API 26`() {
        // API 26 refuses a JobInfo with no constraint at all; the job always sets a latency.
        // Bound without running the provider, as a launcher binding a widget would leave it.
        shadowOf(AppWidgetManager.getInstance(context)).bindAppWidgetId(77, ComponentName(context, RecentWidgetProvider::class.java))
        assertTrue(FotozWidgets.mayBePlaced(context))
        FotozWidgets.placementChanged()
        FotozWidgets.requestRefresh(context, urgent = true)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = scheduler.getPendingJob(WidgetRefreshJob.JOB_ID)
        assertNotNull(job)
        assertEquals(ComponentName(context, WidgetJobService::class.java), job!!.service)
        assertEquals(0L, job.minLatencyMillis)
        assertTrue(!job.isPersisted)
    }

    @Test
    fun `with no widget placed, a write schedules nothing`() {
        FotozWidgets.placementChanged()
        FotozWidgets.requestRefresh(context)
        assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(WidgetRefreshJob.JOB_ID))
    }
}
