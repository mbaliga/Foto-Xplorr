package com.fotoxplorr.app.jobs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The live job list, mirrored here so [JobForegroundService] can see it.
 *
 * A [com.fotoxplorr.app.jobs.JobRunner] lives inside `AppStateViewModel`, which a `Service`
 * has no direct handle on -- Android gives a Service no way to reach into another component's
 * ViewModelStore. Rather than binding the service to the Activity (real complexity for a job that
 * is meant to keep running when the Activity is not even in the foreground), the ViewModel is the
 * sole writer here, mirroring its own [JobRunner.jobs] on every change, and the service is a
 * read-only observer plus the thing that turns that list into a notification.
 */
object JobRunnerRegistry {
    private val _jobs = MutableStateFlow<List<RunningJob>>(emptyList())
    val jobs: StateFlow<List<RunningJob>> = _jobs.asStateFlow()

    fun publish(jobs: List<RunningJob>) {
        _jobs.value = jobs
    }

    /** Looks a running job up by id, for the service's own cancel action. */
    fun find(id: String): RunningJob? = _jobs.value.firstOrNull { it.id == id }
}
