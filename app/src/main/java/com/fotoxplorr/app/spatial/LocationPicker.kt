package com.fotoxplorr.app.spatial

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.app.basemap.BasemapCanvas
import com.fotoxplorr.app.basemap.BasemapColors
import com.fotoxplorr.app.basemap.rememberBasemapColors
import com.fotoxplorr.app.basemap.MapMath
import com.fotoxplorr.app.basemap.MapViewport
import com.fotoxplorr.app.basemap.PinMath
import com.fotoxplorr.app.basemap.PxPoint
import com.fotoxplorr.app.basemap.mapGestures
import com.fotoxplorr.app.basemap.rememberImportedTiles
import com.fotoxplorr.app.hyle.HyleTextField
import com.fotoxplorr.app.hyle.normalizeDecimalInput
import com.fotoxplorr.app.viewer.PLATE_ASPECT
import com.fotoxplorr.app.viewer.PLATE_CORNER_DP
import com.fotoxplorr.app.viewer.PLATE_GROUND
import kotlin.math.roundToInt
import com.fotoxplorr.app.ui.HelpCopy

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
        val tiles = rememberImportedTiles()
        val mapBorder = rememberBasemapColors().border
        val maxLevel = if (tiles != null) IMPORTED_MAX_LEVEL else MapMath.MAX_LEVEL
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                // The plate's own shape and aspect, so replacing the plate with this field (and
                // back) does not change the size of the room.
                .aspectRatio(PLATE_ASPECT)
                .clip(shape)
                .background(PLATE_GROUND)
                .border(1.dp, mapBorder, shape),
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val viewport = MapViewport(widthPx.toDouble(), heightPx.toDouble())
            val pinPx = with(density) { PIN_SIZE.dp.toPx() }
            val grabPx = with(density) { GRAB_RADIUS.dp.toPx() }
            fun openingCamera() =
                if (placed) MapMath.centeredOn(longitude!!, latitude!!, PLACED_LEVEL, viewport, maxLevel)
                else MapMath.centeredOn(0.0, 0.0, 0.0, viewport, maxLevel)
            // One state object for the picker's life: gestures hold on to it, so it must not be
            // re-created when the size changes; a new size just moves the camera.
            var camera by remember { mutableStateOf(openingCamera()) }
            LaunchedEffect(viewport) { camera = openingCamera() }

            fun pinScreen(): PxPoint = MapMath.toScreen(camera, viewport, pinLongitude, pinLatitude)

            val colors = rememberBasemapColors()
            // The pin handler outlives recompositions (and the pin state is re-created when the
            // photo's place changes), so it reads the current ones through these.
            val pinScreenNow by rememberUpdatedState { pinScreen() }
            val hasPinNow by rememberUpdatedState(hasPin)
            val moveNow by rememberUpdatedState<(Double, Double) -> Unit>({ lat, lon -> moveLocally(lat, lon) })
            val commitNow by rememberUpdatedState { commit() }
            val cameraNow by rememberUpdatedState(camera)

            // A typed coordinate can put the pin off the visible map; bring the map to it.
            LaunchedEffect(pinLatitude, pinLongitude, hasPin) {
                if (!hasPin) return@LaunchedEffect
                val p = pinScreen()
                if (p.x < 0 || p.y < 0 || p.x > viewport.widthPx || p.y > viewport.heightPx) {
                    camera = MapMath.centeredOn(pinLongitude, pinLatitude, maxOf(camera.level, PLACED_LEVEL), viewport, maxLevel)
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Pick a place on the map" }
                    .mapGestures(
                        camera = { camera },
                        viewport = { viewport },
                        onCamera = { camera = it },
                        maxLevel = { maxLevel },
                        // One finger scrolls the room (and the shell owns the screen's side edges,
                        // TRAPS #72); two fingers and the buttons below move the map.
                        onePointerPans = false,
                        onTap = { at ->
                            val p = PinMath.pinAt(camera, viewport, at.x.toDouble(), at.y.toDouble())
                            moveLocally(p.lat, p.lon)
                            commit()
                        },
                    )
                    .pointerInput(viewport) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val grabbed = hasPinNow && PinMath.grabs(pinScreenNow(), down.position.x.toDouble(), down.position.y.toDouble(), grabPx.toDouble())
                            if (grabbed) {
                                // Fine-tuning the pin that is already there. Consumed, so the
                                // room does not scroll while it is being moved.
                                var moved = false
                                drag(down.id) { change ->
                                    change.consume()
                                    moved = true
                                    val p = PinMath.pinAt(cameraNow, viewport, change.position.x.toDouble(), change.position.y.toDouble())
                                    moveNow(p.lat, p.lon)
                                }
                                // Both endings commit: a cancelled drag (a second finger landing,
                                // the gesture being taken over) still leaves the pin somewhere,
                                // and that somewhere should not silently fail to save.
                                if (moved) commitNow()
                            }
                        }
                    },
            ) {
                BasemapCanvas(camera = { camera }, modifier = Modifier.fillMaxSize(), tiles = tiles)

                // The pin: only once a place exists. A circle, in the same visual language as the
                // plate's pin rather than a square outline.
                if (hasPin) {
                    val p = pinScreen()
                    Box(
                        Modifier
                            .offset { IntOffset((p.x - pinPx / 2f).roundToInt(), (p.y - pinPx / 2f).roundToInt()) }
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
                text = if (hasPin) "Drag the pin to move it" else "Tap the map to place this photo",
                color = colors.label,
                style = RoomStyle.Caption,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
            // The non-drag way to move around: zoom about the pin (or the middle), one tap each.
            Column(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                ZoomButton("+", "Zoom the map in", colors) {
                    val f = if (hasPin) pinScreen() else PxPoint(viewport.widthPx / 2, viewport.heightPx / 2)
                    camera = MapMath.zoomedAt(camera, viewport, camera.level + 1.0, f.x, f.y, maxLevel)
                }
                ZoomButton("\u2212", "Zoom the map out", colors) {
                    val f = if (hasPin) pinScreen() else PxPoint(viewport.widthPx / 2, viewport.heightPx / 2)
                    camera = MapMath.zoomedAt(camera, viewport, camera.level - 1.0, f.x, f.y, maxLevel)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CoordinateField(
                label = "Latitude",
                limit = 90.0,
                value = latitudeText,
                onValueChange = { text ->
                    latitudeText = normalizeDecimalInput(text)
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
                limit = 180.0,
                value = longitudeText,
                onValueChange = { text ->
                    longitudeText = normalizeDecimalInput(text)
                    typed()
                },
                onFocusLost = ::commit,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = if (placed) {
                HelpCopy.Hint.LOCATION_SAVED_IN_FOTOZ
            } else {
                HelpCopy.Hint.NO_LOCATION_IN_PHOTO
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

@Composable
private fun ZoomButton(glyph: String, description: String, colors: BasemapColors, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(colors.sea.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
            Text(glyph, color = colors.label, style = RoomStyle.Caption.copy(fontSize = 18.sp))
        }
    }
}

/**
 * One coordinate on the shared Hyle field: a decimal keyboard, a clear button, and a line saying
 * what is wrong when the text is not a number in range. The keyboard's decimal layout often has no
 * minus sign, so a 48 dp +/- button flips the sign (southern and western hemispheres).
 */
@Composable
private fun CoordinateField(
    label: String,
    value: String,
    limit: Double,
    onValueChange: (String) -> Unit,
    onFocusLost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Tracked locally rather than derived from the FocusState transition alone: a FocusState
    // callback fires on ATTACH as "not focused" too (nothing was ever focused to lose), which
    // would call onFocusLost on first composition. Only a genuine focused-to-unfocused edge counts.
    var wasFocused by remember { mutableStateOf(false) }
    val number = value.toDoubleOrNull()
    val problem = when {
        value.isBlank() || value == "-" || value == "." || value == "-." -> null
        number == null -> "Use digits, like 17.4435."
        number !in -limit..limit -> "Between -${limit.toInt()} and ${limit.toInt()}."
        else -> null
    }
    HyleTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        errorText = problem,
        clearable = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onFocusLost() }),
        modifier = modifier.onFocusChanged { state ->
            if (wasFocused && !state.isFocused) onFocusLost()
            wasFocused = state.isFocused
        },
        trailing = {
            Box(
                Modifier
                    .size(48.dp)
                    .clickable {
                        onValueChange(if (value.startsWith("-")) value.drop(1) else "-$value")
                    }
                    .semantics { contentDescription = "Flip the sign of $label" },
                contentAlignment = Alignment.Center,
            ) { Text("\u00B1", color = Color(0xFF3A3A44), style = RoomStyle.Caption.copy(fontSize = 18.sp)) }
        },
    )
}

/** Five decimals is about a metre, and past what any phone's GPS resolves. */
private fun trim(value: Double): String = ((value * 100_000).roundToInt() / 100_000.0).toString()

/** The Web Mercator cutoff, so the pin cannot be dragged into the projection's singularity. */
internal const val MAX_LATITUDE = MapMath.MAX_LAT
internal const val MAX_LONGITUDE = 180.0
private const val PIN_SIZE = 28

/** How near the existing pin a touch must start to drag IT rather than place a new one. */
private const val GRAB_RADIUS = 36

/** How far the map opens in on a photo that already has a place, and how far an imported file lets it go. */
private const val PLACED_LEVEL = 4.0
private const val IMPORTED_MAX_LEVEL = 12.0
// Fixed on purpose (semantic): the set-pin red is a conventional map colour.
private val PIN_SET = Color(0xFFE65A58)
