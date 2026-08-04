package com.fotoxplorr.app.experience

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.PI

enum class SceneOrientationMode {
    RELATIVE,
    ABSOLUTE_NORTH,
}

class SceneOrientationController(
    context: Context,
    private val mode: SceneOrientationMode = SceneOrientationMode.RELATIVE,
    private val onOrientation: (yawDegrees: Float, pitchDegrees: Float) -> Unit,
    private val onAccuracy: (Int) -> Unit = {},
) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = when (mode) {
        SceneOrientationMode.RELATIVE -> sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        SceneOrientationMode.ABSOLUTE_NORTH -> sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }
    private val rotationSensorType: Int? = rotationSensor?.type
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    @Volatile private var enabled = true
    @Volatile private var running = false
    private var baselineYaw: Float? = null
    private var baselinePitch: Float? = null
    private var filteredYaw = 0f
    private var filteredPitch = 0f

    val hasSensor: Boolean
        get() = rotationSensor != null

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
        val sensor = rotationSensor ?: return
        if (running) return
        running = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
    }

    fun calibrate() {
        baselineYaw = null
        baselinePitch = null
        filteredYaw = 0f
        filteredPitch = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        val expectedType = rotationSensorType ?: return
        if (!enabled || event.sensor.type != expectedType) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val matrix = remapForDisplay(rotationMatrix)
        SensorManager.getOrientation(matrix, orientation)
        val rawYaw = normalizeDegrees(radiansToDegrees(orientation[0]))
        val rawPitch = radiansToDegrees(orientation[1]).coerceIn(-85f, 85f)

        val targetYaw = when (mode) {
            SceneOrientationMode.RELATIVE -> {
                val base = baselineYaw ?: rawYaw.also { baselineYaw = it }
                shortestAngle(rawYaw - base)
            }
            SceneOrientationMode.ABSOLUTE_NORTH -> rawYaw
        }
        val targetPitch = when (mode) {
            SceneOrientationMode.RELATIVE -> {
                val base = baselinePitch ?: rawPitch.also { baselinePitch = it }
                (rawPitch - base).coerceIn(-70f, 70f)
            }
            SceneOrientationMode.ABSOLUTE_NORTH -> rawPitch
        }

        filteredYaw = when (mode) {
            SceneOrientationMode.RELATIVE -> filteredYaw + shortestAngle(targetYaw - filteredYaw) * FILTER_ALPHA
            SceneOrientationMode.ABSOLUTE_NORTH -> normalizeDegrees(
                filteredYaw + shortestAngle(targetYaw - filteredYaw) * FILTER_ALPHA,
            )
        }
        filteredPitch += (targetPitch - filteredPitch) * FILTER_ALPHA
        onOrientation(filteredYaw, filteredPitch)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val expectedType = rotationSensorType ?: return
        if (sensor?.type == expectedType) onAccuracy(accuracy)
    }

    @Suppress("DEPRECATION")
    private fun remapForDisplay(source: FloatArray): FloatArray {
        val rotation = windowManager.defaultDisplay.rotation
        val (axisX, axisY) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        return if (SensorManager.remapCoordinateSystem(source, axisX, axisY, remappedMatrix)) {
            remappedMatrix
        } else {
            source
        }
    }

    private fun radiansToDegrees(value: Float): Float = value * 180f / PI.toFloat()

    private fun normalizeDegrees(value: Float): Float {
        var angle = value % 360f
        if (angle < 0f) angle += 360f
        return angle
    }

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
