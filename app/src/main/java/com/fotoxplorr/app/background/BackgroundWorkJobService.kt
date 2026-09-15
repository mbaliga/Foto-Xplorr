package com.fotoxplorr.app.background

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The platform wakes THIS every time [BackgroundScheduler]'s coarse `JobInfo` constraints hold.
 * All it does is re-check the REAL rules against live [DeviceState] and either run the pending
 * work or record why it did not -- see [BackgroundScheduler]'s KDoc for the full two-layer design
 * this class is the second half of.
 *
 * ## Integration step this change could not make itself
 *
 * A `JobService` must be declared in the manifest before `JobScheduler` can ever actually start
 * it -- `AndroidManifest.xml` is a shared file this change does not own (see the task's file
 * list), so this is landing WITHOUT that declaration. [BackgroundScheduler.reconcile] is still
 * safe to call in the meantime: `JobScheduler.schedule()` succeeds even when the target service
 * is not yet registered, and the job simply never gets started by the platform until the manifest
 * catches up. The one block needed, inside the existing `<application>` element of
 * `app/src/main/AndroidManifest.xml` (both flavours -- idle/charging/battery gating is meaningful
 * in `offline` too, only [WorkRules.onlyOnUnmetered] is `connect`-only and that is already gated
 * in code, not in the manifest):
 *
 * ```xml
 * <service
 *     android:name="com.fotoxplorr.app.background.BackgroundWorkJobService"
 *     android:permission="android.permission.BIND_JOB_SERVICE"
 *     android:exported="false" />
 * ```
 *
 * No new `<uses-permission>` is needed: `BIND_JOB_SERVICE` above is declared ON the service
 * element to restrict who may bind to it (only the system holds that permission), which is
 * standard `JobService` boilerplate, not something this app requests for itself.
 *
 * ## What actually runs
 *
 * This class enforces WHEN, not WHAT. The heavy pass itself -- recognition indexing, exports,
 * whatever else -- is deliberately NOT called from here: `recognition/` and the other feature
 * packages are owned by concurrent changes this one must not reach into. [BackgroundWorkRunner]
 * is the seam: whoever wires the real work assigns [BackgroundWorkRunnerRegistry.runner] once
 * (e.g. from `Application.onCreate`) and never needs to touch this file again. Until that
 * happens, [NoBackgroundWork] means every [WorkVerdict.Allowed] tick does honestly nothing.
 */
class BackgroundWorkJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeWork: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        activeWork = scope.launch {
            val rules = WorkRulesStore(applicationContext).observe().value
            val state = readDeviceState(applicationContext)

            when (val verdict = WorkRuleEvaluator.evaluate(rules, state)) {
                is WorkVerdict.Blocked -> {
                    BackgroundWorkStatusCenter.publish(
                        BackgroundWorkStatus.Blocked(verdict.reason, checkedAt = System.currentTimeMillis()),
                    )
                }
                WorkVerdict.Allowed -> {
                    BackgroundWorkStatusCenter.publish(BackgroundWorkStatus.Running)
                    // A failure here is the runner's to explain, not this service's to crash
                    // over -- a background pass that silently never runs again because one
                    // pass threw is a worse outcome than one that logs nothing and retries next
                    // period, which is what returning to Pending below already achieves.
                    //
                    // Cancellation is the one thing that must NOT be swallowed. runCatching
                    // catches every Throwable, CancellationException included, so without the
                    // rethrow an onStopJob mid-pass would be followed by this coroutine calmly
                    // publishing Pending over the "Android paused this" status onStopJob just
                    // wrote, and calling jobFinished(false) over its "please reschedule".
                    runCatching { BackgroundWorkRunnerRegistry.runner.runPendingWork() }
                        .onFailure { if (it is CancellationException) throw it }
                    BackgroundWorkStatusCenter.publish(BackgroundWorkStatus.Pending)
                }
            }

            // Not reached after onStopJob: the rethrow above ends this coroutine first, and the
            // platform has already been told (onStopJob's `true`) to reschedule this occurrence.
            ensureActive()
            // false: this is a PERIODIC job (see BackgroundScheduler.POLL_INTERVAL_MS), so the
            // platform already knows to wake it again next period on its own. Rescheduling here
            // as well would be the same duplicate-schedule problem BackgroundScheduler's KDoc
            // describes for a hand-rolled retry, just triggered from the other end.
            jobFinished(params, false)
        }
        return true // work continues asynchronously; see activeWork and onStopJob.
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // The platform is reclaiming this job's execution window before it finished on its own
        // (a real possibility: JobService time budgets are not something this app controls).
        // Cancel cooperatively -- BackgroundWorkRunner implementations are expected to check
        // isActive / respond to cancellation the same as any suspend function must -- and be
        // honest that this stopped early rather than silently going quiet.
        activeWork?.cancel()
        BackgroundWorkStatusCenter.publish(
            BackgroundWorkStatus.Blocked(
                "Android paused background work before this pass finished; it will try again later",
                checkedAt = System.currentTimeMillis(),
            ),
        )
        return true // let the platform reschedule this occurrence.
    }
}

/**
 * The seam between "is now a good time for heavy background work" (this package's whole job) and
 * "what that work actually is" (recognition indexing, exports, ... -- owned by their own
 * packages, not this one). See [BackgroundWorkJobService]'s KDoc for why this exists instead of
 * a direct call into, say, `RecognitionStore`.
 */
fun interface BackgroundWorkRunner {
    /**
     * Do whatever heavy work is currently pending. Must cooperate with coroutine cancellation --
     * [BackgroundWorkJobService.onStopJob] cancels the scope this runs in the moment the platform
     * reclaims the job's execution window, and an implementation that ignores that keeps writing
     * after the window it was trusted to write within has already closed.
     *
     * @return a short, human-readable summary of what happened, or null if there was nothing
     *   pending. "Nothing pending" is this return value, not an exception -- a runner that throws
     *   to mean "no work" would make [BackgroundWorkJobService]'s `runCatching` unable to tell
     *   that apart from an actual failure.
     */
    suspend fun runPendingWork(): String?
}

/** The default until something registers a real [BackgroundWorkRunner]: not an error, just none. */
object NoBackgroundWork : BackgroundWorkRunner {
    override suspend fun runPendingWork(): String? = null
}

/**
 * The one process-wide slot a real [BackgroundWorkRunner] is assigned into. `@Volatile` because
 * it is written from whatever thread wires it up (app start) and read from
 * [BackgroundWorkJobService]'s coroutine, which is not that thread.
 */
object BackgroundWorkRunnerRegistry {
    @Volatile
    var runner: BackgroundWorkRunner = NoBackgroundWork
}
