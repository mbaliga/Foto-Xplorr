package com.fotoxplorr.app.viewer

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.aarso.cellshell.SpatialMotion
import dev.aarso.cellshell.parkDistance
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
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

    /**
     * Whether a fix read from a file is exactly 0.0, 0.0 -- "Null Island", in the Gulf of Guinea.
     * In range, so [isDrawable] accepts it, but no photograph in a library was taken there: it is
     * what a camera or an editor writes when it has a GPS tag to fill and no fix to put in it. An
     * embedded fix at exactly this point is treated as absent by the EXIF reader, so the room
     * does not draw a confident located plate on the ocean. A hand-placed location is not run
     * through this: someone who chose the point meant it.
     */
    fun isNullIsland(latitude: Double, longitude: Double): Boolean = latitude == 0.0 && longitude == 0.0

    /** Which of the place block's five faces is drawn. See [placeBlockMode]. */
    enum class PlaceBlockMode { LOADING, PICKER, PLATE, READ_FAILED, UNKNOWN }

    /**
     * Which face the room's place block shows, in the order the block tests them.
     *
     * - [PlaceBlockMode.LOADING]: the metadata has not been read yet. Nothing is known, so nothing
     *   is claimed: neither "Add a place" nor "No location yet", which would be the P0-02 failure
     *   (a geotagged file offered a hand-placed location) through the loading state instead of
     *   the failed-read one. The block reserves the plate's footprint and has no pin.
     * - [PlaceBlockMode.PICKER]: the location picker is up ([editing] and a way to set a place).
     * - [PlaceBlockMode.PLATE]: a drawable place, embedded or hand-placed.
     * - [PlaceBlockMode.READ_FAILED]: the read failed; never the picker.
     * - [PlaceBlockMode.UNKNOWN]: read fine, no place.
     */
    fun placeBlockMode(pending: Boolean, editingWithSetter: Boolean, drawable: Boolean, readOk: Boolean): PlaceBlockMode =
        when {
            pending -> PlaceBlockMode.LOADING
            editingWithSetter -> PlaceBlockMode.PICKER
            drawable -> PlaceBlockMode.PLATE
            !readOk -> PlaceBlockMode.READ_FAILED
            else -> PlaceBlockMode.UNKNOWN
        }

    /** Whether the block in [mode] draws a pin tile (the flier's landing target). */
    fun hasPin(mode: PlaceBlockMode): Boolean = mode == PlaceBlockMode.PLATE || mode == PlaceBlockMode.UNKNOWN

    // ── The shared-element flight: the photo you were looking at becomes the pin ──────────────
    //
    // Everything below is geometry for [PhotoToPinFlight]: where the photo is, where the card
    // carrying it has parked, where the pin will be, and how those blend as one drag. It is all
    // a pure function of the shell's vertical progress `v` (0 = photo, 1 = details room open),
    // so it reverses on close for free and holds perfectly still under a stationary finger.
    // The older [flight] / [thumbnailScale] above describe the in-plate hero that plays when no
    // flier is available; they are kept unchanged because that path still has callers and tests.

    /**
     * The grab band the shell leaves visible under the parked card, in dp. It IS cell-shell's
     * [SpatialMotion.BAND_DP] (a compile-time constant, so this stays a `const`); the room is
     * padded by it at the bottom, so it is also the amount of the viewport the room does NOT use.
     */
    const val SHELL_BAND_DP = SpatialMotion.BAND_DP

    /** How dark the ghost pin is on the unknown-location plate, 0..1. See [landedDim]. */
    const val GHOST_DIM = 0.35f

    /**
     * Where `ContentScale.Fit` draws an image of [imageWidth] x [imageHeight] inside a container
     * of [containerWidth] x [containerHeight] (container origin at 0,0): centred, aspect kept.
     *
     * A degenerate image (a zero or NaN dimension, which a photo whose size was never read
     * reports) gets the whole container rather than NaN geometry; a degenerate container gets
     * [Rect.Zero].
     */
    fun fitRect(containerWidth: Float, containerHeight: Float, imageWidth: Float, imageHeight: Float): Rect {
        if (!(containerWidth > 0f) || !(containerHeight > 0f)) return Rect.Zero
        if (!(imageWidth > 0f) || !(imageHeight > 0f)) return Rect(0f, 0f, containerWidth, containerHeight)
        val scale = min(containerWidth / imageWidth, containerHeight / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        val left = (containerWidth - width) / 2f
        val top = (containerHeight - height) / 2f
        return Rect(left, top, left + width, top + height)
    }

    /**
     * The size of a decoded image as it is DISPLAYED, from its stored pixel size and its EXIF
     * [orientation] (1..8, anything else meaning "none"). Orientations 5 to 8 turn the picture a
     * quarter, so the displayed width is the stored height. Null when either stored side is not
     * positive.
     *
     * MediaStore's WIDTH and HEIGHT are the stored pixels, and the scanner reads no orientation,
     * so a phone portrait (stored landscape, orientation 6) would otherwise start the flight from
     * a landscape-shaped rectangle and change shape on the first frame of every pull.
     */
    fun uprightSize(storedWidth: Int, storedHeight: Int, orientation: Int): Size? {
        if (storedWidth <= 0 || storedHeight <= 0) return null
        return if (orientation in 5..8) {
            Size(storedHeight.toFloat(), storedWidth.toFloat())
        } else {
            Size(storedWidth.toFloat(), storedHeight.toFloat())
        }
    }

    /**
     * Whether the image decoder turns a file of [mimeType] upright by its EXIF orientation when
     * it decodes it, which is what the displayed size has to follow. Mirrors Coil's default
     * policy: honoured for JPEG, WebP and HEIF/HEIC, not applied to anything else (a PNG can
     * carry an orientation tag that the decode ignores).
     */
    fun decoderAppliesOrientation(mimeType: String): Boolean = when (mimeType.lowercase(Locale.ROOT)) {
        "image/jpeg", "image/jpg", "image/webp", "image/heic", "image/heif" -> true
        else -> false
    }

    /**
     * The size the home photo is laid out at: the displayed ([displayWidth] x [displayHeight])
     * size read off the file when there is one, else the stored size MediaStore reported. Both
     * zero means "unknown", for which [fitRect] already falls back to the whole container.
     */
    fun restingImageSize(storedWidth: Int, storedHeight: Int, displayWidth: Int, displayHeight: Int): Size =
        if (displayWidth > 0 && displayHeight > 0) {
            Size(displayWidth.toFloat(), displayHeight.toFloat())
        } else {
            Size(storedWidth.toFloat(), storedHeight.toFloat())
        }

    /**
     * Where [rect] (a rectangle in the home card's own coordinates -- the photo's fit rect) is
     * drawn once the shell has parked the card downward by [v] of the way to the top room.
     *
     * The shell scales the card about its centre by `1 - PARK_SCALE_DROP * v` and then places it
     * `v * parkDistance` lower; this is exactly that, from the shell's own constants and its own
     * [parkDistance], so the two cannot drift apart. There is no swivel term: the viewer parks
     * the card with a flat SLIDE while the top room is pulled (see [PhotoToPinFlight]'s contract),
     * because a tilted card has no rectangle to follow.
     *
     * @param bandPx the band the shell leaves visible, in px ([SHELL_BAND_DP] x density).
     */
    fun parkedCardRect(rect: Rect, viewportWidth: Float, viewportHeight: Float, v: Float, bandPx: Float): Rect {
        val progress = v.coerceIn(0f, 1f)
        val scale = 1f - SpatialMotion.PARK_SCALE_DROP * progress
        val centreX = viewportWidth / 2f
        val centreY = viewportHeight / 2f
        val dy = progress * parkDistance(viewportHeight, scale, bandPx)
        return Rect(
            left = centreX + (rect.left - centreX) * scale,
            top = centreY + (rect.top - centreY) * scale + dy,
            right = centreX + (rect.right - centreX) * scale,
            bottom = centreY + (rect.bottom - centreY) * scale + dy,
        )
    }

    /**
     * Component-wise blend of two rectangles; [t] is not clamped. The ends are returned as given,
     * not recomputed: `a + (b - a)` is not always exactly `b` in floating point, and the flier's
     * landing has to be pixel-exact for the swap to the real pin to be invisible.
     */
    fun lerpRect(a: Rect, b: Rect, t: Float): Rect = when (t) {
        0f -> a
        1f -> b
        else -> Rect(
            left = a.left + (b.left - a.left) * t,
            top = a.top + (b.top - a.top) * t,
            right = a.right + (b.right - a.right) * t,
            bottom = a.bottom + (b.bottom - a.bottom) * t,
        )
    }

    /**
     * How far along its flight the photo is, 0 (still riding the parked card) to 1 (the pin), for
     * the shell's progress [v]. Smoothstep over the WHOLE pull: the photo leaves the card gently,
     * and arrives with zero slope so there is no jump into the hand-off at [HANDOFF_START].
     *
     * It deliberately starts at 0 rather than at "when the plate comes into view". The old morph
     * was timed to a window the plate was still off screen for -- the room slides 1:1 from above
     * -- so most of the shrink happened where nobody could see it. The flier is its own surface,
     * visible the whole way, so the shrink is visible the whole way.
     */
    fun flightT(v: Float): Float = flight(stagger(v, FLY_START, FLY_END))

    /**
     * The flier's opacity: fully opaque until [HANDOFF_START], then fading out over the last of
     * the pull while the room's real pin, already fully opaque underneath ([realPinVisible]),
     * is revealed. Opaque-under-fading-over is what makes the swap exact: cross-fading two
     * copies that sum to 1 would dim the plate for a few frames.
     */
    fun handoff(v: Float): Float = 1f - stagger(v, HANDOFF_START, 1f)

    /**
     * How far the flier has faded IN, 0..1, over the first [LIFT_ALPHA_END] of the pull. The flier
     * starts exactly on top of the card's own photo, so any difference between the two (a
     * rectangle of a slightly wrong shape, or for a video a poster frame that is not the frame
     * the player is showing) is a pop if the flier is opaque from the first pixel. Ramping it in
     * turns that into a cross-fade under the finger. Applied by [PhotoToPinFlight] on top of
     * [FlightFrame.alpha] so [handoff]'s own contract (opaque until the hand-off) is untouched.
     */
    fun liftOffAlpha(v: Float): Float = flight(stagger(v, 0f, LIFT_ALPHA_END))

    /** Whether the room's own pin tile is drawn at [v] -- hidden while the flier carries the photo. */
    fun realPinVisible(v: Float): Boolean = v >= HANDOFF_START

    /**
     * Where the pin tile is on screen at progress [v], given [pinRoomRect], its rectangle in the
     * ROOM's own coordinates (origin at the room's top-left, which is the shell's).
     *
     * The shell moves the top room with a placement offset of `-viewportHeight * (1 - v)`, a rigid
     * slide, so the pin's room-local position is constant and its screen position is analytic.
     * Measuring it once is enough; it never needs a per-frame measurement and cannot drift.
     */
    fun pinRectAt(pinRoomRect: Rect, viewportHeight: Float, v: Float): Rect =
        pinRoomRect.translate(0f, -viewportHeight * (1f - v.coerceIn(0f, 1f)))

    /**
     * Whether the flier may fly to this pin: the whole tile is inside the room's [roomWidth] x
     * [roomHeight]. False when the room is scrolled so the pin is partly or wholly out of reach,
     * in which case there is no honest place to land and the pin simply fades in in place.
     *
     * Both the flier and the room's own pin ask this of the same numbers, so they can never
     * disagree about which of the two is drawing.
     */
    fun canFly(pinRoomRect: Rect, roomWidth: Float, roomHeight: Float): Boolean {
        if (pinRoomRect.width <= 0f || pinRoomRect.height <= 0f) return false
        val slack = 1f
        return pinRoomRect.left >= -slack && pinRoomRect.top >= -slack &&
            pinRoomRect.right <= roomWidth + slack && pinRoomRect.bottom <= roomHeight + slack
    }

    /** The flier's outer rectangle at [v]: the parked photo blended into the live pin by [flightT]. */
    fun flightRect(
        photoRect: Rect,
        pinRoomRect: Rect,
        viewportWidth: Float,
        viewportHeight: Float,
        bandPx: Float,
        v: Float,
    ): Rect = lerpRect(
        parkedCardRect(photoRect, viewportWidth, viewportHeight, v, bandPx),
        pinRectAt(pinRoomRect, viewportHeight, v),
        flightT(v),
    )

    /**
     * One frame of the flier.
     *
     * @property outer the tile including its white frame, in the overlay's coordinates; equal to
     *   the pin tile at v = 1.
     * @property photo the photo's own area inside [outer] (the frame deflated), where the image
     *   is cropped to.
     * @property flightT 0..1, see [PlaceMorph.flightT].
     * @property frameAlpha 0..1 opacity of the white frame; the same curve the pin's frame uses.
     * @property outerCornerPx the tile's corner radius in px, growing 0 -> the pin's.
     * @property alpha the whole flier's opacity ([handoff]).
     */
    data class FlightFrame(
        val outer: Rect,
        val photo: Rect,
        val flightT: Float,
        val frameAlpha: Float,
        val outerCornerPx: Float,
        val alpha: Float,
    )

    /**
     * The flier's frame at [v], or null when it should draw nothing: the shell is closed, the pin
     * is unknown or out of reach ([canFly]), or there is no photo rectangle.
     *
     * @param pinRoomRect the pin tile in the room's coordinates, see [pinRectAt].
     * @param density px per dp, for the frame and corner.
     */
    fun flightFrame(
        photoRect: Rect,
        pinRoomRect: Rect,
        viewportWidth: Float,
        viewportHeight: Float,
        bandPx: Float,
        v: Float,
        density: Float,
    ): FlightFrame? {
        if (!(v > FLY_MIN_PROGRESS)) return null
        if (photoRect.width <= 0f || photoRect.height <= 0f) return null
        if (!canFly(pinRoomRect, viewportWidth, viewportHeight - bandPx)) return null
        val t = flightT(v)
        val outer = flightRect(photoRect, pinRoomRect, viewportWidth, viewportHeight, bandPx, v)
        val frameAlpha = plateAlpha(v)
        val inset = min(PIN_BORDER_DP * density * frameAlpha, min(outer.width, outer.height) / 2f)
        return FlightFrame(
            outer = outer,
            photo = outer.deflate(inset),
            flightT = t,
            frameAlpha = frameAlpha,
            outerCornerPx = PIN_CORNER_DP * density * t,
            alpha = handoff(v),
        )
    }

    /**
     * How the flier's image is placed: a base layer of the photo's own size ([CropPlacement.scale]
     * about its top-left, then translated) with only [CropPlacement.window] of it showing.
     *
     * The photo morphs from its own aspect to the pin's square, and a single stretched layer
     * would squash it on the way. So the layer is scaled UNIFORMLY and a window onto it changes
     * aspect instead: the biggest centred rectangle of the target's aspect that fits the photo,
     * which is exactly what `ContentScale.Crop` shows at each size, with no re-decode or relayout.
     *
     * @param window the visible part, in the base layer's own coordinates.
     * @param scale uniform scale of the base layer.
     * @param translationX where the base layer's top-left lands, in the overlay.
     */
    data class CropPlacement(
        val window: Rect,
        val scale: Float,
        val translationX: Float,
        val translationY: Float,
    )

    /** See [CropPlacement]. Null when either size is degenerate. */
    fun cropPlacement(baseWidth: Float, baseHeight: Float, target: Rect): CropPlacement? {
        if (!(baseWidth > 0f) || !(baseHeight > 0f) || target.width <= 0f || target.height <= 0f) return null
        val targetAspect = target.width / target.height
        val windowWidth: Float
        val windowHeight: Float
        if (targetAspect >= baseWidth / baseHeight) {
            windowWidth = baseWidth
            windowHeight = baseWidth / targetAspect
        } else {
            windowHeight = baseHeight
            windowWidth = baseHeight * targetAspect
        }
        val windowLeft = (baseWidth - windowWidth) / 2f
        val windowTop = (baseHeight - windowHeight) / 2f
        val scale = target.width / windowWidth
        return CropPlacement(
            window = Rect(windowLeft, windowTop, windowLeft + windowWidth, windowTop + windowHeight),
            scale = scale,
            translationX = target.left - scale * windowLeft,
            translationY = target.top - scale * windowTop,
        )
    }

    /** How dark the pin is once landed: [GHOST_DIM] for a photo with no place, 0 for a located one. */
    fun landedDim(placeKnown: Boolean): Float = if (placeKnown) 0f else GHOST_DIM

    /** Whether two rectangles agree to within [tolerance] px on every edge. */
    fun sameRect(a: Rect?, b: Rect?, tolerance: Float = 0.5f): Boolean {
        if (a == null || b == null) return a == null && b == null
        return abs(a.left - b.left) <= tolerance && abs(a.top - b.top) <= tolerance &&
            abs(a.right - b.right) <= tolerance && abs(a.bottom - b.bottom) <= tolerance
    }

    /**
     * Who draws the pin tile, and how it arrives.
     *
     * - [HERO]: nobody else does. The tile itself starts hero-sized inside the plate and shrinks
     *   into its place ([thumbnailScale]) -- the original morph, still used where there is no
     *   flier (previews, render tests, a viewer that has not been wired for the flight).
     * - [FLIGHT]: a [PhotoToPinFlight] carries the photo, so the tile stays at 1x, is hidden
     *   until the flier hands off to it ([realPinVisible]), and its pointer comes in with it.
     * - [FADE]: a flier was expected but the pin is out of reach ([canFly]), so the tile stays
     *   at 1x and simply fades in with the plate, in place.
     */
    enum class PinMode { HERO, FLIGHT, FADE }

    /** The mode for the two switches the room is given. */
    fun pinMode(flightActive: Boolean, flightFlies: Boolean): PinMode = when {
        !flightActive -> PinMode.HERO
        flightFlies -> PinMode.FLIGHT
        else -> PinMode.FADE
    }

    /** The tile's scale at [reveal] in [mode]. Only [PinMode.HERO] ever scales. */
    fun pinScale(mode: PinMode, reveal: Float, heroScale: Float): Float =
        if (mode == PinMode.HERO) thumbnailScale(reveal, heroScale) else 1f

    /** The tile's opacity at [reveal] in [mode]. */
    fun pinTileAlpha(mode: PinMode, reveal: Float): Float = when (mode) {
        PinMode.HERO -> 1f
        PinMode.FLIGHT -> if (realPinVisible(reveal)) 1f else 0f
        PinMode.FADE -> plateAlpha(reveal)
    }

    /** A further multiplier on the pointer's opacity at [reveal] in [mode]. */
    fun pinPointerGate(mode: PinMode, reveal: Float): Float =
        if (mode == PinMode.FLIGHT) 1f - handoff(reveal) else 1f

    /** Metres in one degree of latitude — constant enough at this scale to be a constant. */
    private const val METRES_PER_DEGREE_LATITUDE = 111_320.0

    /** How much of the plate's width the thumbnail spans at the start of its flight. */
    private const val HERO_PLATE_FRACTION = 0.92f

    /** How far the room's text starts below its resting place, in dp. */
    private const val TEXT_SLIDE_DP = 18f

    /** The tile scale at which the pointer starts to appear; at 1x it is fully there. */
    private const val POINTER_APPEAR_SCALE = 1.25f

    /** The flier leaves the parked card as the pull begins, and has landed by the end of it. */
    private const val FLY_START = 0f
    private const val FLY_END = 1f

    /** Below this the shell is closed for practical purposes and the flier draws nothing. */
    private const val FLY_MIN_PROGRESS = 0.001f

    /** The flier is fully faded in by this much of the pull. */
    private const val LIFT_ALPHA_END = 0.08f

    /** The flier starts fading, and the real pin takes over beneath it, in the last 4% of the pull. */
    private const val HANDOFF_START = 0.96f

    /** The photo is already moving before the room is a third open. */
    private const val FLIGHT_START = 0f
    private const val FLIGHT_END = 0.85f
    private const val PLATE_START = 0.15f
    private const val PLATE_END = 1f
    private const val TEXT_START = 0.45f
    private const val TEXT_END = 1f
}
