package com.fotoxplorr.app.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.ui.RoomStyle

/**
 * What the place plate shows for a photo that has no location: the same square, rounded surface
 * as [PlacePlate], so the room does not change shape when a place is added, but calm instead of
 * searching.
 *
 * The previous stand-in was the location picker's own field -- a hard-cornered rectangle of a
 * different aspect with an unclipped world grid turning inside it, and a pin sitting at 0°N 0°E
 * before anyone had touched anything. Owner: *"the map bg when the location is unknown is weird,
 * and not comforting at all."* The 2026-08-18 ask for a slowly spinning map was a request for
 * "no location yet", not for motion; it is superseded, and nothing here moves.
 *
 * What is on it:
 * - a **static, low-contrast graticule** at a fixed neutral coordinate (it carries no information
 *   about this photo, which is the point -- unlike [PlacePlate]'s, whose lines fall where the
 *   photo's own coordinate does), softened toward the edges so it reads as texture;
 * - the photo as a **ghost pin**: the same 80 dp tile, darkened by [PlaceMorph.GHOST_DIM] and with
 *   no pointer, because a pointer says "here" and there is no here. It is where the shrink-to-map
 *   flight lands, so the effect works on a screenshot as it does on a geotagged photo;
 * - the words **"No location yet"**, and, only when [onAddPlace] is given, an **"Add a place"**
 *   action. With no action (the room is read-only, or the metadata read failed -- the caller
 *   decides; see P0-02 in docs/TRAPS.md) it says why instead, in one sentence.
 *
 * It draws no map, ever: there is nothing to put one on, and the offline flavour has none anyway.
 * Everything about it has to look right on the graticule alone.
 *
 * @param onAddPlace opens the picker. Null means no action is offered. The caller must pass null
 *   after a failed metadata read: a failed read is not evidence the file has no GPS tag, and
 *   hand-placing a location over one that may exist is the failure P0-02 closed.
 * @param flightActive, flightFlies, onPinPositioned as for [PlacePlate]; the ghost pin is the
 *   landing target and reports its tile the same way.
 */
@Composable
fun PlaceUnknownPlate(
    asset: MediaAsset,
    reveal: () -> Float,
    modifier: Modifier = Modifier,
    onAddPlace: (() -> Unit)? = null,
    flightActive: Boolean = false,
    flightFlies: Boolean = true,
    onPinPositioned: ((Rect) -> Unit)? = null,
) {
    // The same fractions serve both axes: the grid is neutral, so it is square.
    val gridLines = remember { PlaceMorph.graticuleFractions(NEUTRAL_DEGREES) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(PLATE_ASPECT)
            .clip(RoundedCornerShape(PLATE_CORNER_DP.dp))
            .background(PLATE_GROUND),
        contentAlignment = Alignment.Center,
    ) {
        val heroScale = PlaceMorph.heroScale(maxWidth.value)

        Canvas(Modifier.fillMaxSize()) {
            // Arrives with the plate on the same curve, so a half-open room is a half-resolved
            // plate; still, once open, nothing on it moves.
            val resolved = PlaceMorph.plateAlpha(reveal())
            if (resolved <= 0.01f) return@Canvas
            val ink = GRATICULE.copy(alpha = GRATICULE.alpha * GRATICULE_STRENGTH * resolved)
            val stroke = 1.dp.toPx()
            gridLines.forEach { fraction ->
                val y = size.height * (1f - fraction)
                drawLine(ink, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            }
            gridLines.forEach { fraction ->
                val x = size.width * fraction
                drawLine(ink, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
            }
            // Softens the lines toward the corners: a grid that runs hard to the edge reads as
            // a diagram, one that fades out reads as a surface.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, PLATE_GROUND.copy(alpha = VIGNETTE_ALPHA * resolved)),
                    center = center,
                    radius = size.maxDimension * VIGNETTE_RADIUS,
                ),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        ) {
            RevealingPin(
                asset = asset,
                reveal = reveal,
                heroScale = heroScale,
                mode = PlaceMorph.pinMode(flightActive, flightFlies),
                ghost = true,
                onTilePositioned = onPinPositioned,
            )
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier.textArrival(reveal),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "No location yet",
                    color = RoomStyle.InkMuted,
                    style = RoomStyle.Row,
                    textAlign = TextAlign.Center,
                )
                if (onAddPlace != null) {
                    Spacer(Modifier.height(14.dp))
                    AddPlaceButton(onClick = onAddPlace)
                } else {
                    Text(
                        text = "Photos saved from messaging apps and websites usually have their " +
                            "location removed before they arrive.",
                        color = RoomStyle.InkFaint,
                        style = RoomStyle.Caption,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** The one action on the plate: a filled pill in the accent, a full 48 dp to hit. */
@Composable
private fun AddPlaceButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Add a place",
            color = MaterialTheme.colorScheme.onPrimary,
            style = RoomStyle.Row.copy(fontSize = 15.sp),
        )
    }
}

/**
 * Where the neutral graticule is centred, in degrees on both axes. Any fixed number would do; 0
 * is chosen because it gives a symmetric grid. It is NOT a claim about where the photo was.
 */
private const val NEUTRAL_DEGREES = 0.0

/** The graticule's ink relative to the located plate's: about half, so it is texture not data. */
private const val GRATICULE_STRENGTH = 0.5f
private const val VIGNETTE_ALPHA = 0.7f
private const val VIGNETTE_RADIUS = 0.62f
