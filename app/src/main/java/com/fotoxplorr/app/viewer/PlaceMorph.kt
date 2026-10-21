package com.fotoxplorr.app.viewer

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The maths and copy behind the viewer's details room — the place plate, the flight the photo
 * makes into its own pin as the room opens, and the slide the room's text makes as it settles.
 *
 * Every function here is pure so the feel of the reveal can be asserted in a test rather than
 * eyeballed on a device. That matters more than usual for this surface: the flight is driven
 * by a *drag*, so it has to be correct at every fractional openness the finger can stop at,
 * not merely at the two ends a screenshot would show.
 */
object PlaceMorph {

    /**
     * The plate's span, in degrees of latitude. Fixed rather than fitted to anything, because a
     * plate whose zoom changed per photo would make two photos taken a street apart look like
     * they were taken the same distance apart — the scale readout is only meaningful if the
     * span behind it is constant.
     *
     * 0.01° of latitude is ~1.1 km, so the plate covers roughly a neighbourhood.
     */
    const val PLATE_SPAN_DEGREES = 0.01

    /** Degrees between graticule lines — 0.002° is ~220 m, so a plate shows about five. */
    const val GRATICULE_STEP_DEGREES = 0.002

    /**
     * The pin's geometry, in dp, measured off the owner's reference (6.png at 1 px = 1 dp): an
     * 80 dp rounded square with a 2 dp white border, and a 14 x 7 dp pointer hanging under it.
     * The pointer belongs to the pin, so the whole group is [PIN_GROUP_HEIGHT_DP] tall and it is
     * the GROUP that is centred on the location, which is how the reference draws it.
     */
    const val PIN_SIZE_DP = 80
    const val PIN_BORDER_DP = 2
    const val PIN_CORNER_DP = 8
    const val POINTER_WIDTH_DP = 14
    const val POINTER_HEIGHT_DP = 7
    const val PIN_GROUP_HEIGHT_DP = PIN_SIZE_DP + POINTER_HEIGHT_DP

    /**
     * The reveal curve: smoothstep over the shell's linear drag.
     *
     * The shell moves the room 1:1 with the finger, which is right for the room but wrong for
     * what happens *inside* it — a linear flight leaves the photo at full size for the first
     * pixel of pull and slams it into the pin at the last. Easing both ends makes the thumbnail
     * look like it departs and arrives.
     *
     * Deliberately not [dev.aarso.cellshell.SpatialMotion.settleSpec]: that governs where the
     * room lands when the finger lifts, and is an animation over time. This is a mapping over
     * *position*, and has to hold perfectly still under a stationary finger mid-drag.
     */
    fun flight(reveal: Float): Float {
        val t = reveal.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Re-times a 0..1 [reveal] onto the window [start]..[end], so the parts of the room can
     * arrive in sequence off one drag: the photo leaves first, the plate resolves under it,
     * the text settles last. Everything outside the window is clamped, which is what lets a
     * half-open room look deliberately half-finished rather than uniformly faded.
     */
    fun stagger(reveal: Float, start: Float, end: Float): Float {
        if (end <= start) return if (reveal >= end) 1f else 0f
        return ((reveal - start) / (end - start)).coerceIn(0f, 1f)
    }

    /**
     * Where the photo sits during its flight, as a fraction of the way from hero to pin.
     *
     * The thumbnail starts at [heroScale]× the pin's size — big enough to read as "the photo
     * you were just looking at" — and lands at 1×, its pin size.
     */
    fun thumbnailScale(reveal: Float, heroScale: Float): Float {
        val t = flight(stagger(reveal, FLIGHT_START, FLIGHT_END))
        return heroScale + (1f - heroScale) * t
    }

    /**
     * The hero scale for a plate [plateWidthDp] wide: the photo starts spanning
     * [HERO_PLATE_FRACTION] of the plate and lands at [PIN_SIZE_DP]. Pure so the render test can
     * assert the hero really is "the photo you were just looking at" and not a slightly larger
     * pin.
     */
    fun heroScale(plateWidthDp: Float): Float =
        if (plateWidthDp <= 0f) 1f else ((plateWidthDp * HERO_PLATE_FRACTION) / PIN_SIZE_DP).coerceAtLeast(1f)

    /**
     * Opacity of the plate under the flying photo. It resolves slightly *after* the photo
     * starts moving, so the ground appears to come up to meet the pin rather than being there
     * all along waiting for it.
     */
    fun plateAlpha(reveal: Float): Float = flight(stagger(reveal, PLATE_START, PLATE_END))

    /** Opacity of the room's text. Last to arrive, so the eye follows the photo first. */
    fun textAlpha(reveal: Float): Float = flight(stagger(reveal, TEXT_START, TEXT_END))

    /**
     * Opacity of the pin's pointer, from the tile's current [thumbnailScale] and the plate's
     * [landed] alpha: fully hidden while the photo is still visibly larger than its pin (the
     * pointer hangs off an edge that is nowhere near it yet), fading in over the last few percent
     * of the flight, and never brighter than the plate it belongs to.
     */
    fun pointerAlpha(thumbnailScale: Float, landed: Float): Float {
        val nearlyLanded = ((POINTER_APPEAR_SCALE - thumbnailScale) / (POINTER_APPEAR_SCALE - 1f)).coerceIn(0f, 1f)
        return (nearlyLanded * landed.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    /**
     * How far below its resting place the room's text sits, in dp, as it settles: it slides UP
     * into position over the same window it fades in over (owner: the animated shrinkage and
     * the sliding text are *"important"*). Starts [TEXT_SLIDE_DP] low and lands at 0, on the
     * same eased curve as [textAlpha] so the two never visibly disagree about when a block has
     * arrived.
     */
    fun textOffsetDp(reveal: Float): Float = TEXT_SLIDE_DP * (1f - textAlpha(reveal))

    /**
     * Offsets of the graticule lines across a plate of [sizePx], as fractions of its size.
     *
     * The pin is always at the plate's centre — the plate is centred on the photo — so the
     * lines are what carry the photo's actual position: their offset is set by where the
     * coordinate falls between two whole graticule steps. Two photos a hundred metres apart
     * therefore get visibly different grids, and neither grid is decorative.
     *
     * @param degrees the coordinate on this axis.
     * @param spanDegrees how much of that axis the plate covers.
     */
    fun graticuleFractions(
        degrees: Double,
        spanDegrees: Double = PLATE_SPAN_DEGREES,
        stepDegrees: Double = GRATICULE_STEP_DEGREES,
    ): List<Float> {
        if (spanDegrees <= 0.0 || stepDegrees <= 0.0) return emptyList()
        if (degrees.isNaN() || degrees.isInfinite()) return emptyList()
        val low = degrees - spanDegrees / 2.0
        val high = degrees + spanDegrees / 2.0
        // Start at the first whole step at or above the plate's low edge, then walk up.
        var line = floor(low / stepDegrees) * stepDegrees
        val fractions = mutableListOf<Float>()
        while (line <= high) {
            if (line >= low) fractions += ((line - low) / spanDegrees).toFloat()
            line += stepDegrees
        }
        return fractions
    }

    /**
     * Metres per degree of longitude at [latitude]. Longitude lines converge towards the poles,
     * so a plate that used one number everywhere would be wrong by a factor of two by 60°.
     */
    fun metresPerDegreeLongitude(latitude: Double): Double =
        METRES_PER_DEGREE_LATITUDE * cos(Math.toRadians(latitude.coerceIn(-89.9, 89.9)))

    /**
     * The plate's scale readout: how wide it is on the ground, rounded to something a person
     * would say. Uses the longitude scale at this latitude, so it describes the plate's actual
     * width rather than its height.
     */
    fun scaleLine(latitude: Double, spanDegrees: Double = PLATE_SPAN_DEGREES): String {
        val metres = metresPerDegreeLongitude(latitude) * spanDegrees
        return when {
            metres >= 1_000.0 -> "%.1f km across".format(Locale.US, metres / 1_000.0)
            else -> "${metres.roundToInt()} m across"
        }
    }

    /**
     * "17.4435° N · 78.3772° E" — the coordinate as a line of copy.
     *
     * Four decimal places is ~11 m, which is about as precise as an embedded GPS fix honestly
     * is; printing the full stored precision would imply an accuracy the file does not have.
     */
    fun coordinateLine(latitude: Double, longitude: Double): String {
        val north = if (latitude >= 0.0) "N" else "S"
        val east = if (longitude >= 0.0) "E" else "W"
        return "%.4f° %s · %.4f° %s".format(
            Locale.US,
            abs(latitude),
            north,
            abs(longitude),
            east,
        )
    }

    /**
     * Whether a coordinate pair is drawable at all. A NaN or an out-of-range value reaches this
     * room from a hand-typed location or a malformed EXIF rational, and a plate drawn at it
     * would either throw inside a draw lambda or draw a confident pin on nonsense.
     */
    fun isDrawable(latitude: Double?, longitude: Double?): Boolean {
        if (latitude == null || longitude == null) return false
        if (latitude.isNaN() || longitude.isNaN() || latitude.isInfinite() || longitude.isInfinite()) return false
        return latitude in -90.0..90.0 && longitude in -180.0..180.0
    }

    /** Metres in one degree of latitude — constant enough at this scale to be a constant. */
    private const val METRES_PER_DEGREE_LATITUDE = 111_320.0

    /** How much of the plate's width the thumbnail spans at the start of its flight. */
    private const val HERO_PLATE_FRACTION = 0.92f

    /** How far the room's text starts below its resting place, in dp. */
    private const val TEXT_SLIDE_DP = 18f

    /** The tile scale at which the pointer starts to appear; at 1x it is fully there. */
    private const val POINTER_APPEAR_SCALE = 1.25f

    /** The photo is already moving before the room is a third open. */
    private const val FLIGHT_START = 0f
    private const val FLIGHT_END = 0.85f
    private const val PLATE_START = 0.15f
    private const val PLATE_END = 1f
    private const val TEXT_START = 0.45f
    private const val TEXT_END = 1f
}
