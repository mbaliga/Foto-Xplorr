package com.fotoxplorr.app.vision

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** One dot of a plate, in a unit square: centre ([x], [y]), radius [r], colour [argb]. */
internal class PlateDot(val x: Float, val y: Float, val r: Float, val argb: Int, val inFigure: Boolean)

/**
 * A procedural dot plate: a disc of dots with a digit hidden in them. In a test plate the figure's
 * dots and the ground's dots differ only along the confusion axis of [axis]; in a [control] plate they
 * differ in lightness only, which anyone with working vision (and a working screen) should read.
 */
internal class Plate(
    val digit: Int,
    val axis: Deficiency,
    val control: Boolean,
    val dots: List<PlateDot>,
)

/** How a run of plates went. [UNRELIABLE] means the control plate was missed, so nothing else can be read from it. */
internal enum class PlateBand { ALL_SEEN, SOME_MISSED, MOST_MISSED, UNRELIABLE }

internal class PlateScore(val correct: Int, val total: Int, val controlSeen: Boolean) {
    val band: PlateBand
        get() = when {
            !controlSeen -> PlateBand.UNRELIABLE
            total == 0 || correct >= total -> PlateBand.ALL_SEEN
            correct * 2 >= total -> PlateBand.SOME_MISSED
            else -> PlateBand.MOST_MISSED
        }
}

/**
 * A generator for pseudo-isochromatic test plates of our own design. Nothing is copied from the
 * Ishihara or any other published plate: the dots come from a seeded random packing and the digits
 * are plain seven-segment strokes.
 *
 * How the hidden digit works. For protan and deutan the Machado matrix at severity 1 is rank two, so
 * one direction in linear RGB, [axisDirection], is mapped to nothing: two colours that differ only
 * along it look identical to the modelled dichromat. Figure dots are a random base colour pushed
 * along that direction one way, ground dots the same pushed the other way, with the base lightness
 * and a little hue jitter varied dot by dot so lightness gives nothing away. A normal eye sees a
 * reddish digit on a greenish ground (or the like); the modelled dichromat sees one speckled field.
 * The tritan matrix is not rank two, so its direction is the one the matrix flattens most, and a
 * small residue remains (the tests bound it).
 *
 * This is a screening toy, NOT a diagnosis: it has not been validated on people, a miss can mean a
 * tired eye, a dim or tinted screen, night mode or the wrong viewing distance, and a pass does not
 * exclude a milder deficiency. Results depend on the screen. The plates are drawn in sRGB on the
 * assumption of a typical screen at full colour.
 */
internal object TestPlates {
    private const val CENTRE = 0.5
    private const val DISC_RADIUS = 0.47
    private const val MIN_DOT = 0.009
    private const val MAX_DOT = 0.022
    private const val GAP = 0.004
    private const val ATTEMPTS = 14000
    private const val STROKE = 0.11

    // Digit box inside the disc.
    private const val LEFT = 0.33
    private const val RIGHT = 0.67
    private const val TOP = 0.2
    private const val MIDDLE = 0.5
    private const val BOTTOM = 0.8

    /** Base colour (linear RGB) the dots are scattered around, with its lightness varying per dot. */
    private const val BASE = 0.5
    private const val LIGHT_SPREAD = 0.2
    private const val JITTER = 0.04
    private const val PUSH_MIN = 0.14
    private const val PUSH_MAX = 0.22

    /** Control plates: a small push along grey and almost no lightness spread, so the lightness cue reads. */
    private const val CONTROL_PUSH = 0.14
    private const val CONTROL_SPREAD = 0.04

    /**
     * The unit-scaled (largest component 1) direction in linear RGB that [axis]'s full-severity
     * matrix removes: the direction of its smallest singular value, found by inverse iteration on
     * the 3x3 `S^T S`. Sign: positive in the first component that is not tiny, so it is stable.
     */
    fun axisDirection(axis: Deficiency): DoubleArray {
        val s = MachadoMatrices.matrix(axis, 1.0)
        val st = DoubleArray(9) { s[(it % 3) * 3 + it / 3] }
        val gram = Mat3.mul(st, s)
        // Inverse iteration; the tiny shift keeps an exactly rank-two Gram matrix invertible.
        val shifted = DoubleArray(9) { gram[it] + if (it % 4 == 0) 1e-9 else 0.0 }
        val inv = Mat3.invert(shifted)
        var v = doubleArrayOf(0.3, 0.5, 0.8)
        repeat(30) {
            val w = DoubleArray(3) { inv[it * 3] * v[0] + inv[it * 3 + 1] * v[1] + inv[it * 3 + 2] * v[2] }
            val norm = max(max(kotlin.math.abs(w[0]), kotlin.math.abs(w[1])), kotlin.math.abs(w[2]))
            v = DoubleArray(3) { w[it] / norm }
        }
        val lead = v.firstOrNull { kotlin.math.abs(it) > 0.05 } ?: 1.0
        return if (lead < 0) DoubleArray(3) { -v[it] } else v
    }

    /** One plate for [digit] (0..9). The same arguments always give the same plate. */
    fun generate(digit: Int, axis: Deficiency, seed: Long, control: Boolean = false): Plate {
        require(digit in 0..9) { "digit must be 0..9" }
        val rng = Random(seed * 31 + digit * 7 + axis.ordinal)
        val strokes = strokesOf(digit)
        val direction = if (control) doubleArrayOf(1.0, 1.0, 1.0) else axisDirection(axis)
        val dots = pack(rng).map { (x, y, r) ->
            val figure = strokes.any { distanceToSegment(x, y, it) <= STROKE / 2 }
            val sign = if (figure) 1.0 else -1.0
            val push = if (control) CONTROL_PUSH else PUSH_MIN + rng.nextDouble() * (PUSH_MAX - PUSH_MIN)
            val spread = if (control) CONTROL_SPREAD else LIGHT_SPREAD
            val light = 1.0 + (rng.nextDouble() * 2 - 1) * spread
            val rgb = DoubleArray(3) {
                BASE * light + (rng.nextDouble() * 2 - 1) * JITTER + sign * push * direction[it]
            }
            PlateDot(
                x = x.toFloat(),
                y = y.toFloat(),
                r = r.toFloat(),
                argb = ColourSpace.pack(
                    255,
                    ColourSpace.encode8(rgb[0]),
                    ColourSpace.encode8(rgb[1]),
                    ColourSpace.encode8(rgb[2]),
                ),
                inFigure = figure,
            )
        }
        return Plate(digit, axis, control, dots)
    }

    /**
     * A run: one control plate first, then [count] test plates with distinct digits (at most 9 or so),
     * all derived from [seed].
     */
    fun session(axis: Deficiency, seed: Long, count: Int = 4): List<Plate> {
        val rng = Random(seed)
        val digits = (0..9).shuffled(rng)
        val tests = digits.drop(1).take(count.coerceIn(0, 9))
        return listOf(generate(digits[0], axis, seed, control = true)) +
            tests.mapIndexed { i, d -> generate(d, axis, seed + 1 + i, control = false) }
    }

    /**
     * Scores [answers] (one per plate in the same order, null for "I see nothing") against [plates].
     * A control plate does not count toward [PlateScore.total]; missing it makes the run unreliable.
     * An answer list shorter than the plates counts the missing ones as not seen.
     */
    fun score(plates: List<Plate>, answers: List<Int?>): PlateScore {
        var correct = 0
        var total = 0
        var controlSeen = true
        plates.forEachIndexed { i, plate ->
            val right = answers.getOrNull(i) == plate.digit
            if (plate.control) {
                if (!right) controlSeen = false
            } else {
                total++
                if (right) correct++
            }
        }
        return PlateScore(correct, total, controlSeen)
    }

    private class Segment(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    private fun seg(x0: Double, y0: Double, x1: Double, y1: Double) = Segment(x0, y0, x1, y1)

    private fun strokesOf(digit: Int): List<Segment> {
        val a = seg(LEFT, TOP, RIGHT, TOP)
        val b = seg(RIGHT, TOP, RIGHT, MIDDLE)
        val c = seg(RIGHT, MIDDLE, RIGHT, BOTTOM)
        val d = seg(LEFT, BOTTOM, RIGHT, BOTTOM)
        val e = seg(LEFT, MIDDLE, LEFT, BOTTOM)
        val f = seg(LEFT, TOP, LEFT, MIDDLE)
        val g = seg(LEFT, MIDDLE, RIGHT, MIDDLE)
        return when (digit) {
            0 -> listOf(a, b, c, d, e, f)
            1 -> listOf(seg(CENTRE, TOP, CENTRE, BOTTOM))
            2 -> listOf(a, b, g, e, d)
            3 -> listOf(a, b, g, c, d)
            4 -> listOf(f, g, b, c)
            5 -> listOf(a, f, g, c, d)
            6 -> listOf(a, f, e, d, c, g)
            7 -> listOf(a, b, c)
            8 -> listOf(a, b, c, d, e, f, g)
            else -> listOf(a, b, c, d, f, g)
        }
    }

    private fun distanceToSegment(px: Double, py: Double, s: Segment): Double {
        val dx = s.x1 - s.x0
        val dy = s.y1 - s.y0
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - s.x0) * dx + (py - s.y0) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (s.x0 + t * dx), py - (s.y0 + t * dy))
    }

    /** Random non-overlapping circles in the disc, by dart throwing with a bucket grid. */
    private fun pack(rng: Random): List<Triple<Double, Double, Double>> {
        val cell = 2 * MAX_DOT + GAP
        val cols = (1.0 / cell).toInt() + 1
        val grid = HashMap<Int, MutableList<Int>>()
        val placed = ArrayList<Triple<Double, Double, Double>>()
        repeat(ATTEMPTS) {
            val r = MIN_DOT + rng.nextDouble() * (MAX_DOT - MIN_DOT)
            val x = CENTRE + (rng.nextDouble() * 2 - 1) * DISC_RADIUS
            val y = CENTRE + (rng.nextDouble() * 2 - 1) * DISC_RADIUS
            if (hypot(x - CENTRE, y - CENTRE) + r > DISC_RADIUS) return@repeat
            val cx = (x / cell).toInt()
            val cy = (y / cell).toInt()
            var clear = true
            loop@ for (gx in max(0, cx - 1)..min(cols, cx + 1)) for (gy in max(0, cy - 1)..min(cols, cy + 1)) {
                for (i in grid[gx * (cols + 1) + gy].orEmpty()) {
                    val o = placed[i]
                    if (hypot(x - o.first, y - o.second) < r + o.third + GAP) {
                        clear = false
                        break@loop
                    }
                }
            }
            if (clear) {
                grid.getOrPut(cx * (cols + 1) + cy) { ArrayList() } += placed.size
                placed += Triple(x, y, r)
            }
        }
        return placed
    }
}
