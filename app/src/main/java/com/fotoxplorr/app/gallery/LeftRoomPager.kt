package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.media.MediaAsset
import dev.aarso.cellshell.WheelItem
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * The left room's card column (owner, 2026-11-16, from the transition video): the destinations'
 * cards stand in a vertical column beside the word wheel. The current one is the live gallery card
 * (80% of the window high, parked by SpatialShell's open park); the previous and next peek in the
 * free strips above and below, and turning the wheel slides the WHOLE column in step with the
 * wheel's fractional position. The live card is moved by the shell's parkedCardOffsetY; every
 * other visible card is a ghost drawn here, in the room layer behind it. Everything below the
 * pure helpers is draw/placement-phase work: turning the wheel recomposes nothing.
 */

/** Share of the window width the parked card keeps; the one source for the shell's OpenPark and the ghosts. */
internal const val LEFT_ROOM_KEEP = 0.47f

/** The parked card's scale: 80% of the window high at rest. */
internal const val LEFT_ROOM_SCALE = 0.80f

/** Corner radius of the parked card, in dp before its scale. */
internal const val LEFT_ROOM_CORNER_DP = 28f

/** The gap between cards, as a share of the window height. */
internal const val LEFT_ROOM_GAP = 0.03f

/** The distance between two cards' centres, in px: a card plus the gap. */
internal fun pagerPitchPx(windowHeightPx: Float, scale: Float = LEFT_ROOM_SCALE, gap: Float = LEFT_ROOM_GAP): Float =
    windowHeightPx * (scale + gap)

/** The wheel position kept inside the run: the wheel cannot go past the first or the last word. */
internal fun pagerClamp(position: Float, count: Int): Float =
    if (count <= 0 || !position.isFinite()) 0f else position.coerceIn(0f, (count - 1).toFloat())

/**
 * Card [index]'s vertical offset from the centred position, in px: zero when the wheel rests on
 * it, one pitch down for each row it is below the wheel.
 */
internal fun pagerOffsetPx(index: Int, position: Float, count: Int, windowHeightPx: Float): Float =
    (index - pagerClamp(position, count)) * pagerPitchPx(windowHeightPx)

/**
 * The cards to draw as ghosts: every index but [selected] that is at least a pixel inside the
 * window at this [position]. No looping: indices outside 0 until [count] are never returned, so
 * the first card has nothing above it and the last nothing below.
 */
internal fun pagerGhostIndices(position: Float, selected: Int, count: Int, windowHeightPx: Float, scale: Float = LEFT_ROOM_SCALE): List<Int> {
    if (count <= 0 || windowHeightPx <= 0f) return emptyList()
    val p = pagerClamp(position, count)
    val pitch = pagerPitchPx(windowHeightPx, scale)
    val reach = windowHeightPx / 2f + windowHeightPx * scale / 2f
    val lo = floor(p - reach / pitch).toInt().coerceAtLeast(0)
    val hi = floor(p + reach / pitch).toInt() + 1
    return (lo..hi.coerceAtMost(count - 1)).filter { it != selected && abs(it - p) * pitch < reach - 1f }
}

/** Where the live card sits: its own offset, which is what the shell's `parkedCardOffsetY` gets. */
internal fun pagerLiveOffsetPx(selected: Int, position: Float, count: Int, windowHeightPx: Float): Float =
    pagerOffsetPx(selected, position, count, windowHeightPx)

/** Whether a destination's card may show a cover. Protected never; Experimental has no photos. */
internal fun coverEligible(destination: HyleDestination): Boolean =
    destination != HyleDestination.PROTECTED && destination != HyleDestination.PLACES

/**
 * The cover photo of each destination word, by rail id: the newest photo of the destination's
 * BROWSABLE list (TRAPS #33), never a sensitive one, null where there is none (Map, Experimental,
 * Protected, an empty destination). Heavy on a big library, so call it off the main thread.
 */
internal fun railCovers(state: GalleryUiState): Map<String, MediaAsset?> =
    HyleDestination.entries.filter(::coverEligible).associate { d ->
        d.name to destinationAssets(d, state).firstOrNull { it.id !in state.sensitiveIds }
    }

/** A soft, stable tint for a card with no cover: the same word always gets the same colour. */
internal fun ghostTintFor(id: String): Color {
    val hue = ((id.hashCode() and 0x7fffffff) % 360).toFloat()
    return Color.hsv(hue, 0.22f, 0.86f)
}

/**
 * The ghost cards behind the live one, right of the room. Composed in the room (so it only exists
 * while the room does), sized to fill it; each ghost overflows to the right, into the half the live
 * card covers. Decorative: no semantics, the wheel stays the control.
 *
 * @param position the wheel's fractional position, read only in placement.
 * @param covers cover photo by item id, from [railCovers].
 * @param windowWidthPx the window's width, to size a ghost to the share the card keeps.
 */
@Composable
internal fun LeftRoomGhosts(
    items: List<WheelItem>,
    selectedIndex: Int,
    position: MutableFloatState,
    covers: Map<String, MediaAsset?>,
    windowWidthPx: Int,
    windowHeightPx: Int,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val count = items.size
    // With reduced motion the column does not slide: the neighbours stand at rest around the live card.
    val pos: () -> Float = { if (reduceMotion) selectedIndex.toFloat() else position.floatValue }
    val indices by remember(selectedIndex, count, windowHeightPx, reduceMotion) {
        derivedStateOf { pagerGhostIndices(pos(), selectedIndex, count, windowHeightPx.toFloat()) }
    }
    val radius = (LEFT_ROOM_CORNER_DP * LEFT_ROOM_SCALE).dp
    Box(modifier.fillMaxSize().clearAndSetSemantics { }) {
        for (i in indices) {
            val item = items[i]
            val cover = covers[item.id]
            Box(
                Modifier
                    .ghostPlacement(
                        widthPx = (windowWidthPx * LEFT_ROOM_KEEP).roundToInt(),
                        offsetY = { pagerOffsetPx(i, pos(), count, windowHeightPx.toFloat()) },
                    )
                    .clip(RoundedCornerShape(topStart = radius, bottomStart = radius))
                    .background(ghostTintFor(item.id)),
            ) {
                if (cover != null) {
                    MediaImage(asset = cover, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
    }
}

/**
 * Sizes the node to a ghost (the parked card's height, [widthPx] wide) and places it just right of
 * its parent, centred, plus [offsetY]. The offset is read in the placement block, so a turn of the
 * wheel moves it without recomposing.
 */
private fun Modifier.ghostPlacement(widthPx: Int, offsetY: () -> Float): Modifier = layout { measurable, constraints ->
    val h = constraints.maxHeight
    val cardH = (h * LEFT_ROOM_SCALE).roundToInt()
    val placeable = measurable.measure(Constraints.fixed(widthPx.coerceAtLeast(0), cardH))
    layout(0, 0) {
        placeable.place(IntOffset(constraints.maxWidth, (h - cardH) / 2 + offsetY().roundToInt()))
    }
}
