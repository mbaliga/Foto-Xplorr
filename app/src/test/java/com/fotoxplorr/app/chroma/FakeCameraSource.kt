package com.fotoxplorr.app.chroma

import java.util.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A camera that obeys the model the solver assumes (a matte patch under a room light plus a torch, one
 * locked exposure), records every call in order, and can be told to fail or to wait. It stands for the
 * Android seam so the job's order of calls, its release on stop and its failure paths are tested without
 * a device. It says nothing about how a real camera behaves.
 */
internal class FakeCameraSource(
    override val facts: CameraFacts = facts(),
) : CameraSource {
    val calls = mutableListOf<String>()
    val shots = mutableListOf<Shot>()
    var closeCount = 0
        private set

    private val _live = MutableStateFlow(LiveView())
    override val live: StateFlow<LiveView> = _live

    /** What the next shots are of. */
    var rho = LinearRgb(0.5, 0.3, 0.2)
    var room = LinearRgb(0.2, 0.15, 0.1)
    var led = LinearRgb(0.4, 0.42, 0.36)

    /** Thrown by [open] when set. */
    var openProblem: CameraProblem? = null

    /** When set, a shot waits on it (a capture in flight). */
    var shootGate: CompletableDeferred<Unit>? = null

    /** Runs inside each shot, before it returns (to move the phone, say). */
    var duringShoot: (torch: Boolean) -> Unit = {}

    /** The facts handed out with each shot; change [exposureNanos] between shots to make the lock slip. */
    var exposureNanos: Long = 10_000_000L
    var exposureWhenFlash: Long? = null
    var focusSettled = true
    var focusDiopters = 10f

    /** False hands out shots with no white-balance gains, as a camera that does not report them does. */
    var gainsReported = true

    private val rng = Random(7)

    override suspend fun open() {
        calls += "open"
        openProblem?.let { throw it }
    }

    override suspend fun lockLight(torch: Boolean): LockedLight {
        calls += "lock(torch=$torch)"
        return LockedLight(exposureNanos, 100, floatArrayOf(1.9f, 1f, 1f, 1.5f))
    }

    override suspend fun focus(torch: Boolean) {
        calls += "focus(torch=$torch)"
    }

    override suspend fun shoot(torch: Boolean): Shot {
        calls += "shoot(torch=$torch)"
        shootGate?.await()
        duringShoot(torch)
        val light = if (torch) LinearRgb.of { room[it] + led[it] } else room
        // Exposure locked on a card: the brightest flash channel of a 85% card lands at 0.7.
        var peak = 0.0
        for (c in 0..2) peak = maxOf(peak, 0.85 * (room[c] + led[c]))
        val frame = ChromaTestKit.frame(rho, light, 0.7 / peak, 0.01, 0.0, rng)
        val e = if (torch) exposureWhenFlash ?: exposureNanos else exposureNanos
        val gains = if (gainsReported) floatArrayOf(1.9f, 1f, 1f, 1.5f) else null
        val shot = Shot(frame, ShotFacts(e, 100, gains, focusDiopters, focusSettled))
        shots += shot
        return shot
    }

    override suspend fun endShots() {
        calls += "endShots"
    }

    override suspend fun unlock() {
        calls += "unlock"
    }

    override fun close() {
        calls += "close"
        closeCount++
    }

    companion object {
        fun facts(
            hasFlash: Boolean = true,
            manualSensor: Boolean = true,
            aeLock: Boolean = true,
            awbLock: Boolean = true,
            reportsGains: Boolean = true,
            knownTone: Boolean = true,
            focusCalibration: FocusCalibration = FocusCalibration.CALIBRATED,
        ) = CameraFacts(
            hasFlash = hasFlash,
            manualSensor = manualSensor,
            aeLock = aeLock,
            awbLock = awbLock,
            reportsGains = reportsGains,
            knownTone = knownTone,
            focusCalibration = focusCalibration,
            captureWidth = ChromaTestKit.SIZE,
            captureHeight = ChromaTestKit.SIZE,
            previewWidth = 640,
            previewHeight = 480,
            minFocusDiopters = 10f,
        )
    }
}
