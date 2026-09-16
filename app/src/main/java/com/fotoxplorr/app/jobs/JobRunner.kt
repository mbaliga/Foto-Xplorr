package com.fotoxplorr.app.jobs

import com.fotoxplorr.app.hyle.ActivityKind
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A job the [JobRunner] is currently running, in the shape [com.fotoxplorr.app.hyle.ActivityShade]
 * (via [com.fotoxplorr.app.gallery.buildBackgroundActivities]) and [JobForegroundService] both need
 * to show it: a title, what kind it is, how far along it is, and a way to stop it.
 *
 * @param progress 0..1, or null while the job cannot yet say how far along it is -- the shade and
 *   the notification both already know how to draw that as an indeterminate sweep.
 */
data class RunningJob(
    val id: String,
    val title: String,
    val kind: ActivityKind,
    val progress: Float? = null,
    val cancel: () -> Unit,
)

/**
 * What a finished job reported. Delivered once, through [JobRunner.outcomes] -- not through
 * [JobHandle], because the composable that started a job is not always the one still on screen
 * (or even still alive, across a rotation) when it finishes.
 */
data class JobOutcome<T>(
    val id: String,
    val title: String,
    val kind: ActivityKind,
    val result: Result<T>,
)

/** A handle to a job just launched: enough to cancel it. The result is read off [JobRunner.outcomes]. */
class JobHandle internal constructor(val id: String, private val job: Job) {
    fun cancel() = job.cancel()
}

/**
 * Runs a long operation (a video/audio conversion, an editor save, a copy or move, a ZIP export, a
 * metadata batch write) as a cancellable, observable job instead of a bare `rememberCoroutineScope()`
 * launch, which a rotation used to kill outright and which had no way to report progress or accept a
 * second tap without silently ignoring it.
 *
 * [scope] is meant to be a [androidx.lifecycle.ViewModel]'s `viewModelScope`: a job started here
 * keeps running across a configuration change (the Activity that started it may already be gone by
 * the time it finishes) and is only ever stopped by [JobHandle.cancel], by [block] returning, or by
 * the owning ViewModel being cleared -- see [cancelAll].
 */
class JobRunner(private val scope: CoroutineScope) {
    private val nextId = AtomicLong(0)

    private val _jobs = MutableStateFlow<List<RunningJob>>(emptyList())
    val jobs: StateFlow<List<RunningJob>> = _jobs.asStateFlow()

    // Buffered rather than a plain event with no history: a job that finishes between the moment
    // this flow is created and the moment something starts collecting it (a rotation lands right
    // on a completion) must not lose that outcome -- there would be nothing left to tell the user
    // whether their export actually worked.
    private val _outcomes = MutableSharedFlow<JobOutcome<*>>(extraBufferCapacity = OUTCOME_BUFFER)
    val outcomes: SharedFlow<JobOutcome<*>> = _outcomes

    fun <T> launch(
        title: String,
        kind: ActivityKind,
        block: suspend (onProgress: (Float?) -> Unit) -> Result<T>,
    ): JobHandle {
        val id = "job-${nextId.incrementAndGet()}"
        lateinit var handle: JobHandle

        val job = scope.launch {
            fun updateProgress(progress: Float?) {
                _jobs.value = _jobs.value.map { if (it.id == id) it.copy(progress = progress) else it }
            }
            // runCatching around the WHOLE call, not just trusting block's own Result: block is
            // caller-supplied glue code, and a thrown exception in it (including the
            // CancellationException `job.cancel()` raises at its next suspension point) must land
            // in the same failure path a returned Result.failure would, or a cancelled job would
            // never reach the cleanup below at all.
            val result: Result<T> = runCatching { block(::updateProgress) }.getOrElse { Result.failure(it) }
            // Both calls below are ordinary (non-suspending) function calls, which is what lets
            // them still run after a cancellation was requested: Kotlin's cancellation is
            // cooperative and only throws at an actual suspension point, and runCatching just
            // above already absorbed the one that would otherwise have hit here.
            _jobs.value = _jobs.value.filterNot { it.id == id }
            _outcomes.tryEmit(JobOutcome(id, title, kind, result))
        }
        handle = JobHandle(id, job)
        _jobs.value = _jobs.value + RunningJob(id, title, kind, progress = null, cancel = handle::cancel)
        return handle
    }

    /** True while at least one job is running -- what [JobForegroundService] starts and stops on. */
    fun hasRunningJobs(): Boolean = _jobs.value.isNotEmpty()

    /** Stops every job still running. Called when the owning ViewModel is cleared. */
    fun cancelAll() {
        _jobs.value.forEach { it.cancel() }
    }

    private companion object {
        const val OUTCOME_BUFFER = 8
    }
}
