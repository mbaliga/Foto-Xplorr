package com.fotoxplorr.app.hyle

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.minimumInteractiveComponentSize
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
 * Disabled is a **fade**, not a second palette: the whole control is drawn at a fraction of its
 * strength, so it recedes from whatever it sits on. The previous disabled pair was a lighter grey
 * than the live one, which is right on the light control-sheet card and backwards on the black
 * rooms this lives in -- a dead switch glowed brighter than a live one. See [toggleTrackColor].
 *
 * The knob glides with a short ease rather than a spring: a spring overshoots, and nothing else
 * in the shell does -- an arrival lands, it does not wobble.
 *
 * The drawn track is 64 x 32dp but the touch target is the platform's 48dp floor, so a fingertip
 * does not have to land on a control that small.
 *
 * @param onCheckedChange null draws the switch without making it interactive, for a row that is
 *   itself the one switch (the row carries the tap and the semantics; this carries the picture).
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
    val trackColor = toggleTrackColor(enabled)
    val knobColor = toggleKnobColor(enabled)
    val markColor = if (enabled) HYLE_MARK else HYLE_MARK.copy(alpha = DISABLED_TRACK_ALPHA)

    val pos by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = ToggleGlideSpec,
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
            // Below the platform's 48dp minimum touch target on its own (32dp tall) -- this pads
            // the tappable area out to that floor without changing the track's drawn size, the
            // same ordering (before the fixed `.size`) Material3's own controls use for the same
            // problem.
            .minimumInteractiveComponentSize()
            .then(interaction)
            .size(TRACK_W.dp, TRACK_H.dp)
            .clip(RoundedCornerShape(TRACK_RADIUS.dp))
            .background(trackColor)
            .semantics {
                if (description != null) contentDescription = description
                stateDescription = if (checked) "On" else "Off"
                // Only when this is the control. A display-only switch inside a row that is
                // itself the switch must not claim the role too, or a screen reader finds two.
                if (onCheckedChange != null) role = Role.Switch
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
                drawPath(bracket, markColor, style = Stroke(width = stroke))
                drawCircle(
                    markColor,
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
                ToggleGlyphCell(off, onKnob = pos < 0.5f, enabled = enabled)
                ToggleGlyphCell(on, onKnob = pos >= 0.5f, enabled = enabled)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ToggleGlyphCell(
    glyph: Char,
    onKnob: Boolean,
    enabled: Boolean,
) {
    Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = glyph.toString(),
            color = (if (onKnob) Color.White else HYLE_KNOB)
                .copy(alpha = if (enabled) 1f else DISABLED_TRACK_ALPHA),
            style = TextStyle(
                fontFamily = HyleGrotesk,
                fontSize = GLYPH_SIZE.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

/**
 * The track colour: the control-sheet neutral live, the same neutral faded when disabled.
 *
 * Over the light card the fade lands within a shade of the old disabled grey (#E6E6E8); over black
 * it drops to a dark grey where the old colour went *brighter* than the live one. Internal so the
 * ordering "disabled is dimmer than live, on both grounds" is pinned by a test and not by eye.
 */
internal fun toggleTrackColor(enabled: Boolean): Color =
    if (enabled) HYLE_TRACK else HYLE_TRACK.copy(alpha = DISABLED_TRACK_ALPHA)

/** The knob colour; faded harder than the track so a disabled knob still reads as the knob. */
internal fun toggleKnobColor(enabled: Boolean): Color =
    if (enabled) HYLE_KNOB else HYLE_KNOB.copy(alpha = DISABLED_KNOB_ALPHA)

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

// Fixed on purpose (reference design): control-sheet neutrals, the on state is the knob's position
// and not a hue.
private val HYLE_TRACK = Color(0xFFD7D7D9)
private val HYLE_KNOB = Color(0xFF0B0B0D)

// Disabled strengths. The track fades to ~40% (about #E8E8E9 over the light card, ~#565657 over
// black); the knob to ~25% so it stays a step darker than its faded track on either ground.
private const val DISABLED_TRACK_ALPHA = 0.4f
private const val DISABLED_KNOB_ALPHA = 0.25f

/**
 * How the knob travels: a short ease that arrives and stops. Internal so a test can sample it and
 * pin that it never passes its target -- the spring this replaced did, by a few percent of the
 * travel, and nothing else in the shell wobbles on arrival.
 */
internal val ToggleGlideSpec: AnimationSpec<Float> =
    tween(durationMillis = KNOB_GLIDE_MILLIS, easing = FastOutSlowInEasing)

/** Long enough to see which way it went, short enough that the row never feels like it lags. */
private const val KNOB_GLIDE_MILLIS = 140
private val HYLE_MARK = Color(0xFFFFFFFF)
