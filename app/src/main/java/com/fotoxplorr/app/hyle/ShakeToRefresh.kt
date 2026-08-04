package com.fotoxplorr.app.hyle

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.sqrt

/**
 * Shake-to-refresh.
 *
 * Replaces the retired pull-to-backup gesture (owner direction, 2026-08-05): the pull-down
 * space at the top of a room belongs to the fonebrew top-room reveal, not to refresh — so
 * refresh moves off the touch plane entirely and onto the device itself. A deliberate shake
 * is hard to do by accident, needs no on-screen affordance or copy, and doesn't compete with
 * any scroll gesture, which is exactly why it suits an action whose only job is "look again".
 *
 * Detection: gravity-normalized acceleration must exceed [SHAKE_THRESHOLD_G] on
 * [SHAKES_REQUIRED] distinct peaks inside [SHAKE_WINDOW_MS] — one bump against a table is one
 * peak and does nothing; an actual shake is a back-and-forth train of them. After firing, a
 * [COOLDOWN_MS] refractory period stops one enthusiastic shake from triggering twice.
 *
 * The listener is registered only while the owning composition is RESUMED — backgrounded, the
 * sensor is off and costs nothing.
 */
@Composable
fun ShakeToRefresh(onShake: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnShake by rememberUpdatedState(onShake)

    DisposableEffect(lifecycleOwner) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val listener = object : SensorEventListener {
            private var peakTimes = LongArray(SHAKES_REQUIRED)
            private var peakCount = 0
            private var lastPeakAt = 0L
            private var firedAt = 0L

            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                if (gForce < SHAKE_THRESHOLD_G) return
                val now = System.currentTimeMillis()
                if (now - firedAt < COOLDOWN_MS) return
                // Successive readings above threshold belong to ONE peak; a new peak needs a
                // dip long enough for the hand to reverse direction.
                if (now - lastPeakAt < PEAK_SEPARATION_MS) return
                lastPeakAt = now
                // Slide the window: drop peaks older than the window, then record this one.
                val cutoff = now - SHAKE_WINDOW_MS
                var kept = 0
                for (i in 0 until peakCount) {
                    if (peakTimes[i] >= cutoff) peakTimes[kept++] = peakTimes[i]
                }
                peakCount = kept
                if (peakCount < peakTimes.size) peakTimes[peakCount++] = now
                if (peakCount >= SHAKES_REQUIRED) {
                    peakCount = 0
                    firedAt = now
                    currentOnShake()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (accelerometer != null) {
                        sensorManager.registerListener(
                            listener, accelerometer, SensorManager.SENSOR_DELAY_UI,
                        )
                    }
                }
                Lifecycle.Event.ON_PAUSE -> sensorManager?.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            sensorManager?.unregisterListener(listener)
        }
    }
}

// A firm, deliberate shake reads ~2.5-4g; walking and normal handling stay well under 2g.
private const val SHAKE_THRESHOLD_G = 2.4f
private const val SHAKES_REQUIRED = 3
private const val SHAKE_WINDOW_MS = 900L
private const val PEAK_SEPARATION_MS = 90L
private const val COOLDOWN_MS = 2_000L
