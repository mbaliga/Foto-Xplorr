package com.fotoxplorr.app.hyle

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ui.HyleGrotesk

/**
 * The Hyle toggle switch: a light track with a black, right-leaning **parallelogram** knob that
 * slides across it. The slant is the whole tell — a plain rounded knob is a Material switch, and
 * the point of this one is that the moving piece leans into its travel, so "which way is on" is
 * legible from the shape alone, not only from which side the knob sits on.
 *
 * The knob is a hard-edged parallelogram *clipped to the track*, so the three edges it shares with
 * the track inherit the track's rounding while the leading slant stays crisp. That is how the
 * mockup reads: rounded on the outside, sharp on the diagonal.
 *
 * The small bracket-and-rivet at the knob's top corner is the Hyle registration mark — the same
 * "cove + rivet" motif the edge notches carry, shrunk onto the control so a switch and a notch
 * plainly belong to one language.
 *
 * @param glyphs optional (off, on) single-character marks. The active one rides the knob in white;
 *   the idle one waits on the track in ink. Null renders a plain switch, which is what a settings
 *   row wants; the lettered form is for the design gallery.
 */
@Composable
fun HyleToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
    glyphs: Pair<Char, Char>? = null,
) {
    val trackColor = if (enabled) HYLE_TRACK else HYLE_TRACK_DISABLED
    val knobColor = if (enabled) HYLE_KNOB else HYLE_KNOB_DISABLED

    val pos by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(stiffness = 900f, dampingRatio = 0.72f),
        label = "hyle-toggle-pos",
    )

    val interaction = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(interaction)
            .size(TRACK_W.dp, TRACK_H.dp)
            .clip(RoundedCornerShape(TRACK_RADIUS.dp))
            .background(trackColor)
            .semantics {
                if (description != null) contentDescription = description
                stateDescription = if (checked) "On" else "Off"
                role = Role.Switch
            }
            .drawBehind {
                val skew = KNOB_SKEW.dp.toPx()
                val knobW = KNOB_W.dp.toPx()
                val travel = size.width - knobW
                val left = pos * travel

                val knob = Path().apply {
                    moveTo(left + skew, 0f)
                    lineTo(left + knobW, 0f)
                    lineTo(left + knobW - skew, size.height)
                    lineTo(left, size.height)
                    close()
                }
                drawPath(knob, knobColor)

                // Registration mark at the knob's top corner: a rounded-corner bracket opening
                // down-and-in, plus a rivet dot below it. Travels with the knob.
                val markX = left + knobW - MARK_INSET_X.dp.toPx()
                val markY = MARK_INSET_Y.dp.toPx()
                val arm = MARK_ARM.dp.toPx()
                val r = MARK_RADIUS.dp.toPx()
                val stroke = MARK_STROKE.dp.toPx()
                val bracket = Path().apply {
                    moveTo(markX, markY)
                    lineTo(markX, markY + arm - r)
                    quadraticBezierTo(markX, markY + arm, markX - r, markY + arm)
                    lineTo(markX - arm, markY + arm)
                }
                drawPath(bracket, HYLE_MARK, style = Stroke(width = stroke))
                drawCircle(
                    HYLE_MARK,
                    radius = MARK_DOT.dp.toPx(),
                    center = Offset(markX - arm - stroke, markY + arm + stroke),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (glyphs != null) {
            val (off, on) = glyphs
            // Each glyph is centred in its own half — so it reads as the label of that side, at the
            // quarter points, clear of the corner registration mark.
            androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                ToggleGlyphCell(off, onKnob = pos < 0.5f)
                ToggleGlyphCell(on, onKnob = pos >= 0.5f)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ToggleGlyphCell(glyph: Char, onKnob: Boolean) {
    Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = glyph.toString(),
            color = if (onKnob) Color.White else HYLE_KNOB,
            style = TextStyle(
                fontFamily = HyleGrotesk,
                fontSize = GLYPH_SIZE.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

// ---- Hyle toggle geometry & palette (from the owner's control-sheet mockup) ----
private const val TRACK_W = 64
private const val TRACK_H = 32
private const val TRACK_RADIUS = 9
private const val KNOB_W = 34
private const val KNOB_SKEW = 6
private const val GLYPH_SIZE = 15
private const val GLYPH_INSET = 9

// Registration mark (bracket + rivet) at the knob's top corner.
private const val MARK_INSET_X = 7
private const val MARK_INSET_Y = 6
private const val MARK_ARM = 7
private const val MARK_RADIUS = 4
private const val MARK_STROKE = 2
private const val MARK_DOT = 1

private val HYLE_TRACK = Color(0xFFD7D7D9)
private val HYLE_TRACK_DISABLED = Color(0xFFE6E6E8)
private val HYLE_KNOB = Color(0xFF0B0B0D)
private val HYLE_KNOB_DISABLED = Color(0xFFBFBFC2)
private val HYLE_MARK = Color(0xFFFFFFFF)
