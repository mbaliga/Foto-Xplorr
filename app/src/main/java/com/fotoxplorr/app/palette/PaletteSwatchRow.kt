package com.fotoxplorr.app.palette

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ui.HyleGrotesk

/**
 * A photo's palette as the segmented bar the details room already draws -- one strip, each
 * colour's width its share of the photo, hex values printed underneath -- with every segment a
 * button that searches the library for that colour.
 *
 * Stateless: the caller owns the swatches (read off the recognition index, never decoded here) and
 * what a tap means. [onSwatchTap] receives the segment's packed ARGB; the caller turns it into the
 * query with [com.fotoxplorr.core.search.ColourSearch.colourQuery] and hands it to whatever opens
 * search, so this composable knows nothing about the viewer or the gallery.
 *
 * Two layers, on purpose. The strip is ONE draw call over all the swatches, not a composable per
 * segment, so drawing allocates nothing and composition does not grow with the swatch count. The
 * tap targets are a transparent [Row] over the whole thing, weighted by the same
 * [segmentWeight] the strip is drawn with, so a finger on a segment lands on that segment's
 * button -- and because the row covers the hex line and the 48dp minimum height as well as the
 * 14dp strip, a target is never as thin as the strip it belongs to. A sliver of colour also gets
 * [MIN_SEGMENT_WEIGHT] of the width whatever its true share, so a 1% accent is still something a
 * finger can hit and TalkBack can land on; the drawn strip stretches that sliver by the same rule,
 * which is a small lie about proportion told in exchange for a tappable segment.
 *
 * Renders nothing at all for an empty palette: the room's own "-" dash for a missing fact is the
 * caller's to draw, as it is for every other row there.
 */
@Composable
fun PaletteSwatchRow(
    swatches: List<PaletteSwatch>,
    onSwatchTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Colour of the hex line. Defaults to the details room's secondary text. */
    labelColor: Color = DEFAULT_LABEL_COLOR,
) {
    if (swatches.isEmpty()) return
    Box(modifier = modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET)) {
        Column(verticalArrangement = Arrangement.spacedBy(LABEL_GAP)) {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(STRIP_HEIGHT)
                    .clip(RoundedCornerShape(STRIP_RADIUS))
                    .drawBehind { drawStrip(swatches) },
            )
            Text(
                text = hexLine(swatches),
                color = labelColor,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 11.sp, letterSpacing = 0.2.sp),
            )
        }
        Row(Modifier.matchParentSize()) {
            for (index in swatches.indices) {
                val swatch = swatches[index]
                val description = "Search photos coloured ${swatch.hex}"
                Box(
                    Modifier
                        .weight(segmentWeight(swatch))
                        .fillMaxHeight()
                        .semantics { contentDescription = description }
                        .clickable(onClickLabel = description, role = Role.Button) { onSwatchTap(swatch.argb) },
                )
            }
        }
    }
}

/**
 * Draws every segment in one pass. Index loops and inline value classes ([Color], [Offset],
 * [Size]) throughout, so a frame allocates nothing; segment edges come from the cumulative weight
 * rather than from summing widths, so rounding never drifts the last segment short of the end.
 */
private fun DrawScope.drawStrip(swatches: List<PaletteSwatch>) {
    var total = 0f
    for (index in swatches.indices) total += segmentWeight(swatches[index])
    if (total <= 0f) return
    var start = 0f
    var cumulative = 0f
    for (index in swatches.indices) {
        cumulative += segmentWeight(swatches[index])
        val end = if (index == swatches.lastIndex) size.width else size.width * (cumulative / total)
        drawRect(
            color = Color(swatches[index].argb),
            topLeft = Offset(start, 0f),
            // One pixel over, so an anti-aliased edge never shows as a seam between two
            // segments; the next segment paints over the overlap, and the clip trims the last.
            size = Size(end - start + 1f, size.height),
        )
        start = end
    }
}

/**
 * A segment's share of the row. The true proportion, except that nothing goes below
 * [MIN_SEGMENT_WEIGHT] -- see the composable's own doc for why a sliver is widened.
 */
private fun segmentWeight(swatch: PaletteSwatch): Float = swatch.proportion.coerceAtLeast(MIN_SEGMENT_WEIGHT)

/** Built in composition, so only when the swatches change -- never on a draw. */
private fun hexLine(swatches: List<PaletteSwatch>): String = swatches.joinToString("   ") { it.hex }

/**
 * Widest share a segment is guaranteed, whatever its true proportion. 8% of the row keeps a
 * sliver findable by finger and by TalkBack without turning the bar into five equal chips; the
 * 48dp minimum height does the rest of the touch-target work.
 */
private const val MIN_SEGMENT_WEIGHT = 0.08f

private val TOUCH_TARGET = 48.dp
private val STRIP_HEIGHT = 14.dp
private val STRIP_RADIUS = 4.dp
private val LABEL_GAP = 6.dp

// Fixed on purpose (semantic): swatches are the photo's own colours and the label is neutral text.
/** The details room's own secondary text colour, so the hex line matches the rows around it. */
private val DEFAULT_LABEL_COLOR = Color(0xFF8A8A8A)
