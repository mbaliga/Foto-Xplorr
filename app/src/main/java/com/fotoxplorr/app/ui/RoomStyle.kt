package com.fotoxplorr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The shared type and controls of a room.
 *
 * Every room in the app is a surface parked off one edge, and they were each inventing their own
 * language: the left rail used explicit [TextStyle]s at 18sp with negative tracking, the viewer's
 * top room used Material's typography tokens and a Material `Switch`, and the detail room used a
 * third set of hand-picked sizes. Side by side they read as three different apps, which is exactly
 * what the owner meant by asking that the top and bottom rooms be *restyled to match*.
 *
 * The rail wins, because it is the one the owner signed off on and the one the reference
 * screenshots were drawn from: black ground, large low-weight type, hierarchy carried by opacity
 * rather than by rules, boxes and dividers, and no Material component chrome. Everything here is
 * that language written down once so a fourth room cannot drift.
 */
object RoomStyle {

    /** The rail's own row type: large, light, very slightly tightened. */
    val Row = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.3).sp)

    /** A section eyebrow. Small, wide-tracked, shouted — the one place capitals are allowed. */
    val Eyebrow = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)

    /** The explanatory line under a row. Never larger than the row it explains. */
    val Caption = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)

    /** A room's own title, when it needs one. */
    val Title = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp)

    /** Full-strength ink. */
    val Ink = Color.White

    /** A row that is present but secondary — the rail's "Settings" step-down. */
    val InkMuted = Color.White.copy(alpha = 0.55f)

    /** Explanatory text and disabled states. */
    val InkFaint = Color.White.copy(alpha = 0.40f)

    /** The rail's gutters, so every room lines its text up on the same two edges. */
    val GutterStart = 20.dp
    val GutterEnd = 20.dp
}

/** A section heading. */
@Composable
fun RoomEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = RoomStyle.InkFaint,
        style = RoomStyle.Eyebrow,
        modifier = modifier.padding(bottom = 2.dp),
    )
}

/**
 * One row of a room: a name, an optional line of explanation, and something on the right.
 *
 * The whole row is the touch target when [onClick] is given, rather than only the control at its
 * end — a 40dp switch on a 400dp row is a needlessly small thing to hit.
 */
@Composable
fun RoomRow(
    label: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = label,
                color = if (enabled) RoomStyle.Ink else RoomStyle.InkFaint,
                style = RoomStyle.Row,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    color = RoomStyle.InkFaint,
                    style = RoomStyle.Caption,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * A toggle, drawn rather than imported.
 *
 * Material's `Switch` carries its own elevation, ripple, thumb shadow and rounded track — a piece
 * of a different design system sitting in the middle of a black brutalist room. This is the same
 * information as a rectangle and a block that moves to one end of it.
 */
@Composable
fun RoomToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val ink = when {
        !enabled -> RoomStyle.InkFaint
        checked -> RoomStyle.Ink
        else -> RoomStyle.InkMuted
    }
    Box(
        modifier = Modifier
            .width(TOGGLE_WIDTH)
            .border(1.dp, ink)
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier)
            .padding(2.dp),
        // The block sits at the end it means: right for on, left for off. No animation, no
        // thumb — the state is legible from across the room and reads as a switch, not a chip.
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(width = TOGGLE_BLOCK, height = TOGGLE_HEIGHT)
                .background(if (checked) ink else Color.Transparent)
                .then(if (checked) Modifier else Modifier.border(1.dp, ink)),
        )
    }
}

/**
 * A number the user nudges. `−  value  +`, in the room's own type.
 *
 * The bounds are the caller's to decide and are shown by dimming the arrow that would do nothing,
 * rather than by letting it fire and silently clamp.
 */
@Composable
fun RoomStepper(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    canDecrease: Boolean,
    canIncrease: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StepperArrow("−", enabled = canDecrease, onClick = onDecrease, description = "Less")
        Text(
            text = value,
            color = RoomStyle.Ink,
            style = RoomStyle.Row,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        StepperArrow("+", enabled = canIncrease, onClick = onIncrease, description = "More")
    }
}

@Composable
private fun StepperArrow(glyph: String, enabled: Boolean, onClick: () -> Unit, description: String) {
    Text(
        text = glyph,
        color = if (enabled) RoomStyle.Ink else RoomStyle.InkFaint,
        style = RoomStyle.Title,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick, onClickLabel = description)
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

/** A hairline between groups, at the opacity the rail uses for its dimmest text. */
@Composable
fun RoomRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.10f)),
    )
}

private val TOGGLE_WIDTH = 52.dp
private val TOGGLE_HEIGHT = 22.dp
private val TOGGLE_BLOCK = 22.dp
