package com.fotoxplorr.app.widget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/*
 * The widgets' two JobScheduler jobs, both run by WidgetJobService. JobScheduler is a platform
 * class, not a library: this app adds no scheduling library (WorkManager is ruled out), and no
 * exact-alarm permission is involved. Both ids are distinct from BackgroundScheduler.JOB_ID (20_918).
 *
 * Every job built here calls setMinimumLatency, even with 0: on API 26 and 27 JobInfo.Builder.build()
 * throws for a job with no constraint at all.
 */

/**
 * Every pushed redraw goes through this job, never an in-process timer. A job lives in the system,
 * not in this process: a redraw requested just before the app is swiped away from Recents, or
 * frozen as a cached process, still runs (the system starts the process for it), and while it runs
 * the job keeps the process alive. That matters most for the redraws that take something off the
 * home screen (a photo flagged sensitive, archived, put in a protected folder), which are therefore
 * "urgent": scheduled with no delay instead of the short wait that lets a burst of writes share one
 * redraw. Not persisted: after a reboot the launcher asks every widget to update anyway.
 */
internal object WidgetRefreshJob {
    const val JOB_ID = 20_932

    /** A burst of writes (a multi-select favourite) shares one redraw this long after the first. */
    const val DEBOUNCE_MS = 1_500L

    private val coalescer = RefreshCoalescer(DEBOUNCE_MS)

    /** Something a widget may show has changed. Never throws; does nothing when no widget is placed. */
    fun request(context: Context, urgent: Boolean) {
        val app = context.applicationContext
        if (!FotozWidgets.mayBePlaced(app)) return
        val delay = coalescer.request(urgent) ?: return
        if (!schedule(app, delay)) coalescer.scheduleFailed()
    }

    /** One run of the job: redraws until nothing has changed since its last read of the stores. */
    suspend fun run(app: Context) {
        val run = coalescer.runStarting()
        try {
            while (true) {
                val readFrom = coalescer.passStarting()
                try {
                    FotozWidgets.updateAll(app)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    // The pass itself already drew the error state where it could.
                    Log.w("WidgetRefreshJob", "widget refresh failed", t)
                }
                val wait = coalescer.passEnded(run, readFrom) ?: break
                coalescer.awaitNextPass(wait)
            }
        } finally {
            coalescer.runStopped(run)
        }
    }

    private fun schedule(app: Context, delayMs: Long): Boolean {
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return false
        return runCatching {
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(app, WidgetJobService::class.java))
                    .setMinimumLatency(delayMs)
                    .build(),
            ) == JobScheduler.RESULT_SUCCESS
        }.getOrElse {
            Log.w("WidgetRefreshJob", "could not schedule a widget redraw", it)
            false
        }
    }
}

/**
 * The bookkeeping behind [WidgetRefreshJob], kept free of Android so its rules are unit-tested:
 *
 * - The first request while nothing waits schedules a pass after [debounceMs] (an urgent one: now).
 * - A request while a pass waits adds nothing: that pass reads the stores when it runs, so it covers
 *   the write. A stream of writes never postpones a redraw.
 * - An urgent request while a non-urgent pass waits brings it forward to now.
 * - A request while a pass runs marks the run dirty: that pass may have read the stores before the
 *   write, so the run goes again (at once when urgent) instead of ending. Nothing is scheduled while
 *   a run is in progress, because scheduling a job's own id stops the running instance.
 *
 * "Waiting" is this process's knowledge only. A new process does not know about a job a previous
 * one scheduled, and simply schedules again, which replaces it: harmless.
 */
internal class RefreshCoalescer(private val debounceMs: Long) {
    private var generation = 0L
    private var waiting = false
    private var waitingUrgent = false
    private var running = false
    private var runId = 0
    private val urgentDuringRun = MutableStateFlow(false)

    /** A store changed. Returns the delay to schedule a pass after, or null when a pass already covers it. */
    @Synchronized
    fun request(urgent: Boolean): Long? {
        generation++
        if (running) {
            if (urgent) urgentDuringRun.value = true
            return null
        }
        if (waiting && (waitingUrgent || !urgent)) return null
        waiting = true
        waitingUrgent = urgent
        return if (urgent) 0L else debounceMs
    }

    /** The scheduler refused the job, so nothing is waiting after all. */
    @Synchronized
    fun scheduleFailed() {
        waiting = false
        waitingUrgent = false
    }

    /** The job started. Returns this run's id, for [passEnded] and [runStopped]. */
    @Synchronized
    fun runStarting(): Int {
        waiting = false
        waitingUrgent = false
        running = true
        urgentDuringRun.value = false
        return ++runId
    }

    /** A pass is about to read the stores. Returns the generation it reads. */
    @Synchronized
    fun passStarting(): Long {
        urgentDuringRun.value = false
        return generation
    }

    /**
     * A pass that read [readFrom] has drawn. Null when nothing changed since (the run is over, and
     * the next request schedules a new job); otherwise how long to wait before drawing again.
     */
    @Synchronized
    fun passEnded(run: Int, readFrom: Long): Long? {
        if (run != runId) return null
        if (generation == readFrom) {
            running = false
            return null
        }
        return if (urgentDuringRun.value) 0L else debounceMs
    }

    /** The run is over, normally or because the platform stopped it. A superseded run changes nothing. */
    @Synchronized
    fun runStopped(run: Int) {
        if (run != runId) return
        running = false
        urgentDuringRun.value = false
    }

    /** Waits up to [ms] before the next pass, cut short by an urgent request. */
    suspend fun awaitNextPass(ms: Long) {
        if (ms > 0L) withTimeoutOrNull(ms) { urgentDuringRun.first { it } }
    }
}

/**
 * One one-off job just after local midnight, while an "A favourite" or "On this day" widget is
 * placed: both change with the date. The platform may run it late (Doze, an OEM battery manager),
 * which is why those widgets also print the date they are showing. Not persisted: after a reboot
 * the launcher asks every provider to update anyway, and that update re-arms this job.
 */
internal object WidgetDayJob {
    const val JOB_ID = 20_931

    private const val EXTRA_AT = "widget_day_at"
    private const val AFTER_MIDNIGHT_MS = 60_000L

    fun reconcile(context: Context, needed: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        runCatching {
            if (!needed) {
                scheduler.cancel(JOB_ID)
                return
            }
            val now = System.currentTimeMillis()
            val delay = millisUntilNextLocalMidnight(now, ZoneId.systemDefault()) + AFTER_MIDNIGHT_MS
            val at = now + delay
            // Already armed for this same moment: leave it, rather than calling schedule() on every
            // redraw (which also stops a running job with the same id).
            if (scheduler.getPendingJob(JOB_ID)?.extras?.getLong(EXTRA_AT) == at) return
            // No deadline: with no other constraint it would not make the job run any sooner (the
            // platform says so in its log), and it never overrides Doze.
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, WidgetJobService::class.java))
                    .setMinimumLatency(delay)
                    .setExtras(PersistableBundle().apply { putLong(EXTRA_AT, at) })
                    .build(),
            )
        }.onFailure { Log.w("WidgetDayJob", "could not arm the midnight widget redraw", it) }
    }
}

/** Runs [WidgetRefreshJob] and [WidgetDayJob]. Not exported; only the system may bind it. */
internal class WidgetJobService : JobService() {
    private val work = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        val job = when (params.jobId) {
            WidgetRefreshJob.JOB_ID -> FotozWidgets.scope.launch {
                WidgetRefreshJob.run(app)
                jobFinished(params, false)
            }
            WidgetDayJob.JOB_ID -> FotozWidgets.scope.launch {
                try {
                    FotozWidgets.updateAll(app, rescheduleDayJob = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    Log.w("WidgetJobService", "midnight widget redraw failed", t)
                }
                jobFinished(params, false)
                // Re-armed only after finishing: scheduling this job's own id while it runs stops it.
                FotozWidgets.reconcileDayJob(app)
            }
            else -> return false
        }
        work[params.jobId] = job
        job.invokeOnCompletion { work.remove(params.jobId, job) }
        return true
    }

    /** Stopped early by the platform: both jobs ask to run again, since neither finished drawing. */
    override fun onStopJob(params: JobParameters): Boolean {
        work.remove(params.jobId)?.cancel()
        return true
    }
}
