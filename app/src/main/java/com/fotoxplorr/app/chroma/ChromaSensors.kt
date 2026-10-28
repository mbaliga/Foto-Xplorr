package com.fotoxplorr.app.chroma

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The gyroscope, read only while the Chroma screen is in front: [start] registers, [stop] lets go,
 * and the screen calls them on resume and pause (and on dispose). It feeds [MotionWindow], which does the
 * judging. A phone with no gyroscope is not an error: [steady] stays null and the movement check between
 * the two shots is simply not made.
 *
 * Events arrive on the main thread (no handler is given), the same thread [MotionWindow] and the
 * capture job are used from.
 */
internal class GyroSensor(context: Context, private val motion: MotionWindow) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyro: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var registered = false

    private val _steady = MutableStateFlow<Boolean?>(null)

    /** True when the phone is held still enough, false when shaking, null when not known. */
    val steady: StateFlow<Boolean?> = _steady.asStateFlow()

    val available: Boolean get() = gyro != null

    fun start() {
        if (registered || gyro == null) return
        registered = manager?.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME) == true
    }

    fun stop() {
        if (registered) manager?.unregisterListener(this)
        registered = false
        _steady.value = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        motion.push(sqrt(x * x + y * y + z * z))
        _steady.value = motion.steady
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
