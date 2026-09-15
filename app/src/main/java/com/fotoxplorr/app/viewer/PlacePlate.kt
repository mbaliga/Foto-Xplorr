package com.fotoxplorr.app.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage

/**
 * The place plate: where this photo was taken, and the surface its thumbnail flies into as the
 * top room opens.
 *
 * **What this is, and what it is not.** The plate is a *coordinate* visualisation drawn from the
 * photo's own embedded GPS fix — a graticule, a scale and a pin, rendered locally. It is not a
 * street map and deliberately downloads nothing: the room opens on a drag, and a surface that
 * reached for map tiles every time a finger moved would turn a gesture into a network request
 * and leak the user's photo locations to a tile server for the privilege. Foto Xplorr's real
 * street map stays where it already is, behind Places, where the user asks for it explicitly.
 *
 * The pin sits at the plate's centre because the plate is centred on the photo. The *graticule*
 * is what carries the actual position — its lines fall where the whole arc-minute steps really
 * land either side of the coordinate, so two photos a street apart get visibly different grids.
 *
 * @param reveal how open the room is, 0..1, read on the draw pass. A lambda rather than a value
 *   so a drag re-draws the flight without recomposing the room around it.
 */
@Composable
fun PlacePlate(
    asset: MediaAsset,
    latitude: Double,
    longitude: Double,
    reveal: () -> Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(PLATE_ASPECT)
            .clip(RoundedCornerShape(14.dp))
            .background(PLATE_GROUND),
        contentAlignment = Alignment.Center,
    ) {
        // The thumbnail starts big enough to read as the photo the user was just looking at and
        // lands at pin size. Derived from the measured plate rather than a constant so the
        // flight covers the same proportion of the plate on every screen size.
        val heroScale = (maxWidth * HERO_PLATE_FRACTION) / PIN_SIZE.dp

        val latitudeLines = PlaceMorph.graticuleFractions(latitude)
        val longitudeLines = PlaceMorph.graticuleFractions(longitude)

        Canvas(Modifier.fillMaxSize()) {
            val resolved = PlaceMorph.plateAlpha(reveal())
            if (resolved <= 0.01f) return@Canvas
            val ink = GRATICULE.copy(alpha = GRATICULE.alpha * resolved)
            val stroke = GRATICULE_STROKE_DP.dp.toPx()
            // Latitude runs across, longitude runs down: a line of constant latitude is drawn
            // horizontally. Getting this the wrong way round would still look like a grid,
            // which is exactly why it is worth being explicit about.
            latitudeLines.forEach { fraction ->
                val y = size.height * (1f - fraction)
                drawLine(ink, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            }
            longitudeLines.forEach { fraction ->
                val x = size.width * fraction
                drawLine(ink, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
            }
        }

        // The pin's tail, under the thumbnail. It belongs to the *landed* pin, so it tracks the
        // plate's arrival rather than the flight — a tail hanging off a full-size photo mid-air
        // would read as a speech bubble.
        Box(
            modifier = Modifier
                .size(PIN_SIZE.dp)
                .graphicsLayer { alpha = PlaceMorph.plateAlpha(reveal()) }
                .drawBehind {
                    val halfWidth = TAIL_WIDTH_DP.dp.toPx() / 2f
                    val height = TAIL_HEIGHT_DP.dp.toPx()
                    // Overlap the pin body by a pixel so the join has no hairline seam.
                    val top = size.height - 1f
                    drawPath(
                        path = Path().apply {
                            moveTo(size.width / 2f - halfWidth, top)
                            lineTo(size.width / 2f + halfWidth, top)
                            lineTo(size.width / 2f, top + height)
                            close()
                        },
                        color = Color.White,
                    )
                },
        )

        Box(
            modifier = Modifier
                .size(PIN_SIZE.dp)
                .graphicsLayer {
                    val scale = PlaceMorph.thumbnailScale(reveal(), heroScale)
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(PIN_CORNER_DP.dp))
                .background(Color.White)
                .padding(PIN_BORDER_DP.dp)
                .clip(RoundedCornerShape((PIN_CORNER_DP - PIN_BORDER_DP).dp))
                .semantics { contentDescription = "Photo location" },
        ) {
            MediaImage(
                asset = asset,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .graphicsLayer { alpha = PlaceMorph.textAlpha(reveal()) },
        ) {
            Text(
                text = PlaceMorph.coordinateLine(latitude, longitude),
                color = Color.White,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            )
            Text(
                text = PlaceMorph.scaleLine(latitude),
                color = Color.White.copy(alpha = 0.62f),
                style = TextStyle(fontSize = 11.sp),
            )
        }
    }
}

private const val PLATE_ASPECT = 1.22f
private const val PIN_SIZE = 72
private const val PIN_CORNER_DP = 10
private const val PIN_BORDER_DP = 3
private const val TAIL_WIDTH_DP = 16
private const val TAIL_HEIGHT_DP = 9
private const val GRATICULE_STROKE_DP = 1
/** How much of the plate's width the thumbnail spans at the start of its flight. */
private const val HERO_PLATE_FRACTION = 0.92f

private val PLATE_GROUND = Color(0xFF12161C)
private val GRATICULE = Color(0xFF2E3946)
