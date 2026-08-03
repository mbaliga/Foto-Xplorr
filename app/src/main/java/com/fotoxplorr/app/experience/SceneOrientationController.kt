package com.fotoxplorr.app.experience

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI

class SceneOrientationController(
    context: Context,
    private val onOrientation: (yawDegrees: Float, pitchDegrees: Float) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    @Volatile private var enabled = true
    @Volatile private var running = false
    private var baselineYaw: Float? = null
    private var baselinePitch: Float? = null
    private var filteredYaw = 0f
    private var filteredPitch = 0f

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            baselineYaw = null
            baselinePitch = null
            filteredYaw = 0f
            filteredPitch = 0f
            onOrientation(0f, 0f)
        }
    }

    fun start() {
        if (running || rotationSensor == null) return
        running = sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
    }

    fun calibrate() {
        baselineYaw = null
        baselinePitch = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!enabled || event.sensor.type != rotationSensor?.type) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val yaw = radiansToDegrees(orientation[0])
        val pitch = radiansToDegrees(orientation[1])
        val baseYaw = baselineYaw ?: yaw.also { baselineYaw = it }
        val basePitch = baselinePitch ?: pitch.also { baselinePitch = it }
        val relativeYaw = shortestAngle(yaw - baseYaw)
        val relativePitch = (pitch - basePitch).coerceIn(-70f, 70f)
        filteredYaw += shortestAngle(relativeYaw - filteredYaw) * FILTER_ALPHA
        filteredPitch += (relativePitch - filteredPitch) * FILTER_ALPHA
        onOrientation(filteredYaw, filteredPitch)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun radiansToDegrees(value: Float): Float = (value * 180f / PI.toFloat())

    private fun shortestAngle(value: Float): Float {
        var angle = value % 360f
        if (angle > 180f) angle -= 360f
        if (angle < -180f) angle += 360f
        return angle
    }

    private companion object {
        const val FILTER_ALPHA = 0.18f
    }
}
