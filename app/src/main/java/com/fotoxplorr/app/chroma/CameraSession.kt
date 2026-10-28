package com.fotoxplorr.app.chroma

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The camera the tool will use, and what it can do. Found without opening it, so before the permission. */
internal class CameraChoice(
    val id: String,
    val facts: CameraFacts,
    val sensorOrientation: Int,
    val maxFrameDurationNanos: Long?,
)

internal object CameraProbe {
    /** The first back camera that can produce ordinary pictures, else the first camera that can; null if none. */
    fun choose(context: Context): CameraChoice? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val ids = try {
            manager.cameraIdList
        } catch (e: CameraAccessException) {
            return null
        }
        val usable = ids.mapNotNull { id ->
            val chars = try {
                manager.getCameraCharacteristics(id)
            } catch (e: CameraAccessException) {
                return@mapNotNull null
            } catch (e: IllegalArgumentException) {
                return@mapNotNull null
            }
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
            if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) return@mapNotNull null
            id to chars
        }
        val pick = usable.firstOrNull { it.second.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: usable.firstOrNull()
            ?: return null
        return describe(pick.first, pick.second)
    }

    private fun describe(id: String, chars: CameraCharacteristics): CameraChoice? {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.map { CaptureSize.Size(it.width, it.height) }.orEmpty()
        val previews = map.getOutputSizes(SurfaceHolder::class.java)?.map { CaptureSize.Size(it.width, it.height) }.orEmpty()
        val preview = CaptureSize.pickPreview(previews) ?: return null
        val capture = CaptureSize.pickCapture(yuv, preview) ?: return null
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        val facts = CameraFacts(
            hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            manualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            aeLock = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
            awbLock = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
            captureWidth = capture.width,
            captureHeight = capture.height,
            previewWidth = preview.width,
            previewHeight = preview.height,
            minFocusDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
        )
        return CameraChoice(
            id = id,
            facts = facts,
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            maxFrameDurationNanos = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION),
        )
    }
}

/**
 * The real camera, over camera2 and nothing else (no CameraX, no extra dependency).
 *
 * ## Streams
 * Two: the on-screen preview and one small YUV_420_888 reader. The reader feeds both the live
 * checks (a look at the middle for washed-out pixels, a few times a second) and the two measuring
 * shots, so there is no third stream (which a basic camera might not allow). A repeating request
 * runs the whole time; the phases below only change its settings.
 *
 * ## How the light is held
 * [lockLight] switches the torch on (when asked) and lets auto exposure and white balance settle on
 * the scene lit that way, so the flash frame does not clip. It then freezes them: with MANUAL_SENSOR
 * the exposure time and ISO that auto exposure arrived at are set outright and auto exposure is turned
 * off; without it, the auto-exposure lock is used. White balance is locked either way when the camera
 * has the lock. Focus is separate ([focus]) and is settled once per pair, because the distance of
 * that pair is wanted.
 *
 * ## The flash is a torch
 * The second shot is taken with the torch held on, not with a flash burst. A torch is steady, so
 * there is no pre-flash and no ramp to guess at, and the locked exposure sees one constant light.
 * The price is a dimmer LED than a burst gives, so the tool works closer in. Every frame is matched to
 * the result the camera reported for it, and [LockCheck] (in the job) refuses a pair whose exposure,
 * ISO or white balance differ.
 *
 * ## What has not been verified
 * Nothing here has run on a device. In particular: whether the torch settles within the waits below,
 * whether AE lock really freezes the exposure with the torch on (the report check says), whether
 * the focus distance is accurate at 10 to 40 cm, and which phones allow a torch with manual exposure.
 *
 * Frames live in memory only: the pixels are converted and the copies of the camera's bytes are zeroed
 * at once. Every callback runs on one background thread; every wait is bounded and fails with a
 * [CameraProblem]; [close] is idempotent and releases the device, session, reader and thread (the reader
 * and the thread's end are queued on that thread, behind a frame that is being handled).
 */
@SuppressLint("MissingPermission")
internal class Camera2Source(
    private val context: Context,
    private val choice: CameraChoice,
    private val previewSurface: StateFlow<Surface?>,
) : CameraSource {
    override val facts: CameraFacts get() = choice.facts

    private val _live = MutableStateFlow(LiveView())
    override val live: StateFlow<LiveView> = _live.asStateFlow()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var surface: Surface? = null

    @Volatile private var closed = true

    @Volatile private var epoch = 0

    /** Set when the camera is lost under a running wait. */
    private val lost = MutableStateFlow<CaptureFailure?>(null)

    private val snaps = MutableStateFlow(Snap.NONE)
    private val recent = object : LinkedHashMap<Long, Snap>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Snap>?): Boolean = size > RECENT
    }

    @Volatile private var want: Want? = null
    @Volatile private var held: HeldValues? = null
    @Volatile private var torch = false
    @Volatile private var afAuto = false
    private var imagesSeen = 0

    private class Want(val fromTimestamp: Long, val result: CompletableDeferred<Pair<Long, ChromaFrame>>)

    private class HeldValues(val exposureNanos: Long?, val iso: Int?, val gains: FloatArray?, val manual: Boolean)

    private class Snap(
        val count: Long,
        val timestamp: Long,
        val aeState: Int?,
        val awbState: Int?,
        val afState: Int?,
        val exposureNanos: Long?,
        val iso: Int?,
        val gains: FloatArray?,
        val focusDiopters: Float?,
    ) {
        val focusSettled: Boolean
            get() = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED

        companion object {
            val NONE = Snap(0L, 0L, null, null, null, null, null, null, null)
        }
    }

    // ---- opening ---------------------------------------------------------------------------------------

    override suspend fun open() {
        close()
        closed = false
        val mine = ++epoch
        lost.value = null
        snaps.value = Snap.NONE
        _live.value = LiveView()
        val target = previewSurface.filterNotNull().first()
        val t = HandlerThread("chroma-camera").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val dev = openDevice(mine, h)
        device = dev
        val size = choice.facts
        val r = ImageReader.newInstance(size.captureWidth, size.captureHeight, ImageFormat.YUV_420_888, READER_IMAGES)
        reader = r
        r.setOnImageAvailableListener({ onImage(it) }, h)
        surface = target
        session = createSession(dev, listOf(target, r.surface), h, mine)
        torch = false
        held = null
        afAuto = false
        applyRepeating()
        // The first frames prove the camera is delivering before the screen calls it ready.
        guarded(OPEN_WAIT_MS) { snaps.first { it.count >= 3 } }
    }

    private suspend fun openDevice(mine: Int, h: Handler): CameraDevice = suspendCancellableCoroutine { cont ->
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (closed || mine != epoch || !cont.isActive) {
                    camera.close()
                } else {
                    // If the screen is left between this callback and the coroutine running again, nobody else
                    // holds the device: it is closed here, or the camera would stay busy for other apps.
                    cont.resume(camera) { _, _, _ -> camera.close() }
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                loseCamera(mine, CaptureFailure.DISCONNECTED)
                if (cont.isActive) cont.resumeWithException(CameraProblem(CaptureFailure.DISCONNECTED))
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                val why = when (error) {
                    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE, CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> CaptureFailure.IN_USE
                    else -> CaptureFailure.FAILED
                }
                loseCamera(mine, why)
                if (cont.isActive) cont.resumeWithException(CameraProblem(why))
            }
        }
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.openCamera(choice.id, callback, h)
        } catch (e: CameraAccessException) {
            cont.resumeWithException(CameraProblem(accessFailure(e), e))
        } catch (e: SecurityException) {
            cont.resumeWithException(CameraProblem(CaptureFailure.FAILED, e))
        } catch (e: IllegalArgumentException) {
            cont.resumeWithException(CameraProblem(CaptureFailure.NO_CAMERA, e))
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun createSession(dev: CameraDevice, targets: List<Surface>, h: Handler, mine: Int): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            try {
                dev.createCaptureSession(
                    targets,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (closed || mine != epoch || !cont.isActive) s.close() else cont.resume(s) { _, _, _ -> s.close() }
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            if (cont.isActive) cont.resumeWithException(CameraProblem(CaptureFailure.FAILED))
                        }
                    },
                    h,
                )
            } catch (e: CameraAccessException) {
                cont.resumeWithException(CameraProblem(accessFailure(e), e))
            } catch (e: IllegalStateException) {
                cont.resumeWithException(CameraProblem(CaptureFailure.DISCONNECTED, e))
            }
        }

    private fun accessFailure(e: CameraAccessException): CaptureFailure = when (e.reason) {
        CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE -> CaptureFailure.IN_USE
        CameraAccessException.CAMERA_DISCONNECTED -> CaptureFailure.DISCONNECTED
        else -> CaptureFailure.FAILED
    }

    private fun loseCamera(mine: Int, why: CaptureFailure) {
        if (mine == epoch && !closed) lost.value = why
    }

    // ---- the repeating request -----------------------------------------------------------------------

    private enum class Af { CONTINUOUS, AUTO }

    private fun buildRequest(afTrigger: Int? = null, afCancel: Boolean = false): CaptureRequest {
        val dev = device ?: throw CameraProblem(CaptureFailure.DISCONNECTED)
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        surface?.let { b.addTarget(it) }
        reader?.let { b.addTarget(it.surface) }
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        val f = choice.facts
        val fixedFocus = f.minFocusDiopters != null && f.minFocusDiopters == 0f
        b.set(
            CaptureRequest.CONTROL_AF_MODE,
            when {
                fixedFocus -> CaptureRequest.CONTROL_AF_MODE_OFF
                afAuto -> CaptureRequest.CONTROL_AF_MODE_AUTO
                else -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            },
        )
        if (afTrigger != null) b.set(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger)
        if (afCancel) b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)

        if (f.hasFlash) b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)

        val h = held
        if (h == null) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        } else {
            if (h.manual && h.exposureNanos != null && h.iso != null) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, h.exposureNanos)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, h.iso)
                val duration = maxOf(h.exposureNanos, MIN_FRAME_DURATION_NANOS)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, choice.maxFrameDurationNanos?.let { minOf(duration, it) } ?: duration)
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AE_LOCK, true)
            }
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            if (f.awbLock) b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        return b.build()
    }

    private fun applyRepeating() {
        val s = session ?: throw CameraProblem(CaptureFailure.DISCONNECTED)
        try {
            s.setRepeatingRequest(buildRequest(), callback, handler)
        } catch (e: CameraAccessException) {
            throw CameraProblem(accessFailure(e), e)
        } catch (e: IllegalStateException) {
            throw CameraProblem(CaptureFailure.DISCONNECTED, e)
        }
    }

    private val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            onResult(result)
        }
    }

    private fun onResult(r: TotalCaptureResult) {
        val gains = r.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { g ->
            FloatArray(4).also { arr -> g.copyTo(arr, 0) }
        }
        val prev = snaps.value
        val snap = Snap(
            count = prev.count + 1,
            timestamp = r.get(CaptureResult.SENSOR_TIMESTAMP) ?: prev.timestamp,
            aeState = r.get(CaptureResult.CONTROL_AE_STATE),
            awbState = r.get(CaptureResult.CONTROL_AWB_STATE),
            afState = r.get(CaptureResult.CONTROL_AF_STATE),
            exposureNanos = r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            iso = r.get(CaptureResult.SENSOR_SENSITIVITY),
            gains = gains,
            focusDiopters = r.get(CaptureResult.LENS_FOCUS_DISTANCE),
        )
        synchronized(recent) { recent[snap.timestamp] = snap }
        snaps.value = snap
        // The room reading is only meaningful before the torch and the hold change the exposure.
        if (snap.count % LIVE_EVERY == 0L && held == null && !torch) {
            val metres = if (snap.focusSettled) FocusDistance.metresFromDiopters(snap.focusDiopters) else null
            _live.update { it.copy(distanceMetres = metres, brightRoom = RoomLight.isBright(snap.exposureNanos, snap.iso)) }
        }
    }

    // ---- frames --------------------------------------------------------------------------------------

    private fun onImage(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        var taken: Want? = null
        try {
            val ts = image.timestamp
            val w = want
            if (w != null && ts >= w.fromTimestamp) {
                want = null
                taken = w
                val planes = planesOf(image)
                try {
                    w.result.complete(ts to YuvFrames.toFrame(planes))
                } finally {
                    planes.clear()
                }
            } else if (++imagesSeen % ANALYSE_EVERY == 0 && held == null && !torch) {
                val planes = planesOf(image)
                val share = try {
                    YuvFrames.clippedShare(planes)
                } finally {
                    planes.clear()
                }
                _live.update { it.copy(clippedShare = share) }
            }
        } catch (e: IllegalStateException) {
            // The reader was closed under this frame (the screen was left mid-measurement). close() normally
            // waits for the frame, so this is a backstop: nothing may take the camera thread down with it.
            taken?.result?.completeExceptionally(CameraProblem(CaptureFailure.DISCONNECTED, e))
        } finally {
            try {
                image.close()
            } catch (e: IllegalStateException) {
                // Already gone with its reader.
            }
        }
    }

    private fun planesOf(image: Image): YuvPlanes {
        val p = image.planes
        fun bytes(b: java.nio.ByteBuffer): ByteArray = ByteArray(b.remaining()).also { b.duplicate().get(it) }
        return YuvPlanes(
            width = image.width,
            height = image.height,
            y = bytes(p[0].buffer),
            yRowStride = p[0].rowStride,
            u = bytes(p[1].buffer),
            v = bytes(p[2].buffer),
            uvRowStride = p[1].rowStride,
            uvPixelStride = p[1].pixelStride,
        )
    }

    // ---- the sequence --------------------------------------------------------------------------------

    override suspend fun lockLight(torch: Boolean): LockedLight {
        this.held = null
        afAuto = false
        this.torch = torch && choice.facts.hasFlash
        applyRepeating()
        val start = snaps.value.count
        val settled = guarded(SETTLE_WAIT_MS) {
            snaps.first { it.count >= start + SETTLE_FRAMES && aeSettled(it) && awbSettled(it) }
        }
        val kind = choice.facts.lockKind
        if (kind == LockKind.NONE) throw CameraProblem(CaptureFailure.NO_LOCK)
        val manual = kind == LockKind.MANUAL && settled.exposureNanos != null && settled.iso != null
        if (!manual && !choice.facts.aeLock) throw CameraProblem(CaptureFailure.NO_LOCK)
        held = HeldValues(settled.exposureNanos, settled.iso, settled.gains, manual)
        applyRepeating()
        val from = snaps.value.count
        guarded(SETTLE_WAIT_MS) { snaps.first { it.count >= from + HOLD_FRAMES } }
        return LockedLight(settled.exposureNanos, settled.iso, settled.gains)
    }

    private fun aeSettled(s: Snap): Boolean = s.aeState == null || s.aeState in AE_DONE
    private fun awbSettled(s: Snap): Boolean = s.awbState == null || s.awbState in AWB_DONE

    override suspend fun focus(torch: Boolean) {
        val f = choice.facts
        if (f.minFocusDiopters != null && f.minFocusDiopters == 0f) return
        if (held == null) throw CameraProblem(CaptureFailure.FAILED)
        this.torch = torch && f.hasFlash
        afAuto = true
        applyRepeating()
        val s = session ?: throw CameraProblem(CaptureFailure.DISCONNECTED)
        val start = snaps.value.count
        try {
            s.capture(buildRequest(afTrigger = CaptureRequest.CONTROL_AF_TRIGGER_START), callback, handler)
        } catch (e: CameraAccessException) {
            throw CameraProblem(accessFailure(e), e)
        } catch (e: IllegalStateException) {
            throw CameraProblem(CaptureFailure.DISCONNECTED, e)
        }
        // A focus that never settles (a dark, blank wall) is not a failure: the shots then carry no distance.
        guardedOrNull(FOCUS_WAIT_MS) {
            snaps.first {
                it.count >= start + FOCUS_FRAMES && (
                    it.afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        it.afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                    )
            }
        }
    }

    override suspend fun shoot(torch: Boolean): Shot {
        val f = choice.facts
        if (held == null) throw CameraProblem(CaptureFailure.FAILED)
        val wantTorch = torch && f.hasFlash
        val changed = this.torch != wantTorch
        this.torch = wantTorch
        applyRepeating()
        val from = snaps.value.count
        val startedAt = SystemClock.elapsedRealtime()
        if (changed) {
            guarded(SETTLE_WAIT_MS) { snaps.first { it.count >= from + TORCH_FRAMES } }
            val left = TORCH_MIN_MS - (SystemClock.elapsedRealtime() - startedAt)
            if (left > 0) delay(left)
        } else {
            guarded(SETTLE_WAIT_MS) { snaps.first { it.count >= from + HOLD_FRAMES } }
        }
        val request = Want(snaps.value.timestamp + 1, CompletableDeferred())
        want = request
        val (ts, frame) = try {
            guarded(SHOT_WAIT_MS) { request.result.await() }
        } finally {
            if (want === request) want = null
        }
        val snap = factsFor(ts)
        return Shot(
            frame,
            ShotFacts(
                exposureNanos = snap.exposureNanos,
                iso = snap.iso,
                gains = snap.gains,
                focusDiopters = snap.focusDiopters,
                focusSettled = snap.focusSettled && afAuto,
            ),
        )
    }

    /** The result the camera reported for the frame taken at [timestamp], else the latest one. */
    private suspend fun factsFor(timestamp: Long): Snap {
        repeat(RESULT_POLLS) {
            synchronized(recent) { recent[timestamp] }?.let { return it }
            delay(RESULT_POLL_MS)
        }
        return snaps.value
    }

    override suspend fun endShots() {
        torch = false
        afAuto = false
        val s = session ?: return
        try {
            applyRepeating()
            s.capture(buildRequest(afCancel = true), callback, handler)
        } catch (e: Exception) {
            // Closing down: a camera that is already gone has nothing to put back.
        }
    }

    override suspend fun unlock() {
        held = null
        torch = false
        afAuto = false
        if (session == null) return
        try {
            applyRepeating()
        } catch (e: CameraProblem) {
            // Same: nothing to restore on a camera that is gone.
        }
    }

    // ---- waiting and releasing -------------------------------------------------------------------------

    /** Runs [block] for at most [timeoutMs]; a lost camera or a timeout becomes a [CameraProblem]. */
    private suspend fun <T : Any> guarded(timeoutMs: Long, block: suspend () -> T): T =
        guardedOrNull(timeoutMs, block) ?: throw CameraProblem(CaptureFailure.TIMED_OUT)

    private suspend fun <T : Any> guardedOrNull(timeoutMs: Long, block: suspend () -> T): T? = coroutineScope {
        lost.value?.let { throw CameraProblem(it) }
        val watch = launch { throw CameraProblem(lost.filterNotNull().first()) }
        try {
            withTimeoutOrNull(timeoutMs) { block() }
        } finally {
            watch.cancel()
        }
    }

    override fun close() {
        closed = true
        epoch++
        want?.result?.cancel()
        want = null
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        val r = reader
        val t = thread
        val h = handler
        runCatching { r?.setOnImageAvailableListener(null, null) }
        session = null
        device = null
        reader = null
        surface = null
        held = null
        torch = false
        thread = null
        handler = null
        _live.value = LiveView()
        // The reader is closed ON the camera thread, behind any frame being handled there: closing it from
        // here would free an image (and its buffers) under a conversion that is still reading it.
        if (t != null) {
            val posted = h != null && h.post {
                runCatching { r?.close() }
                t.quitSafely()
            }
            if (!posted) {
                runCatching { r?.close() }
                runCatching { t.quitSafely() }
            }
        } else {
            runCatching { r?.close() }
        }
    }

    private companion object {
        const val READER_IMAGES = 4
        const val RECENT = 24
        const val LIVE_EVERY = 6L
        const val ANALYSE_EVERY = 4
        const val OPEN_WAIT_MS = 5_000L
        const val SETTLE_WAIT_MS = 4_000L
        const val FOCUS_WAIT_MS = 3_000L
        const val SHOT_WAIT_MS = 3_000L
        const val SETTLE_FRAMES = 8L
        const val HOLD_FRAMES = 4L
        const val FOCUS_FRAMES = 3L
        const val TORCH_FRAMES = 8L
        const val TORCH_MIN_MS = 350L
        const val RESULT_POLLS = 10
        const val RESULT_POLL_MS = 30L
        const val MIN_FRAME_DURATION_NANOS = 33_333_333L
        val AE_DONE = setOf(
            CaptureResult.CONTROL_AE_STATE_CONVERGED,
            CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED,
            CaptureResult.CONTROL_AE_STATE_LOCKED,
        )
        val AWB_DONE = setOf(CaptureResult.CONTROL_AWB_STATE_CONVERGED, CaptureResult.CONTROL_AWB_STATE_LOCKED)
    }
}
