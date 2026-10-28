package com.fotoxplorr.app.chroma

import kotlin.math.abs
import kotlinx.coroutines.flow.StateFlow

/**
 * What the phone's camera can do, found without opening it (so before the permission is granted).
 *
 * @property hasFlash the camera has an LED that can be held on (a torch).
 * @property manualSensor the camera can be told an exposure time and ISO outright (the MANUAL_SENSOR
 *   capability). If it cannot, the exposure is frozen by the auto-exposure lock instead.
 * @property aeLock the auto-exposure lock is available.
 * @property awbLock the auto-white-balance lock is available. Without it the white balance may drift
 *   between the shots; [LockCheck] notices a drift the camera reports, it cannot stop one.
 * @property captureWidth the size the two measuring shots are taken at: small on purpose, this is
 *   colour measurement, not photography.
 * @property previewWidth the size of the on-screen preview; same shape as the capture size.
 * @property minFocusDiopters the nearest the lens can focus, in dioptres (10 is 10 cm); 0 for a
 *   fixed-focus lens, null when the camera does not say.
 */
internal class CameraFacts(
    val hasFlash: Boolean,
    val manualSensor: Boolean,
    val aeLock: Boolean,
    val awbLock: Boolean,
    val captureWidth: Int,
    val captureHeight: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val minFocusDiopters: Float?,
) {
    /** How the exposure is held between the shots, or [LockKind.NONE] when the camera cannot. */
    val lockKind: LockKind
        get() = when {
            manualSensor -> LockKind.MANUAL
            aeLock -> LockKind.AE_LOCK
            else -> LockKind.NONE
        }
}

internal enum class LockKind { MANUAL, AE_LOCK, NONE }

/**
 * What a frame was actually taken with, as the camera reported it for that frame. Null means the
 * camera did not say, and a null can never prove the exposure slipped.
 */
internal class ShotFacts(
    val exposureNanos: Long?,
    val iso: Int?,
    /** The white-balance gains applied (red, green even, green odd, blue), or null. */
    val gains: FloatArray?,
    /** The lens focus distance in dioptres, and whether the focus had settled for this frame. */
    val focusDiopters: Float?,
    val focusSettled: Boolean,
)

/** One captured frame and what it was taken with. The frame is in memory only; nothing here is saved. */
internal class Shot(val frame: ChromaFrame, val facts: ShotFacts) {
    /** The flash-to-spot distance in metres, or null when focus had not settled or the lens is fixed. */
    val distanceMetres: Double?
        get() = if (facts.focusSettled) FocusDistance.metresFromDiopters(facts.focusDiopters) else null

    /** Overwrites the pixels so the picture does not outlive the result. */
    fun clear() = frame.argb.fill(0)
}

/** The exposure the camera settled on and then held for every shot that follows. */
internal class LockedLight(val exposureNanos: Long?, val iso: Int?, val gains: FloatArray?)

/** Why the camera could not take the pictures. [closesCamera] says whether it must be opened again. */
internal enum class CaptureFailure(val message: String, val closesCamera: Boolean) {
    NO_CAMERA(ChromaCopy.NO_CAMERA, true),
    IN_USE(ChromaCopy.CAMERA_BUSY, true),
    DISCONNECTED(ChromaCopy.CAMERA_LOST, true),
    FAILED(ChromaCopy.CAMERA_FAILED, true),
    NO_LOCK(ChromaCopy.NO_LOCK, false),
    LOCK_SLIPPED(ChromaCopy.LOCK_SLIPPED, false),
    MOVED(ChromaCopy.MOVED, false),
    TIMED_OUT(ChromaCopy.TIMED_OUT, false),
}

/** What a [CameraSource] throws; the job turns it into a [CaptureState.Failed]. */
internal class CameraProblem(val failure: CaptureFailure, cause: Throwable? = null) : Exception(failure.message, cause)

/** What the live preview says about the view, refreshed a few times a second. */
internal data class LiveView(
    /** The share of the middle of the view at or above the clip code, or null before the first look. */
    val clippedShare: Double? = null,
    /** The focus distance in metres when focus has settled, else null. */
    val distanceMetres: Double? = null,
    /** True when the exposure the camera chose says the room is bright (see [RoomLight]). */
    val brightRoom: Boolean = false,
) {
    val washedOut: Boolean get() = clippedShare != null && clippedShare > PatchSampler.MAX_CLIPPED_SHARE
}

/**
 * The camera, as the capture job sees it. The real one is [Camera2Source]; the tests use a fake, so
 * the order of calls, the release on stop and the failure paths are checked without a device.
 *
 * All calls are cancellable. The shots are taken with the exposure, ISO and white balance of
 * [lockLight] held, because the flash-minus-ambient subtraction means nothing if any of them moves.
 */
internal interface CameraSource {
    val facts: CameraFacts

    /** What the preview sees now. */
    val live: StateFlow<LiveView>

    /** Opens the camera and starts the preview. */
    suspend fun open()

    /**
     * With the torch on when [torch] (it helps the camera pick an exposure that the flash will not
     * wash out), lets auto exposure and white balance settle, then holds them. Returns what was held.
     */
    suspend fun lockLight(torch: Boolean): LockedLight

    /**
     * Settles the focus for the pair about to be shot (with the torch on when [torch], which helps in a
     * dim room) and then holds it, so the distance read with the shots is the one they were taken at.
     * A fixed-focus lens, or a focus that never settles, is not an error: the shots then say so.
     */
    suspend fun focus(torch: Boolean)

    /**
     * Takes one frame with the lock on and the torch on or off, after settling focus and the light.
     * Call it once with the torch off, then once with it on.
     */
    suspend fun shoot(torch: Boolean): Shot

    /** Torch off and focus free again; the lock stays. Safe to call at any time. */
    suspend fun endShots()

    /** Lets go of the lock and returns to the plain preview. Safe to call at any time. */
    suspend fun unlock()

    /** Releases the camera and everything it holds. Idempotent, never throws. */
    fun close()
}

/** The check that the exposure and white balance really stayed put. */
internal object LockCheck {
    /** How far apart two readings may be, as a fraction. Frame-to-frame reporting is exact when locked. */
    const val TOLERANCE = 0.02

    /** True when [a] and [b] disagree about the exposure time, ISO or white balance by more than [TOLERANCE]. */
    fun slipped(a: ShotFacts, b: ShotFacts): Boolean {
        if (apart(a.exposureNanos?.toDouble(), b.exposureNanos?.toDouble())) return true
        if (apart(a.iso?.toDouble(), b.iso?.toDouble())) return true
        val ga = a.gains
        val gb = b.gains
        if (ga != null && gb != null && ga.size == gb.size) {
            for (i in ga.indices) if (apart(ga[i].toDouble(), gb[i].toDouble())) return true
        }
        return false
    }

    private fun apart(x: Double?, y: Double?): Boolean {
        if (x == null || y == null) return false
        val top = maxOf(abs(x), abs(y))
        return top > 0.0 && abs(x - y) / top > TOLERANCE
    }
}
