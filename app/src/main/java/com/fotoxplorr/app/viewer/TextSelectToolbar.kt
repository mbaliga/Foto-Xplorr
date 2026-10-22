package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ui.HyleGrotesk

/** Id of the toolbar's status line, for tests. */
internal const val TEXT_SELECT_STATUS_TAG = "textSelectStatus"

/** Id of the toolbar's notes (a translation, why an action did not happen), for tests. */
internal const val TEXT_SELECT_NOTES_TAG = "textSelectNotes"

/**
 * One button on the Select text toolbar. [label] is what the button says and what a screen reader
 * reads, so there is no icon-only target; [enabled] false dims it and tells assistive technology it
 * is unavailable.
 */
internal class TextSelectAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The bottom toolbar of the Select text mode: a status line, then labelled buttons.
 *
 * The status line is a polite live region, so a screen reader announces "3 words selected" or
 * "Copied 3 words" as it changes without taking focus. The buttons are the NON-GESTURE way through
 * the mode (Select all, Copy, then whatever [extraActions] adds), because drawing over words is a
 * touch gesture that touch-exploration does not deliver: someone who cannot draw can still take
 * all the text. Every button is at least 48 dp square and carries its label as text.
 *
 * Lives outside the zoom layer (it must not scale or turn with the photo), over the bottom edge
 * where the filmstrip would be: the chrome is hidden while the mode is on, so they never share it.
 *
 * @param extraActions further buttons after Copy, in order: the actions that need the text but not
 *   this file's clipboard (share, search, listen, translate) are supplied by the caller.
 * @param notes lines under the status line: a translation, or why an action could not happen. Also
 *   a polite live region, and scrollable past a few lines so a long translation cannot push the
 *   buttons off the screen.
 * @param onDone leaves the mode.
 */
@Composable
internal fun TextSelectToolbar(
    state: TextSelectState,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    extraActions: List<TextSelectAction> = emptyList(),
    notes: List<String> = emptyList(),
) {
    val layout = state.layout
    val selection = state.selection
    val clipboard = LocalClipboardManager.current
    // Cleared by any change to the selection: "Copied" must not outlive what it was about.
    var copied by remember(selection) { mutableStateOf(false) }

    val status = if (copied) {
        TextSelectCopy.copied(selection.size, layout.wordLevel)
    } else {
        TextSelectCopy.status(selection.size, layout.wordLevel, state.hasText, state.working)
    }
    val allSelected = selection.isAll(layout)

    Column(
        modifier = modifier
            .background(PANEL_FILL, RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = status,
            color = Color.White,
            style = TextStyle(fontFamily = HyleGrotesk, fontSize = 14.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag(TEXT_SELECT_STATUS_TAG)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (notes.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .heightIn(max = NOTES_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .testTag(TEXT_SELECT_NOTES_TAG)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                notes.forEach {
                    Text(
                        text = it,
                        color = Color.White.copy(alpha = 0.85f),
                        style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        // Wraps onto a second line rather than scrolling: seven labelled buttons are wider than a
        // phone, and a button that has to be swiped to is one nobody finds (the lesson of the
        // details card's tile row). Every button is whole and reachable without a gesture.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolbarButton(
                action = TextSelectAction(
                    label = if (allSelected) "Deselect" else "Select all",
                    icon = if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll,
                    enabled = state.hasText,
                    onClick = {
                        state.select(if (allSelected) selection.clear() else selection.selectAll(layout))
                    },
                ),
            )
            ToolbarButton(
                action = TextSelectAction(
                    label = "Copy",
                    icon = Icons.Outlined.ContentCopy,
                    enabled = !selection.isEmpty,
                    onClick = {
                        clipboard.setText(AnnotatedString(selection.text(layout)))
                        copied = true
                    },
                ),
            )
            extraActions.forEach { ToolbarButton(action = it) }
            ToolbarButton(action = TextSelectAction(label = "Done", icon = Icons.Outlined.Check, onClick = onDone))
        }
    }
}

@Composable
private fun ToolbarButton(action: TextSelectAction) {
    Column(
        modifier = Modifier
            .alpha(if (action.enabled) 1f else DISABLED_ALPHA)
            // 48 dp is the floor for a touch target; the label makes a button wider than that. The
            // minimum comes BEFORE the click, so the clickable area is the whole target.
            .defaultMinSize(minWidth = 64.dp, minHeight = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Decorative: the label beside it is what is read.
        Icon(action.icon, contentDescription = null, tint = Color.White)
        Text(
            text = action.label,
            color = Color.White,
            style = TextStyle(fontFamily = HyleGrotesk, fontSize = 12.sp),
        )
    }
}

private const val DISABLED_ALPHA = 0.4f

private val NOTES_MAX_HEIGHT = 120.dp

// Fixed on purpose: a neutral scrim behind the toolbar, no accent role (as the old action pill was).
private val PANEL_FILL = Color(0xE6121216)
