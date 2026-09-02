package com.fotoxplorr.app.spatial

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomStyle
import kotlin.math.roundToInt

/**
 * The map for a photo that has no location, and the way to give it one.
 *
 * Owner, 2026-08-18: *"When there is no location data, you must still show me the map, but keep it
 * slowly spinning to indicate no location yet, and a lat+long and location entry space which a
 * user can use to mark a location — move pin on map, as well as enter location or lat long."*
 *
 * So the absence is drawn rather than described. The field is the same stylized graticule the
 * places map uses, turning slowly on its own axis: unmistakably "searching, nothing found", and
 * unmistakably still a map. The moment a coordinate exists — dragged or typed — the spin stops.
 * That stop is the feedback; there is no separate "saved" state to notice.
 *
 * Both inputs edit the same value, in both directions: drag the pin and the numbers follow, type a
 * number and the pin moves. Two controls over one fact, which is the only arrangement that does
 * not eventually disagree with itself.
 *
 * The location is written to Foto Xplorr's own index, not into the photo's EXIF — see
 * [GeoMetadataRepository.setManualLocation] for why, and the caption below says so plainly rather
 * than letting someone believe the file itself was changed.
 */
@Composable
fun LocationPicker(
    latitude: Double?,
    longitude: Double?,
    onSet: (Double, Double) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val placed = latitude != null && longitude != null
    // The window the pin moves within. A whole-world span, so dragging can reach anywhere; typing
    // is there for precision, which is the division of labour a pin on a world map implies anyway.
    var pinLatitude by remember(latitude) { mutableFloatStateOf(latitude?.toFloat() ?: 0f) }
    var pinLongitude by remember(longitude) { mutableFloatStateOf(longitude?.toFloat() ?: 0f) }
    var latitudeText by remember(latitude) { mutableStateOf(latitude?.let { trim(it) } ?: "") }
    var longitudeText by remember(longitude) { mutableStateOf(longitude?.let { trim(it) } ?: "") }

    fun commit(lat: Float, lon: Float) {
        val clampedLat = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val clampedLon = lon.coerceIn(-180f, 180f)
        pinLatitude = clampedLat
        pinLongitude = clampedLon
        latitudeText = trim(clampedLat.toDouble())
        longitudeText = trim(clampedLon.toDouble())
        onSet(clampedLat.toDouble(), clampedLon.toDouble())
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RoomEyebrow(if (placed) "PLACE" else "NO LOCATION YET")

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .background(FIELD_DARK)
                .border(1.dp, Color.White.copy(alpha = 0.10f)),
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val pinPx = with(density) { PIN_SIZE.dp.toPx() }

            SpinningGraticule(spinning = !placed)

            // The pin. Dragged in screen space and converted straight back to degrees, so what the
            // finger does and what the numbers say cannot drift apart.
            val x = ((pinLongitude + 180f) / 360f) * widthPx
            val y = ((MAX_LATITUDE - pinLatitude) / (2 * MAX_LATITUDE)) * heightPx
            Box(
                Modifier
                    .offset { IntOffset((x - pinPx / 2f).roundToInt(), (y - pinPx / 2f).roundToInt()) }
                    .size(PIN_SIZE.dp)
                    .pointerInput(widthPx, heightPx) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            commit(
                                lat = pinLatitude - drag.y / heightPx * (2 * MAX_LATITUDE),
                                lon = pinLongitude + drag.x / widthPx * 360f,
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (placed) 14.dp else 10.dp)
                        .background(if (placed) PIN_SET else PIN_UNSET),
                )
                Box(
                    Modifier
                        .size(PIN_SIZE.dp)
                        .border(1.dp, (if (placed) PIN_SET else PIN_UNSET).copy(alpha = 0.5f)),
                )
            }

            Text(
                text = if (placed) "Drag the pin, or type below" else "Drag the pin to place this photo",
                color = Color.White.copy(alpha = 0.45f),
                style = RoomStyle.Caption,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CoordinateField(
                label = "Latitude",
                value = latitudeText,
                onValueChange = { text ->
                    latitudeText = text
                    text.toFloatOrNull()?.let { commit(it, pinLongitude) }
                },
                modifier = Modifier.weight(1f),
            )
            CoordinateField(
                label = "Longitude",
                value = longitudeText,
                onValueChange = { text ->
                    longitudeText = text
                    text.toFloatOrNull()?.let { commit(pinLatitude, it) }
                },
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = if (placed) {
                "Saved in Foto Xplorr, not written into the photo file itself."
            } else {
                "This photo carries no GPS tag. Photos saved from messaging apps and websites " +
                    "usually have theirs removed before they arrive."
            },
            color = RoomStyle.InkFaint,
            style = RoomStyle.Caption,
        )

        if (placed) {
            Text(
                text = "Remove this location",
                color = PIN_SET,
                style = RoomStyle.Caption,
                modifier = Modifier.clickable(onClick = onClear).padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * The field: a graticule that turns while there is nothing to show.
 *
 * The rotation is the whole message — a still grid reads as a map with no pins, a turning one
 * reads as a map still looking. Slow on purpose: fast enough to be seen at a glance, slow enough
 * that it never competes with the photo above it for attention.
 */
@Composable
private fun SpinningGraticule(spinning: Boolean) {
    val transition = rememberInfiniteTransition(label = "map-spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SPIN_MILLIS, easing = LinearEasing)),
        label = "map-spin-angle",
    )
    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { rotationZ = if (spinning) angle else 0f },
    ) {
        val ink = Color.White.copy(alpha = 0.09f)
        val rows = 6
        val columns = 10
        for (row in 1 until rows) {
            val y = size.height * row / rows
            drawLine(ink, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y))
        }
        for (column in 1 until columns) {
            val x = size.width * column / columns
            drawLine(ink, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height))
        }
        // The equator and the meridian, marked a shade brighter so the grid reads as a globe's
        // rather than as decoration.
        drawLine(
            Color.White.copy(alpha = 0.18f),
            androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
            androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.12f),
            radius = minOf(size.width, size.height) * 0.38f,
            style = Stroke(1f),
        )
    }
}

@Composable
private fun CoordinateField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                .height(20.dp),
        )
    }
}

/** Five decimals is about a metre, and past what any phone's GPS resolves. */
private fun trim(value: Double): String = ((value * 100_000).roundToInt() / 100_000.0).toString()

/** The Web Mercator cutoff, so the pin cannot be dragged into the projection's singularity. */
private const val MAX_LATITUDE = 85.05112878f
private const val PIN_SIZE = 28
private const val SPIN_MILLIS = 24_000
private val FIELD_DARK = Color(0xFF0E111A)
private val PIN_UNSET = Color(0xFF8A8F9E)
private val PIN_SET = Color(0xFFE65A58)
