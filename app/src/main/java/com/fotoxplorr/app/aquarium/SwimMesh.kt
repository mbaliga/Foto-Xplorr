package com.fotoxplorr.app.aquarium

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How one creature undulates. All three are derived from the drawing's aspect by
 * [CreatureParams.forAspect]; they are starting values to tune on a device, not measured ones.
 */
data class SwimParams(
    /** Full wave cycles per second at cruise. */
    val waveHz: Float,
    /** Wavelengths that fit along the body. */
    val wavelengths: Float,
    /** Side-to-side swing of the tail tip, as a fraction of the sprite WIDTH. */
    val amplitude: Float,
    /** Exponent of the head-to-tail growth of the swing: 1 is linear, larger keeps the head stiller. */
    val tailBias: Float = 1.5f,
)

/**
 * The pose of one sprite for one frame, written by the caller and read by [SwimMesh.fill]. One
 * instance per creature (or one shared) is reused every frame so drawing allocates nothing.
 */
class SwimPose {
    var widthPx = 1f
    var heightPx = 1f
    var centerX = 0f
    var centerY = 0f

    /** Radians, from [Creature.phase]. */
    var phase = 0f

    /** 0 = a rigid sprite, 1 = the full wave; the motion profile's wave scale. */
    var waveScale = 1f

    /** +1 shows the sprite as drawn, -1 mirrored, 0 edge-on (halfway through a turn). */
    var flipX = 1f

    /** Radians, tilt about the centre, positive nose-down on a y-down canvas. */
    var pitch = 0f

    /** True when the drawing's head is on the right of the bitmap (the default). */
    var headRight = true
}

/**
 * The mesh warp that makes a flat doodle swim: a grid of [COLS] x [ROWS] cells whose columns slide
 * up and down along a sine wave that travels from head to tail and grows toward the tail, with a
 * little extra flap at the very end. [fill] writes the vertex array that
 * `Canvas.drawBitmapMesh(bitmap, COLS, ROWS, verts, 0, null, 0, paint)` wants, in canvas pixels.
 *
 * Pure arithmetic on floats; [fill] writes into a caller-owned array and allocates nothing, so a
 * dozen creatures cost no garbage per frame. UNVERIFIED on a device: whether drawBitmapMesh is
 * smooth and cheap on a minSdk 26 phone, and whether these amplitudes read well, are the owner's
 * first device checks.
 */
object SwimMesh {
    const val COLS = 8
    const val ROWS = 4
    const val VERTEX_COUNT = (COLS + 1) * (ROWS + 1)

    /** Length of the array [fill] writes: x then y for every vertex, row by row. */
    const val FLOATS = VERTEX_COUNT * 2

    /** The head still swings this much of the tail's amplitude. */
    const val HEAD_FLOOR = 0.1f

    /** The last quarter of the body gets a faster second harmonic, the tail flapping. */
    const val TAIL_FLAP_ZONE = 0.25f
    const val TAIL_FLAP_GAIN = 0.3f

    /** A column never advances along the body by less than this share of its resting step. */
    private const val MIN_STEP = 0.2f

    /** Steepest tilt either way, about 35 degrees. */
    const val MAX_PITCH = 0.61f

    /** Opposite velocity must exceed this share of the top speed before the creature turns round. */
    const val FLIP_HYSTERESIS = 0.05f

    /** A full turn from +1 to -1 takes 0.3 s. */
    const val FLIP_RATE = 2f / 0.3f

    private const val TWO_PI = (2.0 * PI).toFloat()

    /** Swing strength along the body: [u] is 0 at the tail and 1 at the head. */
    fun envelope(u: Float, tailBias: Float): Float {
        val tail = (1f - u.coerceIn(0f, 1f))
        val bias = if (tailBias.isFinite()) tailBias.coerceIn(0.5f, 4f) else 1.5f
        return HEAD_FLOOR + (1f - HEAD_FLOOR) * tail.pow(bias)
    }

    /**
     * Sideways offset at body position [u] (0 tail .. 1 head) for wave [phase], in sprite widths at
     * full strength. The argument grows with phase and falls toward the head, so crests travel from
     * the head to the tail.
     */
    fun lateral(u: Float, phase: Float, p: SwimParams): Float {
        val theta = TWO_PI * p.wavelengths * u + phase
        var wave = sin(theta)
        if (u < TAIL_FLAP_ZONE) {
            val k = 1f - u / TAIL_FLAP_ZONE
            wave += TAIL_FLAP_GAIN * k * k * sin(2f * theta + 0.5f)
        }
        return p.amplitude * envelope(u, p.tailBias) * wave
    }

    /**
     * Writes the [FLOATS] vertex coordinates for [pose] into [out]. Rows run top to bottom, columns
     * left to right in BITMAP space, so the array matches drawBitmapMesh whichever way the head
     * points. The head column is anchored and the tail is drawn in toward it as the body bends, which
     * keeps the middle row within a few percent of its resting length. Non-finite input is replaced
     * by a safe value, so the output is always finite.
     */
    fun fill(out: FloatArray, pose: SwimPose, swim: SwimParams) {
        require(out.size >= FLOATS) { "mesh array too small: ${out.size}" }
        val w = finite(pose.widthPx, 1f).coerceIn(1f, 1e6f)
        val h = finite(pose.heightPx, 1f).coerceIn(1f, 1e6f)
        val cx = finite(pose.centerX, 0f)
        val cy = finite(pose.centerY, 0f)
        val phase = finite(pose.phase, 0f) % TWO_PI
        val scale = finite(pose.waveScale, 0f).coerceIn(0f, 1.5f)
        val flip = finite(pose.flipX, 1f).coerceIn(-1f, 1f)
        val pitch = finite(pose.pitch, 0f)
        val cosP = cos(pitch)
        val sinP = sin(pitch)
        val params = SwimParams(
            waveHz = swim.waveHz,
            wavelengths = finite(swim.wavelengths, 1f),
            amplitude = finite(swim.amplitude, 0f).coerceIn(0f, 0.5f),
            tailBias = swim.tailBias,
        )
        val rest = w / COLS
        var shift = 0f
        var prevDy = 0f
        // Walk from the head (k = 0) to the tail (k = COLS); the bitmap column is mirrored when the
        // head is on the left of the drawing.
        for (k in 0..COLS) {
            val u = 1f - k.toFloat() / COLS
            val col = if (pose.headRight) COLS - k else k
            val dy = lateral(u, phase, params) * scale * w
            if (k > 0) {
                val ddy = dy - prevDy
                val along = sqrt(max(rest * rest - ddy * ddy, (MIN_STEP * rest) * (MIN_STEP * rest)))
                shift += rest - along
            }
            prevDy = dy
            val restX = (col.toFloat() / COLS - 0.5f) * w
            // The tail is pulled toward the head: rightwards when the head is on the right.
            val x = if (pose.headRight) restX + shift else restX - shift
            for (row in 0..ROWS) {
                val y = (row.toFloat() / ROWS - 0.5f) * h + dy
                val fx = x * flip
                val i = (row * (COLS + 1) + col) * 2
                out[i] = cx + fx * cosP - y * sinP
                out[i + 1] = cy + fx * sinP + y * cosP
            }
        }
    }

    /**
     * Which way the creature faces after this frame: it turns round only when its velocity points the
     * other way by more than [FLIP_HYSTERESIS] of [maxSpeed], so a wobble around zero does not flicker.
     */
    fun nextFacing(facing: Int, vx: Float, maxSpeed: Float): Int {
        if (!vx.isFinite() || !maxSpeed.isFinite()) return if (facing < 0) -1 else 1
        val current = if (facing < 0) -1 else 1
        return if (vx * current < 0f && abs(vx) > FLIP_HYSTERESIS * maxSpeed) -current else current
    }

    /** Eases [flipX] toward [facing] (+1 or -1) at [FLIP_RATE]; never overshoots; monotonic. */
    fun nextFlip(flipX: Float, facing: Int, dt: Float): Float {
        val target = if (facing < 0) -1f else 1f
        val from = finite(flipX, target)
        if (!dt.isFinite() || dt <= 0f) return from
        val step = FLIP_RATE * dt
        return when {
            from < target -> min(target, from + step)
            from > target -> max(target, from - step)
            else -> target
        }
    }

    /** Tilt for a creature moving at ([vx], [vy]) facing [facing]: nose up when climbing, clamped. */
    fun pitchFor(facing: Int, vx: Float, vy: Float): Float {
        if (!vx.isFinite() || !vy.isFinite()) return 0f
        val angle = atan2(vy, max(abs(vx), 1e-4f)).coerceIn(-MAX_PITCH, MAX_PITCH)
        return if (facing < 0) -angle else angle
    }

    private fun finite(v: Float, fallback: Float) = if (v.isFinite()) v else fallback
}
