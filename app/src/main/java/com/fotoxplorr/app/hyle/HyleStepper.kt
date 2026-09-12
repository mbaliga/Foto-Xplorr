package com.fotoxplorr.app.hyle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ui.HyleGrotesk

/**
 * The Hyle stepper: one light track carrying a black cell at each end and the value waiting
 * between them in ink.
 *
 * It exists because the settings room's steppers were the last Material chrome left on a screen
 * whose every other control is Hyle — two `IconButton`s holding bare `+` / `-` glyphs floating in
 * space (owner, 2026-09-01: *"As for the internals like the binary toggles, please use Hyle and do
 * not invent buttons."*). The answer to that is not a new button, so this control invents nothing:
 * each end cell **is** [HyleToggle]'s knob — the same width, the same near-black, the same
 * right-leaning shear — parked at its end of the same track instead of sliding along it. Read the
 * two together and the stepper is a switch whose knob went both ways.
 *
 * The cells are hard-edged parallelograms drawn a skew *past* the track's outer edge and clipped to
 * it, which is the toggle's own trick: the edges a cell shares with the track inherit the track's
 * rounding, and only the inner diagonal stays crisp. Both cells lean the same way, as everything in
 * this language does (the field's wedge tab leans too), so the light gap between them is itself a
 * parallelogram rather than a gap with two mismatched sides.
 *
 * Disabled is stated per end, because that is how a stepper is actually disabled: at 100% only the
 * decrease end is live, and greying the whole control there would be a lie about what a tap does.
 * A dead end takes the toggle's disabled knob grey; only when *both* ends are dead do the track and
 * the value fall back too, since at that point the control really is inert.
 *
 * @param label names the setting for a screen reader — it is not drawn. The room draws its own
 *   label in its own type, exactly as it does around [HyleToggle]; a control in this package never
 *   assumes the ink colour of the surface it lands on.
 * @param value already formatted by the caller ("60%", "05:00", "3"). This control never parses,
 *   rounds or unit-suffixes it — the caller owns what a number means.
 */
@Composable
fun HyleStepper(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
) {
    val decreaseInteraction = remember { MutableInteractionSource() }
    val increaseInteraction = remember { MutableInteractionSource() }
    val decreasePressed by decreaseInteraction.collectIsPressedAsState()
    val increasePressed by increaseInteraction.collectIsPressedAsState()

    // Both ends dead is the only state in which the control as a whole has nothing to offer, so it
    // is the only state in which the track and the value are allowed to go quiet.
    val live = canDecrease || canIncrease
    val trackColor = if (live) HYLE_TRACK else HYLE_TRACK_DISABLED
    val decreaseColor = cellColor(canDecrease, decreasePressed)
    val increaseColor = cellColor(canIncrease, increasePressed)
    val valueColor = if (live) HYLE_KNOB else HYLE_INK_FAINT

    Row(
        modifier = modifier
            .width(STEPPER_W.dp)
            .height(HIT_H.dp)
            .drawBehind {
                val skew = KNOB_SKEW.dp.toPx()
                val cell = CELL_W.dp.toPx()
                val trackH = TRACK_H.dp.toPx()
                // The drawn track keeps the toggle's height and sits centred in a taller,
                // transparent hit band (see [HIT_H]), so the band never shows.
                val top = (size.height - trackH) / 2f
                val bottom = top + trackH
                val w = size.width

                val track = Path().apply {
                    addRoundRect(
                        RoundRect(
                            0f, top, w, bottom,
                            CornerRadius(TRACK_RADIUS.dp.toPx()),
                        )
                    )
                }
                clipPath(track) {
                    drawRect(trackColor, topLeft = Offset(0f, top), size = Size(w, trackH))
                    // Leading edge slanted, outer edge run past the clip: the cell reads as the
                    // knob pushed hard against its end of the track.
                    drawPath(
                        Path().apply {
                            moveTo(-skew, top)
                            lineTo(cell, top)
                            lineTo(cell - skew, bottom)
                            lineTo(-skew, bottom)
                            close()
                        },
                        decreaseColor,
                    )
                    drawPath(
                        Path().apply {
                            moveTo(w - cell + skew, top)
                            lineTo(w + skew, top)
                            lineTo(w + skew, bottom)
                            lineTo(w - cell, bottom)
                            close()
                        },
                        increaseColor,
                    )
                }

                // A leaning cell's visual mass does not sit at its slot's midpoint: after the clip
                // each cell is a trapezoid (full width along one horizontal edge, a skew narrower
                // along the other), whose centroid is the mean of its four corners. Centring the
                // glyph on the slot instead would crowd it against the diagonal.
                val glyphInset = (2f * cell - skew) / 4f
                val cy = top + trackH / 2f
                drawStepGlyph(glyphInset, cy, upright = false)
                drawStepGlyph(w - glyphInset, cy, upright = true)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepCell(
            description = "Fewer, $label",
            enabled = canDecrease,
            onClick = onDecrease,
            interactionSource = decreaseInteraction,
        )
        Text(
            text = value,
            color = valueColor,
            style = TextStyle(
                fontFamily = HyleGrotesk,
                fontSize = GLYPH_SIZE.sp,
                fontWeight = FontWeight.Medium,
            ),
            textAlign = TextAlign.Center,
            maxLines = 1,
            // Qualified with the setting's name because a screen reader lands on this node on its
            // own, and "60%" by itself names nothing.
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "$label, $value" },
        )
        StepCell(
            description = "More, $label",
            enabled = canIncrease,
            onClick = onIncrease,
            interactionSource = increaseInteraction,
        )
    }
}

/**
 * The tap target over one end cell. Transparent by design: the cell it drives is painted by the
 * parent's single `drawBehind` pass, because the two cells and the track are one drawing and
 * splitting them across three layout nodes would put a seam where the diagonal is.
 *
 * Indication is suppressed and the press answered by the cell's own fill instead. A default ripple
 * is a rectangle in a control made of diagonals, and this target is deliberately taller than the
 * track it sits on, so the ripple would also spill above and below the drawn shape.
 */
@Composable
private fun StepCell(
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource,
) {
    Box(
        Modifier
            .width(CELL_W.dp)
            .fillMaxHeight()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description }
    )
}

/**
 * Pressed lifts the cell from the knob's near-black to the field's softer ink — a shade already in
 * the palette rather than a new one — so a press is visible without borrowing Material's ripple.
 */
private fun cellColor(enabled: Boolean, pressed: Boolean): Color = when {
    !enabled -> HYLE_KNOB_DISABLED
    pressed -> HYLE_KNOB_PRESSED
    else -> HYLE_KNOB
}

/**
 * The `-` and `+` marks, drawn rather than typed for the same reason [HyleField]'s `!` is: at this
 * size a font glyph goes spindly next to the hard shapes around it, and the two marks would not
 * share a weight.
 *
 * They stay white on a disabled cell, as the toggle's registration mark does on a disabled knob —
 * the mark receding into the pale grey *is* the disabled reading, and re-tinting it would be a
 * second, contradictory statement of the same fact.
 */
private fun DrawScope.drawStepGlyph(cx: Float, cy: Float, upright: Boolean) {
    val bar = GLYPH_BAR.dp.toPx()
    val stroke = GLYPH_STROKE.dp.toPx()
    drawLine(HYLE_MARK, Offset(cx - bar / 2f, cy), Offset(cx + bar / 2f, cy), strokeWidth = stroke)
    if (upright) {
        // The upright leans on exactly the cell's slope — the skew taken across the track's full
        // height, scaled down to the bar's length. A plumb-vertical stroke inside a sheared cell is
        // the one detail that would give this away as a Material plus wearing Hyle's coat.
        val lean = KNOB_SKEW.dp.toPx() * bar / TRACK_H.dp.toPx()
        drawLine(
            HYLE_MARK,
            Offset(cx + lean / 2f, cy - bar / 2f),
            Offset(cx - lean / 2f, cy + bar / 2f),
            strokeWidth = stroke,
        )
    }
}

// ---- Hyle stepper geometry & palette ----
// The track height, radius, skew, cell width, glyph type size and the whole palette are HyleToggle's
// own numbers, restated here rather than shared because they are file-private over there and this
// control did not seem worth widening their visibility for. They are one set of values, not two: if
// the toggle's track ever changes, these change with it.
private const val TRACK_H = 32
private const val TRACK_RADIUS = 9
private const val CELL_W = 34       // = the toggle's KNOB_W. The cell is the knob, parked.
private const val KNOB_SKEW = 6
private const val GLYPH_SIZE = 15

// Wide enough that the longest value the app feeds this ("05:00", from the background room's
// active-hours rows) sits clear of both diagonals, so the control never resizes as digits change.
private const val STEPPER_W = 124

// The drawn track stays at the toggle's 32dp; the extra height is transparent slop, because a
// stepper is the one control in this language that gets tapped repeatedly and 32dp of target is a
// miss waiting to happen. 48 is the platform's stated minimum.
private const val HIT_H = 48

private const val GLYPH_BAR = 12
private const val GLYPH_STROKE = 2.6f   // the weight HyleField draws its `!` at, so marks match

private val HYLE_TRACK = Color(0xFFD7D7D9)
private val HYLE_TRACK_DISABLED = Color(0xFFE6E6E8)
private val HYLE_KNOB = Color(0xFF0B0B0D)
private val HYLE_KNOB_PRESSED = Color(0xFF2A2A30)
private val HYLE_KNOB_DISABLED = Color(0xFFBFBFC2)
private val HYLE_MARK = Color(0xFFFFFFFF)
private val HYLE_INK_FAINT = Color(0xFF9A9AA2)
