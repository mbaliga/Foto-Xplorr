package com.fotoxplorr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * How tall the quick-action strip is, hairline and padding included. [RoomGeography.ActionsPeek]
 * is built from it: the shell is told to stop the pull where this much of the room is on screen
 * below its head band, so it must be the strip's own measure and not a second number kept
 * beside it.
 */
val QuickActionStripHeight: Dp = 72.dp

/** The strip's test tag, for the tests that measure or look for it. */
const val QUICK_ACTION_STRIP_TAG = "quick-action-strip"

/** The test tag of the black slice under the strip that stands in for the navigation bar's share of the peek. */
const val ACTIONS_PEEK_NAV_SPACER_TAG = "actions-peek-nav-spacer"

/** The smallest a quick action's touch target may be, either way. */
val QuickActionMinTarget: Dp = 48.dp

/**
 * One cell of a [QuickActionStrip]: a glyph over a short word.
 *
 * @param highlighted draws the glyph and word in the accent, for a state the person has switched on
 *   (a favourited photo).
 * @param enabled false dims the cell and stops it answering. Prefer leaving an action out of the
 *   list when it is simply not allowed; a disabled cell is for one that is allowed but not now.
 * @param stateDescription what a screen reader adds after the label ("Favourited"), for a cell
 *   whose [highlighted] look means something sighted users read from the tint alone. Null adds
 *   nothing.
 */
class QuickActionItem(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val highlighted: Boolean = false,
    val stateDescription: String? = null,
)

/**
 * The cells to draw: [items] when the shell's bottom room really is two-stage, none when it is not.
 * On a window too short for the peek (split-screen, landscape at a large font) the shell is
 * single-stage ([dev.aarso.cellshell.SpatialController.hasBottomPeek] false) and the first screenful
 * of the room is the list itself, so a strip would only repeat the rows below it.
 */
fun visibleQuickActions(showStrip: Boolean, items: List<QuickActionItem>): List<QuickActionItem> =
    if (showStrip) items else emptyList()

/**
 * The few actions a room's owner reaches for most, laid in one row as icons with short labels.
 *
 * It is the first thing of the BOTTOM actions room a person sees: the shell's two-stage reveal
 * stops a short pull on exactly this strip ([RoomGeography.ActionsPeek]) and a longer pull carries
 * on to the whole room beneath it. So the strip
 * is not an extra; it is the room's head, positioned where the room's first inch is.
 * The full actions stay in the room for everything the strip does not carry.
 *
 * Cells share the width equally and are at least [QuickActionMinTarget] either way. Tapping one
 * performs the action; whether that also closes the room is the caller's handler's business (the
 * hosts' handlers close it, because an action's result landing behind the panel that triggered it
 * reads as the control doing nothing).
 *
 * An empty list draws nothing, so a room whose quick actions are all disallowed has no hole.
 */
@Composable
fun QuickActionStrip(items: List<QuickActionItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Column(modifier.fillMaxWidth().height(QuickActionStripHeight).testTag(QUICK_ACTION_STRIP_TAG)) {
        RoomRule(Modifier.padding(horizontal = RoomStyle.GutterStart))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEach { item -> QuickActionCell(item, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun QuickActionCell(item: QuickActionItem, modifier: Modifier) {
    val alpha = if (item.enabled) 1f else 0.38f
    val ink = if (item.highlighted) MaterialTheme.colorScheme.primary else Color.White
    Column(
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = QuickActionMinTarget)
            .clip(RoundedCornerShape(12.dp))
            // The whole cell is the target, label and glyph alike: the viewer's full-action rows
            // can only be hit on their icon, and a word that looks tappable and is not is worse
            // than none. Role.Button with the text merged in reads as one named button.
            .clickable(enabled = item.enabled, role = Role.Button, onClick = item.onClick)
            .semantics { item.stateDescription?.let { stateDescription = it } }
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = item.icon,
            // Null, not the label: the word under it is already read out and merged into the
            // cell, and a described glyph beside its own caption announces every action twice.
            contentDescription = null,
            tint = ink.copy(alpha = alpha),
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = item.label,
            color = ink.copy(alpha = if (item.highlighted) alpha else alpha * 0.85f),
            style = RoomStyle.Caption.copy(fontSize = 12.sp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The frame both actions rooms (the gallery's and the viewer's) are built in: the
 * [QuickActionStrip] first, pinned at the head of the room, and the full list of actions scrolling
 * beneath it.
 *
 * Head, because the bottom room arrives from below like a sheet: its TOP is the first part of it
 * on screen, so a short pull shows the strip and a longer one brings in the list under it. A strip
 * at the bottom of the room arrives last and is off screen at the peek. The strip does not scroll
 * away: it is the room's first inch at every stage. [clearSystemBarsFor] keeps the list's last
 * row above the navigation bar.
 *
 * The shell adds the navigation bar's height to the peek, so at the peek that much more of the
 * room than the strip is on screen. A black spacer of exactly that height sits under the strip, so
 * the slice shows nothing of the list. (At the full stage it is a margin between the two.)
 *
 * @param quickActions the strip's cells; empty means no strip.
 * @param showStrip false drops the strip AND the navigation-bar spacer under it, for a shell that
 *   has no bottom peek on this window ([dev.aarso.cellshell.SpatialController.hasBottomPeek]): the
 *   room is single-stage there, so both would sit on top of the rows they duplicate.
 * @param contentPadding the list's own padding, inside the scroll.
 */
@Composable
fun ActionsRoomFrame(
    quickActions: List<QuickActionItem>,
    modifier: Modifier = Modifier,
    showStrip: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val strip = visibleQuickActions(showStrip, quickActions)
    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        QuickActionStrip(strip)
        // The peek's navigation-bar slice: see above. Only when there is a strip, because with none
        // the shell's peek is not used and the list begins at the head. It is measured OUTSIDE the
        // list's column below: that column's navigation-bar padding consumes the inset for
        // everything inside it, and a slice measured in there would be zero tall.
        if (strip.isNotEmpty()) {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                    .background(Color.Black)
                    .testTag(ACTIONS_PEEK_NAV_SPACER_TAG),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // The shell insets a bottom room by its band at the TOP (where the parked card's
                // bottom edge sits), so the system bar to clear here is the navigation bar.
                .clearSystemBarsFor(RoomGeography.Actions)
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}
