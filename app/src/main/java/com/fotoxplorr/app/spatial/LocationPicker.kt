package com.fotoxplorr.app.spatial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.app.viewer.GRATICULE
import com.fotoxplorr.app.viewer.PLATE_ASPECT
import com.fotoxplorr.app.viewer.PLATE_CORNER_DP
import com.fotoxplorr.app.viewer.PLATE_GROUND
import kotlin.math.roundToInt

/**
 * The way to give a photo that has no location one: tap the grid to drop a pin, drag it to move
 * it, or type the coordinates.
 *
 * **What it used to be, and why it no longer is.** Owner, 2026-08-18: *"keep it slowly
 * spinning to indicate no location yet"*. So the field was a world graticule turning on its own
 * axis. That ask is superseded -- owner, on the shipped result: the unknown-location map is
 * *"weird, and not comforting at all"* -- and the spin is gone for good; do not restore it. The
 * absence is now said by [com.fotoxplorr.app.viewer.PlaceUnknownPlate], which sits where this
 * opens, and this picker is only the tool. Concretely, what was wrong with the old field:
 * - it rotated an UNCLIPPED canvas, so the grid's corners swept outside its own box;
 * - it was a 1.6:1 hard-cornered rectangle inside a room whose plate is a 1:1 12 dp-rounded
 *   square, so opening it changed the room's shape;
 * - its pin defaulted to 0°N 0°E and was drawn there before anyone had touched anything, so a
 *   photo with no place looked like it had been taken off the coast of Africa;
 * - the pin was the only thing that could be dragged, a 28 dp target on a world map at about a
 *   degree per pixel.
 *
 * Now the field is the plate's shape (clipped, 1:1), static, draws NO pin until a place exists,
 * and a single tap anywhere places it. Dragging the pin then fine-tunes; a drag that starts
 * anywhere else is left alone so the room can still be scrolled with a thumb on the grid. The
 * grid is the whole world, so the typed coordinates below are the precise input.
 *
 * Both inputs edit the same value, in both directions: tap or drag and the numbers follow, type
 * a number and the pin moves. Two controls over one fact, which is the only arrangement that does
 * not eventually disagree with itself.
 *
 * [onSet] fires once per gesture -- a tap, the END of a drag, or a coordinate field losing FOCUS
 * -- never per drag frame or per keystroke, and never before a pin exists. It used to fire
 * continuously, and the host turned each call into a fresh `MediaStore.createWriteRequest` plus a
 * system consent dialog (see `FotoXplorrActivity`'s own history here): dragging the pin across a
 * room was a storm of Android permission prompts, one per frame. Everything in between is the pin
 * following the finger, which the visible position tracks locally regardless of when [onSet] is
 * told about it.
 *
 * The location [onSet] reports is written to Fotoz's own index only -- see
 * [GeoMetadataRepository.setManualLocation] -- never into the photo's EXIF. [onWriteIntoFile],
 * when non-null, is the explicit, separate action for that: one tap, one consent dialog, not a
 * side effect of moving a pin.
 *
 * @param onDone closes the picker. Null shows no such action (the picker is not collapsible).
 */
@Composable
fun LocationPicker(
    latitude: Double?,
    longitude: Double?,
    onSet: (Double, Double) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    /** Embeds the current location into the photo's own EXIF, with Android's one-time write
     *  consent -- see this composable's own doc. Null hides the action (a video, say, whose
     *  metadata story this app does not yet have). */
    onWriteIntoFile: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
) {
    val placed = latitude != null && longitude != null
    // Whether a pin exists at all. False until a place is given, by the caller or by the user:
    // there is no default coordinate, so there is nothing to draw and nothing to report.
    var hasPin by remember(latitude, longitude) { mutableStateOf(placed) }
    var pinLatitude by remember(latitude, longitude) { mutableDoubleStateOf(latitude ?: 0.0) }
    var pinLongitude by remember(latitude, longitude) { mutableDoubleStateOf(longitude ?: 0.0) }
    var latitudeText by remember(latitude, longitude) { mutableStateOf(latitude?.let { trim(it) } ?: "") }
    var longitudeText by remember(latitude, longitude) { mutableStateOf(longitude?.let { trim(it) } ?: "") }

    // Moves the pin and the two text fields, WITHOUT reporting anything upward -- what every drag
    // frame and every keystroke used to do. See this file's own doc for why that had to change.
    fun moveLocally(lat: Double, lon: Double) {
        pinLatitude = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        pinLongitude = lon.coerceIn(-MAX_LONGITUDE, MAX_LONGITUDE)
        hasPin = true
        latitudeText = trim(pinLatitude)
        longitudeText = trim(pinLongitude)
    }

    /** The one place [onSet] is actually called: a tap, a drag's end, or a field losing focus. */
    fun commit() {
        if (hasPin) onSet(pinLatitude, pinLongitude)
    }

    /** A typed coordinate moves the pin once BOTH fields hold a number, and never rewrites what
     *  the user is in the middle of typing (rewriting "17." as "17.0" under the caret turns the
     *  next keystroke into "17.05"). */
    fun typed() {
        val lat = latitudeText.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
        val lon = longitudeText.toDoubleOrNull()?.takeIf { it in -MAX_LONGITUDE..MAX_LONGITUDE }
        if (lat != null && lon != null) {
            pinLatitude = lat
            pinLongitude = lon
            hasPin = true
        } else {
            hasPin = false
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RoomEyebrow("PLACE")

        val shape = RoundedCornerShape(PLATE_CORNER_DP.dp)
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                // The plate's own shape and aspect, so replacing the plate with this field (and
                // back) does not change the size of the room.
                .aspectRatio(PLATE_ASPECT)
                .clip(shape)
                .background(PLATE_GROUND)
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val pinPx = with(density) { PIN_SIZE.dp.toPx() }
            val grabPx = with(density) { GRAB_RADIUS.dp.toPx() }

            Box(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Pick a place on the map" }
                    .pointerInput(widthPx, heightPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val pin = Offset(fieldX(pinLongitude, widthPx), fieldY(pinLatitude, heightPx))
                            val grabbed = hasPin && (down.position - pin).getDistance() <= grabPx
                            if (grabbed) {
                                // Fine-tuning the pin that is already there. Consumed, so the
                                // room does not scroll while it is being moved.
                                var moved = false
                                drag(down.id) { change ->
                                    change.consume()
                                    moved = true
                                    moveLocally(
                                        fieldLatitude(change.position.y, heightPx),
                                        fieldLongitude(change.position.x, widthPx),
                                    )
                                }
                                // Both endings commit: a cancelled drag (a second finger landing,
                                // the gesture being taken over) still leaves the pin somewhere,
                                // and that somewhere should not silently fail to save.
                                if (moved) commit()
                            } else {
                                // Anywhere else: a tap places the pin; a drag is the room being
                                // scrolled, which the parent takes (waitForUpOrCancellation
                                // returns null once it has), so it is not ours to consume.
                                val up = waitForUpOrCancellation()
                                if (up != null && (up.position - down.position).getDistance() <= viewConfiguration.touchSlop) {
                                    up.consume()
                                    moveLocally(
                                        fieldLatitude(up.position.y, heightPx),
                                        fieldLongitude(up.position.x, widthPx),
                                    )
                                    commit()
                                }
                            }
                        }
                    },
            ) {
                StaticGraticule()

                // The pin: only once a place exists. A circle, in the same visual language as the
                // plate's pin rather than a square outline.
                if (hasPin) {
                    val x = fieldX(pinLongitude, widthPx)
                    val y = fieldY(pinLatitude, heightPx)
                    Box(
                        Modifier
                            .offset { IntOffset((x - pinPx / 2f).roundToInt(), (y - pinPx / 2f).roundToInt()) }
                            .size(PIN_SIZE.dp)
                            .semantics { contentDescription = "Chosen place" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(PIN_SET))
                        Box(Modifier.size(PIN_SIZE.dp).border(1.dp, PIN_SET.copy(alpha = 0.5f), CircleShape))
                    }
                }
            }

            Text(
                text = if (hasPin) "Drag the pin, or type below" else "Tap the map to place this photo",
                color = Color.White.copy(alpha = 0.45f),
                style = RoomStyle.Caption,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CoordinateField(
                label = "Latitude",
                value = latitudeText,
                onValueChange = { text ->
                    latitudeText = text
                    typed()
                },
                // Typing "-3" is not a coordinate yet -- committing on every keystroke reported a
                // half-typed number as though it were a deliberate placement. Focus loss (tabbing
                // to Longitude, or dismissing the keyboard) is the actual "I am done typing" signal.
                onFocusLost = ::commit,
                modifier = Modifier.weight(1f),
            )
            CoordinateField(
                label = "Longitude",
                value = longitudeText,
                onValueChange = { text ->
                    longitudeText = text
                    typed()
                },
                onFocusLost = ::commit,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = if (placed) {
                "Saved in Fotoz's own index. The photo file itself is unchanged unless you " +
                    "choose to write into it below."
            } else {
                "This photo carries no GPS tag. Photos saved from messaging apps and websites " +
                    "usually have theirs removed before they arrive."
            },
            color = RoomStyle.InkFaint,
            style = RoomStyle.Caption,
        )

        if (placed && onWriteIntoFile != null) {
            Text(
                text = "Also write into the photo file",
                color = Color.White.copy(alpha = 0.75f),
                style = RoomStyle.Caption,
                modifier = Modifier.clickable(onClick = onWriteIntoFile).padding(vertical = 4.dp),
            )
        }

        if (placed) {
            Text(
                text = "Remove this location",
                color = PIN_SET,
                style = RoomStyle.Caption,
                modifier = Modifier.clickable(onClick = onClear).padding(vertical = 4.dp),
            )
        }

        if (onDone != null) {
            Text(
                text = "Done",
                color = MaterialTheme.colorScheme.primary,
                style = RoomStyle.Caption.copy(fontSize = 15.sp),
                modifier = Modifier.clickable(onClick = onDone).padding(vertical = 8.dp),
            )
        }
    }
}

/**
 * The field's grid: the world, still. Rows and columns at the plate's own ink, the equator and
 * the prime meridian a shade brighter so it reads as a globe's and not as decoration. Nothing
 * here moves, and nothing is drawn outside the field's clip.
 */
@Composable
private fun StaticGraticule() {
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 1.dp.toPx()
        // A touch quieter than the located plate's own lines: this is a tool surface, not data.
        val ink = GRATICULE.copy(alpha = 0.7f)
        val rows = 6
        val columns = 6
        for (row in 1 until rows) {
            val y = size.height * row / rows
            drawLine(ink, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        }
        for (column in 1 until columns) {
            val x = size.width * column / columns
            drawLine(ink, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
        }
        val axis = Color.White.copy(alpha = 0.14f)
        drawLine(axis, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = stroke)
        drawLine(axis, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = stroke)
    }
}

@Composable
private fun CoordinateField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onFocusLost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Tracked locally rather than derived from the FocusState transition alone: a FocusState
    // callback fires on ATTACH as "not focused" too (nothing was ever focused to lose), which
    // would call onFocusLost on first composition. Only a genuine focused-to-unfocused edge counts.
    var wasFocused by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, color = RoomStyle.InkFaint, style = RoomStyle.Caption)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .border(1.dp, Color.White.copy(alpha = 0.22f))
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .height(20.dp)
                .onFocusChanged { state ->
                    if (wasFocused && !state.isFocused) onFocusLost()
                    wasFocused = state.isFocused
                },
        )
    }
}

// ── The field's mapping between pixels and degrees ─────────────────────────────────────────────
// Pure, so a tap's coordinate can be asserted without a device. The window is the whole world in
// Web Mercator's latitude range, x linear in longitude and y linear in latitude (a plate-carrée
// grid, not a Mercator projection -- it is a graticule, not a map).

/** Degrees of longitude under pixel column [x] of a field [width] px wide, clamped to the world. */
internal fun fieldLongitude(x: Float, width: Float): Double =
    if (width <= 0f) 0.0 else ((x / width).coerceIn(0f, 1f) * 360.0 - 180.0)

/** Degrees of latitude under pixel row [y] of a field [height] px tall, clamped to the window. */
internal fun fieldLatitude(y: Float, height: Float): Double =
    if (height <= 0f) 0.0 else (MAX_LATITUDE - (y / height).coerceIn(0f, 1f) * 2.0 * MAX_LATITUDE)

/** The inverse of [fieldLongitude]. */
internal fun fieldX(longitude: Double, width: Float): Float =
    (((longitude.coerceIn(-MAX_LONGITUDE, MAX_LONGITUDE) + 180.0) / 360.0) * width).toFloat()

/** The inverse of [fieldLatitude]; a latitude beyond the window is drawn at its edge. */
internal fun fieldY(latitude: Double, height: Float): Float =
    (((MAX_LATITUDE - latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)) / (2.0 * MAX_LATITUDE)) * height).toFloat()

/** Five decimals is about a metre, and past what any phone's GPS resolves. */
private fun trim(value: Double): String = ((value * 100_000).roundToInt() / 100_000.0).toString()

/** The Web Mercator cutoff, so the pin cannot be dragged into the projection's singularity. */
internal const val MAX_LATITUDE = 85.05112878
internal const val MAX_LONGITUDE = 180.0
private const val PIN_SIZE = 28

/** How near the existing pin a touch must start to drag IT rather than place a new one. */
private const val GRAB_RADIUS = 36
// Fixed on purpose (semantic): the set-pin red is a conventional map colour.
private val PIN_SET = Color(0xFFE65A58)
