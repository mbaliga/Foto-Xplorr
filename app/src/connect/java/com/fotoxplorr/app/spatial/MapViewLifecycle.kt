package com.fotoxplorr.app.spatial

/**
 * The one place a live map view's lifecycle is driven, so it can only ever run in a legal order, once.
 *
 * The crash this exists for (owner's report, 2026-11-05: `RejectedExecutionException` from the map's
 * `GLThread` in `onDrawFrame`, "Terminated, pool size = 0"): the map's renderer shuts its worker pool
 * down in `onDestroy`, but its GL thread keeps drawing for as long as the view is attached to a window.
 * The screen destroyed the view while it was still attached (from the Compose disposal, and again from
 * the activity's own `onDestroy`), so a frame landed after the pool was gone. Two rules fix it, and this
 * class holds both:
 *
 * 1. **Detach first.** [onDestroy] runs [detach] (take the view out of its parent, which makes the
 *    surface view stop its GL thread and wait for it) before the destroy, so no frame can follow it.
 * 2. **Once, and in order.** Every call after the destroy is ignored, a destroy implies the pause and stop
 *    that were still owed, and a pause or stop that was never preceded by a start/resume is not sent.
 *
 * The lambdas keep the class free of the map library, which is why it is unit-tested on the JVM; the
 * detach-then-destroy order against the real renderer is a device check.
 */
internal class MapViewLifecycle(
    private val start: () -> Unit,
    private val resume: () -> Unit,
    private val pause: () -> Unit,
    private val stop: () -> Unit,
    private val detach: () -> Unit,
    private val destroy: () -> Unit,
) {
    private var started = false
    private var resumed = false
    var destroyed = false
        private set

    fun onStart() {
        if (destroyed || started) return
        started = true
        start()
    }

    fun onResume() {
        if (destroyed || resumed) return
        onStart()
        resumed = true
        resume()
    }

    fun onPause() {
        if (destroyed || !resumed) return
        resumed = false
        pause()
    }

    fun onStop() {
        if (destroyed) return
        onPause()
        if (!started) return
        started = false
        stop()
    }

    fun onDestroy() {
        if (destroyed) return
        onStop()
        destroyed = true
        detach()
        destroy()
    }
}
