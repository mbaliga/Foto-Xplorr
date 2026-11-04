package com.fotoxplorr.app.viewer.sphere

import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sqrt

/**
 * How big a copy of a photo sphere the 360 view may decode, and the guard around the decode. Pure
 * apart from [SphereFrameBuffers] (a Bitmap pair) and [StartedGate] (a lifecycle observer), so the
 * memory cap and the "a failure is a message, never a crash" rule are tests.
 */
internal object SphereLoading {
    /** The longest edge ever decoded: the frame is only [FRAME_W]-ish px wide, so more is waste. */
    const val MAX_EDGE = 4096

    /** Below this a sphere is too soft to look around in; the view says there is not enough memory. */
    const val MIN_EDGE = 1024

    /**
     * The longest edge (a 2:1 picture) whose ARGB copy -- the IntArray the projection reads, on the
     * Java heap -- fits in half of [availableHeapBytes], capped at [MAX_EDGE]. Null when even
     * [MIN_EDGE] does not fit.
     */
    fun decodeEdge(availableHeapBytes: Long): Int? {
        if (availableHeapBytes <= 0L) return null
        val pixels = availableHeapBytes / 2L / 4L
        // edge x edge/2 = pixels  ->  edge = sqrt(2 * pixels)
        val edge = sqrt(2.0 * pixels).toLong().coerceAtMost(MAX_EDGE.toLong()).toInt()
        return edge.takeIf { it >= MIN_EDGE }
    }

    /** What this process may still allocate on the Java heap, from the [Runtime]'s own figures. */
    fun availableHeapBytes(runtime: Runtime = Runtime.getRuntime()): Long =
        runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())

    /**
     * The size GPano's missing-field defaults are measured in: the ORIGINAL upright size (the unit
     * FullPano* is in), falling back to the decoded size only when the original is unknown.
     */
    fun metaSize(sourceWidth: Int, sourceHeight: Int, decodedWidth: Int, decodedHeight: Int): Pair<Int, Int> =
        if (sourceWidth > 0 && sourceHeight > 0) sourceWidth to sourceHeight else decodedWidth to decodedHeight

    /**
     * Runs [block]; any failure -- an exception from the decode seam (TRAPS #49: a vanished file or
     * a revoked grant throws before decodeUpright's own guard), or an OutOfMemoryError from the
     * pixel copy -- becomes [onFailure]'s value. Cancellation is not a failure and propagates.
     */
    suspend fun <T> guarded(onFailure: (Throwable) -> T, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onFailure(t)
        }

    /** The one-line message for a failure: memory for an OutOfMemoryError, the plain failure otherwise. */
    fun messageFor(t: Throwable?): String = if (t is OutOfMemoryError) SphereCopy.NO_MEMORY else SphereCopy.FAILED

    const val FRAME_W = 480
}

/**
 * Two reusable frame bitmaps, filled in turn, so a look-around does not allocate a new Bitmap per
 * frame. Two, not one: the frame on screen is never the one being written. A size change makes
 * new ones and leaves the old to the collector (the old frame may still be the one on screen).
 */
internal class SphereFrameBuffers {
    private val slots = arrayOfNulls<Bitmap>(2)
    private var next = 0

    fun fill(argb: IntArray, w: Int, h: Int): Bitmap {
        val slot = next
        next = 1 - next
        val reusable = slots[slot]?.takeIf { it.width == w && it.height == h && !it.isRecycled && it.isMutable }
        val bitmap = reusable ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { slots[slot] = it }
        bitmap.setPixels(argb, 0, w, 0, 0, w, h)
        return bitmap
    }
}

/**
 * Runs [start] while the lifecycle is at least STARTED and [stop] when it stops (or is destroyed,
 * or [close] is called), each once per transition. The gyro listener registers through this, so
 * Home or the lock key unregisters it on ON_STOP rather than only when the 360 view leaves composition.
 */
internal class StartedGate(private val start: () -> Unit, private val stop: () -> Unit) : LifecycleEventObserver {
    var running = false
        private set

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> open()
            Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> close()
            else -> Unit
        }
    }

    private fun open() {
        if (running) return
        running = true
        start()
    }

    fun close() {
        if (!running) return
        running = false
        stop()
    }
}
