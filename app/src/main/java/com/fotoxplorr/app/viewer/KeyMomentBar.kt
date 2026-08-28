package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.moments.MomentFeedback
import com.fotoxplorr.app.moments.MomentSource
import com.fotoxplorr.app.moments.VideoMoment
import com.fotoxplorr.app.ui.HyleGrotesk

/**
 * The "Key moment" pill and its menu: the piece of chrome that only exists while the playhead is
 * at or near a marked point in the video.
 *
 * Everything in this file answers to ONE decision, [activeMomentAt] -- whether the pill shows at
 * all, which stored row "Remove marker" deletes, and which moment thumbs feedback is recorded
 * against. [VideoPlayer] computes that decision once per position update and passes the result in
 * as [KeyMomentBar]'s [activeMoment]; this file never re-derives it, so there is exactly one place
 * in the whole feature where "which moment is this" can be wrong.
 *
 * Renders nothing at all when [activeMoment] is null -- the same "empty renders nothing, so there
 * are no invisible tap targets" rule [LiveTextOverlay] and [LiftOverlay] already use for exactly
 * the same reason: a chrome element that is merely invisible-but-composed still occupies space and
 * can still intercept a touch meant for something else.
 */

/**
 * Which moment, if any, the playhead should be treated as currently "on".
 *
 * Nearest-within-tolerance, not an exact match: [VideoPlayer] polls the player's position roughly
 * every 150ms rather than being told about every frame, so the playhead is almost never sitting on
 * the EXACT millisecond a moment was recorded at -- not even the instant after seeking straight to
 * it, since a seek lands on the nearest decodable keyframe rather than the requested millisecond
 * precisely. An exact `==` here would make the pill flicker in for a single polling tick and back
 * out the next, rather than staying up for the couple of seconds a person actually spends looking
 * at a moment.
 *
 * When two moments both fall inside [toleranceMs] (two markers placed close together), the CLOSER
 * one wins. "Remove marker" deletes a specific database row by its exact position, so the tie-break
 * has to be one that cannot surprise the person looking at the pill above it.
 */
internal fun activeMomentAt(
    moments: List<VideoMoment>,
    positionMs: Long,
    toleranceMs: Long,
): VideoMoment? = moments
    .filter { kotlin.math.abs(it.positionMs - positionMs) <= toleranceMs }
    .minByOrNull { kotlin.math.abs(it.positionMs - positionMs) }

@Composable
fun KeyMomentBar(
    activeMoment: VideoMoment?,
    positionMs: Long,
    durationMs: Long,
    feedback: MomentFeedback?,
    onShareMoment: (VideoMoment) -> Unit,
    onCreateClip: (VideoMoment) -> Unit,
    onRemoveMarker: (VideoMoment) -> Unit,
    onFeedback: (VideoMoment, MomentFeedback) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (activeMoment == null) return

    // Keyed on the moment's OWN identity, not merely on "is one active": dragging straight from
    // one key moment into a neighbouring one (two moments close enough that both fall inside
    // tolerance somewhere along the drag) must not leave a menu that was opened for the FIRST
    // moment still open and now silently pointed at the second one's Remove/Share actions.
    var menuOpen by remember(activeMoment.mediaId, activeMoment.positionMs) { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (menuOpen) {
            MomentMenu(
                moment = activeMoment,
                feedback = feedback,
                onShareMoment = { menuOpen = false; onShareMoment(activeMoment) },
                onCreateClip = { menuOpen = false; onCreateClip(activeMoment) },
                onRemoveMarker = { menuOpen = false; onRemoveMarker(activeMoment) },
                onFeedback = { value -> onFeedback(activeMoment, value) },
            )
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(BAR_FILL)
                .clickable { menuOpen = !menuOpen }
                .padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = "Key moment", color = Color.White, style = PILL_TEXT)
            Icon(
                // Reversed from the usual expand/collapse convention on purpose -- this matches
                // the owner's reference exactly: up (inviting you to open the menu that appears
                // ABOVE the pill) when closed, down (inviting you to collapse it back) when open.
                imageVector = if (menuOpen) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                contentDescription = if (menuOpen) "Collapse" else "Expand",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }

        Text(
            text = "${formatTimecode(positionMs)} / ${formatTimecode(durationMs)}",
            color = Color.White.copy(alpha = 0.75f),
            style = TIME_TEXT,
        )
    }
}

@Composable
private fun MomentMenu(
    moment: VideoMoment,
    feedback: MomentFeedback?,
    onShareMoment: () -> Unit,
    onCreateClip: () -> Unit,
    onRemoveMarker: () -> Unit,
    onFeedback: (MomentFeedback) -> Unit,
) {
    Column(
        // Sized to the WIDEST row's own content rather than a hand-picked constant: `fillMaxWidth`
        // on each row below would otherwise stretch every row to the full screen width (the
        // constraint this Column would receive with no width of its own comes from all the way up
        // at VideoPlayer's fillMaxSize Box), which is correct for a full-bleed list and wrong for
        // a compact dropdown that is meant to hug its own content.
        modifier = Modifier
            .width(IntrinsicSize.Max)
            .clip(RoundedCornerShape(18.dp))
            .background(BAR_FILL)
            .padding(vertical = 8.dp),
    ) {
        MenuRow(
            icon = { Icon(Icons.Outlined.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)) },
            label = "Share moment",
            onClick = onShareMoment,
        )
        MenuRow(
            icon = { SparkleScissorsIcon() },
            label = "Create clip",
            onClick = onCreateClip,
        )
        MenuRow(
            icon = {
                Icon(
                    Icons.Outlined.RemoveCircleOutline,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            },
            label = "Remove marker",
            onClick = onRemoveMarker,
        )

        // Feedback is a question about the DETECTOR's judgement -- "was flagging this spot the
        // right call" -- which only makes sense for a moment the detector actually flagged. A
        // hand-placed marker has no guess behind it to rate; see VideoMoment's own KDoc on why
        // AUTO and MANUAL are kept apart everywhere else this feature touches them too.
        if (moment.source == MomentSource.AUTO) {
            MenuDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
            ) {
                ThumbButton(
                    selected = feedback == MomentFeedback.GOOD,
                    selectedIcon = Icons.Filled.ThumbUp,
                    unselectedIcon = Icons.Outlined.ThumbUp,
                    description = "Good moment",
                    onClick = { onFeedback(MomentFeedback.GOOD) },
                )
                ThumbButton(
                    selected = feedback == MomentFeedback.BAD,
                    selectedIcon = Icons.Filled.ThumbDown,
                    unselectedIcon = Icons.Outlined.ThumbDown,
                    description = "Not a good moment",
                    onClick = { onFeedback(MomentFeedback.BAD) },
                )
            }
        }
    }
}

@Composable
private fun MenuRow(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) { icon() }
        Text(text = label, color = Color.White, style = MENU_TEXT)
    }
}

@Composable
private fun MenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.14f)),
    )
}

@Composable
private fun ThumbButton(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    RoundIconButton(
        icon = if (selected) selectedIcon else unselectedIcon,
        contentDescription = description,
        onClick = onClick,
        tint = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
        diameter = 34.dp,
    )
}

/**
 * "Create clip", drawn as the two ideas the reference's label names rather than as one Material
 * glyph. `material-icons-extended` (checked directly against the jar this module depends on, not
 * guessed) ships nothing resembling "sparkle scissors", and the closest single icon -- plain
 * scissors -- reads as "trim" or "delete", not "an auto-made highlight reel", which is what this
 * action actually does. A small sparkle badge over the scissors' corner borrows the same
 * "something clever happened here" visual vocabulary the reference uses, built from two icons this
 * app already ships rather than a new drawable asset.
 */
@Composable
private fun SparkleScissorsIcon() {
    Box(modifier = Modifier.size(20.dp)) {
        Icon(
            imageVector = Icons.Outlined.ContentCut,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp).align(Alignment.BottomStart),
        )
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(11.dp).align(Alignment.TopEnd),
        )
    }
}

/**
 * A circular icon button on translucent black -- the one shape every floating control in this
 * chrome shares: the two thumbs here, and (see [VideoPlayer]) the scrubber row's play/pause,
 * mute and manual "mark this moment" button. One composable rather than several near-identical
 * `IconButton` call sites is what keeps a future size or opacity tweak a single-line change
 * instead of a multi-file hunt for drift. Matches [ViewerPositionChip]'s own close-button
 * treatment exactly (36dp, 55% black), so this reads as the same floating-chrome language as the
 * rest of the viewer rather than a new one invented for this feature.
 */
@Composable
internal fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    diameter: Dp = 36.dp,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(diameter)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(diameter * 0.5f),
        )
    }
}

private val PILL_TEXT = TextStyle(fontFamily = HyleGrotesk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
private val MENU_TEXT = TextStyle(fontFamily = HyleGrotesk, fontSize = 15.sp)
private val TIME_TEXT = TextStyle(fontFamily = HyleGrotesk, fontSize = 12.sp)

/**
 * The same dark chrome fill [LiveTextOverlay]'s own action bar uses there as `ACTION_FILL`,
 * repeated here (that constant is private to that file, so it cannot be imported) rather than
 * invented anew, so the pill and menu read as the same surface as the rest of the app's floating
 * video/photo chrome instead of a subtly different black.
 */
private val BAR_FILL = Color(0xE6121216)
